package ai.enterprise.inference;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

public final class InferenceModule implements Api.ModuleFactory {
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static String nextRequestId() {
        long id = REQUEST_IDS.updateAndGet(previous -> {
            if (previous == Long.MAX_VALUE)
                throw new Api.ServiceException("REQUEST_ID_LIMIT", "Исчерпан диапазон ID запросов сеанса");
            return previous + 1;
        });
        // Correlation IDs are not secrets. Avoid SecureRandom/CAPI profile writes.
        return new UUID(0L, id).toString();
    }
    public String id() { return "inference"; }
    public void register(Api.Context context) {
        var provider = new WorkerProvider(context);
        context.register(Api.InferenceServiceV1.class, provider);
        context.register(ai.enterprise.contracts.EvidenceModelServiceV1.class, new EvidenceProvider(context));
        context.command("model-status", args -> {
            var result = provider.availability();
            if (!result.success()) return result;
            var p = result.value();
            return Map.of("name", p.name(), "precision", p.precision(), "contextTokens", p.contextTokens(),
                "threads", p.threads(), "minFreeMemoryBytes", p.minFreeMemoryBytes());
        });
        // Useful offline headless operation; stdout is supplied by the caller, never a log file.
        context.command("model-generate", args -> {
            if (args.isEmpty() || args.size() > 2)
                throw new Api.ServiceException("INVALID_REQUEST", "model-generate: запрос и необязательная задержка отмены в мс");
            var token = new Api.CancellationToken();
            if (args.size() == 2) {
                int delay;
                try { delay = Integer.parseInt(args.get(1)); }
                catch (NumberFormatException error) { throw new Api.ServiceException("INVALID_REQUEST", "Неверная задержка отмены"); }
                if (delay < 1 || delay > 30000) throw new Api.ServiceException("INVALID_REQUEST", "Задержка отмены должна быть 1–30000 мс");
                CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(token::cancel);
            }
            var output = new StringBuilder();
            var result = provider.generate(new Api.GenerationRequest("headless-" + nextRequestId(),
                List.of(new Api.ModelMessage("user", args.getFirst())), 128), event -> {
                    if (event.type().equals("token")) {
                        if (output.length() + event.text().length() > 1_048_576) {
                            token.cancel(); throw new Api.ServiceException("RESPONSE_TOO_LARGE", "Ответ превышает 1 МиБ символов");
                        }
                        output.append(event.text());
                    }
                }, token).toCompletableFuture().join();
            return result.success() ? Api.Result.ok(Map.of("text", output.toString())) : result;
        });
    }

