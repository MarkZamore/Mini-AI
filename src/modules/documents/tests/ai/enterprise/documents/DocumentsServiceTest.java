package ai.enterprise.documents;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/** Contract and provenance tests with an in-memory database and disposable build fixtures. */
public final class DocumentsServiceTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "src/build/tests/documents-service" : args[0]).toAbsolutePath().resolve(UUID.randomUUID().toString());
        Path db = Files.createDirectories(root.resolve("db"));
        Path docs = Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(db.resolve("documents"));
        Path original = docs.resolve("Оригинал ә ң.txt");
        Files.writeString(original, "Документ организации\n😀", StandardCharsets.UTF_8);
        String sha = VerifiedPaths.sha256(original, () -> false);
        var row = new LinkedHashMap<String,Object>();
        row.put("document_id", "org:document:1"); row.put("title", "Әмбебап документ"); row.put("source_sha256", sha);
        row.put("source_path", "../docs/" + original.getFileName()); row.put("json_path", "documents/1.json"); row.put("status", "partial");
        row.put("sections", "[\"Ә бөлім\"]"); row.put("warnings", "[{\"code\":\"ocr_unverified\",\"message\":\"Не сверено\"}]");
        var database = new FakeDatabase(row);
        var executor = Executors.newFixedThreadPool(2);
        try (var context = new Api.Context(new Api.Environment(root, executor, new FakePlatform(root)))) {
            context.register(Api.DatabaseServiceV1.class, database);
            new DocumentsModule().register(context);
            var service = context.require(Api.DocumentAccessServiceV1.class);
            var token = new Api.CancellationToken();
            check(Api.await(service.catalog(token)).size() == 1, "Catalog count");
            check(Api.await(service.document("org:document:1", token)).sections().getFirst().equals("Ә бөлім"), "Decoded Unicode sections");
            failure(service.document("missing", token).toCompletableFuture().join(), "DOCUMENT_UNKNOWN");

            var canonical = new LinkedHashMap<String,Object>();
            canonical.put("schema_version", "1.0"); canonical.put("document_id", "org:document:1"); canonical.put("source_version_sha256", sha);
            canonical.put("source", Map.of("path", row.get("source_path"), "sha256", sha));
            canonical.put("blocks", List.of(Map.of("text", "😀", "source_refs", List.of(Map.of("char_start", 1L, "char_end", 2L)))));
            Files.writeString(db.resolve("documents/1.json"), Json.stringify(canonical), StandardCharsets.UTF_8);
            var loaded = Api.await(service.canonical("org:document:1", token));
            check(Json.map(((List<?>)loaded.get("blocks")).getFirst()).get("text").equals("😀"), "Canonical content");
            try { Json.map(loaded.get("source")).put("path", "edited"); throw new AssertionError("Canonical nested map is mutable"); }
            catch (UnsupportedOperationException expected) {}
            canonical.put("schema_version", "2.0");
            Files.writeString(db.resolve("documents/1.json"), Json.stringify(canonical), StandardCharsets.UTF_8);
            failure(service.canonical("org:document:1", token).toCompletableFuture().join(), "SCHEMA_INCOMPATIBLE");

            var originalRef = new Api.SourceRef(Map.of("artifact_id", "original", "source_sha256", sha, "kind", "artifact", "char_start", 1L, "char_end", 2L));
            var resolution = Api.await(service.resolveSource(originalRef, token));
            check(resolution.originalPath().equals(original.toRealPath()) && resolution.path().equals(original.toRealPath()) && resolution.verified(), "Original resolution");
            check(resolution.locator().fields().get("char_start").equals(1L), "Code-point offset preserved");

            List<Map<String,Object>> typedLocators = List.of(
                    Map.of("artifact_id", "original", "source_sha256", sha, "kind", "pdf_region", "page", 1L, "bbox", List.of(1.1,2.2,3.3,4.4), "page_size", List.of(595.0,842.0)),
                    Map.of("artifact_id", "original", "source_sha256", sha, "kind", "ooxml_node", "part", "word/document.xml", "xpath", "/*[1]/*[2]"),
                    Map.of("artifact_id", "original", "source_sha256", sha, "kind", "sheet_range", "sheet", "Қазақ парағы", "range", "A1:D5"),
                    Map.of("artifact_id", "original", "source_sha256", sha, "kind", "drawing", "part", "xl/drawings/drawing1.xml", "xpath", "/*[1]", "anchor", Map.of("from", Map.of("row", 1L, "col", 2L)))
            );
            for (Map<String,Object> locator : typedLocators) {
                resolution = Api.await(service.resolveSource(new Api.SourceRef(locator), token));
                check(resolution.locator().fields().equals(locator), "Locator fields changed for " + locator.get("kind"));
            }

            Path derived = db.resolve("derived.docx"); Path evidence = db.resolve("evidence.png");
            Files.writeString(derived, "derived", StandardCharsets.UTF_8); Files.writeString(evidence, "evidence", StandardCharsets.UTF_8);
            database.artifacts.put("converted", artifact("converted", derived.getFileName().toString(), VerifiedPaths.sha256(derived, () -> false), "original"));
            database.artifacts.put("evidence", artifact("evidence", evidence.getFileName().toString(), VerifiedPaths.sha256(evidence, () -> false), "converted"));
            var derivedRef = new Api.SourceRef(Map.of("artifact_id", "evidence", "source_sha256", sha, "kind", "artifact"));
            resolution = Api.await(service.resolveSource(derivedRef, token));
            check(resolution.derivationChain().size() == 2 && resolution.path().equals(evidence.toRealPath()) && resolution.originalPath().equals(original.toRealPath()), "Complete derivation chain");
            Files.writeString(evidence, "changed", StandardCharsets.UTF_8);
            failure(service.resolveSource(derivedRef, token).toCompletableFuture().join(), "SOURCE_VERIFICATION_FAILED");
            Files.writeString(evidence, "evidence", StandardCharsets.UTF_8);
            database.artifacts.put("converted", artifact("converted", derived.getFileName().toString(), VerifiedPaths.sha256(derived, () -> false), "evidence"));
            failure(service.resolveSource(derivedRef, token).toCompletableFuture().join(), "ARTIFACT_CYCLE");

            var invalidPdf = new Api.SourceRef(Map.of("artifact_id", "original", "source_sha256", sha, "kind", "pdf_region", "page", 0L, "bbox", List.of(0,0,1,1), "page_size", List.of(10,10)));
            failure(service.resolveSource(invalidPdf, token).toCompletableFuture().join(), "LOCATOR_INVALID");
            Files.writeString(original, "altered original", StandardCharsets.UTF_8);
            failure(service.resolveSource(originalRef, token).toCompletableFuture().join(), "SOURCE_VERIFICATION_FAILED");
            var cancelled = new Api.CancellationToken(); cancelled.cancel();
            failure(service.catalog(cancelled).toCompletableFuture().join(), "CANCELLED");
            check(context.commands().containsAll(List.of("catalog", "document", "canonical", "resolve")), "Commands registered");
        }
        var missingExecutor = Executors.newSingleThreadExecutor();
        try (var context = new Api.Context(new Api.Environment(root, missingExecutor, new FakePlatform(root)))) {
            new DocumentsModule().register(context);
            check(context.find(Api.DocumentAccessServiceV1.class).isEmpty() && context.commands().isEmpty(), "Absent database disables capability");
        }
        System.out.println("DocumentsServiceTest passed.");
    }

    private static Map<String,Object> artifact(String id, String path, String sha, String parent) { return Map.of("document_id", "org:document:1", "artifact_id", id, "path", path, "sha256", sha, "derived_from", parent); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static void failure(Api.Result<?> result, String code) { check(!result.success() && result.error().code().equals(code), "Expected " + code + "; got " + result); }

    private static final class FakeDatabase implements Api.DatabaseServiceV1 {
        final Map<String,Object> document; final Map<String,Map<String,Object>> artifacts = new HashMap<>();
        FakeDatabase(Map<String,Object> document) { this.document = document; }
        public String sqliteVersion() { return "fake"; }
        public List<Map<String,Object>> query(String sql, List<?> parameters, Api.CancellationToken cancellation) {
            cancellation.check();
            if (sql.equals("SELECT * FROM documents ORDER BY document_id")) return List.of(document);
            if (sql.equals("SELECT * FROM documents WHERE document_id=?")) return parameters.getFirst().equals(document.get("document_id")) ? List.of(document) : List.of();
            if (sql.equals("SELECT * FROM documents WHERE source_sha256=? LIMIT 1")) return parameters.getFirst().equals(document.get("source_sha256")) ? List.of(document) : List.of();
            if (sql.equals("SELECT * FROM artifacts WHERE document_id=? AND artifact_id=?")) { var row = artifacts.get(parameters.get(1)); return row == null ? List.of() : List.of(row); }
            throw new AssertionError("Unexpected query: " + sql);
        }
    }
    private static final class FakePlatform implements Api.Platform {
        final Path root; FakePlatform(Path root) { this.root = root; }
        public Path executablePath() { return root.resolve("EnterpriseAI.exe"); }
        public long availableMemoryBytes() { return Long.MAX_VALUE; }
        public void openWith(Path original) { throw new AssertionError("Document service must not open sources itself"); }
        public Process launchWorker(Path executable, Path directory, long maxMemory) { throw new AssertionError("Document service must not launch processes"); }
    }
}
