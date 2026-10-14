package ai.enterprise.tests;

import ai.enterprise.contracts.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Developer-only native-image probe. Prints results; observer saves reports under src/build. */
public final class StyledTextLongPathProbe {
    private static final List<Map<String,Object>> checks = new ArrayList<>();
    private static void check(boolean condition, String name) {
        checks.add(Map.of("name", name, "passed", condition));
        if (!condition) throw new AssertionError(name);
    }
    public static void main(String[] ignored) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        Path root = Path.of(org.graalvm.nativeimage.ProcessProperties.getExecutableName()).toAbsolutePath().getParent();
        System.setProperty("swt.library.path", root.resolve("native").toString());
        long started = System.nanoTime();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("suite", "StyledText native long EXE path Unicode/focus probe");
        result.put("nativeImage", org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode());
        result.put("imeDisabled", false);
        result.put("developerOnly", true);
        try {
            String actualExecutable = org.graalvm.nativeimage.ProcessProperties.getExecutableName();
            result.put("executablePath", actualExecutable); result.put("executablePathChars", actualExecutable.length());
            System.out.println(Json.stringify(Map.of("stage", "before-display", "executablePathChars", actualExecutable.length())));
            check(org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode(), "actual NativeImage executable, no short JVM proxy");
            check(actualExecutable.length() >= 350, "actual process EXE path has at least 350 Unicode characters");
            Display display = new Display();
            stage("display-created");
            Throwable[] failure = {null}; boolean[] completed = {false};
            try {
                Shell shell = new Shell(display); shell.setText("EnterpriseAI — StyledText long-path probe");
                stage("shell-created");
                shell.setLayout(new GridLayout(1, false));
                Label title = new Label(shell, SWT.NONE); title.setText("Проверка Unicode-ввода без native EDIT");
                StyledText transcript = new StyledText(shell, SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
                stage("styled-transcript-created");
                transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                StyledText query = new StyledText(shell, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
                stage("styled-query-created");
                GridData inputLayout = new GridData(SWT.FILL, SWT.FILL, true, false); inputLayout.heightHint = 100; query.setLayoutData(inputLayout);
                Button send = new Button(shell, SWT.PUSH); send.setText("Отправить");
                AtomicInteger sends = new AtomicInteger();
                send.addListener(SWT.Selection, e -> { sends.incrementAndGet(); transcript.append("\nВы: " + query.getText()); query.setText(""); query.setFocus(); });
                shell.setSize(800, 540); stage("before-shell-open"); shell.open(); stage("after-shell-open");
                java.util.function.Consumer<Runnable> guarded = action -> {
                    if (failure[0] != null || shell.isDisposed()) return;
                    try { action.run(); } catch (Throwable error) { failure[0] = error; shell.close(); }
                };
                String unicode = "Русский Қазақша Әә Ғғ Ққ Ңң Өө Ұұ Үү Һһ Іі 😀";
                display.timerExec(250, () -> guarded.accept(() -> {
                    transcript.setText(unicode); query.setText(unicode);
                    check(transcript.getText().equals(unicode) && query.getText().equals(unicode), "setText preserves Russian, Kazakh letters and emoji in both StyledText widgets");
                    stage("before-query-focus");
                    check(query.setFocus(), "real native focus can enter StyledText query");
                    stage("after-query-focus");
                    check(query.getIME() != null, "StyledText retains its SWT IME object");
                    query.setText(""); query.setCaretOffset(0);
                    for (char character : unicode.toCharArray()) OS.SendMessage(query.handle, OS.WM_CHAR, character, 1L);
                    check(query.getText().equals(unicode), "real WM_CHAR inserts all UTF-16 units including surrogate-pair emoji");
                    System.out.println(Json.stringify(Map.of("stage", "unicode-and-focus", "passed", true)));
                }));
                display.timerExec(900, () -> guarded.accept(() -> {
                    check(transcript.setFocus(), "real native focus can enter read-only StyledText transcript");
                    String before = transcript.getText(); OS.SendMessage(transcript.handle, OS.WM_CHAR, 'X', 1L);
                    check(transcript.getText().equals(before), "read-only StyledText ignores WM_CHAR edits");
                    check(query.setFocus(), "focus can return to editable StyledText without native EDIT registration");
                    send.notifyListeners(SWT.Selection, new Event());
                    check(sends.get() == 1 && query.getText().isEmpty() && transcript.getText().contains("Вы: " + unicode), "send action transfers Unicode query and clears input");
                }));
                display.timerExec(1500, () -> guarded.accept(() -> {
                    query.setText("қазақша қайта сұрақ 😀"); query.setSelection(0, query.getCharCount());
                    OS.SendMessage(query.handle, OS.WM_CHAR, 'Ж', 1L);
                    check(query.getText().equals("Ж"), "native WM_CHAR replaces selected Unicode text");
                    transcript.setFocus(); send.setFocus(); query.setFocus();
                    send.notifyListeners(SWT.Selection, new Event());
                    check(sends.get() == 2 && transcript.getText().endsWith("Вы: Ж"), "focus changes and repeated send remain usable");
                }));
                display.timerExec(2500, () -> guarded.accept(() -> { completed[0] = true; shell.close(); }));
                while (!shell.isDisposed()) if (!display.readAndDispatch()) display.sleep();
                if (failure[0] != null) throw failure[0];
                check(completed[0], "automatic native UI lifecycle completed");
            } finally { display.dispose(); }
            result.put("passed", true);
        } catch (Throwable error) {
            result.put("passed", false); result.put("error", error.toString());
        } finally {
            result.put("checks", List.copyOf(checks)); result.put("assertions", checks.size());
            result.put("durationMs", (System.nanoTime() - started) / 1_000_000);
            System.out.println(Json.stringify(result));
        }
        if (!Boolean.TRUE.equals(result.get("passed"))) System.exit(1);
    }
    private static void stage(String name) { System.out.println(Json.stringify(Map.of("stage", name))); }
}
