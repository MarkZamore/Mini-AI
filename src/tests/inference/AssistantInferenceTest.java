package ai.enterprise.tests;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.inference.InferenceModule;
import ai.enterprise.inference.WorkerManifest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Module-scoped transport checks. Mocks do not prove model quality or no-write portability. */
public final class AssistantInferenceTest {
    private static final String FID = "a".repeat(64), HASH = "b".repeat(64);
    private static int assertions;
    private static Path workerFixture;
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        workerFixture = workspace.resolve("build/test-results/static-worker/packages/weak/model/inference-worker.exe");
        if (!Files.isRegularFile(workerFixture)) throw new AssertionError("Build actual /MT workers first: tests/static-worker/build-fixtures.ps1");
        Path fixtures = workspace.resolve("build/test-results/inference-fixtures"); Files.createDirectories(fixtures);
        for (String mode : List.of("good", "bad-id", "bad-fields", "error", "non-utf8", "oversize", "crash")) {
            Path root = prepare(fixtures.resolve(mode));
            FakePlatform platform = new FakePlatform(root, mode);
            try (Api.Context context = context(root, platform)) {
                new InferenceModule().register(context);
                List<String> tokens = new ArrayList<>();
                var result = context.require(Api.InferenceServiceV1.class).generate(request(), e -> { if (e.type().equals("token")) tokens.add(e.text()); }, new Api.CancellationToken())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
                check(result.success() == mode.equals("good"), "transport result " + mode);
                if (mode.equals("good")) check(tokens.equals(List.of("Answer")), "token forwarded exactly once");
                if (mode.equals("error")) check(result.error().code().equals("OUT_OF_MEMORY"), "worker OOM isolated");
                if (mode.equals("bad-id") || mode.equals("bad-fields") || mode.equals("oversize")) check(result.error().code().equals("PROTOCOL_ERROR"), "protocol error classified " + mode);
                check(!platform.last.isAlive(), "worker terminated " + mode);
            }
        }
        Path cancelRoot = prepare(fixtures.resolve("cancel"));
        FakePlatform platform = new FakePlatform(cancelRoot, "hang");
        try (Api.Context context = context(cancelRoot, platform)) {
            new InferenceModule().register(context);
            CountDownLatch started = new CountDownLatch(1); Api.CancellationToken token = new Api.CancellationToken();
            var result = context.require(Api.InferenceServiceV1.class).generate(request(), e -> { if (e.type().equals("token")) started.countDown(); }, token).toCompletableFuture();
            check(started.await(3, TimeUnit.SECONDS), "hung worker began");
            long before = System.nanoTime(); token.cancel();
            check((System.nanoTime() - before) / 1_000_000 < 200, "cancel does not block caller/UI");
            check(result.get(4, TimeUnit.SECONDS).error().code().equals("CANCELLED"), "hung cancel returns");
            check(!platform.last.isAlive(), "two-second forced termination");
        }
        Path manifestRoot = prepare(fixtures.resolve("manifest")); Path model = manifestRoot.resolve("model");
        Map<String,Object> value = Json.object(Files.readString(model.resolve("worker.json")));
        check(WorkerManifest.verify(value, model, Set.of("x86-64"), 100_000_000, () -> false).threads() == 1, "valid manifest");
        expectFailure(() -> WorkerManifest.verify(value, model, Set.of(), 100_000_000, () -> false), "unknown CPU");
        expectFailure(() -> WorkerManifest.verify(value, model, Set.of("x86-64"), 1, () -> false), "memory preflight");
        Files.write(model.resolve("unlisted.dll"), new byte[]{2});
        expectFailure(() -> WorkerManifest.verify(value, model, Set.of("x86-64"), 100_000_000, () -> false), "unlisted DLL");
        Files.delete(model.resolve("unlisted.dll")); Files.write(model.resolve("weights/test.gguf"), new byte[]{3});
        expectFailure(() -> WorkerManifest.verify(value, model, Set.of("x86-64"), 100_000_000, () -> false), "changed weights");
        var bad = new LinkedHashMap<>(value); bad.put("weights", Map.of("path", "../escape.gguf", "sha256", HASH));
        expectFailure(() -> WorkerManifest.verify(bad, model, Set.of("x86-64"), 100_000_000, () -> false), "traversal");

