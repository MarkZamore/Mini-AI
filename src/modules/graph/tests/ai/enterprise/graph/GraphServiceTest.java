package ai.enterprise.graph;

import ai.enterprise.contracts.Api;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;

/** Checks contract behavior and SQL shape without creating or changing a database. */
public final class GraphServiceTest {
    public static void main(String[] args) {
        var platform = new Api.Platform() {
            public Path executablePath() { return Path.of("EnterpriseAI.exe").toAbsolutePath(); }
            public long availableMemoryBytes() { return 0; }
            public void openWith(Path path) { throw new UnsupportedOperationException(); }
            public Process launchWorker(Path file, Path folder, long limit) { throw new UnsupportedOperationException(); }
        };
        try (var context = new Api.Context(new Api.Environment(Path.of("."), Executors.newFixedThreadPool(2), platform))) {
            var service = new GraphModule.GraphService(context);
            var absent = service.snapshot(new Api.CancellationToken()).toCompletableFuture().join();
            require(!absent.success() && absent.error().code().equals("SERVICE_ABSENT"), "Missing document service explained");
            context.register(Api.DocumentAccessServiceV1.class, new Documents());
            var db = new Database();
            context.register(Api.DatabaseServiceV1.class, db);
            var graph = Api.await(service.snapshot(new Api.CancellationToken()));
            require(graph.nodes().size() == 4 && graph.edges().size() == 4 && graph.unresolvedCount() == 7,
                    "Complete catalog, confirmed evidence and unresolved count");
            require(graph.nodes().stream().filter(n -> n.documentId().equals("D")).findFirst().orElseThrow().degree() == 0,
                    "Isolated document retained");
            require(graph.nodes().stream().filter(n -> n.documentId().equals("B")).findFirst().orElseThrow().degree() == 2,
                    "Repeated and reverse references count once");
            require(graph.edges().getFirst().fields().get("source_refs") instanceof List<?>, "Source locator JSON decoded");
            require(graph.edges().getFirst().fields().get("quality") instanceof Map<?, ?>, "Quality JSON decoded");
            int queries = db.queries;
            require(Api.await(service.snapshot(new Api.CancellationToken())) == graph && db.queries == queries,
                    "Snapshot stays in memory");
            Api.await(service.neighbors("A", "references", 9_999, new Api.CancellationToken()));
            require(db.parameters.equals(List.of("A", "A", "references", 100)), "Parameterized relation and maximum limit");
            require(db.sql.contains("ORDER BY e.id LIMIT ?"), "Deterministic neighbors ordering");
            Api.await(service.neighbors("A", null, 0, new Api.CancellationToken()));
            require(db.parameters.equals(List.of("A", "A", 1)), "Minimum limit and absent relation");
            var cancelled = new Api.CancellationToken(); cancelled.cancel();
            var failure = service.snapshot(cancelled).toCompletableFuture().join();
            require(!failure.success() && failure.error().code().equals("CANCELLED"), "Cached snapshot honors cancellation");
        }
        System.out.println("GraphServiceTest: PASS");
    }
    private static void require(boolean truth, String message) { if (!truth) throw new AssertionError(message); }
    private static final class Database implements Api.DatabaseServiceV1 {
        int queries;
        String sql;
        List<?> parameters;
        public String sqliteVersion() { return "test"; }
        public List<Map<String, Object>> query(String sql, List<?> parameters, Api.CancellationToken token) {
            this.sql = sql; this.parameters = List.copyOf(parameters); queries++; token.check();
            if (sql.contains("count(*)")) return List.of(Map.of("unresolved_count", 7));
            return List.of(row(1, "A", "B"), row(2, "A", "B"), row(3, "B", "A"), row(4, "B", "C"));
        }
        private static Map<String, Object> row(long id, String a, String b) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", id); row.put("source_id", a); row.put("target_id", b); row.put("relation", "references");
            row.put("source_refs", "[{\"kind\":\"pdf_region\",\"page\":1}]");
            row.put("quality", "{\"needs_review\":true}"); row.put("candidates", "[]"); return row;
        }
    }
    private static final class Documents implements Api.DocumentAccessServiceV1 {
        public CompletionStage<Api.Result<List<Api.DocumentSummary>>> catalog(Api.CancellationToken token) {
            var docs = new ArrayList<Api.DocumentSummary>();
            for (String id : List.of("A", "B", "C", "D")) docs.add(new Api.DocumentSummary(id, id, "sha", "", "",
                    "partial", List.of(), List.of()));
            return CompletableFuture.completedFuture(Api.Result.ok(List.copyOf(docs)));
        }
        public CompletionStage<Api.Result<Api.DocumentSummary>> document(String id, Api.CancellationToken token) { throw new UnsupportedOperationException(); }
        public CompletionStage<Api.Result<Map<String, Object>>> canonical(String id, Api.CancellationToken token) { throw new UnsupportedOperationException(); }
        public CompletionStage<Api.Result<Api.SourceResolution>> resolveSource(Api.SourceRef ref, Api.CancellationToken token) { throw new UnsupportedOperationException(); }
    }
}
