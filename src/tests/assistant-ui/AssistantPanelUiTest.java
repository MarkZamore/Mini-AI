package ai.enterprise.tests;

import ai.enterprise.assistant.AssistantPanel;
import ai.enterprise.assistant.RoundedSourcesBlock;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.shell.UiHost;
import ai.enterprise.shell.UiTheme;
import ai.enterprise.shell.SoftButton;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Developer-only real SWT behavior checks; the injected host records errors without opening dialogs. */
public final class AssistantPanelUiTest {
    private static final List<Map<String,Object>> checks = new ArrayList<>();
    private static void check(boolean condition, String name) {
        checks.add(Map.of("name", name, "passed", condition));
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0 ? "src" : args[0]).toAbsolutePath().normalize();
        String reportName = args.length > 2 ? args[2] : "assistant-ui.json";
        if (!reportName.matches("[A-Za-z0-9_.-]+\\.json")) throw new IllegalArgumentException("Use a report basename ending in .json");
        if (args.length > 1) System.setProperty("swt.library.path", Path.of(args[1]).toAbsolutePath().toString());
        var executor = Executors.newSingleThreadExecutor();
        Api.Context context = new Api.Context(new Api.Environment(source.resolve("build/assistant-ui-fixture"), executor, new ProbePlatform()));
        ProbeSession session = new ProbeSession(); context.register(Api.AssistantSessionServiceV1.class, session);
        ProbeViewer viewer = new ProbeViewer(); context.register(Api.SourceViewerServiceV1.class, viewer);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("suite", "миниИИ assistant real SWT behavior");
        result.put("scope", "Developer UI only; no actual model, documents, SQLite or no-write claim");
        Display display = null;
        try {
            display = new Display(); Shell shell = new Shell(display); UiTheme theme = UiTheme.get(display);
            theme.decorate(shell); shell.setLayout(new FillLayout());
            RecordingHost host = new RecordingHost(theme); UiHost.install(shell, host);
            Composite panel = new AssistantPanel().create(shell, context);
            shell.setSize(700, 800); shell.open(); pump(display, () -> shell.isVisible());
            Text query = (Text) marked(panel, "query"), transcript = (Text) marked(panel, "transcript");
            SoftButton send = (SoftButton) marked(panel, "send"); Control empty = marked(panel, "empty");
            Composite composer = (Composite) marked(panel, "composer"); Control sources = marked(panel, "sources");
            Label sourceTitle = (Label) marked(panel, "sources-title");
            var dpi = display.getDPI(); String requestedScale = System.getProperty("swt.autoScale", "default");
            result.put("dpi", Map.of("requestedSwtScale", requestedScale, "deviceZoom", DPIUtil.getDeviceZoom(),
                "effectiveSwtScale", DPIUtil.getEffectiveAutoScaleValue(), "shellNativeZoom", shell.getZoom(),
                "displayDpiX", dpi.x, "displayDpiY", dpi.y));
            if (requestedScale.matches("100|150|200"))
                check(DPIUtil.getDeviceZoom() == Integer.parseInt(requestedScale), "requested developer SWT scaling is active");
            Rectangle queryBounds = display.map(query, composer, query.getClientArea());
            Rectangle sendBounds = display.map(send, composer, new Rectangle(0, 0, send.getSize().x, send.getSize().y));
            Rectangle composerBounds = composer.getClientArea();
            result.put("geometry", Map.of("composer", rectangle(composerBounds), "query", rectangle(queryBounds),
                "send", rectangle(sendBounds), "sources", rectangle(sources.getBounds()),
                "sourcesTitle", rectangle(sourceTitle.getBounds())));
            check(inside(queryBounds, composerBounds, 8) && queryBounds.width >= 150 && queryBounds.height >= 56,
                "composer text remains usable and inside its rounded frame at the active DPI");
            check(inside(sendBounds, composerBounds, 8), "single composer action fits inside the frame");
            check(sendBounds.width == sendBounds.height && sendBounds.width >= 40,
                "send retains a nonclipped circular hit target at the active DPI");
            check(controls(panel).stream().noneMatch(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui.cancel"))),
                "separate cancellation control is absent");
            check(empty.isVisible() && !transcript.isVisible() && transcript.getText().isEmpty(), "empty history uses a separate centered brand control");
            check(sources instanceof RoundedSourcesBlock block && block.getText().equals("Источники ответа")
                && sources.isVisible() && !((GridData)sources.getLayoutData()).exclude,
                "empty sources retain their visible caption on the rounded frame");
            check(sourceTitle.getText().equals("Источники ответа") && sourceTitle.getBounds().x == 24
                && sourceTitle.getFont() == theme.bodyFont(), "source caption is offset to the right with a plain body font");
            Rectangle contentBounds = ((RoundedSourcesBlock)sources).content().getBounds();
            check(contentBounds.x == 14 && contentBounds.y >= sourceTitle.getBounds().height + 12
                && contentBounds.width + 28 == sources.getSize().x,
                "source content stays padded inside the rounded frame and below its caption");
            check(controls(((RoundedSourcesBlock)sources).content()).stream().noneMatch(c -> c instanceof Label || c instanceof Link),
                "empty sources contain no placeholder text");
            check(!send.isEnabled() && !send.isStopMode() && send.getAccessibleName().equals("Отправить"),
                "blank idle composer disables its single send arrow");
            check(descendant(send, composer), "single send or stop action remains inside the rounded composer");
            check(controls(panel).stream().filter(c -> c instanceof Label).map(c -> ((Label)c).getText()).noneMatch(t ->
                t.contains("AI-помощник по документам") || t.contains("История хранится") || t.contains("Готово") || t.contains("Ссылки и точные места")), "removed labels and ready status are absent");
            query.setText("  "); check(!send.isEnabled(), "whitespace does not enable send");
            query.setText("Первая строка"); check(send.isEnabled(), "nonblank input enables send");
            ImageData arrowImage = snapshot(send); RGB glyph = display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT).getRGB();
            Event newline = key(SWT.CR, SWT.SHIFT); query.notifyListeners(SWT.KeyDown, newline);
            check(newline.doit && session.operations.isEmpty(), "Shift+Enter preserves the text edit and does not submit");
            query.setText("Первая строка\nВторая строка"); Event enter = key(SWT.CR, 0); query.notifyListeners(SWT.KeyDown, enter);
            check(!enter.doit && session.operations.size() == 1 && session.operations.getFirst().question.contains("\n"), "Enter submits the multiline request exactly once");
            check(query.getText().isEmpty() && send.isEnabled() && send.isStopMode()
                && send.getAccessibleName().equals("Остановить") && send.getToolTipText().equals("Остановить"),
                "processing consumes input and changes the same enabled circle to stop");
            ImageData stopImage = snapshot(send);
            check(pixel(arrowImage, 0, 6).equals(glyph) && !pixel(arrowImage, 4, 4).equals(glyph)
                && pixel(stopImage, 4, 4).equals(glyph) && !pixel(stopImage, 0, 6).equals(glyph),
                "the same circle paints an arrow shaft while idle and a filled square while processing");
            result.put("glyphSamples", Map.of("arrowShaft", color(pixel(arrowImage, 0, 6)),
                "arrowCorner", color(pixel(arrowImage, 4, 4)), "stopCorner", color(pixel(stopImage, 4, 4)),
                "stopBelowSquare", color(pixel(stopImage, 0, 6))));
            check(sources.isVisible() && !((GridData)sources.getLayoutData()).exclude,
                "starting a request preserves the visible empty sources block");
            check(!empty.isVisible() && transcript.getText().contains("Первая строка"), "submitted history replaces the centered brand");
            Operation first = session.operations.getFirst(); first.emit("token", "Черновик ответа", List.of(), null);
            pump(display, () -> transcript.getText().contains("Черновик ответа"));
            query.setText("Следующий запрос");
            Event busyEnter = key(SWT.CR, 0); query.notifyListeners(SWT.KeyDown, busyEnter);
            check(!busyEnter.doit && session.operations.size() == 1 && !first.token.cancelled() && send.isStopMode(),
                "editor Enter while busy neither sends a draft nor stops the current request");
            final int[] cancellations = {0}; first.token.onCancel(() -> cancellations[0]++);
            Event down = new Event(); down.button = 1; down.x = send.getSize().x / 2; down.y = send.getSize().y / 2;
            send.notifyListeners(SWT.MouseDown, down);
            Event up = new Event(); up.button = 1; up.x = down.x; up.y = down.y; send.notifyListeners(SWT.MouseUp, up);
            check(first.token.cancelled() && !send.isEnabled() && cancellations[0] == 1,
                "clicking the stop square cancels once and disables repeated activation");
            send.notifyListeners(SWT.Selection, new Event());
            check(cancellations[0] == 1 && session.operations.size() == 1,
                "repeated stop selection cannot create a request or recancel");
            first.fail("CANCELLED", "Генерация отменена", List.of());
            pump(display, () -> !send.isStopMode());
            check(host.errors.isEmpty() && send.isEnabled() && send.getAccessibleName().equals("Отправить"),
                "cancelled event and future restore the send arrow without an error dialog");

            send.notifyListeners(SWT.Selection, new Event()); Operation second = session.operations.getLast();
            Api.Fragment fragment = fragment(); second.emit("sources", "", List.of(fragment), null);
            pump(display, sources::isVisible);
            check(!((GridData)sources.getLayoutData()).exclude && labels((Composite)sources).stream().anyMatch(t -> t.contains("ручной сверкой")), "real source locators and extraction warnings are visible");
            second.fail("MODEL_MISSING", "Не найден model\\inference-worker.exe", List.of(fragment));
            pump(display, () -> host.errors.size() == 1 && !send.isStopMode());
            check(host.errors.size() == 1 && host.errors.getFirst().message().contains("inference-worker.exe"), "event and unsuccessful future share one understandable error report");
            check(!send.isEnabled() && !send.isStopMode() && query.getText().isEmpty()
                && send.getToolTipText().equals("Отправить"), "failure restores a safely disabled blank send arrow");
            check(transcript.getText().contains("Не найден model"), "service failure history remains in the RAM transcript");

            Link sourceLink = controls((Composite)sources).stream().filter(c -> c instanceof Link).map(c -> (Link)c).findFirst().orElseThrow();
            sourceLink.notifyListeners(SWT.Selection, new Event());
            Shell card = Arrays.stream(display.getShells()).filter(s -> s != shell && s.getText().startsWith("Источник:")).findFirst().orElseThrow();
            check(Arrays.equals(card.getImages(), shell.getImages()), "source card uses the same in-memory application icons");
            Text details = controls(card).stream().filter(c -> c instanceof Text).map(c -> (Text)c).findFirst().orElseThrow();
            check(details.getText().contains("не отображение оригинала") && details.getText().contains("pdf_region") && details.getText().contains("page"), "source card preserves the JSON/original distinction and provenance");
            Control open = controls(card).stream().filter(c -> c instanceof Canvas).findFirst().orElseThrow();
            open.notifyListeners(SWT.Selection, new Event()); pump(display, () -> host.errors.size() == 2);
            check(viewer.calls == 1 && host.errors.getLast().code().equals("SOURCE_CHANGED"), "source opening failure reports once through the same host error port");
            card.close();

            query.setText("Будущий ответ"); send.notifyListeners(SWT.Selection, new Event());
            Operation third = session.operations.getLast();
            second.emit("token", "НЕДОПУСТИМЫЙ СТАРЫЙ ЧЕРНОВИК", List.of(), null);
            third.emit("token", "Новый черновик", List.of(), null);
            pump(display, () -> transcript.getText().contains("Новый черновик"));
            check(!transcript.getText().contains("НЕДОПУСТИМЫЙ СТАРЫЙ ЧЕРНОВИК"), "late old request tokens cannot leak into a newer request");
            third.future.completeExceptionally(new Api.ServiceException("WORKER_EXITED", "Локальный движок завершился"));
            pump(display, () -> host.errors.size() == 3 && !send.isStopMode());
            check(host.errors.getLast().code().equals("WORKER_EXITED"), "exceptional future without an error event is reported");

            session.throwOnNext = true; query.setText("Синхронная ошибка"); send.notifyListeners(SWT.Selection, new Event());
            check(host.errors.size() == 4 && !send.isStopMode(), "synchronous service failure recovers the send arrow and reports once");
            query.setText("Успешный ответ"); send.notifyListeners(SWT.Selection, new Event());
            Operation successful = session.operations.getLast(); successful.succeed("Подтверждённый ответ", List.of(fragment));
            pump(display, () -> !send.isStopMode());
            check(!send.isEnabled() && host.errors.size() == 4 && transcript.getText().contains("Подтверждённый ответ"),
                "successful completion restores the disabled blank send arrow and keeps the answer");
            query.setText("Остановка клавишей"); send.notifyListeners(SWT.Selection, new Event());
            Operation keyboardStop = session.operations.getLast();
            send.setFocus();
            Event stopKey = key(' ', 0); stopKey.character = ' '; send.notifyListeners(SWT.KeyDown, stopKey);
            check(keyboardStop.token.cancelled() && !send.isEnabled(), "focused stop action supports keyboard activation");
            keyboardStop.fail("CANCELLED", "Генерация отменена", List.of()); pump(display, () -> !send.isStopMode());
            check(host.errors.size() == 4 && !send.isEnabled(), "keyboard cancellation also restores the blank send arrow without a dialog");
            query.setText("Закрываем активный запрос"); send.notifyListeners(SWT.Selection, new Event());
            Operation closing = session.operations.getLast(); panel.dispose();
            check(closing.token.cancelled(), "disposing the assistant cancels its active request");
            closing.fail("CANCELLED", "Генерация отменена", List.of());
            pump(display, () -> true); check(host.errors.size() == 4, "disposed assistant suppresses late error callbacks");
            shell.dispose(); result.put("passed", true);
        } catch (Throwable error) {
            result.put("passed", false); result.put("error", error.toString());
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error serious) throw serious;
            throw new RuntimeException(error);
        } finally {
            context.close(); executor.shutdownNow(); if (display != null && !display.isDisposed()) display.dispose();
            result.put("assertions", checks.size()); result.put("checks", List.copyOf(checks));
            Path report = source.resolve("build/test-results").resolve(reportName);
            Files.createDirectories(report.getParent()); Files.writeString(report, Json.stringify(result));
        }
        System.out.println("Assistant UI checks passed: " + checks.size());
    }
    private static Event key(int code, int modifiers) { Event e = new Event(); e.keyCode = code; e.stateMask = modifiers; e.doit = true; return e; }
    private static ImageData snapshot(Control control) {
        control.redraw(); control.update();
        var size = control.getSize(); Image image = new Image(control.getDisplay(), size.x, size.y);
        GC gc = new GC(control);
        try { gc.copyArea(image, 0, 0); return image.getImageData(); }
        finally { gc.dispose(); image.dispose(); }
    }
    private static RGB pixel(ImageData image, int dx, int dy) {
        int x = image.width / 2 + (int)Math.round(dx * image.width / 40.0);
        int y = image.height / 2 + (int)Math.round(dy * image.height / 40.0);
        return image.palette.getRGB(image.getPixel(x, y));
    }
    private static Map<String,Integer> color(RGB rgb) { return Map.of("red", rgb.red, "green", rgb.green, "blue", rgb.blue); }
    private static Map<String,Integer> rectangle(Rectangle value) {
        return Map.of("x", value.x, "y", value.y, "width", value.width, "height", value.height);
    }
    private static boolean inside(Rectangle child, Rectangle parent, int padding) {
        return child.x >= parent.x + padding && child.y >= parent.y + padding
            && child.x + child.width <= parent.x + parent.width - padding
            && child.y + child.height <= parent.y + parent.height - padding;
    }
    private static void pump(Display display, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            while (display.readAndDispatch()) { }
            if (condition.getAsBoolean()) return;
            if (System.nanoTime() >= deadline) throw new AssertionError("UI update timeout");
            Thread.sleep(5);
        } while (true);
    }
    private static boolean descendant(Control child, Composite parent) {
        for (Control c = child; c != null; c = c.getParent()) if (c == parent) return true;
        return false;
    }
    private static List<Control> controls(Composite parent) {
        var result = new ArrayList<Control>();
        for (Control child : parent.getChildren()) { result.add(child); if (child instanceof Composite composite) result.addAll(controls(composite)); }
        return result;
    }
    private static Control marked(Composite parent, String name) {
        return controls(parent).stream().filter(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui." + name))).findFirst().orElseThrow();
    }
    private static List<String> labels(Composite parent) { return controls(parent).stream().filter(c -> c instanceof Label).map(c -> ((Label)c).getText()).toList(); }
    private static Api.Fragment fragment() {
        return new Api.Fragment(Map.of("fragment_id", "fragment-1", "document_id", "document-1", "title", "Документ", "text", "Точный исходный текст",
            "source_refs", List.of(Map.of("kind", "pdf_region", "artifact_id", "original", "source_sha256", "a".repeat(64), "page", 2, "bbox", List.of(1, 2, 3, 4))),
            "quality", Map.of("needs_review", true, "verification", "machine_extracted"), "warnings", List.of(), "span", Map.of("start", 0, "end", 20)));
    }
    private static final class RecordingHost implements UiHost {
        final UiTheme theme; final List<Api.ServiceError> errors = new ArrayList<>();
        RecordingHost(UiTheme theme) { this.theme = theme; }
        public UiTheme theme() { return theme; }
        public void error(String id, Api.ServiceError error) { errors.add(error); }
        public void selectedDocument(String id, String title) { }
    }
    private static final class Operation {
        final String question; final Consumer<Api.AssistantEvent> events; final Api.CancellationToken token;
        final CompletableFuture<Api.Result<Void>> future = new CompletableFuture<>(); final ProbeSession session;
        Operation(String question, Consumer<Api.AssistantEvent> events, Api.CancellationToken token, ProbeSession session) {
            this.question = question; this.events = events; this.token = token; this.session = session;
        }
        void emit(String type, String text, List<Api.Fragment> sources, Api.ServiceError error) { events.accept(new Api.AssistantEvent(type, text, sources, error)); }
        void fail(String code, String text, List<Api.Fragment> sources) {
            session.messages.add(new Api.SessionMessage("assistant", text, sources, "error"));
            emit("error", text, sources, new Api.ServiceError(code, text)); future.complete(Api.Result.fail(code, text));
        }
        void succeed(String text, List<Api.Fragment> sources) {
            session.messages.add(new Api.SessionMessage("assistant", text, sources, "complete"));
            emit("done", text, sources, null); future.complete(Api.Result.ok(null));
        }
    }
    private static final class ProbeSession implements Api.AssistantSessionServiceV1 {
        final List<Api.SessionMessage> messages = new ArrayList<>(); final List<Operation> operations = new ArrayList<>(); boolean throwOnNext;
        public List<Api.SessionMessage> history() { return List.copyOf(messages); }
        public CompletionStage<Api.Result<Void>> send(String question, Consumer<Api.AssistantEvent> events, Api.CancellationToken cancellation) {
            if (throwOnNext) { throwOnNext = false; throw new Api.ServiceException("SYNC_FAILED", "Ошибка до начала обработки"); }
            messages.add(new Api.SessionMessage("user", question, List.of(), "complete"));
            Operation op = new Operation(question, events, cancellation, this); operations.add(op); op.emit("user", question, List.of(), null); return op.future;
        }
    }
    private static final class ProbeViewer implements Api.SourceViewerServiceV1 {
        int calls;
        public CompletionStage<Api.Result<Void>> openDocument(String id, Api.CancellationToken cancellation) { calls++; return CompletableFuture.completedFuture(Api.Result.fail("SOURCE_CHANGED", "Изменён docs\\Документ.pdf")); }
        public CompletionStage<Api.Result<Void>> openSource(Api.SourceRef source, Api.CancellationToken cancellation) { return openDocument("document-1", cancellation); }
    }
    private static final class ProbePlatform implements Api.Platform {
        public Path executablePath() { return Path.of("миниИИ.exe"); }
        public long availableMemoryBytes() { throw new AssertionError("No model memory probe expected"); }
        public void openWith(Path path) { throw new AssertionError("No actual external viewer expected"); }
        public Process launchWorker(Path executable, Path directory, long limit) { throw new AssertionError("No actual inference process expected"); }
    }
}
