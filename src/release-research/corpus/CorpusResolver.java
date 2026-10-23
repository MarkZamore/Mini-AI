package ai.enterprise.documents;

import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.platform.WindowsPlatform;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Corpus portability acceptance through the actual full-composition services.
 * No inference, graph operations, GUI opening, corpus writes or external calls.
 */
public final class CorpusResolver {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("runtime-root new-results-folder");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Preserve prior evidence: choose a new results folder");
        Files.createDirectories(output);
        var executor = Executors.newFixedThreadPool(2);
        var token = new Api.CancellationToken();
        try (var platform = new WindowsPlatform(root);
             var component = EnterpriseAIComponent.create(new Api.Environment(root, executor, platform), GeneratedCatalog.modules(), GeneratedCatalog.panels());
             var writer = Files.newBufferedWriter(output.resolve("resolutions.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            var context = component.context();
            var modules = GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).toList();
            if (modules.size() != 7) throw new IllegalStateException("Expected the complete seven-module application");
            var database = context.require(Api.DatabaseServiceV1.class);
            var service = context.require(Api.DocumentAccessServiceV1.class);
            var documents = database.query("SELECT * FROM documents ORDER BY document_id", List.of(), token);
            var artifacts = database.query("SELECT * FROM artifacts ORDER BY document_id,artifact_id", List.of(), token);
            var documentsById = new HashMap<String,Map<String,Object>>();
            var artifactsById = new HashMap<String,Map<String,Object>>();
            for (var row : documents) documentsById.put(Json.string(row.get("document_id")), row);
            for (var row : artifacts) artifactsById.put(key(row.get("document_id"), row.get("artifact_id")), row);
            int originals = 0, canonicals = 0, chains = 0, longPaths = 0;
            for (var document : documents) {
                String id = Json.string(document.get("document_id"));
                var canonical = await(service.canonical(id, token));
                if (!id.equals(canonical.get("document_id"))) throw new IllegalStateException("Canonical ID mismatch");
                canonicals++;
                var reference = new Api.SourceRef(Map.of("kind", "artifact", "artifact_id", "original", "source_sha256", document.get("source_sha256")));
                var result = await(service.resolveSource(reference, token));
                if (!result.verified() || !result.documentId().equals(id) || !result.derivationChain().isEmpty()) throw new IllegalStateException("Original resolution mismatch: " + id);
                Path original = VerifiedPaths.within(root.resolve("db"), Json.string(document.get("source_path")), root.resolve("docs"));
                if (!original.equals(result.path()) || !original.equals(result.originalPath())) throw new IllegalStateException("Original path mismatch");
                if (result.path().toString().length() > 259) longPaths++;
                writer.write(Json.stringify(Map.of("document_id", id, "kind", "original", "resolution", result))); writer.newLine();
                originals++;
            }
            writer.flush();
            for (var artifact : artifacts) {
                String id = Json.string(artifact.get("document_id")), aid = Json.string(artifact.get("artifact_id"));
                var document = documentsById.get(id);
                var reference = new Api.SourceRef(Map.of("kind", "artifact", "artifact_id", aid, "source_sha256", document.get("source_sha256")));
                var result = await(service.resolveSource(reference, token));
                if (!result.verified() || !result.documentId().equals(id)) throw new IllegalStateException("Artifact resolution mismatch");
                var expected = new ArrayList<Map<String,Object>>();
                String current = aid;
                while (!"original".equals(current)) {
                    var row = artifactsById.get(key(id, current));
                    if (row == null || expected.size() >= 1024) throw new IllegalStateException("Invalid expected artifact chain");
                    expected.add(row);
                    current = Json.string(row.get("derived_from"));
                }
                if (result.derivationChain().size() != expected.size()) throw new IllegalStateException("Chain size mismatch");
                for (int i = 0; i < expected.size(); i++) {
                    var row = expected.get(i); var actual = result.derivationChain().get(i);
                    Path path = VerifiedPaths.within(root.resolve("db"), Json.string(row.get("path")), root.resolve("db"));
                    if (!actual.artifactId().equals(row.get("artifact_id")) || !actual.documentId().equals(id) || !actual.path().equals(path) || !actual.sha256().equals(row.get("sha256")) || !actual.derivedFrom().equals(row.get("derived_from"))) throw new IllegalStateException("Chain entry mismatch");
                }
                Path selected = VerifiedPaths.within(root.resolve("db"), Json.string(artifact.get("path")), root.resolve("db"));
                if (!result.path().equals(selected)) throw new IllegalStateException("Selected artifact path mismatch");
                writer.write(Json.stringify(Map.of("document_id", id, "kind", "artifact", "artifact_id", aid, "resolution", result))); writer.newLine();
                if (++chains % 500 == 0) { writer.flush(); System.out.println("Verified artifact chains " + chains + "/" + artifacts.size()); }
            }
            int chunkCount = 0, locatorCount = 0;
            var kinds = new TreeMap<String,Integer>();
            var realExamples = new LinkedHashMap<String,Api.SourceRef>();
            long after = 0;
            while (true) {
                var rows = database.query("SELECT rowid,document_id,source_refs FROM chunks WHERE rowid>? ORDER BY rowid LIMIT 500", List.of(after), token);
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    after = ((Number)row.get("rowid")).longValue();
                    var document = documentsById.get(Json.string(row.get("document_id")));
                    for (var item : (List<?>)row.get("source_refs")) {
                        var reference = new Api.SourceRef(Json.map(item));
                        DocumentsService.validateLocator(reference);
                        if (!reference.sourceSha256().equals(document.get("source_sha256"))) throw new IllegalStateException("Actual chunk locator SHA mismatch");
                        if (!"original".equals(reference.artifactId()) && !artifactsById.containsKey(key(document.get("document_id"), reference.artifactId()))) throw new IllegalStateException("Actual chunk locator artifact missing");
                        kinds.merge(reference.kind(), 1, Integer::sum);
                        realExamples.putIfAbsent(reference.kind(), reference);
                        locatorCount++;
                    }
                    chunkCount++;
                }
            }
            for (var entry : realExamples.entrySet()) {
                var result = await(service.resolveSource(entry.getValue(), token));
                if (!result.verified()) throw new IllegalStateException("Actual locator example did not verify");
                writer.write(Json.stringify(Map.of("kind", "actual_" + entry.getKey(), "resolution", result))); writer.newLine();
            }
            var summary = new LinkedHashMap<String,Object>();
            summary.put("status", "PASS"); summary.put("runtime_root", root.toString()); summary.put("modules", modules);
            summary.put("canonical_documents", canonicals); summary.put("verified_originals", originals); summary.put("verified_artifact_chains", chains);
            summary.put("original_paths_over_259_characters", longPaths); summary.put("chunks", chunkCount); summary.put("actual_validated_locators", locatorCount);
            summary.put("locator_kinds", kinds); summary.put("resolved_actual_locator_examples", realExamples.size());
            summary.put("sqlite_version", database.sqliteVersion()); summary.put("diagnostics", context.diagnostics());
            summary.put("limitations", List.of("Exact SHA and valid locators do not establish OCR correctness or semantic support.", "No external viewer was launched; GUI source opening is a separate final-app acceptance step."));
            Files.writeString(output.resolve("summary.json"), Json.stringify(summary) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println(Json.stringify(summary));
        } finally { executor.shutdownNow(); }
    }
    private static String key(Object documentId, Object artifactId) { return documentId + "\n" + artifactId; }
    private static <T> T await(CompletionStage<Api.Result<T>> result) throws Exception {
        var value = result.toCompletableFuture().get(120, TimeUnit.SECONDS);
        if (!value.success()) throw new IllegalStateException(value.error().code() + ": " + value.error().message());
        return value.value();
    }
}
