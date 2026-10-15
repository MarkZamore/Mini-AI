package ai.enterprise.sources;

import ai.enterprise.contracts.Api;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Source opening depends exclusively on contracts and a captured platform port. */
public final class SourcesServiceTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "src/build/tests/sources-service" : args[0]).toAbsolutePath().resolve(UUID.randomUUID().toString());
        Path docs = Files.createDirectories(root.resolve("docs")); Files.createDirectories(root.resolve("db"));
        Path original = docs.resolve("Оригинал с пробелами ә.txt");
        byte[] content = "Исходный документ".getBytes(StandardCharsets.UTF_8); Files.write(original, content);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var platform = new CapturedPlatform(root);
        var document = new Api.DocumentSummary("other:1", "Другая организация", sha, "../docs/" + original.getFileName(), "documents/1.json", "complete", List.of(), List.of());
        var source = new Api.SourceRef(Map.of("artifact_id", "derived", "source_sha256", sha, "kind", "artifact"));
        var fake = new FakeDocuments(document, new Api.SourceResolution("other:1", original, root.resolve("db/derived.docx"), sha, source, List.of(), true));
        try (var context = new Api.Context(new Api.Environment(root, Executors.newFixedThreadPool(2), platform))) {
            context.register(Api.DocumentAccessServiceV1.class, fake); new SourcesModule().register(context);
            var service = context.require(Api.SourceViewerServiceV1.class); var token = new Api.CancellationToken();
            Api.await(service.openDocument("other:1", token));
            check(platform.opened.equals(original.toRealPath()), "openDocument opened original");
            platform.opened = null;
            Api.await(service.openSource(source, token));
            check(platform.opened.equals(original.toRealPath()), "Derived citation must open original");
            platform.opened = null;
            var cancelled = new Api.CancellationToken(); cancelled.cancel();
            var result = service.openDocument("other:1", cancelled).toCompletableFuture().join();
            check(!result.success() && platform.opened == null, "Cancelled source was opened");
            Files.writeString(original, "changed", StandardCharsets.UTF_8);
            result = service.openDocument("other:1", token).toCompletableFuture().join();
            check(!result.success() && result.error().code().equals("SOURCE_VERSION_CHANGED") && platform.opened == null, "Altered source was opened");
            Files.delete(original);
            result = service.openDocument("other:1", token).toCompletableFuture().join();
            check(!result.success() && result.error().code().equals("SOURCE_UNAVAILABLE")
                && result.error().message().contains(original.toString()) && platform.opened == null, "Missing original needs its path and must not open a viewer");
            Files.delete(docs);
            result = service.openDocument("other:1", token).toCompletableFuture().join();
            check(!result.success() && result.error().code().equals("DOCS_FOLDER_MISSING")
                && result.error().message().contains(docs.toString()) && platform.opened == null, "Missing docs needs an understandable folder error");
            Files.createDirectory(docs); Files.write(original, content);
            fake.document = new Api.DocumentSummary("other:1", "Escape", sha, "../outside.txt", "documents/1.json", "complete", List.of(), List.of());
            Files.write(root.resolve("outside.txt"), content);
            result = service.openDocument("other:1", token).toCompletableFuture().join();
            check(!result.success() && result.error().code().equals("SOURCE_PATH_INVALID") && platform.opened == null, "Escaping source was opened");
            check(context.commands().containsAll(List.of("open", "open-source")), "Opening commands registered");
        }
        try (var context = new Api.Context(new Api.Environment(root, Executors.newSingleThreadExecutor(), platform))) {
            new SourcesModule().register(context);
            check(context.find(Api.SourceViewerServiceV1.class).isEmpty() && context.commands().isEmpty(), "Missing documents disabled opening");
        }
        System.out.println("SourcesServiceTest passed.");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class CapturedPlatform implements Api.Platform {
        final Path root; Path opened;
        CapturedPlatform(Path root) { this.root = root; }
        public Path executablePath() { return root.resolve("EnterpriseAI.exe"); }
        public long availableMemoryBytes() { return Long.MAX_VALUE; }
        public void openWith(Path path) { opened = path; }
        public Process launchWorker(Path executable, Path directory, long memory) { throw new AssertionError("Unexpected worker"); }
    }
    private static final class FakeDocuments implements Api.DocumentAccessServiceV1 {
        Api.DocumentSummary document; final Api.SourceResolution resolution;
        FakeDocuments(Api.DocumentSummary document, Api.SourceResolution resolution) { this.document = document; this.resolution = resolution; }
        public CompletionStage<Api.Result<List<Api.DocumentSummary>>> catalog(Api.CancellationToken token) { return CompletableFuture.completedFuture(Api.Result.ok(List.of(document))); }
        public CompletionStage<Api.Result<Api.DocumentSummary>> document(String id, Api.CancellationToken token) { return CompletableFuture.completedFuture(Api.Result.ok(document)); }
        public CompletionStage<Api.Result<Map<String,Object>>> canonical(String id, Api.CancellationToken token) { return CompletableFuture.completedFuture(Api.Result.ok(Map.of())); }
        public CompletionStage<Api.Result<Api.SourceResolution>> resolveSource(Api.SourceRef ref, Api.CancellationToken token) { return CompletableFuture.completedFuture(Api.Result.ok(resolution)); }
    }
}
