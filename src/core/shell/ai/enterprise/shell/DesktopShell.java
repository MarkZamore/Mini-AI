package ai.enterprise.shell;

import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.bootstrap.LegalResources;
import ai.enterprise.contracts.Api;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Composition-only shell; it has no imports of optional module implementations. */
public final class DesktopShell {
    private DesktopShell() {}
    public static void run(EnterpriseAIComponent component) {
        run(component,0);
    }
    public static void run(EnterpriseAIComponent component,int smokeMilliseconds) {
        run(component,smokeMilliseconds,"");
    }
    /** Standalone host supplies any process-local input limitation before creating Display. */
    public static void run(EnterpriseAIComponent component,int smokeMilliseconds,String inputNotice) {
        var display=new Display();
        try {
            var shell=new Shell(display);
            shell.setText("EnterpriseAI — документы и локальный помощник");
            shell.setLayout(new GridLayout(1,false));
            var menu=new Menu(shell,SWT.BAR);
            shell.setMenuBar(menu);
            var help=new MenuItem(menu,SWT.CASCADE);help.setText("Справка");
            var helpMenu=new Menu(shell,SWT.DROP_DOWN);help.setMenu(helpMenu);
            var licenses=new MenuItem(helpMenu,SWT.PUSH);licenses.setText("Лицензии и исходники");
            licenses.addListener(SWT.Selection,e->showLicenses(shell));
            var header=new Label(shell,SWT.NONE);
            header.setText("EnterpriseAI");
            header.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
            if(inputNotice!=null && !inputNotice.isBlank()) {
                var notice=new Label(shell,SWT.WRAP);
                notice.setText(inputNotice);
                notice.setToolTipText(inputNotice);
                notice.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
                notice.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
                notice.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
                notice.setData("enterpriseai.input.notice",inputNotice);
            }
            Composite content=compose(shell,component.context(),component.panels());
            content.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
            var footer=new Label(shell,SWT.WRAP);
            footer.setText("Данные открыты только для чтения • История этого сеанса хранится в памяти");
            footer.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
            var area=display.getPrimaryMonitor().getClientArea();
            shell.setSize(Math.min(1440,area.width-40),Math.min(920,area.height-60));
            shell.setLocation(area.x+(area.width-shell.getSize().x)/2,area.y+(area.height-shell.getSize().y)/2);
            shell.addListener(SWT.Close,e->component.close());
            shell.open();
            if(smokeMilliseconds>0) {
                display.timerExec(800,()-> { if(!shell.isDisposed()) smokeInput(shell); });
                display.timerExec(smokeMilliseconds,()-> { if(!shell.isDisposed()) shell.close(); });
            }
            while(!shell.isDisposed()) if(!display.readAndDispatch()) display.sleep();
        } finally { if(!display.isDisposed()) display.dispose(); }
    }
    private static void smokeInput(Composite container) {
        for(var child:container.getChildren()) {
            if(child instanceof Text input && (input.getStyle() & SWT.READ_ONLY)==0 && input.isEnabled()) input.setText("Проверка отсутствующей локальной модели");
            if(child instanceof Composite nested) smokeInput(nested);
        }
        for(var child:container.getChildren()) {
            if(child instanceof Button button && button.getText().contains("Отправить") && button.isEnabled()) button.notifyListeners(SWT.Selection,new Event());
        }
    }
    private static void showLicenses(Shell parent) {
        var window=new Shell(parent,SWT.DIALOG_TRIM|SWT.RESIZE);
        window.setText("EnterpriseAI — лицензии и исходники");
        window.setLayout(new GridLayout(1,false));
        var tabs=new TabFolder(window,SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        var notices=new TabItem(tabs,SWT.NONE);notices.setText("Лицензии");
        var text=new Text(tabs,SWT.MULTI|SWT.READ_ONLY|SWT.V_SCROLL|SWT.H_SCROLL|SWT.BORDER);
        notices.setControl(text);
        text.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        try {text.setText(LegalResources.notices()+"\n\nСостав сборки и контрольные суммы:\n"+LegalResources.inventory());}
        catch(java.io.IOException error){text.setText(error.getMessage());}
        var sources=new TabItem(tabs,SWT.NONE);sources.setText("Исходники и изменения");
        var sourceText=new Text(tabs,SWT.MULTI|SWT.READ_ONLY|SWT.V_SCROLL|SWT.H_SCROLL|SWT.BORDER);
        sources.setControl(sourceText);sourceText.setText("Выберите эту вкладку для просмотра исходников изменений.");
        tabs.addListener(SWT.Selection,event->{
            if(tabs.getSelectionIndex()==1 && sourceText.getData("loaded")==null){
                try {sourceText.setText(LegalResources.changes());sourceText.setData("loaded",Boolean.TRUE);}
                catch(java.io.IOException error){sourceText.setText(error.getMessage());}
            }
        });
        var sourceNotice=new Label(window,SWT.WRAP);
        sourceNotice.setText("Полный архив соответствующих исходников встроен в EXE. Параметр --third-party-source-stdout передаёт ZIP в стандартный вывод без распаковки. Сохранение вывода выполняет вызывающая программа.");
        sourceNotice.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
        window.setSize(850,640);window.open();
    }
    public static Composite compose(Composite parent,Api.Context context,List<PanelFactory> factories) {
        var container=new Composite(parent,SWT.NONE);
        container.setLayout(new FillLayout());
        var sorted=new ArrayList<>(factories);
        sorted.sort(Comparator.comparingInt(p->p.slot().equals("assistant")?0:p.slot().equals("graph")?2:1));
        if(sorted.isEmpty()) {
            var empty=new Label(container,SWT.CENTER|SWT.WRAP);
            empty.setText("EnterpriseAI\nФункциональные модули не включены в эту сборку.");
            return container;
        }
        if(sorted.size()==1) createPanel(container,context,sorted.getFirst());
        else {
            var split=new SashForm(container,SWT.HORIZONTAL|SWT.SMOOTH);
            split.setSashWidth(7);
            for(var factory:sorted) createPanel(split,context,factory);
            if(sorted.size()==2) split.setWeights(45,55);
            else split.setWeights(sorted.stream().mapToInt(p->100).toArray());
        }
        return container;
    }
    private static void createPanel(Composite parent,Api.Context context,PanelFactory factory) {
        try { factory.create(parent,context); }
        catch(Throwable error) { context.diagnostic("Панель "+factory.id()+": "+error); var fallback=new Label(parent,SWT.WRAP); fallback.setText(factory.title()+"\n"+Api.failure(error).error().message()); }
    }
    public static void showStartupError(String message) {
        Display display=null;
        try { display=new Display(); var shell=new Shell(display); var box=new MessageBox(shell,SWT.ICON_ERROR|SWT.OK); box.setText("EnterpriseAI"); box.setMessage(message); box.open(); shell.dispose(); }
        catch(Throwable ignored) { System.err.println("EnterpriseAI: "+message); }
        finally { if(display!=null && !display.isDisposed()) display.dispose(); }
    }
}
