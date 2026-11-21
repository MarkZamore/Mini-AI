package org.eclipse.swt.widgets;

import java.util.function.DoublePredicate;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.internal.win32.LRESULT;
import org.eclipse.swt.internal.win32.OS;

/** Raw Windows wheel input for a StyledText hosted by the smooth viewport. */
public class NativeWheelStyledText extends StyledText {
    private DoublePredicate wheelHandler;

    public NativeWheelStyledText(Composite parent, int style) {
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
        // StyledText is a Canvas. Continue its normal SWT wheel handling when the viewport declines.
        return isDisposed() ? LRESULT.ZERO : ((Scrollable)this).wmScrollWheel(true, wParam, lParam, false);
    }
}
