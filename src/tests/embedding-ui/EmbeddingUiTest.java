package ai.enterprise.tests;

import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.assistant.AssistantPanel;
import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.internal.DPIUtil;

/** Manual, developer-only real SWT host test; creates no native inference worker. */
public final class EmbeddingUiTest {
    private static final List<Map<String,Object>> checks = new ArrayList<>();
    private static void check(boolean condition, String name) {
        checks.add(Map.of("name", name, "passed", condition));
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length == 0 ? "src" : args[0]).toAbsolutePath().normalize();
        String reportName=args.length>1?args[1]:"pink-embedding-ui.json";
        if(!reportName.matches("[A-Za-z0-9_.-]+\\.json"))throw new IllegalArgumentException("Report must be a JSON basename");
        Path root = source.getParent(), report = source.resolve("build/test-results").resolve(reportName);
        Path swt = source.resolve("build/native").toRealPath();
        System.setProperty("swt.library.path", swt.toString());
        long started = System.nanoTime();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("suite", "EnterpriseAIComponent real SWT host embedding"); result.put("timestamp", Instant.now().toString());
        result.put("manualDeveloperTest", true); result.put("realAssistantModule", true); result.put("realAssistantPanel", true);
        result.put("swtLibraryPath", swt.toString()); result.put("nativeWorkerStarted", false);
        result.put("limitations", List.of("No real model inference", "Does not prove Process Monitor no-write portability", "Test report is a developer-only build artifact"));
        ExecutorService executor1 = Executors.newFixedThreadPool(2), executor2 = Executors.newFixedThreadPool(2);
        ProbePlatform platform1 = new ProbePlatform(source.resolve("build/pink-embedding-ui/first"));
        ProbePlatform platform2 = new ProbePlatform(source.resolve("build/pink-embedding-ui/second"));
        EnterpriseAIComponent first = EnterpriseAIComponent.create(new Api.Environment(platform1.root, executor1, platform1), List.of(new AssistantModule()), List.of(new AssistantPanel()));
        EnterpriseAIComponent second = EnterpriseAIComponent.create(new Api.Environment(platform2.root, executor2, platform2), List.of(new AssistantModule()), List.of(new AssistantPanel()));
        Display display = null;
        Throwable[] failure = {null};
        boolean[] completed = {false};
        try {
            display = new Display();
            Shell shell = new Shell(display); shell.setText("миниИИ — проверка двух встроенных компонентов"); shell.setLayout(new GridLayout(2, true));
            Composite host1 = new Composite(shell, SWT.BORDER), host2 = new Composite(shell, SWT.BORDER);
            host1.setLayout(new FillLayout()); host2.setLayout(new FillLayout());
            host1.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true)); host2.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            Composite attachment1 = first.attach(host1), attachment2 = second.attach(host2);
            var session1 = first.context().require(Api.AssistantSessionServiceV1.class);
            var session2 = second.context().require(Api.AssistantSessionServiceV1.class);
            check(attachment1.getParent() == host1 && attachment2.getParent() == host2, "facade attaches to caller supplied SWT child hosts");
            Text input1 = input(host1), input2 = input(host2), transcript1 = transcript(host1), transcript2 = transcript(host2);
            Control send1 = send(host1), send2 = send(host2);
            check(input1 != input2 && transcript1 != transcript2 && send1 != send2, "real assistant controls are independent");
            check(controls(host1).stream().noneMatch(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui.cancel")))
                    && controls(host2).stream().noneMatch(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui.cancel"))),
                    "both embedded assistants expose no separate cancellation control");
            check(controls(host1).stream().filter(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui.send"))).count() == 1
                    && controls(host2).stream().filter(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui.send"))).count() == 1,
                    "each embedded composer retains one send and stop action control");
            check(!send1.isEnabled() && !send2.isEnabled(), "blank queries disable both independent send controls");
            var area = display.getPrimaryMonitor().getClientArea();
            shell.setSize(Math.min(1400, area.width - 40), Math.min(860, area.height - 60)); shell.setLocation(area.x + 20, area.y + 25); shell.open();
            shell.layout(true,true);
            String requestedScale=System.getProperty("swt.autoScale", "default");
            var dpi=display.getDPI();
            result.put("dpi",Map.of("requestedSwtScale",requestedScale,"effectiveSwtScale",DPIUtil.getEffectiveAutoScaleValue(),
                "deviceZoom",DPIUtil.getDeviceZoom(),"shellNativeZoom",shell.getZoom(),"displayDpiX",dpi.x,"displayDpiY",dpi.y,
                "scope","Developer SWT configuration on the current monitor; Windows display settings are unchanged"));
            if(requestedScale.matches("100|150|200"))
                check(DPIUtil.getDeviceZoom()==Integer.parseInt(requestedScale),"requested developer SWT scaling is active");
            check(fitsParent(input1)&&fitsParent(input2)&&fitsParent(send1)&&fitsParent(send2),"both scaled composers and send controls fit inside their own hosts");
            check(send1.getBounds().width>=36&&send1.getBounds().height>=36&&send2.getBounds().width>=36&&send2.getBounds().height>=36,
                "scaled custom send controls retain usable logical hit areas");
            result.put("sendLogicalBounds",List.of(bounds(send1),bounds(send2)));
            result.put("queryLogicalBounds",List.of(bounds(input1),bounds(input2)));
            Display liveDisplay = display;
            List<String> popupErrors = new ArrayList<>();
            Runnable dismissOwnedErrors = new Runnable() {
                public void run() {
                    if (shell.isDisposed()) return;
                    for (Shell dialog : liveDisplay.getShells()) {
                        if (dialog.getParent() == shell && dialog.getData("enterpriseai.ui.error") instanceof String code) {
                            check((dialog.getStyle() & SWT.APPLICATION_MODAL) != 0, "component error belongs to its caller and is modal");
                            popupErrors.add(code); dialog.close();
                        }
                    }
                    liveDisplay.timerExec(100, this);
                }
            };
            liveDisplay.timerExec(100, dismissOwnedErrors);
            java.util.function.Consumer<Runnable> guarded = step -> {
                if (failure[0] != null || shell.isDisposed()) return;
                try { step.run(); } catch (Throwable e) { failure[0] = e; shell.close(); }
            };
            liveDisplay.timerExec(250, () -> guarded.accept(() -> {
                input1.setText("Первый встроенный помощник: договор первой организации");
                input2.setText("Второй встроенный помощник: екінші ұйым құжаты");
                check(send1.isEnabled() && send2.isEnabled(), "nonblank queries enable both send controls");
                send1.notifyListeners(SWT.Selection, new Event()); send2.notifyListeners(SWT.Selection, new Event());
                check(input1.getText().isEmpty() && input2.getText().isEmpty(), "both real UI send actions consume their own input");
            }));
            liveDisplay.timerExec(1500, () -> guarded.accept(() -> {
                check(session1.history().size() == 2 && session2.history().size() == 2, "both attached assistants retain query and expected failure");
                check(session1.history().getFirst().text().contains("первой организации") && session2.history().getFirst().text().contains("екінші ұйым"), "UI submitted distinct requests to distinct sessions");
                String absentMessage = "Провайдер локальной модели отсутствует в этой сборке";
                check(session1.history().getLast().state().equals("error") && session1.history().getLast().text().equals(absentMessage)
                    && session2.history().getLast().state().equals("error") && session2.history().getLast().text().equals(absentMessage), "both UI sessions show expected INFERENCE_ABSENT behavior");
                check(transcript1.getText().contains("первой организации") && !transcript1.getText().contains("екінші ұйым")
                    && transcript2.getText().contains("екінші ұйым") && !transcript2.getText().contains("первой организации"), "actual transcript widgets do not mix histories");
                check(transcript1.getText().contains(absentMessage) && transcript2.getText().contains(absentMessage), "expected missing provider error reaches both real UI widgets");
                check(!send1.isEnabled() && !send2.isEnabled(), "both blank send controls stay disabled after provider error");
                check(popupErrors.equals(List.of("INFERENCE_ABSENT", "INFERENCE_ABSENT")), "each failed request opens exactly one owned modal error");
                first.close(); host1.dispose(); shell.layout(true, true);
                check(attachment1.isDisposed() && transcript1.isDisposed() && input1.isDisposed(), "disposing first caller host releases its SWT children");
                check(session1.history().isEmpty() && executor1.isShutdown() && platform1.closes.get() == 1, "first component close clears its session and caller resources");
                check(!host2.isDisposed() && !attachment2.isDisposed() && !transcript2.isDisposed()
                    && !executor2.isShutdown() && platform2.closes.get() == 0, "second host and component survive first disposal");
                input2.setText("Повторный запрос после закрытия первого компонента");
                check(send2.isEnabled(), "surviving send control enables for retry text");
                send2.notifyListeners(SWT.Selection, new Event());
            }));
            liveDisplay.timerExec(3000, () -> guarded.accept(() -> {
                check(session2.history().size() == 4 && session2.history().get(2).text().contains("Повторный запрос"), "surviving real UI accepts a second request");
                check(transcript2.getText().contains("екінші ұйым") && transcript2.getText().contains("Повторный запрос"), "surviving transcript retains previous history and new request");
                check(!send2.isEnabled() && !host2.isDisposed() && !attachment2.isDisposed(), "surviving UI remains reusable with disabled blank send control");
                check(popupErrors.equals(List.of("INFERENCE_ABSENT", "INFERENCE_ABSENT", "INFERENCE_ABSENT")), "retry opens one additional owned modal without duplicate event/future errors");
                check(first.context().diagnostics().isEmpty() && first.context().commands().isEmpty(), "closed first container clears diagnostics and commands");
                check(!liveDisplay.isDisposed() && !shell.isDisposed(), "caller SWT display and shell survive closing one component");
            }));
            liveDisplay.timerExec(6000, () -> guarded.accept(() -> {
                second.close();
                check(session2.history().isEmpty() && executor2.isShutdown() && platform2.closes.get() == 1, "second component closes independently and clears history");
                host2.dispose(); check(attachment2.isDisposed() && transcript2.isDisposed(), "disposing second host releases its SWT children");
                check(platform1.nativeCalls.get() == 0 && platform2.nativeCalls.get() == 0, "real SWT embedding started no model process or external viewer");
                completed[0] = true; shell.close();
            }));
            while (!shell.isDisposed()) if (!display.readAndDispatch()) display.sleep();
            if (failure[0] != null) throw failure[0];
            check(completed[0], "automatic six second UI lifecycle completed");
            result.put("passed", true); result.put("swtHostsCreated", 2);
        } catch (Throwable error) {
            result.put("passed", false); result.put("error", error.toString());
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error serious) throw serious;
            throw new RuntimeException(error);
        } finally {
            first.close(); second.close();
            if (display != null && !display.isDisposed()) display.dispose();
            result.put("durationMs", (System.nanoTime() - started) / 1_000_000); result.put("assertions", checks.size()); result.put("checks", List.copyOf(checks));
            Files.createDirectories(report.getParent()); Files.writeString(report, Json.stringify(result));
        }
        System.out.println("SWT embedding checks passed: " + checks.size());
    }
    private static List<Control> controls(Composite parent) {
        var result = new ArrayList<Control>();
        for (Control child : parent.getChildren()) { result.add(child); if (child instanceof Composite composite) result.addAll(controls(composite)); }
        return result;
    }
    private static Text input(Composite parent) { return controls(parent).stream().filter(c -> c instanceof Text t && (t.getStyle() & SWT.READ_ONLY) == 0).map(c -> (Text)c).findFirst().orElseThrow(); }
    private static Text transcript(Composite parent) { return controls(parent).stream().filter(c -> c instanceof Text t && (t.getStyle() & SWT.READ_ONLY) != 0).map(c -> (Text)c).findFirst().orElseThrow(); }
    private static Control send(Composite parent) { return controls(parent).stream().filter(c -> Boolean.TRUE.equals(c.getData("enterpriseai.ui.send"))).findFirst().orElseThrow(); }
    private static boolean fitsParent(Control control) {
        var area=control.getParent().getClientArea();var b=control.getBounds();
        return b.width>0&&b.height>0&&b.x>=area.x&&b.y>=area.y&&b.x+b.width<=area.x+area.width&&b.y+b.height<=area.y+area.height;
    }
    private static Map<String,Integer> bounds(Control control) {var b=control.getBounds();return Map.of("x",b.x,"y",b.y,"width",b.width,"height",b.height);}
    private static final class ProbePlatform implements Api.Platform {
        final Path root; final AtomicInteger closes = new AtomicInteger(), nativeCalls = new AtomicInteger();
        ProbePlatform(Path root) { this.root = root; }
        public Path executablePath() { return root.resolve("миниИИ.exe"); }
        public long availableMemoryBytes() { nativeCalls.incrementAndGet(); throw new AssertionError("No native memory probe in this UI fixture"); }
        public void openWith(Path path) { nativeCalls.incrementAndGet(); throw new AssertionError("No external viewer in this UI fixture"); }
        public Process launchWorker(Path executable, Path directory, long limit) { nativeCalls.incrementAndGet(); throw new AssertionError("No inference worker in this UI fixture"); }
        public void close() { closes.incrementAndGet(); }
    }
}
