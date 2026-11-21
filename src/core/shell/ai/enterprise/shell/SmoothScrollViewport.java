package ai.enterprise.shell;

import java.util.function.Function;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.*;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.internal.win32.TEXTMETRIC;
import org.eclipse.swt.widgets.*;

/**
 * Clipped, pixel-scrolling viewport preserving native text and source controls.
 * Text controls stay bounded as the document grows. Win32 EDIT scrolls by line,
 * while StyledText scrolls directly by pixel and supports variable line heights.
 */
public final class SmoothScrollViewport extends Composite {
    public static final int SCROLLBAR_GUTTER = 20;
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;
    private final UiTheme theme;
    private final NativeWheelComposite clip;
    private final NativeScrollText text;
    private final NativeWheelStyledText styledText;
    private final NativeWheelComposite content;
    private final Canvas scrollbar;
    private final SmoothScrollController controller;
    private final Display display;
    private final Runnable frame = this::frame;
    private final Runnable selectionFrame = this::selectionFrame;
    private final Listener manualFilter = this::manualInput;
    private final Listener settingsListener = event -> refresh();
    private final Listener shellVisibility = event -> cancelInteraction();
    private boolean updating, nativeSyncQueued, frameQueued, automaticFrames = true;
    private boolean hover, draggingThumb, selecting, selectionQueued, closed;
    private boolean queuedCaretReveal;
    private int thumbDragY, selectionAnchor;
    private long lastFrame;
    private double offset, extent, linePitch = 20, pixelScale = 1, nativeTop, textChildY;
    private int physicalLinePitch = 20, clipWidth, clipHeight;

