package ai.enterprise.tests;

import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the public domain embedding facade without creating SWT widgets or native workers. */
public final class EmbeddingTest {
    private static final List<Map<String,Object>> checks = new ArrayList<>();
    private static void check(boolean condition, String name) {
        checks.add(Map.of("name", name, "passed", condition));
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        Path sourceRoot = Path.of(args.length == 0 ? "src" : args[0]).toAbsolutePath().normalize();
        String reportName=args.length>1?args[1]:"pink-embedding.json";
        if(!reportName.matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.json"))throw new IllegalArgumentException("Report must be a JSON basename");
        Path report = sourceRoot.resolve("build/test-results").resolve(reportName);
        long start = System.nanoTime();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("suite", "EnterpriseAIComponent domain embedding");
        result.put("timestamp", Instant.now().toString());
        result.put("realAssistantModule", true);
        result.put("nativeWorkerStarted", false);
        result.put("swtWidgetsCreated", false);
        result.put("limitations", List.of("Does not test GUI host attachment", "Does not test native model inference", "Does not prove Process Monitor no-write portability"));
        try {
            run(sourceRoot);
            result.put("passed", true);
        } catch (Throwable error) {
            result.put("passed", false);
            result.put("error", error.toString());
            throw error;
        } finally {
            result.put("durationMs", (System.nanoTime() - start) / 1_000_000);
            result.put("checks", List.copyOf(checks));
            result.put("assertions", checks.size());
            Files.createDirectories(report.getParent());
            Files.writeString(report, Json.stringify(result));
        }
        System.out.println("Embedding checks passed: " + checks.size());
    }
    private static void run(Path sourceRoot) throws Exception {
        ExecutorService executor1 = Executors.newFixedThreadPool(2), executor2 = Executors.newFixedThreadPool(2);
        ProbePlatform platform1 = new ProbePlatform(sourceRoot.resolve("build/pink-embedding/first"));
        ProbePlatform platform2 = new ProbePlatform(sourceRoot.resolve("build/pink-embedding/second"));
        Api.Environment environment1 = new Api.Environment(platform1.root, executor1, platform1);
        Api.Environment environment2 = new Api.Environment(platform2.root, executor2, platform2);
        EnterpriseAIComponent first = EnterpriseAIComponent.create(environment1, List.of(new AssistantModule()), List.of());
        EnterpriseAIComponent second = EnterpriseAIComponent.create(environment2, List.of(new AssistantModule()), List.of());
        try {
            Api.Context context1 = first.context(), context2 = second.context();
            Api.AssistantSessionServiceV1 session1 = context1.require(Api.AssistantSessionServiceV1.class);
            Api.AssistantSessionServiceV1 session2 = context2.require(Api.AssistantSessionServiceV1.class);
            check(context1 != context2 && session1 != session2, "separate service containers and assistant sessions");
            check(context1.environment() == environment1 && context2.environment() == environment2, "caller supplied independent environments");
            check(session1.history().isEmpty() && session2.history().isEmpty(), "new component histories initially empty");
            check(first.panels().isEmpty() && second.panels().isEmpty(), "domain composition does not require a UI panel");

            context1.register(Marker.class, new Marker("first")); context2.register(Marker.class, new Marker("second"));
            context1.command("only-first", ignored -> context1.require(Marker.class).value());
            context2.command("only-second", ignored -> context2.require(Marker.class).value());
            context1.diagnostic("first-instance-only"); context2.diagnostic("second-instance-only");
            check(context1.require(Marker.class).value().equals("first") && context2.require(Marker.class).value().equals("second"), "custom service registrations do not mix");
            check(context1.execute("only-first", List.of()).equals("first") && context2.execute("only-second", List.of()).equals("second"), "commands use their own registry");
            check(!context1.commands().contains("only-second") && !context2.commands().contains("only-first"), "commands remain instance local");
            boolean commandAbsent = false;
            try { context2.execute("only-first", List.of()); } catch (Api.ServiceException error) { commandAbsent = error.code.equals("COMMAND_ABSENT"); }
            check(commandAbsent, "other instance command cannot be executed");
            check(context1.diagnostics().stream().noneMatch(line -> line.contains("second-instance-only"))
                && context2.diagnostics().stream().noneMatch(line -> line.contains("first-instance-only")), "diagnostic buffers do not mix");

            ExecutorService callers = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
            List<Api.AssistantEvent> events1 = new CopyOnWriteArrayList<>(), events2 = new CopyOnWriteArrayList<>();
            try {
                Future<Api.Result<Void>> one = callers.submit(() -> { ready.countDown(); release.await(); return session1.send("Запрос первой организации", events1::add, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS); });
                Future<Api.Result<Void>> two = callers.submit(() -> { ready.countDown(); release.await(); return session2.send("Запрос второй организации", events2::add, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS); });
                check(ready.await(3, TimeUnit.SECONDS), "two embedding callers started concurrently"); release.countDown();
                Api.Result<Void> reply1 = one.get(4, TimeUnit.SECONDS), reply2 = two.get(4, TimeUnit.SECONDS);
                check(!reply1.success() && reply1.error().code().equals("INFERENCE_ABSENT"), "first real assistant reports expected absent provider");
                check(!reply2.success() && reply2.error().code().equals("INFERENCE_ABSENT"), "second real assistant reports expected absent provider");
            } finally { release.countDown(); callers.shutdownNow(); }
            check(session1.history().size() == 2 && session2.history().size() == 2, "both requests and failures retained independently");
            check(session1.history().getFirst().text().equals("Запрос первой организации")
                && session2.history().getFirst().text().equals("Запрос второй организации"), "simultaneous request text is not exchanged");
            check(session1.history().stream().noneMatch(message -> message.text().contains("второй организации"))
                && session2.history().stream().noneMatch(message -> message.text().contains("первой организации")), "no cross instance history leakage");
            check(events1.stream().anyMatch(e -> e.type().equals("user") && e.text().equals("Запрос первой организации"))
                && events2.stream().anyMatch(e -> e.type().equals("user") && e.text().equals("Запрос второй организации")), "stream events reach the correct caller");
            check(events1.stream().noneMatch(e -> e.text().contains("второй организации"))
                && events2.stream().noneMatch(e -> e.text().contains("первой организации")), "event listeners do not mix");

            first.close();
            check(session1.history().isEmpty(), "closing first clears its RAM history");
            check(context1.commands().isEmpty() && context1.diagnostics().isEmpty()
                && context1.find(Api.AssistantSessionServiceV1.class).isEmpty() && context1.find(Marker.class).isEmpty(), "closing first clears its registries and diagnostics");
            check(executor1.isShutdown() && executor1.awaitTermination(3, TimeUnit.SECONDS), "closing first shuts down its supplied executor");
            check(platform1.closes.get() == 1, "closing first closes its supplied platform once");
            check(!executor2.isShutdown() && platform2.closes.get() == 0, "closing first leaves second runtime active");
            check(session2.history().size() == 2 && context2.require(Marker.class).value().equals("second"), "second history and services survive first close");
            check(context2.execute("only-second", List.of()).equals("second") && context2.diagnostics().stream().anyMatch(line -> line.contains("second-instance-only")), "second commands and diagnostics survive first close");
            var afterFirstClose = session2.send("Следующий запрос второй организации", e -> {}, new Api.CancellationToken()).toCompletableFuture().get(3, TimeUnit.SECONDS);
            check(!afterFirstClose.success() && afterFirstClose.error().code().equals("INFERENCE_ABSENT")
                && session2.history().size() == 4, "second assistant accepts another request after first close");
            var closedReply = session1.send("Недопустимый запрос", e -> {}, new Api.CancellationToken()).toCompletableFuture().get(1, TimeUnit.SECONDS);
            check(!closedReply.success() && closedReply.error().code().equals("SESSION_CLOSED") && session1.history().isEmpty(), "closed first session refuses additions without repopulating history");
            first.close(); check(platform1.closes.get() == 1, "component close is idempotent");
            second.close();
            check(session2.history().isEmpty() && executor2.isShutdown() && executor2.awaitTermination(3, TimeUnit.SECONDS), "closing second clears history and shuts down executor");
            check(platform2.closes.get() == 1 && context2.commands().isEmpty() && context2.diagnostics().isEmpty(), "closing second clears registries and closes its platform");
            check(platform1.nativeCalls.get() == 0 && platform2.nativeCalls.get() == 0, "no native model process, memory probe or document viewer needed");
        } finally { first.close(); second.close(); }

        ExecutorService executor3 = Executors.newFixedThreadPool(1);
        ProbePlatform platform3 = new ProbePlatform(platform1.root);
        try (EnterpriseAIComponent third = EnterpriseAIComponent.create(new Api.Environment(platform3.root, executor3, platform3), List.of(new AssistantModule()), List.of())) {
            var session3 = third.context().require(Api.AssistantSessionServiceV1.class);
            check(session3.history().isEmpty() && third.context().commands().isEmpty(), "recreating component at same root starts without history or old commands");
            check(third.context().find(Marker.class).isEmpty()
                && third.context().diagnostics().stream().noneMatch(line -> line.contains("first-instance-only") || line.contains("second-instance-only")), "recreated component has no prior services or diagnostics");
        }
        check(executor3.isShutdown() && platform3.closes.get() == 1, "recreated component also releases caller resources");
    }
    private record Marker(String value) {}
    private static final class ProbePlatform implements Api.Platform {
        final Path root; final AtomicInteger closes = new AtomicInteger(), nativeCalls = new AtomicInteger();
        ProbePlatform(Path root) { this.root = root; }
        public Path executablePath() { return root.resolve("миниИИ.exe"); }
        public long availableMemoryBytes() { nativeCalls.incrementAndGet(); throw new AssertionError("Embedding test must not probe native memory"); }
        public void openWith(Path original) { nativeCalls.incrementAndGet(); throw new AssertionError("Embedding test must not open an external viewer"); }
        public Process launchWorker(Path executable, Path directory, long maxMemoryBytes) { nativeCalls.incrementAndGet(); throw new AssertionError("Embedding test must not launch a native worker"); }
        public void close() { closes.incrementAndGet(); }
    }
}
