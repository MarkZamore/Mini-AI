import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.util.*;
import java.nio.file.Path;
import java.util.concurrent.*;

public final class ContractTest {
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static void invalid(String json) { try { Json.parse(json); throw new AssertionError("Accepted invalid JSON: "+json); } catch(IllegalArgumentException expected) {} }
    public static void main(String[] args) throws Exception {
        var object=Json.object("{\"text\":\"Қазақша Русский 😀\\n\",\"nil\":null,\"n\":9223372036854775807,\"list\":[true,1.5]}");
        check(Json.object(Json.stringify(object)).equals(object),"JSON roundtrip");
        invalid("{\"a\":1,\"a\":2}"); invalid("[1,]"); invalid("01"); invalid("+1"); invalid("1."); invalid("1e"); invalid("[1] x"); invalid("\"bad\n\"");
        check(Json.integer(object.get("n"))==Long.MAX_VALUE,"Long identity");
        var token=new Api.CancellationToken(); int[] called={0}; token.onCancel(()->called[0]++); token.cancel(); token.cancel(); check(called[0]==1,"Cancel once");
        token.onCancel(()->called[0]++); check(called[0]==2,"Late cancellation");
        try { token.check(); throw new AssertionError("Cancel not checked"); } catch(Api.ServiceException e) { check(e.code.equals("CANCELLED"),"Cancel error"); }
        var original=new LinkedHashMap<String,Object>(); original.put("source_sha256","abc"); original.put("nullable",null);
        var ref=new Api.SourceRef(original); original.put("source_sha256","changed"); check(ref.sourceSha256().equals("abc"),"Source DTO copies map");
        var bbox=new ArrayList<>(List.of(1,2,3,4)); var nested=new Api.SourceRef(Map.of("bbox",bbox)); bbox.set(0,99); check(((List<?>)nested.fields().get("bbox")).getFirst().equals(1),"Nested DTO copy");
        var executor=Executors.newFixedThreadPool(2);
        Api.Platform platform=new Api.Platform() { public Path executablePath(){return Path.of("test.exe");} public long availableMemoryBytes(){return 1024;} public void openWith(Path p){} public Process launchWorker(Path e,Path d,long m){throw new UnsupportedOperationException();} };
        try(var context=new Api.Context(new Api.Environment(Path.of("."),executor,platform))) {
            check(Api.await(context.async(new Api.CancellationToken(),()->42))==42,"async result");
            var failed=context.async(new Api.CancellationToken(),()-> { throw new Api.ServiceException("MISSING","missing"); }).toCompletableFuture().join();
            check(failed.error().code().equals("MISSING"),"structured error");
            for(int i=0;i<600;i++) context.diagnostic("line "+i); check(context.diagnostics().size()==512,"bounded diagnostics");
        }
        ownership();
        System.out.println("ContractTest: PASS");
    }
    private static void ownership() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        var platformCloses=new java.util.concurrent.atomic.AtomicInteger();
        Api.Platform platform=new Api.Platform() {
            public Path executablePath(){return Path.of("test.exe");}
            public long availableMemoryBytes(){return 1024;}
            public void openWith(Path p){}
            public Process launchWorker(Path e,Path d,long m){throw new UnsupportedOperationException();}
            public void close(){platformCloses.incrementAndGet();}
        };
        var context=new Api.Context(new Api.Environment(Path.of("."),executor,platform));
        var order=Collections.synchronizedList(new ArrayList<String>());
        var lateCloses=new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable late=()->lateCloses.incrementAndGet();
        AutoCloseable first=()->order.add("first");
        AutoCloseable second=()-> {
            order.add("second");
            // The close callback waits on another thread registering a late resource.
            // Closing under the ownership lock would deadlock that lifecycle handoff.
            var handoff=new Thread(()->context.own(late));
            handoff.start();handoff.join(1000);
            check(!handoff.isAlive(),"Close must not hold the ownership lock while joining a worker");
        };
        check(context.own(first)==first,"Ownership returns the caller resource");
        context.own(second);context.own(first);
        context.close();context.close();context.own(late);
        check(order.equals(List.of("second","first")),"Reverse disposal order and identity deduplication");
        check(lateCloses.get()==1,"Late racing ownership disposes exactly once");
        check(platformCloses.get()==1 && executor.isShutdown(),"Idempotent context close frees its environment once");
        var afterCloses=new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable after=()->afterCloses.incrementAndGet();
        context.own(after);context.own(after);
        check(afterCloses.get()==1,"New resource after close is immediately released once");
        boolean refused=false;
        try{context.register(Runnable.class,()->{});}catch(Api.ServiceException error){refused=error.code.equals("CONTEXT_CLOSED");}
        check(refused && context.find(Runnable.class).isEmpty(),"Closed registry cannot be repopulated");
    }
}
