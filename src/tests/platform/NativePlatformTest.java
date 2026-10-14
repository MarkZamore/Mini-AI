package ai.enterprise.tests;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.platform.WindowsPlatform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real JNI process isolation tests. Instrumentation writes only under src/build. */
public final class NativePlatformTest {
    private static final long MIB = 1024 * 1024;
    private static final List<Map<String,Object>> scenarios = new ArrayList<>();
    private static Path fixtureRoot;
    private static Api.Platform platform;

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0 ? "src" : args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(source.resolve("core")) && Files.isDirectory(source.resolve("src/core"))) source = source.resolve("src");
        fixtureRoot = source.resolve("build/test-results/platform-fixtures");
        if (!Files.isRegularFile(fixtureRoot.resolve("worker-fixture.exe")) || !Files.isRegularFile(fixtureRoot.resolve("native/enterpriseai-platform.dll"))) {
            throw new AssertionError("Run src/tests/platform/prepare-fixture.ps1 first; fixture and native library are required.");
        }
        boolean completed = false;
        String failure = null;
        try (var nativePlatform = new WindowsPlatform(fixtureRoot)) {
            platform = nativePlatform;
            check(platform.executablePath().isAbsolute() && Files.isRegularFile(platform.executablePath()), "Actual executable path invalid");
            check(platform.availableMemoryBytes() > 0, "Available physical memory unavailable");
            check(platform.cpuSupports("baseline") && platform.cpuSupports("x86-64") && !platform.cpuSupports("unsupported-test-isa"), "Native CPU feature validation");
            record("platform-information", Map.of("executable", platform.executablePath().toString(), "availableMemoryBytes", platform.availableMemoryBytes(), "avx2", platform.cpuSupports("avx2"), "avx512", platform.cpuSupports("avx512")));
            unicodePipesAndNormalExit();
            cooperativeCancel();
            hangAndForceCancel();
            abnormalExit();
            memoryLimit();
            descendantJobTermination();
            repeatedSpawn();
            unicodeExecutablePath();
            missingExecutable();
            longExecutablePath();
            platformCloseTerminatesForgottenWorker();
            longWorkingDirectory();
            concurrentWorkerIsolation();
            completed = true;
        } catch (Exception | Error error) {
            failure = error.getClass().getName() + ": " + error.getMessage();
            throw error;
        } finally {
            var report = new LinkedHashMap<String,Object>();
            report.put("completed", completed); report.put("failure", failure); report.put("testedAt", Instant.now().toString()); report.put("scenarios", scenarios);
            report.put("limits", List.of("No model was loaded or trained.", "Process Monitor filesystem/registry qualification is separate.", "Abnormal exit fixture uses ExitProcess(42); no real access-violation crash is induced.", "Windows rejects long process current directories; requested long data directory launches with OS-derived short System32 cwd. Future workers must resolve data from their actual EXE and absolute manifest paths."));
            Files.writeString(fixtureRoot.resolve("native-platform-test-report.json"), Json.stringify(report) + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("NativePlatformTest passed " + scenarios.size() + " scenarios.");
    }

    private static void unicodePipesAndNormalExit() throws Exception {
        try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 64 * MIB)) {
            check(Boolean.FALSE.equals(worker.ready.get("console")), "Worker has a visible console");
            worker.send(Map.of("type", "generate", "prompt", "Русский қазақша 😀"));
            Map<String,Object> token = worker.read();
            check("token".equals(token.get("type")) && "Тест ә ғ ң 😀".equals(token.get("text")), "UTF-8 token damaged");
            check("done".equals(worker.read().get("type")), "Missing generation completion");
            worker.quit();
            record("anonymous-utf8-pipes-and-normal-exit", Map.of("exitCode", worker.process.exitValue(), "hidden", true));
        }
    }
    private static void cooperativeCancel() throws Exception {
        try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 64 * MIB)) {
            worker.send(Map.of("type", "generate", "mode", "cancel")); worker.send(Map.of("type", "cancel"));
            check("cancelled".equals(worker.read().get("type")), "Cancellation acknowledgement unavailable");
            check(worker.process.isAlive(), "Cancellation should keep responsive worker alive");
            worker.quit(); record("cooperative-cancel", Map.of("responsive", true));
        }
    }
    private static void hangAndForceCancel() throws Exception {
        try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 64 * MIB)) {
            worker.send(Map.of("type", "generate", "mode", "hang"));
            check(!worker.process.waitFor(100, TimeUnit.MILLISECONDS), "Hanging worker exited early");
            long before = System.nanoTime(); worker.process.destroyForcibly();
            check(worker.process.waitFor(3, TimeUnit.SECONDS) && !worker.process.isAlive(), "Force cancellation failed");
            record("force-cancel-hung-worker", Map.of("elapsedMs", (System.nanoTime() - before) / 1_000_000, "exitCode", worker.process.exitValue()));
        }
    }
    private static void abnormalExit() throws Exception {
        try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 64 * MIB)) {
            worker.send(Map.of("type", "generate", "mode", "crash"));
            check(worker.process.waitFor(3, TimeUnit.SECONDS) && worker.process.exitValue() == 42, "Abnormal worker exit not propagated");
            check(worker.readLine() == null, "Pipe EOF missing after worker failure");
            record("abnormal-exit-with-parent-survival", Map.of("exitCode", 42));
        }
    }
    private static void memoryLimit() throws Exception {
        try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 32 * MIB)) {
            worker.send(Map.of("type", "generate", "mode", "memory"));
            Map<String,Object> memory = worker.read();
            check("memory".equals(memory.get("type")) && Boolean.TRUE.equals(memory.get("denied")), "Job failed to deny 128 MiB allocation under 32 MiB cap");
            check("done".equals(worker.read().get("type")), "Worker did not survive refused memory allocation");
            worker.quit(); record("job-process-memory-limit", Map.of("limitBytes", 32 * MIB, "deniedAllocationBytes", 128 * MIB, "windowsError", memory.get("error")));
        }
    }
    private static void descendantJobTermination() throws Exception {
        long descendantPid = 0;
        try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 64 * MIB)) {
            worker.send(Map.of("type", "generate", "mode", "descendant"));
            Map<String,Object> message = worker.read();
            check("descendant".equals(message.get("type")), "Descendant did not start: " + message);
            descendantPid = Json.integer(message.get("pid"));
            check(ProcessHandle.of(descendantPid).map(ProcessHandle::isAlive).orElse(false), "Descendant not alive");
            worker.process.destroyForcibly(); check(worker.process.waitFor(3, TimeUnit.SECONDS), "Parent termination failed");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ProcessHandle.of(descendantPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
            check(!ProcessHandle.of(descendantPid).map(ProcessHandle::isAlive).orElse(false), "Closing job left descendant alive");
            record("job-descendant-kill-on-close", Map.of("descendantPid", descendantPid, "terminated", true));
        } finally {
            if (descendantPid != 0) ProcessHandle.of(descendantPid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
    private static void repeatedSpawn() throws Exception {
        List<Long> pids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            try (var worker = start(fixtureRoot.resolve("worker-fixture.exe"), 64 * MIB)) { pids.add(Json.integer(worker.ready.get("pid"))); worker.quit(); }
        }
        for (long pid : pids) check(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Repeated spawn left worker alive");
        record("repeated-spawn-cleanup", Map.of("count", pids.size(), "lingeringProcesses", 0));
    }
    private static void unicodeExecutablePath() throws Exception {
        Path folder = Files.createDirectories(fixtureRoot.resolve("Другой путь ә ң с пробелами"));
        Path executable = folder.resolve("исполнитель ә.exe");
        Files.copy(fixtureRoot.resolve("worker-fixture.exe"), executable, StandardCopyOption.REPLACE_EXISTING);
        try (var worker = start(executable, 64 * MIB)) { worker.quit(); record("unicode-executable-path", Map.of("path", executable.toString())); }
    }
    private static void longExecutablePath() throws Exception {
        Path folder = fixtureRoot.resolve("long-path");
        for (int i = 0; i < 4; i++) folder = folder.resolve("длинная папка " + "я".repeat(56));
        Files.createDirectories(folder); Path executable = folder.resolve("исполнитель ә.exe");
        Files.copy(fixtureRoot.resolve("worker-fixture.exe"), executable, StandardCopyOption.REPLACE_EXISTING);
        check(executable.toString().length() > 260, "Long executable fixture is too short");
        try (var worker = start(executable, 64 * MIB)) { worker.quit(); record("long-executable-path", Map.of("characters", executable.toString().length(), "shortWorkingDirectory", true)); }
    }
    private static void missingExecutable() {
        try { platform.launchWorker(fixtureRoot.resolve("missing.exe"), fixtureRoot, 64 * MIB); throw new AssertionError("Missing executable unexpectedly launched"); }
        catch (Api.ServiceException expected) { check(expected.code.equals("WORKER_START"), "Wrong launch error code"); record("missing-worker-error", Map.of("error", expected.code)); }
    }
    private static void longWorkingDirectory() throws Exception {
        Path folder = fixtureRoot.resolve("long-cwd");
        for (int i = 0; i < 4; i++) folder = folder.resolve("рабочая папка " + "я".repeat(56));
        Files.createDirectories(folder);
        Path executable = folder.resolve("исполнитель ә.exe");
        Files.copy(fixtureRoot.resolve("worker-fixture.exe"), executable, StandardCopyOption.REPLACE_EXISTING);
        check(executable.toString().length() > 260, "Long EXE/data-directory fixture is too short");
        try (var worker = new Worker(platform.launchWorker(executable, folder, 64 * MIB))) {
            check(Json.integer(worker.ready.get("cwdCharacters")) <= 260 && Boolean.TRUE.equals(worker.ready.get("cwdIsSystemDirectory")), "Long data directory did not use controlled short System32 cwd");
            worker.send(Map.of("type", "generate"));
            check("token".equals(worker.read().get("type")) && "done".equals(worker.read().get("type")), "Worker failed from long EXE/data path");
            worker.quit(); record("long-exe-and-data-directory-with-short-os-cwd", Map.of("requestedCharacters", folder.toString().length(), "workerReportedCharacters", worker.ready.get("cwdCharacters"), "cwdIsSystemDirectory", true));
        }
    }
    private static void platformCloseTerminatesForgottenWorker() throws Exception {
        var owner = new WindowsPlatform(fixtureRoot);
        try (var worker = new Worker(owner.launchWorker(fixtureRoot.resolve("worker-fixture.exe"), fixtureRoot, 64 * MIB))) {
            worker.send(Map.of("type", "generate", "mode", "hang"));
            check(!worker.process.waitFor(100, TimeUnit.MILLISECONDS), "Forgotten worker exited early");
            owner.close();
            check(worker.process.waitFor(3, TimeUnit.SECONDS) && !worker.process.isAlive(), "Platform.close left controlled worker alive");
            try { owner.launchWorker(fixtureRoot.resolve("worker-fixture.exe"), fixtureRoot, 64 * MIB); throw new AssertionError("Closed platform started a new process"); }
            catch (Api.ServiceException expected) { check("PLATFORM_CLOSED".equals(expected.code), "Wrong closed-platform error code"); }
            record("platform-close-terminates-controlled-worker", Map.of("terminated", true));
        } finally { owner.close(); }
    }
    private static void concurrentWorkerIsolation() throws Exception {
        ExecutorService starts = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 8; iteration++) {
                var ownerA = new WindowsPlatform(fixtureRoot);
                var ownerB = new WindowsPlatform(fixtureRoot);
                var gate = new CountDownLatch(1);
                Future<Worker> pendingA = starts.submit(() -> { gate.await(); return new Worker(ownerA.launchWorker(fixtureRoot.resolve("worker-fixture.exe"), fixtureRoot, 64 * MIB)); });
                Future<Worker> pendingB = starts.submit(() -> { gate.await(); return new Worker(ownerB.launchWorker(fixtureRoot.resolve("worker-fixture.exe"), fixtureRoot, 64 * MIB)); });
                gate.countDown();
                try (Worker workerA = pendingA.get(5, TimeUnit.SECONDS); Worker workerB = pendingB.get(5, TimeUnit.SECONDS)) {
                    workerB.send(Map.of("type", "generate", "mode", "hang"));
                    workerA.quit();
                    check(workerB.process.isAlive(), "Closing one platform worker terminated unrelated worker");
                    check(workerA.readLine() == null, "Sibling inherited another worker's pipe and prevented EOF");
                    workerB.process.destroyForcibly();
                    check(workerB.process.waitFor(3, TimeUnit.SECONDS), "Concurrent worker cleanup failed");
                } finally { ownerB.close(); ownerA.close(); }
            }
        } finally { starts.shutdownNow(); }
        record("concurrent-hosts-pipe-and-job-isolation", Map.of("pairs", 8, "unrelatedJobSurvived", true, "pipeEOFIndependent", true));
    }
    private static Worker start(Path executable, long cap) throws Exception { return new Worker(platform.launchWorker(executable, fixtureRoot, cap)); }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private static void record(String name, Map<String,Object> details) { scenarios.add(Map.of("scenario", name, "passed", true, "details", details)); }

    private static final class Worker implements AutoCloseable {
        final Process process; final BufferedReader output; final BufferedWriter input;
        final ExecutorService reader = Executors.newSingleThreadExecutor(); final Map<String,Object> ready;
        Worker(Process process) throws Exception {
            this.process = process;
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            try {
                send(Map.of("type", "hello")); ready = read();
                check("ready".equals(ready.get("type")), "Invalid handshake: " + ready);
            } catch (Throwable error) { close(); throw error; }
        }
        void send(Map<String,Object> message) throws IOException { input.write(Json.stringify(message)); input.newLine(); input.flush(); }
        String readLine() throws Exception {
            Future<String> pending = reader.submit(output::readLine);
            try { return pending.get(3, TimeUnit.SECONDS); }
            catch (TimeoutException error) { process.destroyForcibly(); pending.cancel(true); throw new AssertionError("Worker response timed out", error); }
        }
        Map<String,Object> read() throws Exception { String line = readLine(); if (line == null) throw new AssertionError("Unexpected EOF from worker"); return Json.object(line); }
        void quit() throws Exception { send(Map.of("type", "quit")); check("bye".equals(read().get("type")), "Quit acknowledgement missing"); check(process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0, "Normal worker exit failed"); }
        public void close() {
            process.destroyForcibly();
            try { input.close(); } catch (IOException ignored) {}
            try { output.close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            reader.shutdownNow();
        }
    }
}
