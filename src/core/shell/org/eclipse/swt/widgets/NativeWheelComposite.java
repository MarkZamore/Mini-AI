package org.eclipse.swt.widgets;

import java.util.function.DoublePredicate;
import org.eclipse.swt.internal.win32.LRESULT;
import org.eclipse.swt.internal.win32.OS;

/**
 * Clipping/content host receiving unrounded Windows wheel deltas, including
 * WM_MOUSEWHEEL propagated by native Label and Link children through DefWindowProc.
 * Uses the existing SWT dispatch path and therefore needs no hook or JNI callback.
 */
public final class NativeWheelComposite extends Composite {
    private DoublePredicate wheelHandler;

    public NativeWheelComposite(Composite parent, int style) {
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