        Path missingRoot = fixtures.resolve("missing"); Files.createDirectories(missingRoot);
        try (Api.Context context = context(missingRoot, new FakePlatform(missingRoot, "good"))) {
            new InferenceModule().register(context); new AssistantModule().register(context);
            AtomicInteger calls = new AtomicInteger(); context.register(Api.SearchServiceV1.class, search(calls));
            var result = context.require(Api.AssistantSessionServiceV1.class).send("test", e -> {}, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);
            check(!result.success() && result.error().code().equals("MODEL_FOLDER_MISSING"), "missing folder error");
            check(calls.get() == 0, "model availability checked before search");
            check(context.require(Api.AssistantSessionServiceV1.class).history().size() == 2, "failure retained in RAM history");
        }
        for (boolean valid : List.of(true, false)) {
            Path root = fixtures.resolve("assistant-" + valid); Files.createDirectories(root);
            try (Api.Context context = context(root, new FakePlatform(root, "good"))) {
                context.register(Api.SearchServiceV1.class, search(new AtomicInteger()));
                context.register(Api.InferenceServiceV1.class, new Api.InferenceServiceV1() {
                    public Api.Result<Api.ProviderInfo> availability() { return Api.Result.ok(new Api.ProviderInfo("mock", "Q6_K", 8192, 1, 1)); }
                    public CompletionStage<Api.Result<Void>> generate(Api.GenerationRequest r, java.util.function.Consumer<Api.GenerationEvent> sink, Api.CancellationToken token) {
                        String prompt = r.messages().getLast().content();
                        check(prompt.contains("needs_review") && prompt.contains("source_refs") && prompt.contains(FID), "exact context preserves warnings/source refs");
                        sink.accept(new Api.GenerationEvent("token", r.requestId(), "Факт [source:" + (valid ? FID : HASH) + "]", null));
                        return CompletableFuture.completedFuture(Api.Result.ok(null));
                    }
                });
                new AssistantModule().register(context);
                var session = context.require(Api.AssistantSessionServiceV1.class);
                var result = session.send("Факт?", e -> {}, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                check(result.success() == valid, "citation validation " + valid);
                check(session.history().size() == 2 && session.history().getLast().sources().size() == 1, "history/source retention");
                if (valid) check(session.history().getLast().text().contains("ручной сверкой"), "OCR warning reaches user");
                else check(!session.history().getLast().text().contains("[source:" + HASH + "]"), "untrusted citation not promoted to final answer");
                session.close(); check(session.history().isEmpty(), "history cleared on close");
            }
        }
        Path budgetRoot = fixtures.resolve("budget"); Files.createDirectories(budgetRoot);
        try (Api.Context context = context(budgetRoot, new FakePlatform(budgetRoot, "good"))) {
            context.register(Api.InferenceServiceV1.class, new Api.InferenceServiceV1() {
                public Api.Result<Api.ProviderInfo> availability() { return Api.Result.ok(new Api.ProviderInfo("mock", "test", 8192, 1, 1)); }
                public CompletionStage<Api.Result<Void>> generate(Api.GenerationRequest request, java.util.function.Consumer<Api.GenerationEvent> events, Api.CancellationToken token) { throw new AssertionError("No evidence must not generate"); }
            });
            context.register(Api.SearchServiceV1.class, new Api.SearchServiceV1() {
                public CompletionStage<Api.Result<List<Api.SearchHit>>> search(String query, int limit, List<String> ids, List<String> sections, Api.CancellationToken token) { return CompletableFuture.completedFuture(Api.Result.ok(List.of())); }
                public CompletionStage<Api.Result<Api.Fragment>> getFragment(String id, Api.CancellationToken token) { throw new AssertionError(); }
            });
            new AssistantModule().register(context); var session = context.require(Api.AssistantSessionServiceV1.class);
            String longQuery = "a".repeat(128000); boolean refused = false;
            for (int i = 0; i < 64; i++) {
                int before = session.history().size();
                var result = session.send(longQuery, e -> {}, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                if (!result.success()) {
                    check(result.error().code().equals("SESSION_MEMORY_LIMIT"), "bounded history refuses addition");
                    check(session.history().size() == before && session.history().getFirst().text().equals(longQuery), "budget refusal preserves every prior message");
                    refused = true; break;
                }
            }
            check(refused, "history budget exercised");
        }
        concurrentRequestLifecycles(fixtures);
        System.out.println("Assistant/inference checks passed: " + assertions);
    }

    private static void concurrentRequestLifecycles(Path fixtures) throws Exception {
        Set<String> requestIds = ConcurrentHashMap.newKeySet();
        List<Api.Context> contexts = new ArrayList<>();
        List<Api.AssistantSessionServiceV1> sessions = new ArrayList<>();
        List<CompletableFuture<Api.Result<Void>>> pending = new ArrayList<>();
        CountDownLatch simultaneous = new CountDownLatch(8);
        Api.InferenceServiceV1 recording = new Api.InferenceServiceV1() {
            public Api.Result<Api.ProviderInfo> availability() { return Api.Result.ok(new Api.ProviderInfo("mock", "test", 8192, 1, 1)); }
            public CompletionStage<Api.Result<Void>> generate(Api.GenerationRequest request, java.util.function.Consumer<Api.GenerationEvent> sink, Api.CancellationToken token) {
                check(requestIds.add(request.requestId()), "independent assistant requests have unique IDs");
                simultaneous.countDown();
                try { check(simultaneous.await(3, TimeUnit.SECONDS), "independent assistant generations overlap"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CompletionException(error); }
                sink.accept(new Api.GenerationEvent("token", request.requestId(), "Факт [source:" + FID + "]", null));
                return CompletableFuture.completedFuture(Api.Result.ok(null));
            }
        };
        try {
            for (int i = 0; i < 8; i++) {
                Path root = fixtures.resolve("concurrent-assistant-" + i); Files.createDirectories(root);
                Api.Context ctx = context(root, new FakePlatform(root, "good")); contexts.add(ctx);
                ctx.register(Api.SearchServiceV1.class, search(new AtomicInteger())); ctx.register(Api.InferenceServiceV1.class, recording);
                new AssistantModule().register(ctx); sessions.add(ctx.require(Api.AssistantSessionServiceV1.class));
            }
            for (var session : sessions) pending.add(session.send("Факт?", e -> {}, new Api.CancellationToken()).toCompletableFuture());
            for (var result : pending) check(result.get(5, TimeUnit.SECONDS).success(), "concurrent assistant response/citation succeeds");
            for (var session : sessions) {
                check(session.send("Повторный факт?", e -> {}, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS).success(), "next assistant request succeeds");
                check(session.history().size() == 4, "separate sessions retain both request/response pairs");
            }
        } finally { for (var ctx : contexts) ctx.close(); }
        check(requestIds.size() == 16, "assistant correlation IDs survive concurrent contexts and repeat requests");

        contexts.clear(); ExecutorService callers = Executors.newFixedThreadPool(8);
        List<Future<?>> commands = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Path root = prepare(fixtures.resolve("concurrent-headless-" + i));
                Api.Context ctx = context(root, new FakePlatform(root, "good", requestIds)); contexts.add(ctx);
                new InferenceModule().register(ctx);
                commands.add(callers.submit(() -> {
                    Object value = ctx.execute("model-generate", List.of("ordinary"));
                    check(value instanceof Api.Result<?> result && result.success(), "concurrent headless worker response succeeds");
                    @SuppressWarnings("unchecked") Api.Result<Map<String,Object>> result = (Api.Result<Map<String,Object>>) value;
                    check(result.value().get("text").equals("Answer"), "generated correlation ID returns the exact worker response");
                }));
            }
            for (var command : commands) command.get(5, TimeUnit.SECONDS);
            for (var ctx : contexts) {
                Object value = ctx.execute("model-generate", List.of("ordinary"));
                check(value instanceof Api.Result<?> result && result.success(), "headless context can generate again after cleanup");
            }
        } finally { callers.shutdownNow(); for (var ctx : contexts) ctx.close(); }
        check(requestIds.size() == 32, "both public workflows use unique IDs across concurrent contexts and repeated lifecycles");
    }