    public static SmoothScrollViewport forText(Composite parent, UiTheme theme, int style) {
        return new SmoothScrollViewport(parent, theme, style, true, null);
    }
    public static SmoothScrollViewport forStyledText(Composite parent, UiTheme theme,
            Function<Composite, ? extends NativeWheelStyledText> createText) {
        return new SmoothScrollViewport(parent, theme, SWT.NONE, false, createText);
    }
    public static SmoothScrollViewport forContent(Composite parent, UiTheme theme) {
        return new SmoothScrollViewport(parent, theme, SWT.NONE, false, null);
    }
    private SmoothScrollViewport(Composite parent, UiTheme theme, int textStyle, boolean textMode,
            Function<Composite, ? extends NativeWheelStyledText> createText) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.theme = theme;
        display = getDisplay();
        theme.apply(this);
        setData("enterpriseai.ui.scroll-viewport", Boolean.TRUE);
        clip = new NativeWheelComposite(this, SWT.DOUBLE_BUFFERED);
        theme.apply(clip);
        if (textMode) {
            text = new NativeScrollText(clip, (textStyle | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL) & ~SWT.BORDER);
            styledText = null;
            content = null;
            theme.apply(text);
            hideNativeScrollbar(text);
        } else if (createText != null) {
            text = null;
            styledText = createText.apply(clip);
            content = null;
            theme.apply(styledText);
            hideNativeScrollbar(styledText);
        } else {
            text = null;
            styledText = null;
            content = new NativeWheelComposite(clip, SWT.DOUBLE_BUFFERED);
            theme.apply(content);
        }
        NativeWheelCanvas scrollCanvas = new NativeWheelCanvas(this, SWT.DOUBLE_BUFFERED);
        scrollbar = scrollCanvas;
        theme.apply(scrollbar);
        scrollbar.setData("enterpriseai.ui.scrollbar", Boolean.TRUE);
        scrollbar.setToolTipText("Прокрутка");
        controller = new SmoothScrollController(new SmoothScrollController.Host() {
            public double maximumOffset() { return SmoothScrollViewport.this.maximumOffset(); }
            public double offset() { return offset; }
            public boolean animationsEnabled() { return readAnimationPreference(); }
            public void render(double value, double spring) { renderOffset(value, spring); }
            public void requestFrame() { scheduleFrame(); }
            public void cancelFrame() { cancelScheduledFrame(); }
        });
        scrollCanvas.setWheelHandler(controller::scrollByWheelDelta);
        clip.setWheelHandler(controller::scrollByWheelDelta);
        if (text != null) {
            text.setWheelHandler(controller::scrollByWheelDelta);
            text.setNativeScrollListener(() -> queueNativeSync(false));
            text.addListener(SWT.Modify, event -> queueNativeSync(true));
            text.addListener(SWT.KeyDown, this::textKeyDown);
            text.addListener(SWT.KeyUp, event -> queueNativeSync(true));
            text.addListener(SWT.FocusIn, event -> queueNativeSync(true));
            text.addListener(SWT.FocusOut, event -> { stopSelection(); controller.cancel(); });
            text.addListener(SWT.MouseDown, this::textMouseDown);
            text.addListener(SWT.MouseMove, event -> { if (selecting) queueNativeSync(false); });
            text.addListener(SWT.MouseUp, event -> { stopSelection(); queueNativeSync(false); });
            if (text.getVerticalBar() != null) text.getVerticalBar().addListener(SWT.Selection, event -> queueNativeSync(false));
            clip.setTabList(new Control[] {text});
        } else if (styledText != null) {
            styledText.setWheelHandler(controller::scrollByWheelDelta);
            styledText.addListener(SWT.Modify, event -> queueNativeSync(false));
            styledText.addListener(SWT.Paint, event -> {
                // Selection dragging and keyboard navigation scroll StyledText internally.
                if (styledText.getTopPixel() != nativeTop) queueNativeSync(false);
            });
            styledText.addListener(SWT.FocusOut, event -> controller.cancel());
            clip.setTabList(new Control[] {styledText});
        } else {
            content.setWheelHandler(controller::scrollByWheelDelta);
            clip.setTabList(new Control[] {content});
        }
        setTabList(new Control[] {clip});
        // Only the subclass owners need the synthetic SWT fallback. A global
        // wheel filter would quantize native deltas before child forwarding.
        clip.addListener(SWT.MouseWheel, this::syntheticWheel);
        if (text != null) text.addListener(SWT.MouseWheel, this::syntheticWheel);
        else if (styledText != null) styledText.addListener(SWT.MouseWheel, this::syntheticWheel);
        else content.addListener(SWT.MouseWheel, this::syntheticWheel);
        scrollbar.addListener(SWT.MouseWheel, this::syntheticWheel);
        installScrollbar();
        setLayout(new Layout() {
            protected Point computeSize(Composite parent, int wHint, int hHint, boolean flush) {
                return new Point(wHint == SWT.DEFAULT ? 240 : wHint, hHint == SWT.DEFAULT ? (content == null ? 88 : 130) : hHint);
            }
            protected void layout(Composite parent, boolean flush) { refresh(); }
        });
        addListener(SWT.Resize, event -> refresh());
        addListener(SWT.ZoomChanged, event -> refresh());
        addListener(SWT.Hide, event -> cancelInteraction());
        addListener(SWT.Dispose, event -> closeResources());
        display.addFilter(SWT.MouseDown, manualFilter);
        display.addFilter(SWT.KeyDown, manualFilter);
        display.addFilter(SWT.Touch, manualFilter);
        display.addFilter(SWT.FocusIn, manualFilter);
        display.addListener(SWT.Settings, settingsListener);
        getShell().addListener(SWT.Deactivate, shellVisibility);
        getShell().addListener(SWT.Iconify, shellVisibility);
        getShell().addListener(SWT.Hide, shellVisibility);
    }

    public Text text() {
        if (text == null) throw new IllegalStateException("This viewport has no Text");
        return text;
    }
    public StyledText styledText() {
        if (styledText == null) throw new IllegalStateException("This viewport has no StyledText");
        return styledText;
    }
    public Composite content() {
        if (content == null) throw new IllegalStateException("This viewport has no control list");
        return content;
    }
    public Canvas scrollbar() { return scrollbar; }
    public SmoothScrollController controller() { return controller; }
    public double maximumOffset() { return Math.max(0, extent - clipHeight); }
    public double contentHeight() { return extent; }
    public int viewportHeight() { return clipHeight; }

    @Override public void setBackground(Color color) {
        super.setBackground(color);
        if (clip != null && !clip.isDisposed()) clip.setBackground(color);
        if (scrollbar != null && !scrollbar.isDisposed()) scrollbar.setBackground(color);
        if (content != null && !content.isDisposed()) content.setBackground(color);
    }

    /** Reflow at the final width, clamp the old position, and discard stale motion. */
    public void refresh() {
        if (closed || isDisposed() || updating || controller == null) return;
        updating = true;
        try {
            Rectangle area = getClientArea();
            clipWidth = Math.max(0, area.width - SCROLLBAR_GUTTER);
            clipHeight = Math.max(0, area.height);
            clip.setBounds(0, 0, clipWidth, clipHeight);
            scrollbar.setBounds(Math.max(0, area.width - 16), 0, 16, clipHeight);
            pixelScale = DPIUtil.getZoomForAutoscaleProperty(getShell().getZoom()) / 100.0;
            if (text != null) {
                measureLinePitch();
                int overscan = (int)Math.ceil(2 * linePitch + 2 * SmoothScrollController.SPRING_LIMIT);
                text.setSize(clipWidth, Math.max(1, clipHeight + overscan));
                hideNativeScrollbar(text);
                // EM_GETLINECOUNT includes native word-wrapped display lines.
                // Multiply physical pitch first, then convert the aggregate once.
                extent = Math.max(0, text.getLineCount() * (double)physicalLinePitch / pixelScale);
            } else if (styledText != null) {
                styledText.setSize(clipWidth, Math.max(1, clipHeight));
                hideNativeScrollbar(styledText);
                // StyledText reports the bottom of the final wrapped line relative to its current scroll.
                extent = clipWidth > 0 && clipHeight > 0 ? styledText.getLinePixel(styledText.getLineCount())
                    + styledText.getTopPixel() + styledText.getBottomMargin() : 0;
            } else {
                Point preferred = content.computeSize(clipWidth, SWT.DEFAULT, true);
                int height = Math.max(clipHeight, preferred.y);
                content.setSize(clipWidth, height);
                content.layout(true, true);
                extent = height;
            }
            offset = Math.max(0, Math.min(maximumOffset(), offset));
        } finally { updating = false; }
        controller.jumpTo(offset);
        scrollbar.setVisible(maximumOffset() > 0);
        scrollbar.redraw();
    }

    /** Production history follows each new streamed draft, as before the restyle. */
    public void revealEnd() {
        if (closed || isDisposed()) return;
        // Invalidate queued Modify/selection reconciliation of an older draft.
        queuedCaretReveal = false;
        refresh();
        controller.jumpTo(maximumOffset());
    }

    private void measureLinePitch() {
        long dc = OS.GetDC(text.handle), oldFont = 0;
        long font = OS.SendMessage(text.handle, OS.WM_GETFONT, 0, 0);
        try {
            if (font != 0) oldFont = OS.SelectObject(dc, font);
            TEXTMETRIC metric = new TEXTMETRIC();
            if (OS.GetTextMetrics(dc, metric) && metric.tmHeight > 0) physicalLinePitch = metric.tmHeight;
            else physicalLinePitch = Math.max(1, (int)Math.round(text.getLineHeight() * pixelScale));
        } finally {
            if (font != 0) OS.SelectObject(dc, oldFont);
            OS.ReleaseDC(text.handle, dc);
        }
        linePitch = physicalLinePitch / pixelScale;
    }

    private static void hideNativeScrollbar(Scrollable control) {
        ScrollBar bar = control.getVerticalBar();
        if (bar != null) bar.setVisible(false);
    }
    private double readNativeTop() {
        if (styledText != null) return styledText.getTopPixel();
        // Read SWT's actual clamped scroll position, including native line clamp.
        int logical = text.getTopPixel();
        double exact = text.getTopIndex() * (double)physicalLinePitch / pixelScale;
        return Math.abs(exact - logical) <= 1.1 ? exact : logical;
    }
    private void renderOffset(double value, double spring) {
        if (closed || isDisposed() || updating) return;
        updating = true;
        try {
            offset = Math.max(0, Math.min(maximumOffset(), value));
            if (text != null) {
                text.setTopIndex((int)Math.floor(offset / Math.max(.1, linePitch)));
                nativeTop = readNativeTop();
                textChildY = nativeTop - offset + spring;
                moveChild(text, textChildY);
            } else if (styledText != null) {
                styledText.setTopPixel((int)Math.round(offset));
                nativeTop = readNativeTop();
                moveChild(styledText, nativeTop - offset + spring);
            } else moveChild(content, -offset + spring);
            setData("enterpriseai.ui.scroll.offset", offset);
            setData("enterpriseai.ui.scroll.target", controller.targetOffset());
            setData("enterpriseai.ui.scroll.spring", spring);
            scrollbar.redraw();
        } finally { updating = false; }
    }
    private void moveChild(Control child, double y) {
        // A physical-pixel location preserves smooth half-DIP movement at 200%.
        OS.SetWindowPos(child.handle, 0, 0, (int)Math.round(y * pixelScale), 0, 0,
                OS.SWP_NOSIZE | OS.SWP_NOZORDER | OS.SWP_NOACTIVATE);
    }

    private boolean readAnimationPreference() {
        if (display.getHighContrast()) return false;
        int[] enabled = {0};
        return OS.SystemParametersInfo(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) && enabled[0] != 0;
    }
    private void scheduleFrame() {
        if (closed || frameQueued || !automaticFrames) return;
        frameQueued = true;
        if (lastFrame == 0) lastFrame = System.nanoTime();
        display.timerExec(16, frame);
    }
    private void cancelScheduledFrame() {
        if (closed || display.isDisposed()) return;
        display.timerExec(-1, frame);
        frameQueued = false;
        lastFrame = 0;
    }
    private void frame() {
        frameQueued = false;
        if (closed || isDisposed()) return;
        long now = System.nanoTime();
        double elapsed = lastFrame == 0 ? 16 : (now - lastFrame) / 1_000_000.0;
        lastFrame = now;
        controller.advanceFrame(elapsed);
    }
    // Package-private deterministic developer fixture switch; no system changes.
    void setAutomaticFramesForTesting(boolean enabled) {
        automaticFrames = enabled;
        if (!enabled) cancelScheduledFrame();
        else if (controller.isAnimating()) scheduleFrame();
    }

    private void syntheticWheel(Event event) {
        if (!event.doit || event.count == 0) return;
        int[] lines = {3};
        OS.SystemParametersInfo(OS.SPI_GETWHEELSCROLLLINES, 0, lines, 0);
        // SWT already multiplies wheel input by the configured line count.
        double delta = event.detail == SWT.SCROLL_PAGE ? Math.signum(event.count) * 120
                : event.count * 120.0 / Math.max(1, lines[0]);
        if (controller.scrollByWheelDelta(delta)) event.doit = false;
    }

    private void manualInput(Event event) {
        if (closed || !(event.widget instanceof Control control) || !inside(control)) return;
        if (event.type == SWT.FocusIn) {
            if (content != null && control != scrollbar) {
                display.asyncExec(() -> { if (!closed && !control.isDisposed()) revealControl(control); });
            }
            return;
        }
        if (control != scrollbar) controller.cancel();
        if (text != null && event.type == SWT.KeyDown) queueNativeSync(true);
    }
    private boolean inside(Control control) {
        for (Control value = control; value != null; value = value.getParent()) if (value == this) return true;
        return false;
    }
    private void revealControl(Control control) {
        Point location = clip.toControl(control.toDisplay(0, 0));
        int bottom = location.y + control.getSize().y;
        if (location.y < 0) controller.jumpTo(offset + location.y);
        else if (bottom > clipHeight) controller.jumpTo(offset + bottom - clipHeight);
    }

    private void queueNativeSync(boolean revealCaret) {
        if (closed || updating || content != null) return;
        queuedCaretReveal |= revealCaret;
        if (nativeSyncQueued) return;
        nativeSyncQueued = true;
        display.asyncExec(() -> {
            nativeSyncQueued = false;
            if (closed || isDisposed() || (text != null && text.isDisposed())
                    || (styledText != null && styledText.isDisposed())) return;
            boolean showCaret = text != null && queuedCaretReveal;
            queuedCaretReveal = false;
            double actualTop = readNativeTop();
            double changed = actualTop - nativeTop;
            double next = offset + changed;
            double caret = showCaret ? actualTop + text.getCaretLocation().y : 0;
            refresh();
            if (showCaret) {
                if (caret < next) next = caret;
                else if (caret + linePitch > next + clipHeight) next = caret + linePitch - clipHeight;
            }
            controller.jumpTo(next);
        });
    }

    private void textKeyDown(Event event) {
        controller.cancel();
        if ((event.keyCode == SWT.PAGE_UP || event.keyCode == SWT.PAGE_DOWN)
                && (event.stateMask & (SWT.CTRL | SWT.ALT)) == 0 && clipHeight > 0) {
            // Native EDIT would page by its overscanned child height. Page by
            // the visible clip instead, keeping selection and caret columns.
            Point selected = text.getSelection();
            int oldCaret = text.getCaretPosition();
            int anchor = oldCaret == selected.x ? selected.y : selected.x;
            Point caret = text.getCaretLocation();
            double distance = Math.max(linePitch, clipHeight - linePitch)
                    * (event.keyCode == SWT.PAGE_UP ? -1 : 1);
            double documentY = nativeTop + caret.y + distance;
            controller.jumpTo(offset + distance);
            int next = characterAt(caret.x, Math.max(0, Math.min(Math.max(0, extent - linePitch), documentY)));
            updating = true;
            try { text.setSelection((event.stateMask & SWT.SHIFT) == 0 ? next : anchor, next); }
            finally { updating = false; }
            renderOffset(offset, 0);
            event.doit = false;
        }
        queueNativeSync(true);
    }

    private int characterAt(int x, double documentY) {
        int localY = (int)Math.round((documentY - nativeTop) * pixelScale);
        int localX = (int)Math.round(Math.max(0, Math.min(clipWidth - 1, x)) * pixelScale);
        long result = OS.SendMessage(text.handle, OS.EM_CHARFROMPOS, 0, OS.MAKELPARAM(localX, localY));
        if (result == -1) return text.getCaretPosition();
        int line = Math.max(0, Math.min(text.getLineCount() - 1,
                (int)Math.floor(documentY / Math.max(.1, linePitch))));
        int start = (int)OS.SendMessage(text.handle, OS.EM_LINEINDEX, line, 0);
        int nextStart = (int)OS.SendMessage(text.handle, OS.EM_LINEINDEX, line + 1, 0);
        // EM_CHARFROMPOS exposes only the low 16 bits of the character offset.
        // Reconstruct them against the full wrapped-line start for long input.
        int low = (int)(result & 0xffff);
        int at = start + ((low - (start & 0xffff) + 0x10000) & 0xffff);
        int end = nextStart < 0 ? text.getCharCount() : nextStart;
        return Math.max(start, Math.min(end, at));
    }

    private void textMouseDown(Event event) {
        if (event.button != 1) return;
        controller.cancel();
        Point previous = text.getSelection();
        int oldCaret = text.getCaretPosition();
        boolean extend = (event.stateMask & SWT.SHIFT) != 0;
        selectionAnchor = extend ? (oldCaret == previous.x ? previous.y : previous.x) : oldCaret;
        selecting = true;
        display.asyncExec(() -> {
            if (closed || !selecting || text.isDisposed()) return;
            if (!extend) selectionAnchor = text.getCaretPosition();
            queueNativeSync(false);
        });
        scheduleSelection();
    }
    private void scheduleSelection() {
        if (!selectionQueued && selecting && !closed) {
            selectionQueued = true;
            display.timerExec(30, selectionFrame);
        }
    }
    private void selectionFrame() {
        selectionQueued = false;
        if (closed || !selecting || text == null || text.isDisposed()) return;
        Point point = clip.toControl(display.getCursorLocation());
        if (point.y < 0 || point.y >= clipHeight) {
            double outside = point.y < 0 ? point.y : point.y - clipHeight + 1;
            double step = Math.signum(outside) * Math.max(linePitch, Math.min(4 * linePitch, Math.abs(outside) * .35));
            controller.jumpTo(offset + step);
            int y = point.y < 0 ? 0 : Math.max(0, clipHeight - 1);
            int at = characterAt(point.x, offset + y);
            updating = true;
            try { text.setSelection(selectionAnchor, at); }
            finally { updating = false; }
            renderOffset(offset, 0);
        }
        scheduleSelection();
    }
    private void stopSelection() {
        selecting = false;
        if (!display.isDisposed()) display.timerExec(-1, selectionFrame);
        selectionQueued = false;
    }

    private void installScrollbar() {
        scrollbar.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override public void getName(AccessibleEvent event) { event.result = "Прокрутка"; }
        });
        scrollbar.getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_SCROLLBAR; }
            @Override public void getState(AccessibleControlEvent event) {
                event.detail = ACC.STATE_FOCUSABLE | (scrollbar.isFocusControl() ? ACC.STATE_FOCUSED : 0);
            }
            @Override public void getValue(AccessibleControlEvent event) {
                event.result = maximumOffset() <= 0 ? "0%" : Math.round(offset * 100 / maximumOffset()) + "%";
            }
        });
        scrollbar.addPaintListener(event -> {
            Rectangle area = scrollbar.getClientArea();
            event.gc.setAdvanced(false);
            event.gc.setBackground(scrollbar.getBackground());
            event.gc.fillRectangle(area);
            if (maximumOffset() <= 0 || clipHeight <= 0) return;
            Rectangle thumb = thumbBounds();
            event.gc.setBackground(hover || draggingThumb ? theme.scrollThumbHover() : theme.scrollThumb());
            int width = hover || draggingThumb ? 6 : 2;
            event.gc.fillRoundRectangle((area.width - width) / 2, thumb.y, width, thumb.height, Math.min(width, 6), 6);
        });
        scrollbar.addListener(SWT.MouseEnter, event -> { hover = true; scrollbar.redraw(); });
        scrollbar.addListener(SWT.MouseExit, event -> { hover = false; scrollbar.redraw(); });
        scrollbar.addListener(SWT.MouseDown, event -> {
            if (event.button != 1 || maximumOffset() <= 0) return;
            controller.cancel();
            scrollbar.setFocus();
            Rectangle thumb = thumbBounds();
            if (event.y >= thumb.y && event.y < thumb.y + thumb.height) {
                draggingThumb = true;
                thumbDragY = event.y - thumb.y;
                scrollbar.setCapture(true);
            } else controller.jumpTo(offset + (event.y < thumb.y ? -1 : 1) * clipHeight);
            scrollbar.redraw();
        });
        scrollbar.addListener(SWT.MouseMove, event -> {
            if (!draggingThumb) return;
            Rectangle thumb = thumbBounds();
            double travel = clipHeight - thumb.height;
            controller.jumpTo(travel <= 0 ? 0 : (event.y - thumbDragY) * maximumOffset() / travel);
        });
        scrollbar.addListener(SWT.MouseUp, event -> {
            if (event.button != 1) return;
            draggingThumb = false;
            scrollbar.setCapture(false);
            scrollbar.redraw();
        });
        scrollbar.addListener(SWT.FocusOut, event -> {
            draggingThumb = false;
            scrollbar.setCapture(false);
            scrollbar.redraw();
        });
        scrollbar.addListener(SWT.KeyDown, event -> {
            double next = offset;
            switch (event.keyCode) {
                case SWT.ARROW_UP -> next -= WHEEL_KEY_STEP;
                case SWT.ARROW_DOWN -> next += WHEEL_KEY_STEP;
                case SWT.PAGE_UP -> next -= clipHeight;
                case SWT.PAGE_DOWN -> next += clipHeight;
                case SWT.HOME -> next = 0;
                case SWT.END -> next = maximumOffset();
                default -> { return; }
            }
            event.doit = false;
            controller.jumpTo(next);
        });
    }
    private static final int WHEEL_KEY_STEP = 36;
    private Rectangle thumbBounds() {
        if (extent <= 0 || clipHeight <= 0) return new Rectangle(0, 0, 16, 0);
        // No artificial minimum thumb height: proportion belongs to the track.
        int height = Math.max(1, Math.min(clipHeight, (int)Math.round(clipHeight * (double)clipHeight / extent)));
        int y = maximumOffset() <= 0 ? 0 : (int)Math.round(offset / maximumOffset() * (clipHeight - height));
        return new Rectangle(0, y, 16, height);
    }
    private void cancelInteraction() {
        if (closed) return;
        controller.cancel();
        stopSelection();
        draggingThumb = false;
        if (!scrollbar.isDisposed()) { scrollbar.setCapture(false); scrollbar.redraw(); }
    }
    private void closeResources() {
        if (closed) return;
        controller.close();
        stopSelection();
        closed = true;
        display.removeFilter(SWT.MouseDown, manualFilter);
        display.removeFilter(SWT.KeyDown, manualFilter);
        display.removeFilter(SWT.Touch, manualFilter);
        display.removeFilter(SWT.FocusIn, manualFilter);
        display.removeListener(SWT.Settings, settingsListener);
        Shell shell = getShell();
        if (!shell.isDisposed()) {
            shell.removeListener(SWT.Deactivate, shellVisibility);
            shell.removeListener(SWT.Iconify, shellVisibility);
            shell.removeListener(SWT.Hide, shellVisibility);
        }
    }
}
