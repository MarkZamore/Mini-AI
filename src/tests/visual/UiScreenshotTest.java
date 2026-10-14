import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.platform.WindowsPlatform;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;

/** Developer-only visual fixture. The production application never exports screenshots. */
public final class UiScreenshotTest {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        Path output=Path.of(args[1]).toAbsolutePath();
        System.setProperty("swt.library.path",root.resolve("native").toString());
        var platform=new WindowsPlatform(root);
        var executor=Executors.newFixedThreadPool(4);
        try(var component=EnterpriseAIComponent.create(new Api.Environment(root,executor,platform),GeneratedCatalog.modules(),GeneratedCatalog.panels())) {
            var display=new Display();
            try {
                var shell=new Shell(display);
                shell.setText("EnterpriseAI — visual test"); shell.setLayout(new FillLayout());
                component.attach(shell);
                var area=display.getPrimaryMonitor().getClientArea();
                shell.setSize(Math.min(1440,area.width-40),Math.min(900,area.height-60));
                shell.setLocation(area.x+20,area.y+30);
                shell.open();
                display.timerExec(1200,()-> { if(!shell.isDisposed())send(shell); });
                display.timerExec(5000,()-> {
                    if(shell.isDisposed())return;
                    var bounds=shell.getClientArea(); var image=new Image(display,bounds.width,bounds.height);var gc=new GC(shell);
                    try { gc.copyArea(image,0,0);var loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData()};loader.save(output.toString(),SWT.IMAGE_PNG); }
                    finally { gc.dispose();image.dispose();shell.close(); }
                });
                while(!shell.isDisposed())if(!display.readAndDispatch())display.sleep();
            } finally { display.dispose(); }
        }
        System.out.println("UiScreenshotTest: PASS "+output);
    }
    private static void send(Composite parent) {
        for(var control:parent.getChildren()) { if(control instanceof Text text&&(text.getStyle()&SWT.READ_ONLY)==0)text.setText("Академическая политика");if(control instanceof Composite composite)send(composite); }
        for(var control:parent.getChildren())if(control instanceof Button button&&button.getText().contains("Отправить"))button.notifyListeners(SWT.Selection,new Event());
    }
}
