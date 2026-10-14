package ai.enterprise.tests;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.database.DatabaseModule;
import ai.enterprise.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Full source/provenance equality, original ranking, corpus stemming and Unicode normalization. */
public final class SearchParityTest {
    private static void require(boolean result,String detail){if(!result)throw new AssertionError(detail);}
    private static final class TestPlatform implements Api.Platform {
        private final Path root;TestPlatform(Path root){this.root=root;}
        public Path executablePath(){return root.resolve("EnterpriseAI.exe");}
        public long availableMemoryBytes(){return Runtime.getRuntime().freeMemory();}
        public void openWith(Path path){throw new UnsupportedOperationException();}
        public Process launchWorker(Path executable,Path dir,long bytes){throw new UnsupportedOperationException();}
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();Path data=root.resolve("src/build/test-results/search-parity");
        int normalization=0,stems=0;
        for(String line:Files.readAllLines(data.resolve("normalization.jsonl"))) {
            var item=(List<Object>)Json.parse(line);String text=(String)item.get(0);
            require(QueryNormalizer.nfc(text).equals(item.get(1)),"NFC mismatch: "+Json.stringify(item));
            require(QueryNormalizer.tokens(text).equals(item.get(2)),"Tokens mismatch: "+Json.stringify(item));normalization++;
        }
        try(var lines=Files.lines(data.resolve("russian.jsonl"))) {
            for(String line:lines.toList()) {var item=(List<Object>)Json.parse(line);require(QueryNormalizer.stem((String)item.get(0)).equals(item.get(1)),"Stem mismatch: "+Json.stringify(item));stems++;}
        }
        Path runtimeRoot=args.length>1?Path.of(args[1]).toAbsolutePath():root;
        var environment=new Api.Environment(runtimeRoot,Executors.newFixedThreadPool(4),new TestPlatform(runtimeRoot));
        try(var context=new Api.Context(environment)) {
            new DatabaseModule().register(context);new SearchModule().register(context);
            var search=context.require(Api.SearchServiceV1.class);var database=context.require(Api.DatabaseServiceV1.class);
            require(database.sqliteVersion().equals("3.53.1"),"Unexpected SQLite version "+database.sqliteVersion());
            var cases=(List<Object>)Json.parse(Files.readString(data.resolve("search.json")));
            for(Object value:cases) {
                var item=Json.map(value);String query=(String)item.get("query");
                var response=search.search(query,((Number)item.get("limit")).intValue(),(List<String>)item.get("document_ids"),(List<String>)item.get("sections"),new Api.CancellationToken()).toCompletableFuture().join();
                require(response.success(),"Query failed: "+query+" "+response.error());
                var hits=response.value();
                var actual=(List<Object>)Json.parse(Json.stringify(hits));var expected=(List<Object>)item.get("expected");
                require(actual.size()==expected.size(),"Result count mismatch: "+query);
                for(int i=0;i<actual.size();i++) {
                    var a=new LinkedHashMap<>(Json.map(actual.get(i)));var e=new LinkedHashMap<>(Json.map(expected.get(i)));
                    double as=((Number)a.remove("score")).doubleValue(),es=((Number)e.remove("score")).doubleValue();
                    require(Math.abs(as-es)<1e-12,"BM25 score mismatch "+query+" at "+i+": "+as+" != "+es);
                    require(a.equals(e),"Fragment/source mismatch "+query+" at "+i);
                    var fragment=Api.await(search.getFragment((String)a.get("fragment_id"),new Api.CancellationToken()));
                    require(fragment.fields().equals(a),"get_fragment mismatch "+query+" at "+i);
                }
            }
            for(String sql:List.of("CREATE TABLE forbidden(x)","DELETE FROM chunks","ATTACH DATABASE ':memory:' AS other","PRAGMA temp_store=FILE","SELECT load_extension('missing')","SELECT 1; SELECT 2")) {
                boolean rejected=false;try{database.query(sql,List.of(),new Api.CancellationToken());}catch(RuntimeException denied){rejected=true;}
                require(rejected,"Mutation statement accepted: "+sql);
            }
            var cancelled=new Api.CancellationToken();cancelled.cancel();
            require(!search.search("качество",10,List.of(),List.of(),cancelled).toCompletableFuture().join().success(),"Cancelled search accepted");
            // Interrupt a deliberately expensive recursive read; it must not hold up other operations.
            var interrupt=new Api.CancellationToken();
            var task=environment.executor().submit(()->database.query("WITH RECURSIVE counter(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM counter WHERE x<100000000) SELECT sum(x) FROM counter",List.of(),interrupt));
            Thread.sleep(50);interrupt.cancel();boolean interrupted=false;try{task.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.ExecutionException expected){interrupted=true;}
            require(interrupted,"SQL cancellation did not interrupt active statement");
            require(database.query("SELECT count(*) AS count FROM documents",List.of(),new Api.CancellationToken()).size()==1,"Connection unusable after interrupt");
            System.out.println("PASS: "+normalization+" NFC/token cases, "+stems+" corpus stems, "+cases.size()+" full-provenance search cases; read-only authorizer + cancellation");
        }
    }
}
