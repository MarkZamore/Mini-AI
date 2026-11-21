package org.eclipse.swt.widgets;

import java.util.function.DoublePredicate;
import org.eclipse.swt.internal.win32.LRESULT;
import org.eclipse.swt.internal.win32.OS;

/**
 * Native EDIT control with a raw wheel handoff for the clipped smooth viewport.
 *
 * <p>This package companion deliberately targets the pinned Windows SWT version:
 * the ordinary MouseWheel event has already rounded high-resolution wheel input
 * to whole system-configured scroll lines. No window procedure or native
 * callback is replaced; SWT continues to own keyboard input and accessibility.
 */
public final class NativeScrollText extends Text {
    private DoublePredicate wheelHandler;
    private Runnable nativeScrollListener;

    public NativeScrollText(Composite parent, int style) {
        super(parent, style);
    }

    @Override
    protected void checkSubclass() {
        // This narrowly scoped Windows companion retains Text's native EDIT.
    }

    public void setWheelHandler(DoublePredicate handler) {
        checkWidget();
        wheelHandler = handler;
    }

    /** The listener should queue reconciliation after the native message ends. */
    public void setNativeScrollListener(Runnable listener) {
        checkWidget();
        nativeScrollListener = listener;
    }

    @Override
    LRESULT WM_MOUSEWHEEL(long wParam, long lParam) {
        DoublePredicate handler = wheelHandler;
        if (handler != null && handler.test(OS.GET_WHEEL_DELTA_WPARAM(wParam))) {
            return LRESULT.ZERO;
        }
        return isDisposed() ? LRESULT.ZERO : super.WM_MOUSEWHEEL(wParam, lParam);
    }

    @Override
    LRESULT WM_VSCROLL(long wParam, long lParam) {
        LRESULT result = super.WM_VSCROLL(wParam, lParam);
        nativeScrolled();
        return result;
    }

    @Override
    LRESULT WM_HSCROLL(long wParam, long lParam) {
        LRESULT result = super.WM_HSCROLL(wParam, lParam);
        nativeScrolled();
        return result;
    }

    @Override
    public void setTopIndex(int index) {
        super.setTopIndex(index);
        nativeScrolled();
    }

    private void nativeScrolled() {
        Runnable listener = nativeScrollListener;
        if (!isDisposed() && listener != null) listener.run();
    }
}