    public static final class WorkerProvider implements Api.InferenceServiceV1 {
        private static final int MAX_FRAME = 8 * 1024 * 1024;
        private static final String PROTOCOL = "enterpriseai.inference.v1";
        private final Api.Context context;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "enterpriseai-inference"); t.setDaemon(true); return t;
        });
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "enterpriseai-inference-cancel"); t.setDaemon(true); return t;
        });
        private final AtomicReference<Active> active = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Resident resident;
        private record Resident(Active transport, WorkerManifest manifest, Path model, Map<String,Object> description) {}
        private synchronized Resident takeResident(Path model, Map<String,Object> description) {
            Resident previous = resident; resident = null;
            if (previous == null) return null;
            if (!closed.get() && previous.model().equals(model) && previous.description().equals(description)
                && previous.transport().reusable()) return previous;
            previous.transport().close(); return null;
        }
        private synchronized void retain(Active transport, WorkerManifest manifest, Path model, Map<String,Object> description) {
            if (resident != null) resident.transport().close();
            if (closed.get() || !transport.reusable()) { transport.close(); resident = null; }
            else resident = new Resident(transport, manifest, model, description);
        }
        private synchronized void discardResident() {
            if (resident != null) { resident.transport().close(); resident = null; }
        }
        public WorkerProvider(Api.Context context) { this.context = context; }

        /** Called explicitly by Send/status only; registration never probes or loads a model. */
        public Api.Result<Api.ProviderInfo> availability() {
            try {
                var model = context.environment().model();
                if (!Files.isDirectory(model)) throw new Api.ServiceException("MODEL_FOLDER_MISSING", "Не найдена папка локальной модели:\n"+model+"\nПоместите подготовленную папку model рядом с приложением.");
                if (!Files.isRegularFile(model.resolve("inference-worker.exe")))
                    throw new Api.ServiceException("MODEL_WORKER_MISSING", "Не найден исполняемый файл локальной модели:\n"+model.resolve("inference-worker.exe")+"\nИспользуйте полный подготовленный комплект model.");
                Map<String,Object> manifest = readManifest(model);
                if (!PROTOCOL.equals(manifest.get("protocol")) || !"windows-x64".equals(manifest.get("architecture")))
                    throw new Api.ServiceException("INCOMPATIBLE_MODEL", "Несовместимый протокол или архитектура модели");
                return Api.Result.ok(new Api.ProviderInfo(Json.string(manifest.get("engine")), Json.string(manifest.get("precision")),
                    Math.toIntExact(Json.integer(manifest.get("contextTokens"))), Math.toIntExact(Json.integer(manifest.get("threads"))),
                    Json.integer(manifest.get("minFreeMemoryBytes"))));
            } catch (Throwable e) { return Api.failure(e); }
        }

        public CompletionStage<Api.Result<Void>> generate(Api.GenerationRequest request,
            Consumer<Api.GenerationEvent> events, Api.CancellationToken cancellation) {
            if (closed.get()) return CompletableFuture.completedFuture(Api.Result.fail("PROVIDER_CLOSED", "Провайдер модели завершён"));
            Active operation = new Active(request.requestId());
            if (!active.compareAndSet(null, operation))
                return CompletableFuture.completedFuture(Api.Result.fail("BUSY", "Модель уже обрабатывает запрос"));
            cancellation.onCancel(() -> cancel(operation));
            return CompletableFuture.supplyAsync(() -> {
                boolean completed = false, persistent = false;
                Path model = null; Map<String,Object> raw = null; WorkerManifest manifest = null;
                try {
                    cancellation.check();
                    Api.Result<Api.ProviderInfo> availability = availability();
                    if (!availability.success()) throw new Api.ServiceException(availability.error().code(), availability.error().message());
                    model = context.environment().model().toRealPath();
                    raw = readManifest(model);
                    persistent = Long.valueOf(2).equals(raw.get("schemaVersion"));
                    Resident cached = takeResident(model, raw);
                    if (cached != null) {
                        operation.bind(cached.transport()); manifest = cached.manifest();
                    }
                    if (manifest == null) {
                        Set<String> availableIsa = new HashSet<>();
                        Object isaRaw = raw.get("supportedIsa");
                        if (isaRaw instanceof List<?> requirements) for (Object item : requirements)
                            if (item instanceof String isa && context.environment().platform().cpuSupports(isa)) availableIsa.add(isa);
                        manifest = WorkerManifest.verify(raw, model, availableIsa,
                            context.environment().platform().availableMemoryBytes(), cancellation::cancelled);
                        validateRequest(request, manifest);
                        cancellation.check();
                        if (context.environment().platform().availableMemoryBytes() < manifest.minFreeMemoryBytes())
                            throw new Api.ServiceException("OUT_OF_MEMORY", "Свободная память уменьшилась во время проверки модели");
                        Process process = context.environment().platform().launchWorker(manifest.executable(), model, manifest.maxProcessMemoryBytes());
                        operation.process.set(process);
                        if (closed.get() || cancellation.cancelled()) { process.destroyForcibly(); cancellation.check(); throw new Api.ServiceException("PROVIDER_CLOSED", "Провайдер завершён"); }
                        operation.startReaders();
                        operation.write(Map.of("type", "hello", "protocol", PROTOCOL));
                        Map<String,Object> ready = operation.read(180, cancellation);
                        if ("error".equals(ready.get("type"))) throw workerError(ready, null);
                        requireKeys(ready, Set.of("type", "protocol"));
                        if (!"ready".equals(ready.get("type")) || !PROTOCOL.equals(ready.get("protocol")))
                            throw new Api.ServiceException("PROTOCOL_ERROR", "Worker не подтвердил протокол");
                    } else {
                        validateRequest(request, manifest);
                    }
                    cancellation.check();
                    operation.write(Map.of("type", "generate", "requestId", request.requestId(),
                        "messages", request.messages(), "maxTokens", request.maxTokens()));
                    while (true) {
                        Map<String,Object> frame = operation.read(120, cancellation);
                        String type = requiredString(frame, "type");
                        if (!request.requestId().equals(frame.get("requestId")))
                            throw new Api.ServiceException("PROTOCOL_ERROR", "Неверный requestId worker");
                        switch (type) {
                            case "token" -> {
                                requireKeys(frame, Set.of("type", "requestId", "text"));
                                String text = requiredStringAllowEmpty(frame, "text");
                                cancellation.check();
                                events.accept(new Api.GenerationEvent("token", request.requestId(), text, null));
                            }
                            case "done" -> {
                                requireKeys(frame, Set.of("type", "requestId"));
                                cancellation.check();
                                events.accept(new Api.GenerationEvent("done", request.requestId(), "", null));
                                completed = true;
                                return Api.Result.ok(null);
                            }
                            case "cancelled" -> {
                                requireKeys(frame, Set.of("type", "requestId"));
                                throw new Api.ServiceException("CANCELLED", "Генерация отменена");
                            }
                            case "error" -> throw workerError(frame, request.requestId());
                            default -> throw new Api.ServiceException("PROTOCOL_ERROR", "Неожиданное сообщение worker");
                        }
                    }
                } catch (Throwable e) {
                    discardResident();
                    Api.Result<Void> result = cancellation.cancelled()
                        ? Api.Result.fail("CANCELLED", "Генерация отменена")
                        : e instanceof WorkerManifest.InsufficientMemoryException ? Api.Result.fail("OUT_OF_MEMORY", e.getMessage())
                        : e instanceof java.util.concurrent.CancellationException ? Api.Result.fail("CANCELLED", "Генерация отменена") : Api.failure(e);
                    events.accept(new Api.GenerationEvent("error", request.requestId(), "", result.error()));
                    return result;
                } finally {
                    if (completed && persistent && !cancellation.cancelled() && !closed.get())
                        retain(operation.transport(), manifest, model, raw);
                    else operation.close();
                    active.compareAndSet(operation, null);
                }
            }, executor);
        }

        private static Map<String,Object> readManifest(Path model) throws IOException {
            Path path = model.resolve("worker.json");
            if (!Files.isRegularFile(path)) throw new Api.ServiceException("MODEL_MANIFEST_MISSING", "Не найден файл описания локальной модели:\n"+path+"\nИспользуйте полный подготовленный комплект model.");
            if (!path.toRealPath().startsWith(model.toRealPath())) throw new Api.ServiceException("MODEL_PATH_INVALID", "worker.json находится вне model");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] bytes = stream.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new Api.ServiceException("MODEL_MANIFEST_INVALID", "worker.json превышает 1 МиБ");
                return Json.object(decode(bytes));
            }
        }
        private void cancel(Active operation) {
            if (active.get() != operation) return;
            if (!timer.isShutdown()) timer.schedule(operation::close, 2, TimeUnit.SECONDS);
            var writer = new Thread(() -> {
                try { if (active.get() == operation && operation.transport().process.get() != null) operation.write(Map.of("type", "cancel", "requestId", operation.id)); }
                catch (Exception ignored) {}
            }, "enterpriseai-worker-cancel-write");
            writer.setDaemon(true); writer.start();
        }
        public void close() {
            if (closed.compareAndSet(false, true)) {
                Active operation = active.get(); if (operation != null) operation.close();
                discardResident();
                executor.shutdownNow(); timer.shutdownNow();
            }
        }
        private static void validateRequest(Api.GenerationRequest request, WorkerManifest manifest) {
            if (request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 128
                || request.maxTokens() < 1 || request.maxTokens() > manifest.contextTokens()
                || request.messages() == null || request.messages().isEmpty() || request.messages().size() > 128)
                throw new Api.ServiceException("INVALID_REQUEST", "Некорректный запрос генерации");
            for (var message : request.messages())
                if (!Set.of("system", "user", "assistant").contains(message.role()) || message.content() == null)
                    throw new Api.ServiceException("INVALID_REQUEST", "Некорректная роль или текст");
        }
        private static Api.ServiceException workerError(Map<String,Object> frame, String requestId) {
            Set<String> keys = requestId == null && !frame.containsKey("requestId")
                ? Set.of("type", "code", "message") : Set.of("type", "requestId", "code", "message");
            requireKeys(frame, keys);
            String code = requiredString(frame, "code"), message = requiredString(frame, "message");
            if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new Api.ServiceException("PROTOCOL_ERROR", "Неверный код ошибки worker");
            if (requestId != null && !requestId.equals(frame.get("requestId"))) throw new Api.ServiceException("PROTOCOL_ERROR", "Неверный requestId ошибки");
            return new Api.ServiceException(code, message.substring(0, Math.min(message.length(), 4096)));
        }
        private static void requireKeys(Map<String,Object> frame, Set<String> keys) {
            if (!frame.keySet().equals(keys)) throw new Api.ServiceException("PROTOCOL_ERROR", "Неверные поля сообщения worker");
        }
        private static String requiredString(Map<String,Object> frame, String key) {
            String s = requiredStringAllowEmpty(frame, key);
            if (s.isBlank()) throw new Api.ServiceException("PROTOCOL_ERROR", "Пустое поле " + key);
            return s;
        }
        private static String requiredStringAllowEmpty(Map<String,Object> frame, String key) {
            if (!(frame.get(key) instanceof String s)) throw new Api.ServiceException("PROTOCOL_ERROR", "Неверное поле " + key);
            return s;
        }
        private static String decode(byte[] bytes) throws CharacterCodingException {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
        private static final class Active implements AutoCloseable {
            final String id;
            final AtomicReference<Process> process = new AtomicReference<>();
            final ArrayBlockingQueue<Object> frames = new ArrayBlockingQueue<>(4);
            final StringBuilder stderr = new StringBuilder();
            final AtomicBoolean stopped = new AtomicBoolean();
            private volatile Active delegate;
            Thread reader, diagnosticReader;
            Active(String id) { this.id = id; }
            Active transport() { Active value = delegate; return value == null ? this : value.transport(); }
            void bind(Active value) {
                delegate = value.transport();
                if (stopped.get()) delegate.close();
            }
            boolean reusable() {
                return !stopped.get() && process.get() != null && process.get().isAlive() && frames.isEmpty();
            }
            void startReaders() {
                reader = new Thread(() -> {
                    try (InputStream input = new BufferedInputStream(process.get().getInputStream())) {
                        while (!stopped.get()) {
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
                            int b;
                            while ((b = input.read()) != -1 && b != '\n') {
                                if (buffer.size() == MAX_FRAME) throw new Api.ServiceException("PROTOCOL_ERROR", "Строка worker превышает 8 МиБ");
                                buffer.write(b);
                            }
                            if (b == -1) throw new EOFException("Worker завершился до terminal frame");
                            byte[] bytes = buffer.toByteArray();
                            if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') bytes = Arrays.copyOf(bytes, bytes.length - 1);
                            frames.put(Json.object(decode(bytes)));
                        }
                    } catch (Throwable e) {
                        if (e instanceof CharacterCodingException || e instanceof IllegalArgumentException)
                            e = new Api.ServiceException("PROTOCOL_ERROR", "Worker передал неверный UTF-8 или JSON", e);
                        try { frames.put(e); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                }, "enterpriseai-worker-protocol");
                reader.setDaemon(true); reader.start();
                diagnosticReader = new Thread(() -> {
                    try (Reader input = new InputStreamReader(process.get().getErrorStream(), StandardCharsets.UTF_8)) {
                        char[] chars = new char[1024]; int n;
                        while ((n = input.read(chars)) != -1) synchronized (stderr) {
                            stderr.append(chars, 0, n);
                            if (stderr.length() > 65536) stderr.delete(0, stderr.length() - 65536);
                        }
                    } catch (IOException ignored) {}
                }, "enterpriseai-worker-diagnostics");
                diagnosticReader.setDaemon(true); diagnosticReader.start();
            }
            synchronized void write(Map<String,Object> message) throws IOException {
                if (delegate != null) { delegate.write(message); return; }
                byte[] bytes = Json.stringify(message).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_FRAME) throw new Api.ServiceException("CONTEXT_TOO_LARGE", "Запрос превышает 8 МиБ");
                Process p = process.get();
                if (p == null || stopped.get()) throw new IOException("Worker unavailable");
                OutputStream output = p.getOutputStream(); output.write(bytes); output.write('\n'); output.flush();
            }
            @SuppressWarnings("unchecked") Map<String,Object> read(int seconds, Api.CancellationToken token) throws Exception {
                if (delegate != null) return delegate.read(seconds, token);
                Object frame = frames.poll(seconds, TimeUnit.SECONDS);
                token.check();
                if (frame == null) throw new Api.ServiceException("WORKER_TIMEOUT", "Worker не отвечает");
                if (frame instanceof Throwable error) {
                    String tail; synchronized (stderr) { tail = stderr.substring(Math.max(0, stderr.length() - 1024)); }
                    if (error instanceof Api.ServiceException service) throw service;
                    throw new Api.ServiceException("WORKER_EXITED", "Вычислительный процесс завершился или передал неверный протокол" + (tail.isBlank() ? "" : ": " + tail), error);
                }
                return (Map<String,Object>) frame;
            }
            public void close() {
                if (delegate != null) { stopped.set(true); delegate.close(); return; }
                if (stopped.compareAndSet(false, true)) {
                    Process p = process.get();
                    if (p != null) {
                        // Terminate first: closing a pipe held by a blocked synchronous writer can otherwise wait forever.
                        if (p.isAlive()) p.destroyForcibly();
                        try { p.getOutputStream().close(); } catch (IOException ignored) {}
                        try { p.getInputStream().close(); } catch (IOException ignored) {}
                        try { p.getErrorStream().close(); } catch (IOException ignored) {}
                    }
                    if (reader != null) reader.interrupt(); if (diagnosticReader != null) diagnosticReader.interrupt();
                    frames.clear(); frames.offer(new Api.ServiceException("WORKER_CLOSED", "Вычислительный процесс завершён"));
                    synchronized (stderr) { stderr.setLength(0); }
                }
            }
        }
    }
}
