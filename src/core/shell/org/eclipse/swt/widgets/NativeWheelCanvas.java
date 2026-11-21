package org.eclipse.swt.widgets;

import java.util.function.DoublePredicate;
import org.eclipse.swt.internal.win32.LRESULT;
import org.eclipse.swt.internal.win32.OS;

/** Raw wheel handoff for the fixed scrollbar's painted, accessible Canvas. */
public final class NativeWheelCanvas extends Canvas {
    private DoublePredicate wheelHandler;

    public NativeWheelCanvas(Composite parent, int style) {
        super(parent, style);
    }

    public void setWheelHandler(DoublePredicate handler) {
        checkWidget();
        wheelHandler = handler;
    }

    @Override
    LRESULT WM_MOUSEWHEEL(long wParam, long lParam) {
        DoublePredicate handler = wheelHandler;
        if (handler != null && handler.test(OS.GET_WHEEL_DELTA_WPARAM(wParam))) {
            return LRESULT.ZERO;
        }
        return isDisposed() ? LRESULT.ZERO : super.WM_MOUSEWHEEL(wParam, lParam);
    }
}
