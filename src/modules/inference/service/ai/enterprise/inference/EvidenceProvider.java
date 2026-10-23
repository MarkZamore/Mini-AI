package ai.enterprise.inference;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.EvidenceModelServiceV1;
import ai.enterprise.contracts.Json;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A bounded, local, persistent evidence channel. No Python, HTTP, files or caches are created at runtime. */
public final class EvidenceProvider implements EvidenceModelServiceV1 {
    private static final int MAX_FRAME=8*1024*1024;
    private static final String PROTOCOL="enterpriseai.evidence.v1";
    private final Api.Context context;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mini-ai-evidence");t.setDaemon(true);return t;});
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicLong ids=new AtomicLong();
    private volatile Channel channel;
    private volatile String corpusSignature="",corpusDigest="";

    public EvidenceProvider(Api.Context context){this.context=context;}
    public Api.Result<Info> availability(){try{
        var manifest=manifest();
        if(!(manifest.get("evidence") instanceof Map<?,?> raw))return Api.Result.fail("EVIDENCE_UNAVAILABLE","Семантическая модель не подготовлена");
        var evidence=object(raw);var index=read(contained(context.environment().model(),Json.string(object(evidence.get("index")).get("path"))),1_048_576);
        int dimension=Math.toIntExact(Json.integer(index.get("dimensions")));
        if(dimension!=384)throw new Api.ServiceException("EVIDENCE_INVALID","Неверная размерность семантического индекса");
        String fingerprint=Json.string(index.get("corpusSha256"));
        if(!fingerprint.matches("[a-f0-9]{64}"))throw new Api.ServiceException("EVIDENCE_INVALID","Нет версии корпуса");
        return Api.Result.ok(new Info(Json.string(object(evidence.get("embedder")).get("sha256")),dimension,fingerprint,evidence.containsKey("reranker")));
    }catch(NoSuchFileException missing){
        return Files.exists(context.environment().model().resolve("worker.json"))
            ?Api.Result.fail("EVIDENCE_INVALID","Отсутствует объявленный файл семантической модели")
            :Api.Result.fail("EVIDENCE_UNAVAILABLE","Семантическая модель не подготовлена");
    }catch(Api.ServiceException error){return Api.failure(error);}
    catch(Throwable error){return Api.Result.fail("EVIDENCE_INVALID","Неверное описание семантической модели: "+error.getMessage());}}

    public Api.Result<Policy> answerPolicy(){try{
        var evidence=object(manifest().get("evidence"));
        var index=read(contained(context.environment().model(),Json.string(object(evidence.get("index")).get("path"))),1_048_576);
        if(!index.containsKey("answerPolicy"))return Api.Result.ok(new Policy("none",0,0,0));
        var value=object(index.get("answerPolicy"));
        if(!value.keySet().equals(Set.of("kind","maxCandidates","adequateMinLogit","insufficientMaxLogit"))
            ||!"bge-relevance-v1".equals(value.get("kind")))throw protocol("Неверная политика оценки источников");
        int count=Math.toIntExact(Json.integer(value.get("maxCandidates")));
        if(!(value.get("maxCandidates") instanceof Number countNumber)||countNumber.doubleValue()!=count
            ||!(value.get("adequateMinLogit") instanceof Number adequate)||!(value.get("insufficientMaxLogit") instanceof Number insufficient)
            ||count<1||count>8||!Double.isFinite(adequate.doubleValue())||!Double.isFinite(insufficient.doubleValue())
            ||insufficient.doubleValue()>=adequate.doubleValue())throw protocol("Неверные границы оценки источников");
        if(!evidence.containsKey("reranker"))throw protocol("Политика источников требует подготовленную модель BGE");
        return Api.Result.ok(new Policy("bge-relevance-v1",count,adequate.doubleValue(),insufficient.doubleValue()));
    }catch(Throwable error){return Api.failure(error);}}

