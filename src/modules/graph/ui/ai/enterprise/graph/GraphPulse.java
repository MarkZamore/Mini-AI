package ai.enterprise.graph;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/** Rare, UI-thread colour animation. It never reads or mutates physics coordinates. */
final class GraphPulse implements AutoCloseable {
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;
    private static final int PERIOD_MILLISECONDS = 8_000;
    private static final int DURATION_MILLISECONDS = 550;
    private final Canvas canvas;
    private final Display display;
    private final Shell shell;
    private final BooleanSupplier idle;
    private final IntConsumer colour;
    private final List<Control> visibilityControls = new ArrayList<>();
    private final Runnable idleTimer = this::begin;
    private final Runnable frameTimer = this::frame;
    private final Runnable resume = this::refresh;
    private final Listener visibility = this::visibilityChanged;
    private final Listener settings = event -> settingsChanged();
    private boolean animationAllowed, eligible, pulsing, closed;
    private long started;
    private int level;

    GraphPulse(Canvas canvas, BooleanSupplier idle, IntConsumer colour) {
        this.canvas = canvas;
        this.display = canvas.getDisplay();
        this.shell = canvas.getShell();
        this.idle = idle;
        this.colour = colour;
        animationAllowed = readAnimationPreference();
        for (Control control = canvas; control != null && control != shell; control = control.getParent()) {
            visibilityControls.add(control);
            control.addListener(SWT.Show, visibility);
            control.addListener(SWT.Hide, visibility);
        }
        for (int event : new int[]{SWT.Activate, SWT.Deactivate, SWT.Iconify, SWT.Deiconify, SWT.Show, SWT.Hide}) {
            shell.addListener(event, visibility);
        }
        display.addListener(SWT.Settings, settings);
        canvas.addDisposeListener(event -> close());
    }

    private void visibilityChanged(org.eclipse.swt.widgets.Event event) {
        if (event.type == SWT.Deactivate || event.type == SWT.Iconify || event.type == SWT.Hide) suspend();
        else if (!closed && !display.isDisposed()) display.asyncExec(resume);
    }

    private void settingsChanged() {
        if (closed || display.isDisposed()) return;
        animationAllowed = readAnimationPreference();
        refresh();
    }

    private boolean readAnimationPreference() {
        if (display.getHighContrast()) return false;
        int[] enabled = {0};
        // Read the BOOL only; neither an SPI_SET action nor persistence flags are used.
        return OS.SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) && enabled[0] != 0;
    }

    private boolean canPulse() {
        boolean visible = !closed && animationAllowed && !display.isDisposed() && !canvas.isDisposed()
                && !display.getHighContrast() && canvas.isVisible() && !shell.isDisposed()
                && !shell.getMinimized() && display.getActiveShell() == shell && idle.getAsBoolean();
        if (!visible) return false;
        var area = canvas.getClientArea();
        return area.width > 0 && area.height > 0;
    }

    /** Call for state transitions, not every physics frame. No timer runs while ineligible. */
    void refresh() {
        if (closed || display.isDisposed()) return;
        boolean next = canPulse();
        if (next == eligible) return;
        eligible = next;
        cancelTimers();
        pulsing = false;
        setLevel(0);
        if (next) display.timerExec(PERIOD_MILLISECONDS, idleTimer);
    }

    private void suspend() {
        if (closed || display.isDisposed()) return;
        eligible = pulsing = false;
        cancelTimers();
        setLevel(0);
    }

    private void begin() {
        if (!canPulse()) { eligible = false; setLevel(0); return; }
        started = System.nanoTime();
        pulsing = true;
        frame();
    }

    private void frame() {
        if (!canPulse()) { refresh(); return; }
        long elapsed = Math.max(0, System.nanoTime() - started);
        if (elapsed >= DURATION_MILLISECONDS * 1_000_000L) {
            pulsing = false;
            setLevel(0);
            long next = PERIOD_MILLISECONDS * 1_000_000L - elapsed;
            display.timerExec((int)Math.max(1, next / 1_000_000L), idleTimer);
        } else {
            setLevel(levelAt(elapsed));
            display.timerExec(16, frameTimer);
        }
    }

    /** Two soft peaks; returned cached colour level represents at most six percent white. */
    static int levelAt(long elapsedNanos) {
        double milliseconds = elapsedNanos / 1_000_000.0;
        if (milliseconds < 0 || milliseconds >= DURATION_MILLISECONDS) return 0;
        double first = softPeak(milliseconds, 110, 110, 110);
        double second = softPeak(milliseconds, 300, 150, 250);
        return Math.max(0, Math.min(6, (int)Math.round(6 * Math.max(first, second))));
    }

    private static double softPeak(double time, double centre, double leftWidth, double rightWidth) {
        double distance = Math.abs(time - centre) / (time < centre ? leftWidth : rightWidth);
        return distance >= 1 ? 0 : (1 + Math.cos(Math.PI * distance)) / 2;
    }

    private void setLevel(int next) {
        if (level != next) { level = next; colour.accept(next); }
    }

    private void cancelTimers() {
        display.timerExec(-1, idleTimer);
        display.timerExec(-1, frameTimer);
    }

    boolean pulsing() { return pulsing; }

    public void close() {
        if (closed) return;
        closed = true;
        eligible = pulsing = false;
        if (!canvas.isDisposed()) setLevel(0);
        if (!display.isDisposed()) {
            cancelTimers();
            display.removeListener(SWT.Settings, settings);
        }
        if (!shell.isDisposed()) {
            for (int event : new int[]{SWT.Activate, SWT.Deactivate, SWT.Iconify, SWT.Deiconify, SWT.Show, SWT.Hide}) {
                shell.removeListener(event, visibility);
            }
        }
        for (Control control : visibilityControls) {
            if (!control.isDisposed()) {
                control.removeListener(SWT.Show, visibility);
                control.removeListener(SWT.Hide, visibility);
            }
        }
        visibilityControls.clear();
    }
}
