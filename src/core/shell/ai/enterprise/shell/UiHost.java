package ai.enterprise.shell;

import ai.enterprise.contracts.Api;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Optional UI-only host port; service contracts and existing embedding APIs stay unchanged. */
public interface UiHost {
    String KEY = "enterpriseai.ui.host.v1";
    UiTheme theme();
    void error(String operationId, Api.ServiceError error);
    void selectedDocument(String documentId, String title);
    static void install(Control control, UiHost host) { control.setData(KEY,host); }
    static UiHost find(Control control) {
        for (Control c=control;c!=null;c=c.getParent()) if (c.getData(KEY) instanceof UiHost host) return host;
        var theme=UiTheme.get(control.getDisplay());
        return new UiHost() {
            public UiTheme theme() { return theme; }
            public void error(String id,Api.ServiceError error) { if (!control.isDisposed()) showError(control.getShell(),theme,error); }
            public void selectedDocument(String id,String title) { }
        };
    }
    static void showError(Shell parent, UiTheme theme, Api.ServiceError error) {
        if (parent.isDisposed() || error == null || "CANCELLED".equals(error.code())) return;
        var dialog=new Shell(parent,SWT.DIALOG_TRIM|SWT.APPLICATION_MODAL);
        theme.decorate(dialog); dialog.setText(UiTheme.NAME);
        dialog.setData("enterpriseai.ui.error",error.code());
        var layout=new GridLayout(1,false);layout.marginWidth=20;layout.marginHeight=16;layout.verticalSpacing=16;dialog.setLayout(layout);
        var message=new Label(dialog,SWT.WRAP);theme.apply(message);message.setText(error.message());
        var textData=new GridData(SWT.FILL,SWT.CENTER,true,false);textData.widthHint=430;message.setLayoutData(textData);
        var ok=new SoftButton(dialog,theme,"OK",false);ok.setLayoutData(new GridData(SWT.END,SWT.CENTER,false,false));
        ok.addListener(SWT.Selection,e->dialog.close());
        dialog.addListener(SWT.Traverse,e->{if(e.detail==SWT.TRAVERSE_ESCAPE){e.doit=false;dialog.close();}});
        dialog.pack();var p=parent.getBounds();var size=dialog.getSize();dialog.setLocation(p.x+(p.width-size.x)/2,p.y+(p.height-size.y)/2);
        // Shell.open() peeks native messages before restoring focus. A quick
        // close can dispose this dialog during that peek, leaving SWT to read
        // its cleared Display. This new dialog needs only its explicit OK focus.
        dialog.setVisible(true);
        if (!dialog.isDisposed() && !ok.isDisposed()) ok.setFocus();
    }
}
