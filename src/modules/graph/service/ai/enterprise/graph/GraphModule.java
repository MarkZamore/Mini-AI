package ai.enterprise.graph;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Service implementation depends exclusively on versioned contracts. */
public final class GraphModule implements Api.ModuleFactory {
    public String id() { return "graph"; }
    public void register(Api.Context context) {
        GraphService service = new GraphService(context);
        context.register(Api.GraphServiceV1.class, service);
        context.command("graph", args -> Api.await(service.snapshot(new Api.CancellationToken())));
        context.command("neighbors", args -> {
            if (args.isEmpty()) throw new Api.ServiceException("ARGUMENT", "Укажите ID документа");
            int limit = args.size() > 2 ? Integer.parseInt(args.get(2)) : 20;
            return Api.await(service.neighbors(args.getFirst(), args.size() > 1 ? args.get(1) : null,
                    limit, new Api.CancellationToken()));
        });
    }

    public static final class GraphService implements Api.GraphServiceV1 {
        private final Api.Context context;
        private final AtomicReference<Api.GraphSnapshot> cached = new AtomicReference<>();
        public GraphService(Api.Context context) { this.context = context; }

        public CompletionStage<Api.Result<Api.GraphSnapshot>> snapshot(Api.CancellationToken cancellation) {
            Api.GraphSnapshot ready = cached.get();
            if (ready != null) return context.async(cancellation, () -> ready);
            var documents = context.find(Api.DocumentAccessServiceV1.class);
            if (documents.isEmpty()) return CompletableFuture.completedFuture(
                    Api.Result.fail("SERVICE_ABSENT", "Граф недоступен: отсутствует сервис документов"));
            return documents.get().catalog(cancellation).thenCompose(catalog -> {
                if (!catalog.success()) return CompletableFuture.completedFuture(
                        Api.Result.fail(catalog.error().code(), catalog.error().message()));
                return context.async(cancellation, () -> {
                    var database = context.require(Api.DatabaseServiceV1.class);
                    var rows = database.query("SELECT e.*,b.quality AS quality FROM edges e "
                            + "LEFT JOIN blocks b ON b.block_id=e.block_id WHERE e.target_id IS NOT NULL ORDER BY e.id",
                            List.of(), cancellation);
                    if (rows.stream().anyMatch(row -> row.get("quality") == null))
                        throw new Api.ServiceException("DATABASE_INCOMPATIBLE", "Для подтверждённой связи отсутствует исходный блок");
                    var edges = rows.stream().map(GraphService::edge).toList();
                    var ids = catalog.value().stream().map(Api.DocumentSummary::documentId).toList();
                    var idSet = new java.util.HashSet<>(ids);
                    for (Api.GraphEdge edge : edges) {
                        if (!idSet.contains(edge.sourceId()) || !idSet.contains(edge.targetId()))
                            throw new Api.ServiceException("DATABASE_INCOMPATIBLE", "Связь ссылается на неизвестный документ");
                    }
                    var links = edges.stream().map(e -> new GraphGeometry.Link(e.sourceId(), e.targetId())).toList();
                    var degrees = GraphGeometry.uniqueDegrees(ids, links);
                    var nodes = catalog.value().stream().map(document -> {
                        int degree = degrees.get(document.documentId());
                        return new Api.GraphNode(document, degree, GraphGeometry.radius(degree));
                    }).toList();
                    var unresolved = database.query("SELECT count(*) AS unresolved_count FROM edges WHERE target_id IS NULL",
                            List.of(), cancellation);
                    long unresolvedCount = Json.integer(unresolved.getFirst().get("unresolved_count"));
                    cancellation.check();
                    var result = new Api.GraphSnapshot(List.copyOf(nodes), List.copyOf(edges), unresolvedCount);
                    cached.compareAndSet(null, result);
                    return result;
                });
            }).exceptionally(Api::failure);
        }

        public CompletionStage<Api.Result<List<Api.GraphEdge>>> neighbors(String documentId, String relation,
                                                                          int limit, Api.CancellationToken cancellation) {
            return context.async(cancellation, () -> {
                var database = context.require(Api.DatabaseServiceV1.class);
                String sql = "SELECT e.*,b.quality AS quality FROM edges e JOIN blocks b ON b.block_id=e.block_id "
                        + "WHERE e.target_id IS NOT NULL AND (e.source_id=? OR e.target_id=?)";
                var parameters = new ArrayList<Object>();
                parameters.add(documentId); parameters.add(documentId);
                if (relation != null && !relation.isEmpty()) { sql += " AND e.relation=?"; parameters.add(relation); }
                sql += " ORDER BY e.id LIMIT ?";
                parameters.add(Math.min(100, Math.max(1, limit)));
                return database.query(sql, parameters, cancellation).stream().map(GraphService::edge).toList();
            });
        }

        private static Api.GraphEdge edge(Map<String, Object> row) {
            var decoded = new LinkedHashMap<>(row);
            for (String key : List.of("source_refs", "quality", "candidates")) {
                Object value = decoded.get(key);
                if (value instanceof String text) decoded.put(key, Json.parse(text));
            }
            return new Api.GraphEdge(Json.integer(decoded.get("id")), Json.string(decoded.get("source_id")),
                    Json.string(decoded.get("target_id")), Json.string(decoded.get("relation")), decoded);
        }
    }
}