    private static Api.Context context(Path root, Api.Platform platform) { return new Api.Context(new Api.Environment(root, Executors.newFixedThreadPool(2), platform)); }
    private static Api.GenerationRequest request() { return new Api.GenerationRequest("test-1", List.of(new Api.ModelMessage("user", "test")), 64); }
    private static Api.SearchServiceV1 search(AtomicInteger calls) {
        Api.Fragment fragment = new Api.Fragment(Map.of("fragment_id", FID, "document_id", "document-1", "title", "Generic document", "text", "Факт",
            "source_sha256", HASH, "source_refs", List.of(Map.of("artifact_id", "original", "source_sha256", HASH, "kind", "pdf_region", "page", 1, "bbox", List.of(0, 0, 1, 1), "page_size", List.of(100, 100))),
            "quality", Map.of("needs_review", true, "verification", "machine_extracted", "warnings", List.of()), "warnings", List.of(), "span", Map.of("char_start", 0, "char_end", 4)));
        return new Api.SearchServiceV1() {
            public CompletionStage<Api.Result<List<Api.SearchHit>>> search(String query, int limit, List<String> ids, List<String> sections, Api.CancellationToken token) { calls.incrementAndGet(); return CompletableFuture.completedFuture(Api.Result.ok(List.of(new Api.SearchHit(fragment, 0)))); }
            public CompletionStage<Api.Result<Api.Fragment>> getFragment(String id, Api.CancellationToken token) { return CompletableFuture.completedFuture(Api.Result.ok(fragment)); }
        };
    }
    private static Path prepare(Path root) throws Exception {
        Path model = root.resolve("model"); Files.createDirectories(model.resolve("weights"));
        byte[] exe = Files.readAllBytes(workerFixture), weight = {2}; Files.write(model.resolve("inference-worker.exe"), exe); Files.write(model.resolve("weights/test.gguf"), weight);
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("schemaVersion", 1); values.put("protocol", "enterpriseai.inference.v1"); values.put("executable", "inference-worker.exe");
        values.put("engine", "mock"); values.put("revision", "test-only"); values.put("architecture", "windows-x64"); values.put("supportedIsa", List.of("x86-64"));
        values.put("weights", Map.of("path", "weights/test.gguf", "sha256", hash(weight)));
        values.put("files", List.of(Map.of("path", "inference-worker.exe", "sha256", hash(exe)), Map.of("path", "weights/test.gguf", "sha256", hash(weight))));
        values.put("precision", "test"); values.put("contextTokens", 8192); values.put("threads", 1); values.put("minFreeMemoryBytes", 32_000_000); values.put("maxProcessMemoryBytes", 16_000_000);
        Files.writeString(model.resolve("worker.json"), Json.stringify(values)); return root;
    }
    private static String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static synchronized void check(boolean truth, String message) { assertions++; if (!truth) throw new AssertionError(message); }
    private static void expectFailure(Throwing action, String message) throws Exception { try { action.run(); } catch (IllegalArgumentException | Api.ServiceException e) { check(true, message); return; } throw new AssertionError("Expected failure: " + message); }
    private interface Throwing { void run() throws Exception; }
    private static final class FakePlatform implements Api.Platform {
        final Path root; final String mode; final Set<String> requestIds; FakeProcess last;
        FakePlatform(Path root, String mode) { this(root, mode, null); }
        FakePlatform(Path root, String mode, Set<String> requestIds) { this.root = root; this.mode = mode; this.requestIds = requestIds; }
        public Path executablePath() { return root.resolve("EnterpriseAI.exe"); }
        public long availableMemoryBytes() { return 100_000_000; }
        public void openWith(Path path) { throw new UnsupportedOperationException(); }
        public Process launchWorker(Path exe, Path dir, long limit) { try { return last = new FakeProcess(mode, requestIds); } catch (IOException e) { throw new RuntimeException(e); } }
    }
    private static final class FakeProcess extends Process {
        final PipedInputStream parentStdout = new PipedInputStream(65536), workerStdin = new PipedInputStream(65536);
        final PipedOutputStream workerStdout = new PipedOutputStream(parentStdout), parentStdin = new PipedOutputStream(workerStdin);
        final CountDownLatch exit = new CountDownLatch(1); volatile boolean alive = true; final Thread thread;
        FakeProcess(String mode, Set<String> requestIds) throws IOException {
            thread = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(workerStdin, StandardCharsets.UTF_8))) {
                    String line;
                    while (alive && (line = input.readLine()) != null) {
                        var m = Json.object(line); String type = Json.string(m.get("type"));
                        if (type.equals("hello")) {
                            if (mode.equals("non-utf8")) { workerStdout.write(new byte[]{(byte) 0xc0, '\n'}); workerStdout.flush(); }
                            else output(Map.of("type", "ready", "protocol", "enterpriseai.inference.v1"));
                        } else if (type.equals("generate")) {
                            String id = Json.string(m.get("requestId"));
                            if (requestIds != null) check(requestIds.add(id), "actual worker protocol receives a unique generated correlation ID");
                            switch (mode) {
                                case "crash" -> { destroy(); return; }
                                case "bad-id" -> output(Map.of("type", "token", "requestId", "other", "text", "bad"));
                                case "bad-fields" -> output(Map.of("type", "token", "requestId", id, "text", "bad", "extra", true));
                                case "error" -> output(Map.of("type", "error", "requestId", id, "code", "OUT_OF_MEMORY", "message", "test OOM"));
                                case "oversize" -> { workerStdout.write("x".repeat(8 * 1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8)); workerStdout.write('\n'); workerStdout.flush(); }
                                case "hang" -> output(Map.of("type", "token", "requestId", id, "text", "partial"));
                                default -> { output(Map.of("type", "token", "requestId", id, "text", "Answer")); output(Map.of("type", "done", "requestId", id)); }
                            }
                        } else if (type.equals("cancel") && !mode.equals("hang")) output(Map.of("type", "cancelled", "requestId", m.get("requestId")));
                    }
                } catch (Exception ignored) {} finally { destroy(); }
            }, "test-only-mock-worker"); thread.setDaemon(true); thread.start();
        }
        private synchronized void output(Map<String,Object> value) throws IOException { workerStdout.write((Json.stringify(value) + "\n").getBytes(StandardCharsets.UTF_8)); workerStdout.flush(); }
        public OutputStream getOutputStream() { return parentStdin; }
        public InputStream getInputStream() { return parentStdout; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() throws InterruptedException { exit.await(); return 0; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public boolean isAlive() { return alive; }
        public void destroy() {
            if (!alive) return; alive = false;
            try { workerStdout.close(); } catch (IOException ignored) {} try { workerStdin.close(); } catch (IOException ignored) {}
            try { parentStdin.close(); } catch (IOException ignored) {} exit.countDown();
        }
        public Process destroyForcibly() { destroy(); return this; }
    }
}
