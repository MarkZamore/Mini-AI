package ai.enterprise.shell;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.*;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.*;

/** Keyboard-accessible button drawn using in-memory GDI, including the send glyph. */
public final class SoftButton extends Canvas {
    private final UiTheme theme;
    private final String label;
    private final boolean arrow;
    private boolean hover, pressed, stopMode;
    public SoftButton(Composite parent, UiTheme theme, String label, boolean circleArrow) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.theme = theme; this.label = label; this.arrow = circleArrow;
        theme.apply(this); setBackground(parent.getBackground()); setToolTipText(label);
        if (arrow) setData("enterpriseai.ui.stop", Boolean.FALSE);
        getAccessible().addAccessibleListener(new AccessibleAdapter() {
            public void getName(AccessibleEvent event) { event.result = getAccessibleName(); }
        });
        getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            public void getRole(AccessibleControlEvent event) { event.detail = ACC.ROLE_PUSHBUTTON; }
            public void getState(AccessibleControlEvent event) {
                event.detail = ACC.STATE_FOCUSABLE | (isFocusControl() ? ACC.STATE_FOCUSED : 0)
                    | (!isEnabled() ? ACC.STATE_DISABLED : 0);
            }
            public void getDefaultAction(AccessibleControlEvent event) { event.result = "Нажать"; }
        });
        getAccessible().addAccessibleActionListener(new AccessibleActionAdapter() {
            public void getActionCount(AccessibleActionEvent event) { event.count = 1; }
            public void getName(AccessibleActionEvent event) { event.result = "Нажать"; }
            public void doAction(AccessibleActionEvent event) { if (event.index == 0 && isEnabled()) { activate(); event.result = ACC.OK; } }
        });
        addListener(SWT.MouseEnter, e -> { hover = true; redraw(); });
        addListener(SWT.MouseExit, e -> { hover = false; redraw(); });
        addListener(SWT.MouseDown, e -> { if (e.button == 1 && isEnabled()) { setFocus(); pressed = true; setCapture(true); redraw(); } });
        addListener(SWT.MouseUp, e -> {
            if (e.button != 1 || !pressed) return;
            pressed = false; setCapture(false); redraw();
            if (getClientArea().contains(e.x,e.y)) activate();
        });
        addListener(SWT.KeyDown, e -> { if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR || e.character == ' ') { e.doit = false; activate(); } });
        addListener(SWT.FocusIn, e -> redraw());
        addListener(SWT.FocusOut, e -> { pressed = false; setCapture(false); redraw(); });
        addPaintListener(e -> {
            var gc = e.gc; var area = getClientArea(); gc.setAdvanced(false);
            gc.setBackground(getBackground()); gc.fillRectangle(area);
            gc.setBackground(isEnabled() ? (pressed || hover ? theme.accentHover() : theme.accent()) : theme.border());
            if (arrow) gc.fillOval(2,2,Math.max(1,area.width-4),Math.max(1,area.height-4));
            else gc.fillRoundRectangle(1,1,Math.max(1,area.width-2),Math.max(1,area.height-2),14,14);
            gc.setForeground(isEnabled() ? getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT) : theme.muted());
            if (arrow) {
                int x=area.width/2,y=area.height/2; gc.setLineWidth(2);
                if (stopMode) { gc.setBackground(gc.getForeground()); gc.fillRectangle(x-5,y-5,10,10); }
                else { gc.drawLine(x,y+7,x,y-7); gc.drawLine(x,y-7,x-6,y-1); gc.drawLine(x,y-7,x+6,y-1); }
            } else {
                Point size=gc.textExtent(label); gc.drawText(label,(area.width-size.x)/2,(area.height-size.y)/2,true);
            }
            if (isFocusControl()) { gc.setForeground(theme.text()); gc.drawFocus(3,3,Math.max(1,area.width-6),Math.max(1,area.height-6)); }
        });
    }
    public boolean isStopMode() { return stopMode; }
    public String getAccessibleName() { return stopMode ? "Остановить" : label; }
    /** The single composer action changes meaning without creating another control. */
    public void setStopMode(boolean stopping) {
        checkWidget();
        if (stopping && !arrow) SWT.error(SWT.ERROR_INVALID_ARGUMENT);
        if (stopMode == stopping) return;
        stopMode = stopping; setData("enterpriseai.ui.stop", stopping);
        setToolTipText(getAccessibleName());
        getAccessible().sendEvent(ACC.EVENT_NAME_CHANGED, null); redraw();
    }
    private void activate() { if (isEnabled() && !isDisposed()) notifyListeners(SWT.Selection,new Event()); }
    public Point computeSize(int wHint,int hHint,boolean changed) {
        int w=arrow?40:Math.max(72,label.length()*8+28);
        return new Point(wHint==SWT.DEFAULT?w:wHint,hHint==SWT.DEFAULT?40:hHint);
    }
    public void setEnabled(boolean enabled) { super.setEnabled(enabled); if (!enabled) { pressed=false;setCapture(false); } redraw(); }
}
