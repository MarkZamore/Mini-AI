package ai.enterprise.tests;

import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.assistant.AssistantPanel;
import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.graph.GraphPanelFactory;
import ai.enterprise.shell.UiHost;
import ai.enterprise.shell.UiTheme;
import ai.enterprise.shell.SoftButton;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;

/** Developer SWT scale fixture. Does not change Windows scaling or settings. */
public final class UiDpiTest {
    private static final List<Map<String,Object>> checks = new ArrayList<>();
    private static void check(boolean passed,String name) {
        checks.add(Map.of("name",name,"passed",passed));if(!passed)throw new AssertionError(name);
    }
    private static List<Control> descendants(Composite parent) {
        var result=new ArrayList<Control>();
        for(var child:parent.getChildren()){result.add(child);if(child instanceof Composite nested)result.addAll(descendants(nested));}
        return result;
    }
    private static Control marked(Composite parent,String key,Object value) {
        return descendants(parent).stream().filter(c->Objects.equals(c.getData(key),value)).findFirst().orElseThrow(()->new AssertionError(key));
    }
    private static boolean inside(Control control,Composite parent) {
        var p=control.toDisplay(0,0);var origin=parent.toDisplay(0,0);var size=control.getSize();var area=parent.getClientArea();
        return p.x>=origin.x && p.y>=origin.y && p.x+size.x<=origin.x+area.width+1 && p.y+size.y<=origin.y+area.height+1;
    }
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]).toAbsolutePath();int scale=Integer.parseInt(args[1]);
        String reportPrefix=args.length>2?args[2]:"dusty-yellow-dpi";
        if(!reportPrefix.matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("Invalid report prefix");
        Path output=source.resolve("build/test-results/"+reportPrefix+"-"+scale+".json");
        System.setProperty("swt.library.path",source.resolve("build/native").toString());
        var executor=Executors.newFixedThreadPool(2);var environment=new Api.Environment(source.resolve("build/pink-dpi-fixture"),executor,new Api.Platform(){
            public Path executablePath(){return source.resolve("build/pink-dpi-fixture/миниИИ.exe");}
            public long availableMemoryBytes(){return 32L<<30;}
            public void openWith(Path path){throw new AssertionError("No external viewer in scale fixture");}
            public Process launchWorker(Path path,Path dir,long budget){throw new AssertionError("No worker in scale fixture");}
        });
        var report=new LinkedHashMap<String,Object>();var errors=new ArrayList<String>();
        Display display=null;
        try(var component=EnterpriseAIComponent.create(environment,List.of(new AssistantModule()),List.of(new AssistantPanel(),new GraphPanelFactory()))) {
            display=new Display();UiTheme theme=UiTheme.get(display);Shell shell=new Shell(display);theme.decorate(shell);shell.setText("миниИИ — DPI "+scale);shell.setLayout(new FillLayout());
            UiHost.install(shell,new UiHost(){public UiTheme theme(){return theme;}public void error(String id,Api.ServiceError error){errors.add(error.code());}public void selectedDocument(String id,String title){}});
            component.attach(shell);shell.setSize(1280,800);shell.open();
            long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(450);
            while(System.nanoTime()<until){if(!display.readAndDispatch())Thread.sleep(5);}
            shell.layout(true,true);
            check(Integer.toString(scale).equals(System.getProperty("swt.autoScale")),"explicit developer SWT scale configuration");
            int effectiveScale=DPIUtil.getZoomForAutoscaleProperty(shell.getZoom());
            check(effectiveScale==scale,"actual SWT shell zoom resolves to requested effective scale");
            check(shell.getImages().length>0,"owned shell icon installed from embedded resources");
            var query=(Text)marked(shell,"enterpriseai.ui.query",Boolean.TRUE);
            var send=(SoftButton)marked(shell,"enterpriseai.ui.send",Boolean.TRUE);
            var sources=(Composite)marked(shell,"enterpriseai.ui.sources",Boolean.TRUE);var empty=marked(shell,"enterpriseai.ui.empty",Boolean.TRUE);
            var sourcesTitle=(Label)marked(shell,"enterpriseai.ui.sources-title",Boolean.TRUE);
            var graph=(Composite)marked(shell,"enterpriseai.ui.graph","canvas");var fit=marked(shell,"enterpriseai.ui.graph","fit");
            check(query.isVisible() && sources.isVisible() && empty.isVisible(),"query, framed empty source group and centered empty state remain visible");
            check(sourcesTitle.getText().equals("Источники ответа") && sources.getClientArea().height>=90,"Sources caption and useful permanent empty area remain");
            check(!(sources instanceof Group) && (sources.getStyle()&SWT.BORDER)==0,"Sources uses the custom rounded frame rather than the native square GroupBox");
            check(sourcesTitle.getBounds().x==24 && inside(sourcesTitle,sources),"Sources title is inset24 DIP and fits inside its rounded frame");
            var sourceScroll=descendants(sources).stream().filter(ScrolledComposite.class::isInstance).map(ScrolledComposite.class::cast).findFirst().orElseThrow();
            var sourceOrigin=sources.toDisplay(0,0);var scrollOrigin=sourceScroll.toDisplay(0,0);
            check(inside(sourceScroll,sources) && scrollOrigin.x-sourceOrigin.x>=13 && scrollOrigin.y-sourceOrigin.y>=sourcesTitle.getSize().y,"Sources content fits below the notch caption with horizontal padding");
            check((query.getStyle()&SWT.BORDER)==0 && (query.getStyle()&SWT.MULTI)!=0,"unbordered multiline query");
            check(descendants(shell).stream().noneMatch(c->Boolean.TRUE.equals(c.getData("enterpriseai.ui.cancel"))),"separate Cancel control is absent");
            check(descendants(shell).stream().filter(c->Boolean.TRUE.equals(c.getData("enterpriseai.ui.send"))).count()==1,"one shared Send/Stop action is registered");
            check(send instanceof Canvas,"shared rounded Send/Stop remains a custom accessible Canvas control");
            check(inside(query,query.getParent()) && inside(send,send.getParent()),"composer input and shared action fit their parent without clipping");
            check(send.getBounds().width==send.getBounds().height && send.getBounds().width>=32,"send action stays circular and usable");
            check(!send.isStopMode(),"shared action initially presents the Send arrow");
            var sendBounds=send.getBounds();
            var idleIcon=captureAction(send,output.resolveSibling(reportPrefix+"-"+scale+"-send.png"),effectiveScale);
            send.setStopMode(true);send.redraw();send.update();
            check(send.isStopMode() && send.getBounds().equals(sendBounds) && inside(send,send.getParent()),"Stop square reuses the same circular bounds without shifting the composer");
            var stopIcon=captureAction(send,output.resolveSibling(reportPrefix+"-"+scale+"-stop.png"),effectiveScale);
            check(!Arrays.equals(idleIcon.data,stopIcon.data),"actual painted Stop square differs from the Send arrow at effective scale");
            send.setStopMode(false);send.redraw();send.update();
            var restoredIcon=captureAction(send,output.resolveSibling(reportPrefix+"-"+scale+"-send-restored.png"),effectiveScale);
            check(!send.isStopMode() && Arrays.equals(idleIcon.data,restoredIcon.data),"returning to idle restores the same painted Send arrow");
            report.put("separateCancelAbsent",true);report.put("sendStopRendererObserved",true);report.put("sendStopBounds",sendBounds.toString());
            var split=descendants(shell).stream().filter(SashForm.class::isInstance).map(SashForm.class::cast).findFirst().orElseThrow();
            check(Arrays.equals(split.getWeights(),new int[]{450,550}),"assistant/graph proportion remains 45/55");
            var sash=descendants(split).stream().filter(Sash.class::isInstance).map(Sash.class::cast).findFirst().orElseThrow();
            check(Boolean.TRUE.equals(sash.getData("enterpriseai.ui.splitter")) && sash.getSize().x==10,
                    "the actual lazily created Sash receives its visible ten-DIP style after layout");
            check(sash.getBackground().equals(theme.separator()) && sash.getCursor().equals(display.getSystemCursor(SWT.CURSOR_SIZEWE)),
                    "the actual splitter has the theme background and width-resize cursor");
            sash.notifyListeners(SWT.MouseEnter,new Event());
            check(sash.getBackground().equals(theme.separatorHover()),"the actual splitter highlights on pointer entry");
            sash.notifyListeners(SWT.MouseExit,new Event());
            check(sash.getBackground().equals(theme.separator()),"the actual splitter restores its quiet colour after pointer exit");
            check(fit.getParent()==graph && inside(fit,graph),"Graph action overlays its Canvas rather than taking a toolbar row");
            var area=graph.getClientArea();var box=fit.getBounds();
            check(box.width==60 && box.height==30 && box.y==12 && area.width-box.x-box.width==12,"Graph action stays 60x30 DIP at top-right inset12");
            if(!display.getHighContrast())check(theme.isolatedNode().getRGB().equals(new RGB(226,221,233)) && theme.node(64,64).getRGB().equals(new RGB(159,129,195)) && theme.panel().getRGB().equals(new RGB(247,243,231)),"pastel-yellow panel and purple degree-gradient palette");
            var bounds=shell.getClientArea();var image=new Image(display,bounds.width,bounds.height);var gc=new GC(shell);
            Path screenshot=output.resolveSibling(reportPrefix+"-"+scale+".png");
            try{gc.copyArea(image,0,0);var physical=image.getBoundsInPixels();var pixels=image.getImageData(effectiveScale);
                check(Math.abs(physical.width-Math.round(bounds.width*effectiveScale/100.0))<=1 && Math.abs(physical.height-Math.round(bounds.height*effectiveScale/100.0))<=1 && pixels.width==physical.width && pixels.height==physical.height,"physical captured image dimensions reflect effective SWT scaling");
                var loader=new ImageLoader();loader.data=new ImageData[]{pixels};loader.save(screenshot.toString(),SWT.IMAGE_PNG);report.put("imagePixelSize",List.of(pixels.width,pixels.height));}
            finally{gc.dispose();image.dispose();}
            errorDialogLifecycle(display,shell,theme,report);
            report.put("passed",true);report.put("requestedSwtScale",scale);report.put("configuredSwtScale",System.getProperty("swt.autoScale"));report.put("effectiveSwtDeviceZoom",DPIUtil.getDeviceZoom());
            report.put("effectiveShellScale",effectiveScale);report.put("effectiveAutoScaleValue",DPIUtil.getEffectiveAutoScaleValue());report.put("nativeSwtDeviceZoom",DPIUtil.getNativeDeviceZoom());report.put("nativeShellZoom",shell.getZoom());
            report.put("actualDisplayDpi",List.of(display.getDPI().x,display.getDPI().y));report.put("windowsScalingChanged",false);report.put("screenshot",screenshot.toString());
            report.put("queryBounds",query.getBounds().toString());report.put("sourcesBounds",sources.getBounds().toString());report.put("graphButtonBounds",fit.getBounds().toString());
            shell.close();
        }catch(Throwable failure){report.put("passed",false);report.put("error",failure.toString());throw failure;}
        finally{if(display!=null&&!display.isDisposed())display.dispose();executor.shutdownNow();report.put("checks",checks);report.put("expectedUnavailableServices",errors);report.put("limitations",List.of("Developer SWT autoScale configurations; Windows monitor scaling not altered", "No real model, viewer or native distribution no-write claim", "Actual native monitor DPI tested separately"));Files.writeString(output,Json.stringify(report));}
        System.out.println("UiDpiTest: PASS "+scale+" "+checks.size());
    }
    /** Closing our modal while it is being shown must not tear down its caller. */
    private static void errorDialogLifecycle(Display display,Shell parent,UiTheme theme,Map<String,Object> report) throws Exception {
        var runtimeHandler=display.getRuntimeExceptionHandler();var errorHandler=display.getErrorHandler();
        var failures=new ArrayList<String>();
        display.setRuntimeExceptionHandler(failure->failures.add(failure.toString()));
        display.setErrorHandler(failure->failures.add(failure.toString()));
        try {
            UiHost.showError(parent,theme,new Api.ServiceError("DPI_MODAL_NORMAL","Проверка окна ошибки и фокуса клавиатуры"));
            var owned=Arrays.stream(display.getShells()).filter(s->"DPI_MODAL_NORMAL".equals(s.getData("enterpriseai.ui.error"))).toList();
            check(owned.size()==1,"one owned error dialog is shown for one error");var dialog=owned.get(0);
            check(dialog.getParent()==parent && (dialog.getStyle()&SWT.APPLICATION_MODAL)!=0 && dialog.isVisible(),"the error dialog is a visible application modal owned by the caller");
            check(dialog.getText().equals(UiTheme.NAME) && dialog.getImages().length>0 && Arrays.equals(dialog.getImages(),parent.getImages()),"error dialog shares the application title and embedded window icons");
            var ok=descendants(dialog).stream().filter(SoftButton.class::isInstance).map(SoftButton.class::cast).findFirst().orElseThrow();
            check(display.getFocusControl()==ok,"normal error dialog assigns actual keyboard focus to its OK action");
            ok.notifyListeners(SWT.Selection,new Event());
            check(dialog.isDisposed() && !parent.isDisposed() && parent.isEnabled(),"dismissing the normal error preserves and re-enables its caller");
            for(int closeEvent:new int[]{SWT.Show,SWT.Activate}) {
                String code="DPI_MODAL_CLOSE_"+closeEvent;var captured=new ArrayList<Shell>();var calls=new int[1];var returned=new boolean[1];
                Listener closeDuringShow=event->{
                    if(event.widget instanceof Shell candidate && code.equals(candidate.getData("enterpriseai.ui.error"))) {
                        captured.add(candidate);calls[0]++;candidate.close();
                    }
                };
                display.addFilter(closeEvent,closeDuringShow);
                try {
                    display.asyncExec(()->{try{UiHost.showError(parent,theme,new Api.ServiceError(code,"Окно закрывается непосредственно при показе"));}finally{returned[0]=true;}});
                    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(!returned[0] && System.nanoTime()<until){if(!display.readAndDispatch())Thread.sleep(2);}
                    check(returned[0] && calls[0]==1 && captured.size()==1 && captured.get(0).isDisposed(),"owned error closes synchronously during actual "+(closeEvent==SWT.Show?"Show":"Activate")+" event");
                    check(!parent.isDisposed() && parent.isVisible() && parent.isEnabled(),"early error-dialog closure leaves its caller alive and usable after "+(closeEvent==SWT.Show?"Show":"Activate"));
                    var sentinel=new boolean[1];display.asyncExec(()->sentinel[0]=true);
                    until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(!sentinel[0] && System.nanoTime()<until){if(!display.readAndDispatch())Thread.sleep(2);}
                    check(sentinel[0] && failures.isEmpty(),"UI queue remains responsive without a runnable or callback failure after "+(closeEvent==SWT.Show?"Show":"Activate"));
                    check(Arrays.stream(display.getShells()).noneMatch(s->code.equals(s.getData("enterpriseai.ui.error"))),"early closure leaves no additional error shell");
                } finally {
                    display.removeFilter(closeEvent,closeDuringShow);
                    for(var candidate:display.getShells())if(code.equals(candidate.getData("enterpriseai.ui.error")))candidate.dispose();
                }
            }
            check(failures.isEmpty(),"all error-dialog lifecycle cases complete without asynchronous errors");
            report.put("errorDialogLifecycle",Map.of("normalKeyboardFocus",true,"ownedModalTitleAndIcons",true,"synchronousCloseEvents",List.of("Show","Activate"),"callerPreserved",true,"asyncFailures",List.copyOf(failures)));
        } finally {
            display.setRuntimeExceptionHandler(runtimeHandler);display.setErrorHandler(errorHandler);
            for(var candidate:display.getShells())if(candidate.getData("enterpriseai.ui.error") instanceof String code && code.startsWith("DPI_MODAL_"))candidate.dispose();
        }
    }
    private static ImageData captureAction(Control control,Path screenshot,int scale) {
        var size=control.getSize();var image=new Image(control.getDisplay(),size.x,size.y);var gc=new GC(control);
        try{gc.copyArea(image,0,0);var pixels=image.getImageData(scale);var loader=new ImageLoader();loader.data=new ImageData[]{pixels};loader.save(screenshot.toString(),SWT.IMAGE_PNG);return pixels;}
        finally{gc.dispose();image.dispose();}
    }
}