    public CompletionStage<Api.Result<List<ScoredFragment>>> retrieve(String baseId,String query,int limit,List<String> documents,List<String> sections,Api.CancellationToken cancellation){
        return perform(cancellation,Map.of("type","retrieve","baseId",baseId,"query",query,"limit",limit,
            "documentIds",documents==null?List.of():List.copyOf(documents),"sections",sections==null?List.of():List.copyOf(sections)),EvidenceProvider::hits);
    }
    public CompletionStage<Api.Result<List<ScoredFragment>>> rerank(String query,List<Candidate> candidates,Api.CancellationToken cancellation){
        // The application JSON codec deliberately has no reflective record
        // serialization. Send only the explicitly versioned wire fields.
        var rows=candidates.stream().map(c->Map.of("fragmentId",c.fragmentId(),"documentId",c.documentId(),"text",c.text())).toList();
        return perform(cancellation,Map.of("type","rerank","query",query,"candidates",rows),EvidenceProvider::hits);
    }
    public CompletionStage<Api.Result<List<float[]>>> embed(List<String> texts,boolean queries,Api.CancellationToken cancellation){
        return perform(cancellation,Map.of("type","embed","texts",List.copyOf(texts),"queries",queries),result->{
            if(!(result.get("vectors") instanceof List<?> vectors)||vectors.size()!=texts.size())throw protocol("Неверный embedding batch");
            var output=new ArrayList<float[]>();for(Object item:vectors){if(!(item instanceof List<?> values)||values.size()!=384)throw protocol("Неверный embedding vector");
                float[] vector=new float[384];double norm=0;for(int i=0;i<384;i++){if(!(values.get(i) instanceof Number number)||!Double.isFinite(number.doubleValue()))throw protocol("Неверное значение embedding");vector[i]=number.floatValue();norm+=(double)vector[i]*vector[i];}
                if(Math.abs(norm-1)>0.01)throw protocol("Embedding не нормализован");output.add(vector);}return List.copyOf(output);
        });
    }
    private static List<ScoredFragment> hits(Map<String,Object> result){
        if(!(result.get("hits") instanceof List<?> rows)||rows.size()>300)throw protocol("Неверный список семантических результатов");
        var hits=new ArrayList<ScoredFragment>();var seen=new HashSet<String>();for(Object row:rows){var item=object(row);
            if(!item.keySet().equals(Set.of("fragmentId","documentId","score")))throw protocol("Неверные поля результата");
            String id=Json.string(item.get("fragmentId")),document=Json.string(item.get("documentId"));
            if(id.isBlank()||id.length()>128||document.isBlank()||document.length()>128||!seen.add(id))throw protocol("Неверный ID результата");
            if(!(item.get("score") instanceof Number number)||!Double.isFinite(number.doubleValue()))throw protocol("Неверная оценка результата");
            hits.add(new ScoredFragment(id,document,number.doubleValue()));}return List.copyOf(hits);
    }
    private <T> CompletionStage<Api.Result<T>> perform(Api.CancellationToken token,Map<String,Object> fields,java.util.function.Function<Map<String,Object>,T> decode){
        if(closed.get())return CompletableFuture.completedFuture(Api.Result.fail("PROVIDER_CLOSED","Семантический провайдер завершён"));
        return CompletableFuture.supplyAsync(()->{Channel active=null;try{
            token.check();var available=availability();if(!available.success())throw new Api.ServiceException(available.error().code(),available.error().message());
            verifyCorpus(available.value().corpusSha256(),token);active=connection(token);
            String id="evidence-"+ids.incrementAndGet();var request=new LinkedHashMap<String,Object>(fields);request.put("requestId",id);
            var owned=active;var finished=new AtomicBoolean();token.onCancel(()->{if(!finished.get()){owned.close();if(channel==owned)channel=null;}});
            active.write(request);Map<String,Object> frame=active.read(token,120);finished.set(true);token.check();
            if(!id.equals(frame.get("requestId")))throw protocol("Чужой ID ответа evidence");
            if("error".equals(frame.get("type"))){if(!frame.keySet().equals(Set.of("type","requestId","code","message")))throw protocol("Неверная ошибка evidence");throw new Api.ServiceException(Json.string(frame.get("code")),Json.string(frame.get("message")));}
            if(!"result".equals(frame.get("type"))||!frame.keySet().equals(Set.of("type","requestId","result")))throw protocol("Неверный terminal frame evidence");
            return Api.Result.ok(decode.apply(object(frame.get("result"))));
        }catch(Throwable failure){if(active!=null){active.close();if(channel==active)channel=null;}return token.cancelled()?Api.Result.fail("CANCELLED","Семантический поиск отменён"):Api.failure(failure);}},executor);
    }
    private void verifyCorpus(String expected,Api.CancellationToken cancellation)throws Exception{
        Path path=context.environment().db().resolve("index.sqlite");var attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
        String signature=path.toRealPath()+":"+attrs.fileKey()+":"+attrs.size()+":"+attrs.lastModifiedTime();
        if(!signature.equals(corpusSignature)){MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[1<<20];int n;while((n=input.read(buffer))!=-1){cancellation.check();digest.update(buffer,0,n);}}
            var after=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);
            if(!Objects.equals(attrs.fileKey(),after.fileKey())||attrs.size()!=after.size()||!attrs.lastModifiedTime().equals(after.lastModifiedTime()))
                throw new Api.ServiceException("CORPUS_MISMATCH","База изменилась во время проверки семантического индекса");
            corpusDigest=HexFormat.of().formatHex(digest.digest());corpusSignature=signature;}
        if(!expected.equals(corpusDigest))throw new Api.ServiceException("CORPUS_MISMATCH","Семантический индекс подготовлен для другой версии базы");
    }
    private Channel connection(Api.CancellationToken token)throws Exception{
        Path root=context.environment().model().toRealPath();var raw=manifest();String identity=Json.stringify(raw);
        var current=channel;if(current!=null&&current.identity.equals(identity)&&current.process.isAlive())return current;
        if(current!=null)current.close();
        var available=new HashSet<String>();if(raw.get("supportedIsa") instanceof List<?> requirements)for(Object value:requirements)if(value instanceof String isa&&context.environment().platform().cpuSupports(isa))available.add(isa);
        var verified=WorkerManifest.verify(raw,root,available,context.environment().platform().availableMemoryBytes(),token::cancelled);
        token.check();var process=context.environment().platform().launchWorker(verified.executable(),root,verified.maxProcessMemoryBytes());
        var created=new Channel(process,identity);try{created.write(Map.of("type","hello","protocol",PROTOCOL));var ready=created.read(token,180);
            if(!ready.keySet().equals(Set.of("type","protocol"))||!"ready".equals(ready.get("type"))||!PROTOCOL.equals(ready.get("protocol")))throw protocol("Evidence worker не подтвердил протокол");
            if(closed.get()){created.close();throw new Api.ServiceException("PROVIDER_CLOSED","Провайдер завершён");}channel=created;return created;
        }catch(Throwable error){created.close();throw error;}
    }
    private Map<String,Object> manifest()throws IOException{return read(context.environment().model().resolve("worker.json"),1_048_576);}
    private static Map<String,Object> read(Path path,int maximum)throws IOException{try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length>maximum)throw protocol("Метаданные evidence превышают лимит");return Json.object(utf8(bytes));}}
    private static Path contained(Path root,String relative)throws IOException{
        if(relative.isBlank()||relative.contains("\\")||relative.contains(":")||relative.startsWith("/")||Arrays.asList(relative.split("/")).contains(".."))throw protocol("Неверный путь evidence");
        Path base=root.toRealPath(),path=base.resolve(relative).normalize().toRealPath();if(!path.startsWith(base))throw protocol("Путь evidence вне модели");return path;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw protocol("Ожидался объект evidence");return (Map<String,Object>)value;}
    private static String utf8(byte[] bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    private static Api.ServiceException protocol(String message){return new Api.ServiceException("EVIDENCE_PROTOCOL",message);}
    public void close(){if(closed.compareAndSet(false,true)){var active=channel;if(active!=null)active.close();executor.shutdownNow();}}

    private static final class Channel implements AutoCloseable {
        final Process process;final String identity;final ArrayBlockingQueue<Object> frames=new ArrayBlockingQueue<>(4);final AtomicBoolean stopped=new AtomicBoolean();
        final Thread reader,stderr;
        Channel(Process process,String identity){this.process=process;this.identity=identity;
            reader=new Thread(()->{try(var input=new BufferedInputStream(process.getInputStream())){while(!stopped.get()){
                var bytes=new ByteArrayOutputStream();int b;while((b=input.read())!=-1&&b!='\n'){if(bytes.size()>=MAX_FRAME)throw protocol("Evidence frame превышает 8 МиБ");bytes.write(b);}if(b==-1)throw new EOFException("Evidence worker завершился");
                String line=utf8(bytes.toByteArray());if(line.endsWith("\r"))line=line.substring(0,line.length()-1);frames.put(Json.object(line));
            }}catch(Throwable error){frames.offer(error);}},"mini-ai-evidence-reader");reader.setDaemon(true);reader.start();
            stderr=new Thread(()->{try(var input=process.getErrorStream()){byte[] bytes=new byte[4096];while(input.read(bytes)!=-1){/* drain without unbounded storage or disk logging */}}catch(IOException ignored){}},"mini-ai-evidence-stderr");stderr.setDaemon(true);stderr.start();
        }
        synchronized void write(Map<String,Object> frame){byte[] bytes=(Json.stringify(frame)+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_FRAME)throw protocol("Evidence request превышает 8 МиБ");
            try{process.getOutputStream().write(bytes);process.getOutputStream().flush();}
            catch(IOException error){throw new Api.ServiceException("EVIDENCE_TRANSPORT","Канал семантической модели закрыт",error);}}
        @SuppressWarnings("unchecked") Map<String,Object> read(Api.CancellationToken token,int seconds)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<until){token.check();Object frame=frames.poll(100,TimeUnit.MILLISECONDS);
            if(frame instanceof Map<?,?>)return (Map<String,Object>)frame;
            if(frame instanceof Api.ServiceException error)throw error;
            if(frame instanceof CharacterCodingException||frame instanceof IllegalArgumentException)throw protocol("Evidence worker передал неверный UTF-8 или JSON");
            if(frame instanceof IOException error)throw new Api.ServiceException("EVIDENCE_TRANSPORT","Канал семантической модели закрыт",error);
            if(frame instanceof Throwable error)throw new Api.ServiceException("EVIDENCE_PROTOCOL","Неожиданный сбой чтения evidence",error);
            if(!process.isAlive()&&frames.isEmpty())throw new Api.ServiceException("EVIDENCE_TRANSPORT","Семантическая модель завершилась");
            }throw new Api.ServiceException("EVIDENCE_TIMEOUT","Превышено ожидание evidence worker");}
        public void close(){if(stopped.compareAndSet(false,true)){process.destroyForcibly();reader.interrupt();stderr.interrupt();frames.clear();try{process.getOutputStream().close();}catch(IOException ignored){}try{process.getInputStream().close();}catch(IOException ignored){}try{process.getErrorStream().close();}catch(IOException ignored){}}}
    }
}
