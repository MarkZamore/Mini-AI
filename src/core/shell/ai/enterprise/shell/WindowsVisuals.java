package ai.enterprise.shell;

import ai.enterprise.platform.WindowsPlatform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Shell;

/** Per-owned-HWND Windows appearance, without registry or foreign host changes. */
final class WindowsVisuals {
    private static final String INSTALLED="enterpriseai.ui.windows.visualsInstalled";
    private static final String APPLYING="enterpriseai.ui.windows.visualsApplying";
    private WindowsVisuals() {}
    static void apply(Shell shell, boolean highContrast) {
        if(shell.isDisposed()||Boolean.TRUE.equals(shell.getData(APPLYING)))return;
        if(!Boolean.TRUE.equals(shell.getData(INSTALLED))) {
            shell.setData(INSTALLED,Boolean.TRUE);
            for(int event:new int[]{SWT.Activate,SWT.Resize,SWT.ZoomChanged})
                shell.addListener(event,e->apply(shell,shell.getDisplay().getHighContrast()));
            shell.addListener(SWT.Deactivate,e->shell.redraw());
        }
        shell.setData(APPLYING,Boolean.TRUE);
        try {
            shell.setDarkThemePreferred(!highContrast);
            shell.setData("enterpriseai.ui.windows.darkRequested",!highContrast);
            shell.setData("enterpriseai.ui.windows.backdropType",highContrast?1:2);
            boolean darkResult=OS.DwmSetWindowAttribute(shell.handle,20,new int[]{highContrast?0:1},4);
            // SWT truncates HRESULT to jboolean; only our own bridge reports the
            // full result before enabling alpha painting in uncovered client gutters.
            shell.setData("enterpriseai.ui.windows.darkReturnedNonzero",darkResult);
            int zoom=DPIUtil.getZoomForAutoscaleProperty(shell.getZoom());
            int margin=highContrast?0:Math.max(1,Math.round(24*zoom/100f));
            int result=WindowsPlatform.configureBackdrop(shell.handle,margin);
            shell.setData("enterpriseai.ui.windows.backdropHRESULT",result);
            shell.setData("enterpriseai.ui.windows.backdropMarginPixels",margin);
            shell.setData("enterpriseai.ui.windows.clientBackdropConfigured",!highContrast&&result>=0);
            shell.redraw();
        } catch (UnsatisfiedLinkError ignored) {
            shell.setData("enterpriseai.ui.windows.clientBackdropConfigured",Boolean.FALSE);
        } finally {
            shell.setData(APPLYING,Boolean.FALSE);
        }
    }
}
