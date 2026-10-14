package ai.enterprise.tests;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.database.DatabaseModule;
import ai.enterprise.search.SearchModule;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Same 20 queries and limit=10 as db/report.json. Does not alter existing reports. */
public final class SearchBenchmark {
    private static final class Platform implements Api.Platform {
        private final Path root;Platform(Path root){this.root=root;}
        public Path executablePath(){return root.resolve("EnterpriseAI.exe");}
        public long availableMemoryBytes(){return Runtime.getRuntime().freeMemory();}
        public void openWith(Path file){throw new UnsupportedOperationException();}
        public Process launchWorker(Path executable,Path directory,long bytes){throw new UnsupportedOperationException();}
    }
    private static double percentile(List<Double> numbers,double fraction){var sorted=new ArrayList<>(numbers);sorted.sort(Double::compare);return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*fraction)-1));}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Path project=Path.of(args[0]).toAbsolutePath(),runtime=Path.of(args[1]).toAbsolutePath();
        var source=Json.object(Files.readString(project.resolve("db/report.json")));
        var queryRows=(List<Object>)Json.map(source.get("search_benchmark")).get("queries");
        var queries=queryRows.stream().map(Json::map).map(m->Json.string(m.get("query"))).toList();
        long start=System.nanoTime();
        var environment=new Api.Environment(runtime,Executors.newFixedThreadPool(4),new Platform(runtime));
        try(var context=new Api.Context(environment)) {
            new DatabaseModule().register(context);new SearchModule().register(context);
            double openMs=(System.nanoTime()-start)/1_000_000.0;
            var search=context.require(Api.SearchServiceV1.class);
            long first=System.nanoTime();Api.await(search.search(queries.getFirst(),10,List.of(),List.of(),new Api.CancellationToken()));double firstMs=(System.nanoTime()-first)/1_000_000.0;
            for(int warm=0;warm<2;warm++)for(String query:queries)Api.await(search.search(query,10,List.of(),List.of(),new Api.CancellationToken()));
            var measurements=new LinkedHashMap<String,List<Double>>();var all=new ArrayList<Double>();
            for(String query:queries)measurements.put(query,new ArrayList<>());
            for(int repeat=0;repeat<10;repeat++)for(String query:queries) {
                long then=System.nanoTime();var hits=Api.await(search.search(query,10,List.of(),List.of(),new Api.CancellationToken()));
                double ms=(System.nanoTime()-then)/1_000_000.0;all.add(ms);measurements.get(query).add(ms);
                if(hits.size()!=10)throw new AssertionError("Unexpected hit count "+query);
            }
            var rows=new ArrayList<Map<String,Object>>();for(var entry:measurements.entrySet())rows.add(Map.of("query",entry.getKey(),"samples",entry.getValue().size(),"p50_ms",percentile(entry.getValue(),.5),"p95_ms",percentile(entry.getValue(),.95)));
            var report=Map.of("sqlite_version",context.require(Api.DatabaseServiceV1.class).sqliteVersion(),"queries",rows,"warm_cache",true,"runtime",System.getProperty("java.vm.name")+" "+System.getProperty("java.version"),"samples",all.size(),"p50_ms",percentile(all,.5),"p95_ms",percentile(all,.95),"database_open_ms",openMs,"first_query_including_normalization_tables_ms",firstMs);
            String json=Json.stringify(report);Path target=project.resolve("src/build/test-results/java-search-benchmark.json");Files.writeString(target,json);System.out.println(json);
        }
    }
}
