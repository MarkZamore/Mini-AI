package ai.enterprise.tests;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.inference.InferenceModule;
import ai.enterprise.inference.WorkerManifest;
import ai.enterprise.inference.WorkerPeAudit;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Real /MT worker transport plus adversarial PE parser tests; no model-quality claim. */
public final class StaticWorkerTest {
    private static int assertions;
    private static Path scratch;
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        Path fixtures = source.resolve("build/test-results/static-worker/packages");
        scratch = source.resolve("build/test-results/static-worker/cases"); Files.createDirectories(scratch);
        Path activeRoot = scratch.resolve("active"), model = activeRoot.resolve("model");
        List<Map<String,Object>> reports = new ArrayList<>();
        // Keep the same provider/core process while replacing whole closed-worker packages.
        Platform platform = new Platform(activeRoot);
        try (Api.Context context = new Api.Context(new Api.Environment(activeRoot, Executors.newFixedThreadPool(2), platform))) {
            new InferenceModule().register(context);
            var provider = context.require(Api.InferenceServiceV1.class);
            int variant = 0;
            for (String name : List.of("weak","medium","powerful")) {
                variant++; copyPackage(fixtures.resolve(name).resolve("model"), model);
                List<String> imports = WorkerPeAudit.verify(model.resolve("inference-worker.exe"), () -> false);
                check(imports.equals(List.of("kernel32.dll")), "actual /MT worker has only Windows import " + name);
                var manifest = verify(model);
                check(manifest.engine().equals("fixture-engine-"+name), "engine swapped " + name);
                check(manifest.revision().equals("fixture-revision-"+variant), "revision swapped " + name);
                check(provider.availability().value().name().equals(manifest.engine()), "same provider observes new package");
                List<String> tokens = new ArrayList<>();
                var result = generate(provider, "ordinary", tokens, new Api.CancellationToken());
                check(result.success() && tokens.equals(List.of("Variant"+variant)), "actual native response " + name);
                platform.last.waitFor(2,TimeUnit.SECONDS);
                check(!platform.last.isAlive(), "worker cleaned after done");
                reports.add(Map.of("package",name,"engine",manifest.engine(),"revision",manifest.revision(),"weights",model.relativize(manifest.weights()).toString(),"imports",imports,"workerSha256",hash(Files.readAllBytes(manifest.executable()))));
            }
            check(generate(provider,"fixture-error",new ArrayList<>(),new Api.CancellationToken()).error().code().equals("OUT_OF_MEMORY"), "native OOM isolated");
            check(generate(provider,"fixture-crash",new ArrayList<>(),new Api.CancellationToken()).error().code().equals("WORKER_EXITED"), "native crash isolated");
            CountDownLatch first = new CountDownLatch(1); Api.CancellationToken token = new Api.CancellationToken();
            var future = provider.generate(request("fixture-hang"), e -> {if(e.type().equals("token"))first.countDown();}, token).toCompletableFuture();
            check(first.await(5,TimeUnit.SECONDS), "native hanging worker started");
            long start=System.nanoTime(); token.cancel();
            check((System.nanoTime()-start)/1_000_000 < 200, "cancellation caller responsive");
            check(future.get(5,TimeUnit.SECONDS).error().code().equals("CANCELLED"), "native hang cancelled");
            platform.last.waitFor(2,TimeUnit.SECONDS); check(!platform.last.isAlive(), "native hung process killed");
            Map<String,Object> manifest = Json.object(Files.readString(model.resolve("worker.json")));
            Path weights=model.resolve(Json.string(((Map<?,?>)manifest.get("weights")).get("path")));
            Files.writeString(weights,"incompatible"); rehash(manifest,model,model.relativize(weights).toString().replace('\\','/'));
            Files.writeString(model.resolve("worker.json"),Json.stringify(manifest));
            check(generate(provider,"ordinary",new ArrayList<>(),new Api.CancellationToken()).error().code().equals("INCOMPATIBLE_MODEL"), "worker validates model content before ready");
            copyPackage(fixtures.resolve("weak/model"), model);
            Path dll=model.resolve("listed.dll"); Files.write(dll,new byte[]{1}); manifest=Json.object(Files.readString(model.resolve("worker.json")));
            List<Object> entries=new ArrayList<>((List<?>)manifest.get("files")); entries.add(Map.of("path","listed.dll","sha256",hash(Files.readAllBytes(dll)))); manifest.put("files",entries);
            final Map<String,Object> dllManifest = new LinkedHashMap<>(manifest);
            expectCode(() -> WorkerManifest.verify(dllManifest,model,Set.of("x86-64"),1L<<30,()->false),"MODEL_PACKAGE_NOT_SELF_CONTAINED","hashed DLL rejected");
            Files.delete(dll); Files.copy(model.resolve("inference-worker.exe"),model.resolve("additional.exe"),StandardCopyOption.REPLACE_EXISTING);
            expectCode(() -> verify(model),"MODEL_PACKAGE_NOT_SELF_CONTAINED","extra executable rejected"); Files.delete(model.resolve("additional.exe"));
            Files.write(model.resolve("inference-worker.exe"),new byte[]{1}); manifest=Json.object(Files.readString(model.resolve("worker.json"))); rehash(manifest,model,"inference-worker.exe"); Files.writeString(model.resolve("worker.json"),Json.stringify(manifest));
            int launches=platform.launches;
            check(generate(provider,"ordinary",new ArrayList<>(),new Api.CancellationToken()).error().code().equals("MODEL_PACKAGE_NOT_SELF_CONTAINED"), "corrupt worker rejected before launch");
            check(platform.launches==launches,"no launch of corrupt PE");
            copyPackage(fixtures.resolve("weak/model"),model);
            expectFailure(() -> WorkerManifest.verify(Json.object(Files.readString(model.resolve("worker.json"))),model,Set.of(),1L<<30,()->false),"ISA remains enforced");
            expectFailure(() -> WorkerManifest.verify(Json.object(Files.readString(model.resolve("worker.json"))),model,Set.of("x86-64"),1,()->false),"memory remains enforced");
            try {WorkerManifest.verify(Json.object(Files.readString(model.resolve("worker.json"))),model,Set.of("x86-64"),1L<<30,()->true); throw new AssertionError("cancel ignored");} catch(CancellationException expected){check(true,"validation cancellation");}
        }
        peTests();
        var report=Map.of("assertions",assertions,"packages",reports,"realNativeWorkers",true,"modelQualityTested",false);
        Files.writeString(source.resolve("build/test-results/static-worker/report.json"),Json.stringify(report));
        System.out.println("Standalone worker checks passed: "+assertions);
    }
    private static Api.GenerationRequest request(String text) {return new Api.GenerationRequest("static-worker-test",List.of(new Api.ModelMessage("user",text)),16);}
    private static Api.Result<Void> generate(Api.InferenceServiceV1 provider,String text,List<String> tokens,Api.CancellationToken cancellation)throws Exception {
        return provider.generate(request(text),e->{if(e.type().equals("token"))tokens.add(e.text());},cancellation).toCompletableFuture().get(8,TimeUnit.SECONDS);
    }
    private static WorkerManifest verify(Path model)throws Exception {return WorkerManifest.verify(Json.object(Files.readString(model.resolve("worker.json"))),model,Set.of("x86-64"),1L<<30,()->false);}
    private static void copyPackage(Path source,Path target)throws Exception {
        if(Files.exists(target))try(var paths=Files.walk(target)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){if(!p.toAbsolutePath().normalize().startsWith(scratch))throw new AssertionError("unsafe test cleanup");Files.delete(p);}}
        try(var paths=Files.walk(source)){for(Path p:paths.toList()){Path dest=target.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(dest);else Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING);}}
    }
    @SuppressWarnings("unchecked") private static void rehash(Map<String,Object> manifest,Path root,String name)throws Exception {
        var replacement=Map.of("path",name,"sha256",hash(Files.readAllBytes(root.resolve(name))));
        List<Object> entries=new ArrayList<>();for(Object item:(List<?>)manifest.get("files")){Map<String,Object> entry=(Map<String,Object>)item;entries.add(name.equals(entry.get("path"))?replacement:entry);}manifest.put("files",entries);
        if(name.equals(((Map<?,?>)manifest.get("weights")).get("path")))manifest.put("weights",replacement);
    }
    private static void peTests()throws Exception {
        for(boolean delay:List.of(false,true)){
            check(audit(pe("KERNEL32.dll",delay)).equals(List.of("kernel32.dll")),"synthetic Windows import " + delay);
            check(audit(pe("api-ms-win-core-synch-l1-2-0.dll",delay)).size()==1,"Windows API set import " + delay);
            for(String name:List.of("VCRUNTIME140.dll","VCRUNTIME140_1.dll","MSVCP140.dll","libomp.dll","nvcuda.dll","sycl8.dll","../kernel32.dll","api-ms-win-untrusted-l1-1-0.dll","ext-ms-win-untrusted-l1-1-0.dll"))
                expectCode(()->audit(pe(name,delay)),"MODEL_PACKAGE_NOT_SELF_CONTAINED","external/path import " + name + " delay=" + delay);
            byte[] invalid=pe("kernel32.dll",delay);ByteBuffer b=ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x200+(delay?4:12),0xfffffff0);
            expectCode(()->audit(invalid),"MODEL_PACKAGE_NOT_SELF_CONTAINED","name RVA overflow " + delay);
            byte[] badDir=pe("kernel32.dll",delay);ByteBuffer.wrap(badDir).order(ByteOrder.LITTLE_ENDIAN).putInt(0x98+112+(delay?13:1)*8,0xffffff00);
            expectCode(()->audit(badDir),"MODEL_PACKAGE_NOT_SELF_CONTAINED","directory RVA overflow " + delay);
            byte[] noTerm=pe("kernel32.dll",delay);ByteBuffer.wrap(noTerm).order(ByteOrder.LITTLE_ENDIAN).putInt(0x98+116+(delay?13:1)*8,delay?32:20);
            expectCode(()->audit(noTerm),"MODEL_PACKAGE_NOT_SELF_CONTAINED","unterminated descriptors " + delay);
        }
        byte[] base=pe("kernel32.dll",false);
        for(int length:List.of(0,1,63,64,127,151,200,511,600)){
            byte[] truncated=Arrays.copyOf(base,length);expectCode(()->audit(truncated),"MODEL_PACKAGE_NOT_SELF_CONTAINED","truncated PE " + length);
        }
        for(int offset:List.of(0x84,0x86,0x94,0x96,0x98,0x98+108)){
            byte[] bad=base.clone();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putShort(offset,(short)0xffff);
            expectCode(()->audit(bad),"MODEL_PACKAGE_NOT_SELF_CONTAINED","malformed PE field " + offset);
        }
        byte[] overlapping=base.clone();var b=ByteBuffer.wrap(overlapping).order(ByteOrder.LITTLE_ENDIAN);b.putShort(0x86,(short)2);int section=0x98+240;System.arraycopy(overlapping,section,overlapping,section+40,40);
        expectCode(()->audit(overlapping),"MODEL_PACKAGE_NOT_SELF_CONTAINED","overlapping sections");
        byte[] va=pe("kernel32.dll",true);var v=ByteBuffer.wrap(va).order(ByteOrder.LITTLE_ENDIAN);v.putLong(0x98+24,0x400000);v.putInt(0x200,0);v.putInt(0x204,0x401080);
        check(audit(va).equals(List.of("kernel32.dll")),"legacy delay VA handled");
        v.putInt(0x204,0x300000);expectCode(()->audit(va),"MODEL_PACKAGE_NOT_SELF_CONTAINED","delay VA underflow");
        byte[] attributes=pe("kernel32.dll",true);ByteBuffer.wrap(attributes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200,2);
        expectCode(()->audit(attributes),"MODEL_PACKAGE_NOT_SELF_CONTAINED","unknown delay attributes");
    }
    private static byte[] pe(String name,boolean delay){
        byte[] bytes=new byte[1024];var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putShort(0,(short)0x5a4d);b.putInt(60,0x80);b.putInt(0x80,0x4550);b.putShort(0x84,(short)0x8664);b.putShort(0x86,(short)1);b.putShort(0x94,(short)240);b.putShort(0x96,(short)2);
        int o=0x98;b.putShort(o,(short)0x20b);b.putLong(o+24,0x140000000L);b.putInt(o+60,512);b.putShort(o+68,(short)3);b.putInt(o+108,16);int dir=delay?13:1;b.putInt(o+112+dir*8,0x1000);b.putInt(o+116+dir*8,delay?64:40);
        int s=o+240;b.putInt(s+8,512);b.putInt(s+12,0x1000);b.putInt(s+16,512);b.putInt(s+20,512);if(delay)b.putInt(0x200,1);b.putInt(0x200+(delay?4:12),0x1080);byte[] n=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(n,0,bytes,0x280,n.length);return bytes;
    }
    private static List<String> audit(byte[] bytes)throws Exception {Path file=scratch.resolve("parser.exe");Files.write(file,bytes);return WorkerPeAudit.verify(file,()->false);}
    private static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void expectCode(Action action,String code,String message)throws Exception{try{action.run();}catch(Api.ServiceException e){check(e.code.equals(code),message+": "+e.code);return;}throw new AssertionError("expected error "+message);}
    private static void expectFailure(Action action,String message)throws Exception{try{action.run();}catch(IllegalArgumentException e){check(true,message);return;}throw new AssertionError("expected error "+message);}
    private static final class Platform implements Api.Platform {
        final Path root;Process last;int launches;Platform(Path root){this.root=root;}
        public Path executablePath(){return root.resolve("EnterpriseAI.exe");}public long availableMemoryBytes(){return 1L<<30;}public void openWith(Path file){throw new UnsupportedOperationException();}
        public Process launchWorker(Path exe,Path directory,long memory){try{launches++;return last=new ProcessBuilder(exe.toString(),"--stdio").directory(directory.toFile()).start();}catch(Exception e){throw new RuntimeException(e);}}
    }
}
