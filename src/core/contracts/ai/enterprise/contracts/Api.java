package ai.enterprise.contracts;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Versioned, GUI-free service contracts. No optional implementation is referenced here. */
public final class Api {
    private Api() {}
    public static final String VERSION = "1.0";
    public record ServiceError(String code, String message) {}
    public record Result<T>(T value, ServiceError error) {
        public boolean success() { return error == null; }
        public static <T> Result<T> ok(T value) { return new Result<>(value, null); }
        public static <T> Result<T> fail(String code, String message) { return new Result<>(null, new ServiceError(code,message)); }
    }
    public static final class ServiceException extends RuntimeException {
        public final String code;
        public ServiceException(String code,String message) { super(message); this.code=code; }
        public ServiceException(String code,String message,Throwable cause) { super(message,cause); this.code=code; }
    }
    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        public boolean cancelled() { return cancelled.get(); }
        public void check() { if(cancelled()) throw new ServiceException("CANCELLED","Операция отменена"); }
        public void cancel() { if(cancelled.compareAndSet(false,true)) { for(var listener:listeners) try { listener.run(); } catch(RuntimeException ignored) {} listeners.clear(); } }
        public void onCancel(Runnable action) { var once=new AtomicBoolean(); Runnable guarded=()->{if(once.compareAndSet(false,true))action.run();}; listeners.add(guarded); if(cancelled() && listeners.remove(guarded)) guarded.run(); }
    }
    public interface ModuleFactory { String id(); void register(Context context); }
    public interface Platform extends AutoCloseable {
        Path executablePath();
        long availableMemoryBytes();
        default boolean cpuSupports(String isa) { return isa.equalsIgnoreCase("x86-64") || isa.equalsIgnoreCase("baseline"); }
        /** Explicit opt-in by the owner of the current GUI thread; embedding never calls this implicitly. */
        default String prepareDesktopUi() { return ""; }
        void openWith(Path original);
        Process launchWorker(Path executable, Path directory, long maxMemoryBytes);
        default void close() {}
    }
    public record Environment(Path root, ExecutorService executor, Platform platform) {
        public Environment { root=root.toAbsolutePath().normalize(); Objects.requireNonNull(executor); Objects.requireNonNull(platform); }
        public Path db() { return root.resolve("db"); }
        public Path docs() { return root.resolve("docs"); }
        public Path model() { return root.resolve("model"); }
        public Path nativeDir() { return root.resolve("native"); }
    }
    public static final class Context implements AutoCloseable {
        private final Environment environment;
        private final ConcurrentHashMap<Class<?>,Object> services = new ConcurrentHashMap<>();
        private final LinkedHashMap<String,Function<List<String>,Object>> commands = new LinkedHashMap<>();
        private final ArrayDeque<String> diagnostics = new ArrayDeque<>();
        private final ArrayList<AutoCloseable> closeables = new ArrayList<>();
        private final Set<AutoCloseable> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        private volatile boolean closed;
        public Context(Environment environment) { this.environment=environment; }
        public Environment environment() { return environment; }
        public <T> void register(Class<T> type,T service) {
            synchronized(closeables) {
                if(closed) throw new ServiceException("CONTEXT_CLOSED","Компонент завершён");
                if(services.putIfAbsent(type,service)!=null) throw new IllegalStateException("Duplicate service: "+type.getName());
                if(service instanceof AutoCloseable c && owned.add(c)) closeables.add(c);
            }
        }
        /** Owns background/view resources without importing their optional implementations.
         * A resource arriving concurrently with close is closed immediately, exactly once.
         */
        public <T extends AutoCloseable> T own(T resource) {
            Objects.requireNonNull(resource);
            boolean closeNow;
            synchronized(closeables) {
                if(!owned.add(resource)) return resource;
                closeNow=closed;
                if(!closeNow) closeables.add(resource);
            }
            if(closeNow) closeResource(resource);
            return resource;
        }
        private void closeResource(AutoCloseable resource) { try { resource.close(); } catch(Exception error) { if(!closed) diagnostic(error.toString()); } }
        public <T> Optional<T> find(Class<T> type) { return Optional.ofNullable(type.cast(services.get(type))); }
        public <T> T require(Class<T> type) { return find(type).orElseThrow(()->new ServiceException("SERVICE_ABSENT","Недоступен сервис: "+type.getSimpleName())); }
        public synchronized void command(String name,Function<List<String>,Object> action) { if(commands.putIfAbsent(name,action)!=null) throw new IllegalStateException("Duplicate command: "+name); }
        public synchronized Set<String> commands() { return Set.copyOf(commands.keySet()); }
        public Object execute(String name,List<String> args) { Function<List<String>,Object> action; synchronized(this) { action=commands.get(name); } if(action==null) throw new ServiceException("COMMAND_ABSENT","Недоступна команда: "+name); return action.apply(args); }
        public synchronized void diagnostic(String message) { if(diagnostics.size()==512) diagnostics.removeFirst(); diagnostics.addLast(Instant.now()+" "+message); }
        public synchronized List<String> diagnostics() { return List.copyOf(diagnostics); }
        public <T> CompletionStage<Result<T>> async(CancellationToken token,Callable<T> action) {
            return CompletableFuture.supplyAsync(()-> { try { token.check(); return Result.ok(action.call()); } catch(Throwable error) { return failure(error); } },environment.executor());
        }
        public void close() {
            List<AutoCloseable> resources;
            synchronized(closeables) {
                if(closed) return;
                closed=true;
                resources=List.copyOf(closeables);
                closeables.clear();
            }
            // Joining a worker while holding the ownership lock could block a late owner.
            for(int i=resources.size()-1;i>=0;i--) closeResource(resources.get(i));
            try { environment.executor().shutdownNow(); environment.platform().close(); }
            finally { services.clear(); synchronized(this) { commands.clear(); diagnostics.clear(); } }
        }
    }
    public static <T> Result<T> failure(Throwable cause) { while(cause instanceof CompletionException || cause instanceof ExecutionException) cause=cause.getCause(); if(cause instanceof ServiceException s) return Result.fail(s.code,s.getMessage()); if(cause instanceof OutOfMemoryError) return Result.fail("OUT_OF_MEMORY","Недостаточно памяти"); return Result.fail("FAILED",cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage()); }
    public static <T> T await(CompletionStage<Result<T>> stage) { var r=stage.toCompletableFuture().join(); if(!r.success()) throw new ServiceException(r.error().code(),r.error().message()); return r.value(); }
    public static Map<String,Object> freeze(Map<String,Object> source) { var copy=new LinkedHashMap<String,Object>(); source.forEach((key,value)->copy.put(key,freezeValue(value))); return Collections.unmodifiableMap(copy); }
    @SuppressWarnings("unchecked") private static Object freezeValue(Object value) {
        if(value instanceof Map<?,?> map) return freeze((Map<String,Object>)map);
        if(value instanceof List<?> list) { var copy=new ArrayList<>(); for(Object item:list)copy.add(freezeValue(item)); return Collections.unmodifiableList(copy); }
        return value;
    }
    public record DocumentSummary(String documentId,String title,String sourceSha256,String sourcePath,String jsonPath,String status,List<String> sections,List<Map<String,Object>> warnings) { public DocumentSummary { sections=List.copyOf(sections); warnings=warnings.stream().map(Api::freeze).toList(); } }
    public record SourceRef(Map<String,Object> fields) {
        public SourceRef { fields=freeze(fields); }
        public String sourceSha256() { return Objects.toString(fields.get("source_sha256"),""); }
        public String artifactId() { return Objects.toString(fields.get("artifact_id"),""); }
        public String kind() { return Objects.toString(fields.get("kind"),""); }
    }
    public record DerivationStep(String documentId,String artifactId,Path path,String sha256,String derivedFrom) {}
    public record SourceResolution(String documentId,Path originalPath,Path path,String sourceSha256,SourceRef locator,List<DerivationStep> derivationChain,boolean verified) { public SourceResolution { derivationChain=List.copyOf(derivationChain); } }
    public record Fragment(Map<String,Object> fields) {
        public Fragment { fields=freeze(fields); }
        public String fragmentId() { return Objects.toString(fields.get("fragment_id"),""); }
        public String documentId() { return Objects.toString(fields.get("document_id"),""); }
        public String title() { return Objects.toString(fields.get("title"),""); }
        public String text() { return Objects.toString(fields.get("text"),""); }
        @SuppressWarnings("unchecked") public List<SourceRef> sourceRefs() { Object refs=fields.get("source_refs"); return refs instanceof List<?> list?list.stream().map(r->new SourceRef((Map<String,Object>)r)).toList():List.of(); }
    }
    public record SearchHit(Fragment fragment,double score) {}
    public record GraphNode(DocumentSummary document,int degree,double radius) { public String documentId() { return document.documentId(); } }
    public record GraphEdge(long id,String sourceId,String targetId,String relation,Map<String,Object> fields) { public GraphEdge { fields=freeze(fields); } }
    public record GraphSnapshot(List<GraphNode> nodes,List<GraphEdge> edges,long unresolvedCount) { public GraphSnapshot { nodes=List.copyOf(nodes); edges=List.copyOf(edges); } }
    public interface DatabaseServiceV1 extends AutoCloseable {
        List<Map<String,Object>> query(String sql,List<?> parameters,CancellationToken cancellation);
        String sqliteVersion();
        default void close() {}
    }
    public interface SearchServiceV1 {
        CompletionStage<Result<List<SearchHit>>> search(String query,int limit,List<String> documentIds,List<String> sections,CancellationToken cancellation);
        CompletionStage<Result<Fragment>> getFragment(String fragmentId,CancellationToken cancellation);
    }
    public interface DocumentAccessServiceV1 {
        CompletionStage<Result<List<DocumentSummary>>> catalog(CancellationToken cancellation);
        CompletionStage<Result<DocumentSummary>> document(String documentId,CancellationToken cancellation);
        CompletionStage<Result<Map<String,Object>>> canonical(String documentId,CancellationToken cancellation);
        CompletionStage<Result<SourceResolution>> resolveSource(SourceRef sourceRef,CancellationToken cancellation);
    }
    public interface GraphServiceV1 {
        CompletionStage<Result<GraphSnapshot>> snapshot(CancellationToken cancellation);
        CompletionStage<Result<List<GraphEdge>>> neighbors(String documentId,String relation,int limit,CancellationToken cancellation);
    }
    public interface SourceViewerServiceV1 {
        CompletionStage<Result<Void>> openDocument(String documentId,CancellationToken cancellation);
        CompletionStage<Result<Void>> openSource(SourceRef sourceRef,CancellationToken cancellation);
    }
    public record ProviderInfo(String name,String precision,int contextTokens,int threads,long minFreeMemoryBytes) {}
    public record ModelMessage(String role,String content) {}
    public record GenerationRequest(String requestId,List<ModelMessage> messages,int maxTokens) { public GenerationRequest { messages=List.copyOf(messages); } }
    public record GenerationEvent(String type,String requestId,String text,ServiceError error) {}
    public interface InferenceServiceV1 extends AutoCloseable {
        Result<ProviderInfo> availability();
        CompletionStage<Result<Void>> generate(GenerationRequest request,Consumer<GenerationEvent> events,CancellationToken cancellation);
        default void close() {}
    }
    public record SessionMessage(String role,String text,List<Fragment> sources,String state) { public SessionMessage { sources=List.copyOf(sources); } }
    public record AssistantEvent(String type,String text,List<Fragment> sources,ServiceError error) { public AssistantEvent { sources=List.copyOf(sources); } }
    public interface AssistantSessionServiceV1 extends AutoCloseable {
        List<SessionMessage> history();
        CompletionStage<Result<Void>> send(String query,Consumer<AssistantEvent> events,CancellationToken cancellation);
        default void close() {}
    }
}
