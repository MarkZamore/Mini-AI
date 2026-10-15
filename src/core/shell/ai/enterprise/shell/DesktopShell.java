package ai.enterprise.shell;

import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Composition-only shell. Optional panels communicate through the UI host port. */
public final class DesktopShell {
    private DesktopShell() { }
    public static void run(EnterpriseAIComponent component) { run(component,0); }
    public static void run(EnterpriseAIComponent component,int smokeMilliseconds) { run(component,smokeMilliseconds,""); }
    public static void run(EnterpriseAIComponent component,int smokeMilliseconds,String inputNotice) {
        Display.setAppName(UiTheme.NAME);
        var display=new Display();
        try {
            var theme=UiTheme.get(display);
            var shell=new Shell(display);theme.decorate(shell);shell.setText(UiTheme.NAME);
            var layout=new GridLayout(1,false);layout.marginWidth=12;layout.marginHeight=12;layout.verticalSpacing=8;shell.setLayout(layout);
            var footer=new Label(shell,SWT.WRAP);theme.apply(footer);footer.setBackground(theme.background());
            footer.setData("enterpriseai.ui.selected-document",Boolean.TRUE);
            var footerData=new GridData(SWT.FILL,SWT.CENTER,true,false);footerData.exclude=true;footer.setLayoutData(footerData);footer.setVisible(false);
            var reported=new LinkedHashSet<String>();
            var host=new UiHost() {
                public UiTheme theme() { return theme; }
                public void error(String operation,Api.ServiceError error) {
                    if(error==null||"CANCELLED".equals(error.code())||display.isDisposed())return;
                    Runnable show=()->{
                        if(shell.isDisposed())return;
                        String key=error.code().startsWith("DB_")&&error.code().contains("MISSING")?"startup:database":operation;
                        if(!reported.add(key))return;
                        if(reported.size()>512)reported.remove(reported.iterator().next());
                        UiHost.showError(shell,theme,error);
                    };
                    if(Display.getCurrent()==display)show.run();else display.asyncExec(show);
                }
                public void selectedDocument(String id,String title) {
                    if(shell.isDisposed())return;
                    String value=Objects.toString(title,"");footer.setText(value);footer.setToolTipText(value.isBlank()?null:value);
                    footer.setData("enterpriseai.ui.selected-document-id",id);
                    footerData.exclude=value.isBlank();footer.setVisible(!value.isBlank());shell.layout(true,true);
                }
            };
            UiHost.install(shell,host);
            var content=compose(shell,component.context(),component.panels());
            content.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));content.moveAbove(footer);
            var area=display.getPrimaryMonitor().getClientArea();
            shell.setSize(Math.min(1440,area.width-40),Math.min(920,area.height-60));
            shell.setLocation(area.x+(area.width-shell.getSize().x)/2,area.y+(area.height-shell.getSize().y)/2);
            shell.addListener(SWT.Close,e->component.close());shell.open();
            display.asyncExec(()->{
                if(shell.isDisposed())return;
                checkDataFolders(component.context(),host);
                if(inputNotice!=null&&!inputNotice.isBlank())host.error("startup:input",new Api.ServiceError("INPUT_LIMITATION",inputNotice));
            });
            if(smokeMilliseconds>0) {
                display.timerExec(800,()->{if(!shell.isDisposed())smokeInput(shell);});
                display.timerExec(smokeMilliseconds,()->{if(!shell.isDisposed())shell.close();});
            }
            while(!shell.isDisposed())if(!display.readAndDispatch())display.sleep();
        }finally{if(!display.isDisposed())display.dispose();}
    }
    private static void checkDataFolders(Api.Context context,UiHost host) {
        if(context.find(Api.DatabaseServiceV1.class).isPresent()) {
            var db=context.environment().db();
            if(!Files.isDirectory(db))host.error("startup:database",new Api.ServiceError("DB_FOLDER_MISSING","Не найдена папка db рядом с "+UiTheme.NAME+".exe:\n"+db+"\nВерните папку с существующей базой. Приложение не создаёт её автоматически."));
            else if(!Files.isRegularFile(db.resolve("index.sqlite")))host.error("startup:database",new Api.ServiceError("DB_MISSING","Не найден индекс базы:\n"+db.resolve("index.sqlite")+"\nВерните существующий файл базы."));
        }
        if(context.find(Api.SourceViewerServiceV1.class).isPresent()&&!Files.isDirectory(context.environment().docs()))
            host.error("startup:documents",new Api.ServiceError("DOCS_FOLDER_MISSING","Не найдена папка оригиналов docs:\n"+context.environment().docs()+"\nГраф и поиск по имеющейся базе остаются доступны; открытие оригиналов требует папку docs."));
    }
    private static void smokeInput(Composite parent) {
        for(var c:parent.getChildren()) {
            if(c instanceof Text input&&Boolean.TRUE.equals(input.getData("enterpriseai.ui.query")))input.setText("Проверка отсутствующей локальной модели");
            if(c instanceof Composite nested)smokeInput(nested);
        }
        for(var c:parent.getChildren())if(Boolean.TRUE.equals(c.getData("enterpriseai.ui.send"))&&c.isEnabled())c.notifyListeners(SWT.Selection,new Event());
    }
    public static Composite compose(Composite parent,Api.Context context,List<PanelFactory> factories) {
        var theme=UiTheme.get(parent.getDisplay());
        var container=new Composite(parent,SWT.NONE);theme.apply(container);container.setLayout(new FillLayout());
        var sorted=new ArrayList<>(factories);
        sorted.sort(Comparator.comparingInt(p->p.slot().equals("assistant")||p.slot().equals("left")?0:p.slot().equals("graph")?2:1));
        if(sorted.isEmpty()) {
            container.setLayout(new GridLayout(1,false));
            var empty=new Label(container,SWT.CENTER);theme.apply(empty);empty.setFont(theme.brandFont());empty.setText(UiTheme.NAME);
            empty.setLayoutData(new GridData(SWT.CENTER,SWT.CENTER,true,true));return container;
        }
        if(sorted.size()==1)createPanel(container,context,sorted.getFirst());
        else {
            var split=new SashForm(container,SWT.HORIZONTAL|SWT.SMOOTH);theme.apply(split);split.setSashWidth(10);
            for(var factory:sorted)createPanel(split,context,factory);
            if(sorted.size()==2)split.setWeights(45,55);else split.setWeights(sorted.stream().mapToInt(p->100).toArray());
            installSashStyle(split,theme,context);
        }
        return container;
    }
    private static void installSashStyle(SashForm split,UiTheme theme,Api.Context context) {
        var closed=new AtomicBoolean();context.own(()->closed.set(true));
        boolean[] queued={false};
        Runnable update=()->{
            queued[0]=false;
            if(closed.get()||split.isDisposed())return;
            for(var child:split.getChildren())if(child instanceof Sash sash)styleSash(sash,theme);
        };
        Runnable schedule=()->{
            if(!closed.get()&&!split.isDisposed()&&!queued[0]) {
                queued[0]=true;split.getDisplay().asyncExec(update);
            }
        };
        // SashForm creates its Sash controls only after receiving a nonzero area.
        // Defer until its layout completes; also style any subsequently created Sash.
        split.addListener(SWT.Resize,event->schedule.run());schedule.run();
    }
    private static void styleSash(Sash sash,UiTheme theme) {
        if(Boolean.TRUE.equals(sash.getData("enterpriseai.ui.splitter")))return;
        sash.setBackground(theme.separator());sash.setCursor(sash.getDisplay().getSystemCursor(SWT.CURSOR_SIZEWE));
        sash.setData("enterpriseai.ui.splitter",Boolean.TRUE);
        sash.addListener(SWT.MouseEnter,e->{sash.setBackground(theme.separatorHover());sash.redraw();});
        sash.addListener(SWT.MouseExit,e->{sash.setBackground(theme.separator());sash.redraw();});
        sash.addPaintListener(e->{var size=sash.getSize();e.gc.setAdvanced(false);e.gc.setBackground(sash.getBackground());e.gc.fillRectangle(0,0,size.x,size.y);e.gc.setBackground(theme.accent());for(int y=-9;y<=9;y+=9)e.gc.fillOval(size.x/2-2,size.y/2+y-2,4,4);});
    }
    private static void createPanel(Composite parent,Api.Context context,PanelFactory factory) {
        try{factory.create(parent,context);}
        catch(Throwable error){context.diagnostic("Панель "+factory.id()+": "+error);UiHost.find(parent).error("panel:"+factory.id(),Api.failure(error).error());var fallback=new Composite(parent,SWT.NONE);UiTheme.get(parent.getDisplay()).apply(fallback);}
    }
    public static void showStartupError(String message) {
        Display display=null;
        try {
            display=new Display();var theme=UiTheme.get(display);var parent=new Shell(display);theme.decorate(parent);
            UiHost.showError(parent,theme,new Api.ServiceError("STARTUP",message));
            while(display.getShells().length>1)if(!display.readAndDispatch())display.sleep();parent.dispose();
        }catch(Throwable ignored){System.err.println(UiTheme.NAME+": "+message);}
        finally{if(display!=null&&!display.isDisposed())display.dispose();}
    }
}
