package ai.enterprise.shell;

import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.platform.WindowsPlatform;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.internal.DPIUtil;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;

/** Every test initializes the complete current catalog; none creates a reduced application. */
public final class RestyleUiTest {
    private static final List<Map<String,Object>> checks = new ArrayList<>();
    private static void check(boolean passed,String name) {
        checks.add(Map.of("name",name,"passed",passed));if(!passed)throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        run(Path.of(args[0]).toAbsolutePath(),args.length>1?Integer.parseInt(args[1]):100,
            args.length>2?args[2]:"restyle-ui",false);
    }
    public static void run(Path source,int scale,String prefix,boolean layoutOnly) throws Exception {
        if(!prefix.matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("Invalid report prefix");
        checks.clear();Path output=source.resolve("build/test-results/"+prefix+"-"+scale+".json");Files.createDirectories(output.getParent());
        System.setProperty("swt.library.path",source.resolve("build/native").toString());
        var report=new LinkedHashMap<String,Object>();report.put("suite","Full Mini-AI SWT restyle acceptance");
        report.put("allCatalogModules",GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).toList());
        report.put("allCatalogPanels",GeneratedCatalog.panels().stream().map(PanelFactory::id).toList());
        var executor=Executors.newFixedThreadPool(4);Display display=null;
        Path runtime=source.resolve("build/restyle-ui-root");var platform=new WindowsPlatform(runtime);
        try(var component=EnterpriseAIComponent.create(new Api.Environment(runtime,executor,platform),GeneratedCatalog.modules(),GeneratedCatalog.panels())) {
            for(var service:List.of(Api.DatabaseServiceV1.class,Api.SearchServiceV1.class,Api.DocumentAccessServiceV1.class,
                Api.GraphServiceV1.class,Api.SourceViewerServiceV1.class,Api.InferenceServiceV1.class,Api.AssistantSessionServiceV1.class))
                check(component.context().find(service).isPresent(),"full catalog registered "+service.getSimpleName());
            check(!component.context().require(Api.DatabaseServiceV1.class).sqliteVersion().equals("unavailable"),"full-catalog fixture opens actual read-only database");
            ProbeSession session=new ProbeSession();
            if(!layoutOnly){replaceForUiFixture(component.context(),Api.AssistantSessionServiceV1.class,session);replaceForUiFixture(component.context(),Api.SourceViewerServiceV1.class,new ProbeViewer());}
            Display.setAppName(UiTheme.NAME);display=new Display();UiTheme theme=UiTheme.get(display);Shell shell=new Shell(display);theme.decorate(shell);
            shell.setText(UiTheme.NAME+" — restyle acceptance");GridLayout root=new GridLayout(1,false);root.marginWidth=24;root.marginHeight=24;root.verticalSpacing=16;shell.setLayout(root);
            RecordingHost host=new RecordingHost(theme);UiHost.install(shell,host);Composite attached=component.attach(shell);attached.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
            shell.setMinimumSize(820,560);shell.setSize(820,560);var area=display.getPrimaryMonitor().getClientArea();shell.setLocation(area.x+Math.max(0,(area.width-820)/2),area.y+Math.max(0,(area.height-560)/2));
            shell.open();pump(display,450);int effective=DPIUtil.getZoomForAutoscaleProperty(shell.getZoom());
            check(effective==scale,"effective SWT scale equals requested "+scale);
            Point startup=shell.getSize(),minimum=shell.getMinimumSize();report.put("startupSizeDip",List.of(startup.x,startup.y));report.put("minimumSizeDip",List.of(minimum.x,minimum.y));
            check(Math.abs(startup.x-820)<=1&&Math.abs(startup.y-560)<=1&&Math.abs(minimum.x-820)<=1&&Math.abs(minimum.y-560)<=1,"820x560 DIP fixture startup/minimum within fractional-pixel rounding: "+startup+" / "+minimum);
            check(shell.getImages().length>0,"owned window retains embedded icon");verifyLayout(shell,theme,true);verifyGutter(shell,theme);screenshot(shell,output.resolveSibling(prefix+"-"+scale+"-minimum.png"),effective);
            shell.setSize(1280,800);shell.layout(true,true);pump(display,120);verifyLayout(shell,theme,false);verifyGutter(shell,theme);screenshot(shell,output.resolveSibling(prefix+"-"+scale+"-large.png"),effective);
            shell.setSize(820,560);shell.layout(true,true);pump(display,100);
            if(!layoutOnly){exerciseAssistant(display,shell,session,host,output,effective);exerciseScrolling(display,shell,theme);}
            exerciseError(display,shell,theme,output,effective);report.put("passed",true);report.put("requestedSwtScale",scale);report.put("effectiveSwtScale",effective);
            report.put("actualDisplayDpi",List.of(display.getDPI().x,display.getDPI().y));report.put("windowsScalingChanged",false);report.put("capturedErrors",host.errors.stream().map(Api.ServiceError::code).toList());
            report.put("behaviorServicesReplacedAfterFullCatalogRegistration",!layoutOnly);shell.dispose();
        }catch(Throwable failure){report.put("passed",false);report.put("error",failure.toString());throw failure;}
        finally{
            if(display!=null&&!display.isDisposed())display.dispose();executor.shutdownNow();report.put("checks",List.copyOf(checks));
            report.put("limitations",List.of("Developer effective SWT scales; Windows settings unchanged.","Full catalog initialized against existing data; deterministic assistant/viewer doubles are used only for behavior checks.","No real model generation, external viewer, physical IME composition or registry no-write claim."));Files.writeString(output,Json.stringify(report));
        }
        System.out.println("RestyleUiTest: PASS "+scale+" "+checks.size()+" "+output);
    }
    private static void verifyLayout(Shell shell,UiTheme theme,boolean minimum){
        Text query=(Text)marked(shell,"query");StyledText transcript=(StyledText)marked(shell,"transcript");SmoothScrollViewport input=viewport(query),history=viewport(transcript);
        Control send=marked(shell,"send"),sources=marked(shell,"sources"),empty=marked(shell,"empty");Composite composer=(Composite)marked(shell,"composer");Label title=(Label)marked(shell,"sources-title");
        check(query.isVisible()&&sources.isVisible()&&empty.isVisible(),"idle input, sources and empty brand visible at "+(minimum?"minimum":"large")+" size");
        check((query.getStyle()&(SWT.MULTI|SWT.WRAP))==(SWT.MULTI|SWT.WRAP)&&(query.getStyle()&SWT.READ_ONLY)==0,"query remains editable wrapped native Text");
        check((transcript.getStyle()&SWT.READ_ONLY)!=0,"Markdown transcript remains selectable and read-only");
        check(inside(input,composer)&&input.getSize().y>=56&&inside(send,send.getParent()),"clipped input and action fit composer");
        check(send.getSize().x==40&&send.getSize().y==40,"round send/stop retains 40 DIP target");
        check(descendants(shell).stream().filter(c->Boolean.TRUE.equals(c.getData("enterpriseai.ui.send"))).count()==1,"one shared send/stop action");
        check(descendants(shell).stream().noneMatch(c->Boolean.TRUE.equals(c.getData("enterpriseai.ui.cancel"))),"separate cancellation action absent");
        check(sources.getSize().y>=90&&title.getText().equals("Источники ответа")&&inside(title,(Composite)sources),"sources retain caption and useful area");
        check(history.getParent().getSize().y>=28,"history retains useful height");
        SashForm split=descendants(shell).stream().filter(SashForm.class::isInstance).map(SashForm.class::cast).findFirst().orElseThrow();
        check(Arrays.equals(split.getWeights(),new int[]{450,550}),"assistant/graph remain 45/55");
        Sash sash=descendants(split).stream().filter(Sash.class::isInstance).map(Sash.class::cast).findFirst().orElseThrow();
        check(sash.getSize().x==24&&Boolean.TRUE.equals(sash.getData("enterpriseai.ui.splitter")),"24 DIP gap retains themed draggable sash");
        check(sash.getCursor()==shell.getDisplay().getSystemCursor(SWT.CURSOR_SIZEWE),"splitter retains resize cursor");
        Composite graph=(Composite)descendants(shell).stream().filter(c->"canvas".equals(c.getData("enterpriseai.ui.graph"))).findFirst().orElseThrow();
        Control fit=descendants(shell).stream().filter(c->"fit".equals(c.getData("enterpriseai.ui.graph"))).findFirst().orElseThrow();check(fit.getParent()==graph&&inside(fit,graph),"Graph action remains Canvas overlay");
        if(!theme.highContrast()){
            check(theme.background().getRGB().equals(new RGB(32,32,32)),"Fluent canvas matches #202020");RGB p=theme.panel().getRGB();check(p.red<80&&p.green<80&&p.blue<90,"panels use dark reference surface");
            check(theme.text().getRGB().equals(new RGB(255,255,255)),"reference white body text");RGB node=theme.node(64,64).getRGB();check(node.blue>=node.red,"graph degree palette follows blue-gray reference");
        }
    }
    private static void verifyGutter(Shell shell,UiTheme theme){
        if(theme.highContrast())return;Image sample=new Image(shell.getDisplay(),20,20);GC gc=new GC(shell);
        try{gc.copyArea(sample,0,0);ImageData pixels=sample.getImageData();RGB color=pixels.palette.getRGB(pixels.getPixel(10,10));check(color.blue>color.red,"top-left gutter retains blue reference gradient without B/R swap: "+color);}finally{gc.dispose();sample.dispose();}
    }
    private static void exerciseAssistant(Display display,Shell shell,ProbeSession session,RecordingHost host,Path output,int scale)throws Exception{
        Text query=(Text)marked(shell,"query");StyledText transcript=(StyledText)marked(shell,"transcript");SoftButton send=(SoftButton)marked(shell,"send");
        Control empty=marked(shell,"empty"),sources=marked(shell,"sources");Composite activity=(Composite)marked(shell,"activity");Label stage=(Label)marked(shell,"activity-stage");
        check(!activity.isVisible()&&!send.isEnabled()&&!send.isStopMode(),"idle hides activity and disables blank Send");query.setText("Русский, қазақша, Unicode 😀\nВторая строка");
        Event shift=key(SWT.CR,SWT.SHIFT);query.notifyListeners(SWT.KeyDown,shift);check(shift.doit&&session.operations.isEmpty(),"Shift+Enter retains native newline handling");
        Event enter=key(SWT.CR,0);query.notifyListeners(SWT.KeyDown,enter);pump(display,80);check(!enter.doit&&session.operations.size()==1&&session.operations.getFirst().question.contains("қазақша"),"Enter submits Unicode multiline input once");
        Operation op=session.operations.getFirst();check(query.getText().isEmpty()&&send.isStopMode()&&activity.isVisible()&&!empty.isVisible(),"busy switches same action to Stop and shows activity");
        check(inside(activity,activity.getParent())&&inside(viewport(query),(Composite)marked(shell,"composer")),"busy minimum window keeps status and input visible");
        op.emit("state","Формирование ответа по найденным документам…",List.of(),null);op.emit("token","Потоковый ответ 😀",List.of(),null);until(display,()->transcript.getText().contains("Потоковый ответ"));
        check(stage.getText().contains("Формирование")&&transcript.getText().contains("Потоковый ответ"),"stage and streamed text appear before completion");screenshot(shell,sibling(output,"busy"),scale);
        op.emit("token", "\n\n# Заголовок\n" + ("Длинная строка форматированного ответа. ".repeat(8) + "\n").repeat(30)
            + "Последняя строка", List.of(), null);
        until(display, () -> transcript.getText().contains("Последняя строка"));
        SmoothScrollViewport history = viewport(transcript);
        SmoothScrollController historyScroll = history.controller();
        check(transcript.getStyleRanges().length > 0 && history.maximumOffset() > history.viewportHeight(),
            "wrapped Markdown styles are retained in a scrollable transcript");
        check(Math.abs(historyScroll.offset() - history.maximumOffset()) < 1
            && transcript.getLinePixel(transcript.getLineCount()) <= transcript.getClientArea().height + 1,
            "the newest streamed line is visible at the bottom of the transcript");
        history.setAutomaticFramesForTesting(false);
        historyScroll.setAnimationPreferenceForTesting(true);
        OS.SendMessage(transcript.handle, OS.WM_MOUSEWHEEL, OS.MAKEWPARAM(0, 30), 0);
        check(Math.abs(historyScroll.targetOffset() - (history.maximumOffset() - 9)) < .01,
            "the Markdown transcript retains fractional native wheel input");
        historyScroll.setAnimationPreferenceForTesting(null);
        history.setAutomaticFramesForTesting(true);
        query.setText("Следующий вопрос");query.notifyListeners(SWT.KeyDown,key(SWT.CR,0));check(session.operations.size()==1&&!op.token.cancelled(),"Enter while busy neither sends nor cancels");
        send.notifyListeners(SWT.Selection,new Event());check(op.token.cancelled()&&!send.isEnabled(),"Stop cancels once and prevents repeat activation");op.fail("CANCELLED","Отмена",List.of());until(display,()->!send.isStopMode());
        check(!send.isStopMode()&&!activity.isVisible()&&host.errors.isEmpty(),"acknowledged cancellation restores Send without error: stop="+send.isStopMode()+",activity="+activity.isVisible()+",errors="+host.errors);
        query.setText("Источники");send.notifyListeners(SWT.Selection,new Event());Operation next=session.operations.getLast();Api.Fragment fragment=fragment();next.emit("sources","",List.of(fragment),null);until(display,()->descendants((Composite)sources).stream().anyMatch(Link.class::isInstance));
        check(sources.isVisible()&&descendants((Composite)sources).stream().anyMatch(Link.class::isInstance),"sources retain native clickable links");next.fail("UI_PROBE","Проверка понятной ошибки",List.of(fragment));until(display,()->!send.isStopMode());
        check(host.errors.size()==1&&!send.isStopMode()&&!activity.isVisible(),"error event and future report once and restore idle");
        Link link=descendants((Composite)sources).stream().filter(Link.class::isInstance).map(Link.class::cast).findFirst().orElseThrow();link.notifyListeners(SWT.Selection,new Event());pump(display,80);
        Shell card=Arrays.stream(display.getShells()).filter(s->s!=shell&&s.getText().startsWith("Источник:")).findFirst().orElseThrow();Text details=descendants(card).stream().filter(Text.class::isInstance).map(Text.class::cast).findFirst().orElseThrow();
        check((details.getStyle()&SWT.READ_ONLY)!=0&&details.getText().contains("pdf_region"),"source card preserves selectable native provenance");check(Arrays.equals(card.getImages(),shell.getImages()),"source card retains application icons");screenshot(card,sibling(output,"source"),scale);card.close();query.setText("");
    }
    private static void exerciseScrolling(Display display,Shell shell,UiTheme theme)throws Exception{
        Text query=(Text)marked(shell,"query");SmoothScrollViewport view=viewport(query);query.setText(("Длинная строка с переносом, русский и қазақша 😀. ".repeat(8)+"\n").repeat(100));view.refresh();pump(display,120);
        view.setAutomaticFramesForTesting(false);SmoothScrollController c=view.controller();c.setAnimationPreferenceForTesting(true);c.jumpTo(0);
        check(view.maximumOffset()>view.viewportHeight(),"wrapped input has long extent");check(query.getSize().y<view.viewportHeight()+200,"native Text height stays bounded independently of document extent");
        c.scrollByWheelDelta(-60);check(Math.abs(c.targetOffset()-18)<0.01,"half-notch delta retains 18 DIP");c.scrollByWheelDelta(-60);check(Math.abs(c.targetOffset()-36)<0.01,"wheel targets accumulate before frame");
        c.advanceFrame(16);check(c.offset()>0&&c.offset()<36,"75ms easing moves continuously");c.scrollByWheelDelta(30);check(Math.abs(c.targetOffset()-27)<0.01,"reversal adjusts existing target");
        for(int i=0;i<100;i++)c.advanceFrame(16);check(!c.isAnimating()&&Math.abs(c.offset()-27)<0.25,"easing settles and stops frames");
        c.jumpTo(0);OS.SendMessage(query.handle,OS.WM_MOUSEWHEEL,OS.MAKEWPARAM(0,-30),0);
        check(Math.abs(c.targetOffset()-9)<0.01,"native high-resolution wheel retains quarter-notch delta exactly once");
        c.jumpTo(0);query.setSelection(0);query.setFocus();pump(display,40);
        Point previousCursor=display.getCursorLocation();Event press=new Event();press.button=1;press.x=4;press.y=4;
        try{
            query.notifyListeners(SWT.MouseDown,press);Point below=view.toDisplay(6,view.viewportHeight()+20);display.setCursorLocation(below);pump(display,100);
            double selectedOffset=c.offset();int selectedCount=query.getSelectionCount();pump(display,100);
            check(selectedOffset>0&&selectedCount>0&&c.offset()>selectedOffset&&query.getSelectionCount()>selectedCount,"stationary pointer beyond clipping edge continues native selection and scroll");
        }finally{Event release=new Event();release.button=1;query.notifyListeners(SWT.MouseUp,release);display.setCursorLocation(previousCursor);pump(display,30);}
        query.setSelection(query.getCharCount());query.showSelection();query.notifyListeners(SWT.KeyUp,key(SWT.END,SWT.CTRL));pump(display,60);
        Point caret=query.toDisplay(query.getCaretLocation());Point clipOrigin=view.toDisplay(0,0);
        check(caret.y>=clipOrigin.y-1&&caret.y<clipOrigin.y+view.viewportHeight(),"native caret navigation reconciles end selection into clipped viewport");
        c.jumpTo(0);c.scrollByWheelDelta(120);check(c.bounceOffset()>0&&c.bounceOffset()<=18,"top edge immediately displaces content down");for(int i=0;i<100;i++)c.scrollByWheelDelta(120);check(Math.abs(c.bounceOffset())<=18,"repeated impulses respect 18 DIP clamp");
        for(int i=0;i<160;i++)c.advanceFrame(16);check(!c.isAnimating()&&c.bounceOffset()==0,"spring settles exactly and stops frames");
        c.jumpTo(view.maximumOffset());c.scrollByWheelDelta(-120);check(c.bounceOffset()<0&&Math.abs(c.bounceOffset())<=18,"bottom edge displaces content up");check(c.offset()<=view.maximumOffset()&&c.targetOffset()<=view.maximumOffset(),"legal offset remains bounded during bounce");
        query.notifyListeners(SWT.KeyDown,key(SWT.ARROW_UP,0));pump(display,25);check(!c.isAnimating()&&c.bounceOffset()==0,"manual key cancels easing and spring");
        c.setAnimationPreferenceForTesting(false);c.jumpTo(0);c.scrollByWheelDelta(-120);check(!c.isAnimating()&&c.bounceOffset()==0&&Math.abs(c.offset()-36)<0.25,"disabled animations scroll immediately");c.jumpTo(0);c.scrollByWheelDelta(120);check(c.bounceOffset()==0,"disabled animations suppress edge spring");
        c.setAnimationPreferenceForTesting(true);c.jumpTo(0);c.scrollByWheelDelta(-360);shell.setSize(1000,650);shell.layout(true,true);pump(display,80);check(!c.isAnimating()&&c.bounceOffset()==0,"resize cancels easing and clears spring");
        query.setText("Короткий текст");view.refresh();pump(display,50);c.jumpTo(0);check(view.maximumOffset()==0&&!c.scrollByWheelDelta(120)&&c.bounceOffset()==0,"content without overflow never bounces");query.setText("");view.refresh();shell.setSize(820,560);shell.layout(true,true);pump(display,80);
        Shell owned=new Shell(shell,SWT.DIALOG_TRIM);owned.setLayout(new FillLayout());SmoothScrollViewport content=SmoothScrollViewport.forContent(owned,theme);content.content().setLayout(new GridLayout());
        Link sourceLink=new Link(content.content(),SWT.NONE);theme.apply(sourceLink);sourceLink.setText("<a>Контрольный источник</a>");
        for(int i=0;i<60;i++){Label label=new Label(content.content(),SWT.NONE);theme.apply(label);label.setText("Источник "+i);}owned.setSize(260,180);owned.open();content.refresh();pump(display,50);content.setAutomaticFramesForTesting(false);
        SmoothScrollController list=content.controller();list.setAnimationPreferenceForTesting(true);list.jumpTo(0);OS.SendMessage(sourceLink.handle,OS.WM_MOUSEWHEEL,OS.MAKEWPARAM(0,-30),0);
        check(Math.abs(list.targetOffset()-9)<0.01,"native source-Link quarter-notch forwards once to its viewport");
        list.scrollByWheelDelta(-120);check(list.isAnimating()&&content.maximumOffset()>0,"source-content adapter uses shared smoothing");
        Canvas bar=content.scrollbar();list.jumpTo(0);OS.SendMessage(bar.handle,OS.WM_MOUSEWHEEL,OS.MAKEWPARAM(0,-30),0);check(Math.abs(list.targetOffset()-9)<0.01,"native scrollbar quarter-notch forwards once");list.advanceFrame(16);Event down=new Event();down.button=1;down.x=8;down.y=4;bar.notifyListeners(SWT.MouseDown,down);Event move=new Event();move.x=8;move.y=50;move.stateMask=SWT.BUTTON1;bar.notifyListeners(SWT.MouseMove,move);Event up=new Event();up.button=1;up.x=8;up.y=50;bar.notifyListeners(SWT.MouseUp,up);
        check(!list.isAnimating()&&list.bounceOffset()==0,"scrollbar drag cancels easing and spring");list.scrollByWheelDelta(-120);owned.dispose();pump(display,50);check(!list.isAnimating(),"dispose cancels animation without stale callbacks");
    }
    private static void exerciseError(Display display,Shell shell,UiTheme theme,Path output,int scale)throws Exception{
        UiHost.showError(shell,theme,new Api.ServiceError("RESTYLE_MODAL","Проверка оформления ошибки и клавиатурного фокуса"));pump(display,50);Shell dialog=Arrays.stream(display.getShells()).filter(s->"RESTYLE_MODAL".equals(s.getData("enterpriseai.ui.error"))).findFirst().orElseThrow();
        SoftButton ok=descendants(dialog).stream().filter(SoftButton.class::isInstance).map(SoftButton.class::cast).findFirst().orElseThrow();check(dialog.getParent()==shell&&(dialog.getStyle()&SWT.APPLICATION_MODAL)!=0&&dialog.isVisible(),"error remains owned visible modal");check(display.getFocusControl()==ok,"error assigns keyboard focus to OK");screenshot(dialog,sibling(output,"error"),scale);
        ok.notifyListeners(SWT.Selection,new Event());pump(display,25);check(dialog.isDisposed()&&!shell.isDisposed()&&shell.isEnabled(),"dismissing error preserves enabled caller");
        for(int type:new int[]{SWT.Show,SWT.Activate}){String code="RESTYLE_EARLY_"+type;boolean[] seen={false};Listener closer=e->{if(e.widget instanceof Shell s&&code.equals(s.getData("enterpriseai.ui.error"))){seen[0]=true;s.close();}};display.addFilter(type,closer);try{UiHost.showError(shell,theme,new Api.ServiceError(code,"Закрытие при показе"));pump(display,25);check(seen[0]&&!shell.isDisposed()&&shell.isEnabled(),"immediate modal closure at event "+type+" preserves caller");}finally{display.removeFilter(type,closer);}}
    }
    @SuppressWarnings("unchecked")private static<T>void replaceForUiFixture(Api.Context context,Class<T> type,T probe)throws Exception{var f=Api.Context.class.getDeclaredField("services");f.setAccessible(true);var map=(Map<Class<?>,Object>)f.get(context);if(!map.containsKey(type))throw new AssertionError("Full catalog service absent "+type);map.put(type,probe);}
    private static List<Control> descendants(Composite parent){var all=new ArrayList<Control>();for(var c:parent.getChildren()){all.add(c);if(c instanceof Composite nested)all.addAll(descendants(nested));}return all;}
    private static Control marked(Composite parent,String name){return descendants(parent).stream().filter(c->Boolean.TRUE.equals(c.getData("enterpriseai.ui."+name))).findFirst().orElseThrow(()->new AssertionError("Missing "+name));}
    private static SmoothScrollViewport viewport(Control c){for(Control p=c;p!=null;p=p.getParent())if(p instanceof SmoothScrollViewport v)return v;throw new AssertionError("Missing clipped viewport");}
    private static boolean inside(Control c,Composite parent){Point p=c.toDisplay(0,0),origin=parent.toDisplay(0,0),size=c.getSize();Rectangle a=parent.getClientArea();return p.x>=origin.x&&p.y>=origin.y&&p.x+size.x<=origin.x+a.width+1&&p.y+size.y<=origin.y+a.height+1;}
    private static void pump(Display d,long ms)throws InterruptedException{long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);do{if(!d.readAndDispatch())Thread.sleep(2);}while(System.nanoTime()<end);}
    private static void until(Display d,BooleanSupplier ready)throws InterruptedException{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!ready.getAsBoolean()&&System.nanoTime()<end){if(!d.readAndDispatch())Thread.sleep(2);}if(!ready.getAsBoolean())throw new AssertionError("Timed out awaiting asynchronous UI state");pump(d,10);}
    private static Event key(int k,int mask){Event e=new Event();e.keyCode=k;e.stateMask=mask;return e;}
    private static Path sibling(Path output,String state){return output.resolveSibling(output.getFileName().toString().replace(".json","-"+state+".png"));}
    private static void screenshot(Control c,Path path,int scale){c.redraw();c.update();Rectangle size=c instanceof Scrollable s?s.getClientArea():new Rectangle(0,0,c.getSize().x,c.getSize().y);Image image=new Image(c.getDisplay(),Math.max(1,size.width),Math.max(1,size.height));GC gc=new GC(c);try{gc.copyArea(image,0,0);ImageLoader loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData(scale)};loader.save(path.toString(),SWT.IMAGE_PNG);}finally{gc.dispose();image.dispose();}}
    private static Api.Fragment fragment(){return new Api.Fragment(Map.of("fragment_id","ui-fragment","document_id","ui-document","title","Контрольный источник","text","Точный текст исходного фрагмента.\n".repeat(100),"source_refs",List.of(Map.of("kind","pdf_region","artifact_id","original","source_sha256","a".repeat(64),"page",2,"bbox",List.of(1,2,3,4))),"quality",Map.of("needs_review",true),"warnings",List.of(),"span",Map.of("start",0,"end",30)));}
    private static final class RecordingHost implements UiHost{final UiTheme theme;final List<Api.ServiceError>errors=new ArrayList<>();RecordingHost(UiTheme t){theme=t;}public UiTheme theme(){return theme;}public void error(String id,Api.ServiceError e){if(e!=null&&!e.code().equals("CANCELLED"))errors.add(e);}public void selectedDocument(String id,String title){}}
    private static final class ProbeSession implements Api.AssistantSessionServiceV1{final List<Api.SessionMessage>messages=new ArrayList<>();final List<Operation>operations=new ArrayList<>();public List<Api.SessionMessage>history(){return List.copyOf(messages);}public CompletionStage<Api.Result<Void>>send(String q,Consumer<Api.AssistantEvent>e,Api.CancellationToken t){messages.add(new Api.SessionMessage("user",q,List.of(),"complete"));Operation op=new Operation(q,e,t,this);operations.add(op);return op.future;}}
    private static final class Operation{final String question;final Consumer<Api.AssistantEvent>events;final Api.CancellationToken token;final ProbeSession session;final CompletableFuture<Api.Result<Void>>future=new CompletableFuture<>();Operation(String q,Consumer<Api.AssistantEvent>e,Api.CancellationToken t,ProbeSession s){question=q;events=e;token=t;session=s;}void emit(String type,String text,List<Api.Fragment>fragments,Api.ServiceError error){events.accept(new Api.AssistantEvent(type,text,fragments,error));}void fail(String code,String message,List<Api.Fragment>fragments){session.messages.add(new Api.SessionMessage("assistant",message,fragments,"error"));emit("error",message,fragments,new Api.ServiceError(code,message));future.complete(Api.Result.fail(code,message));}}
    private static final class ProbeViewer implements Api.SourceViewerServiceV1{public CompletionStage<Api.Result<Void>>openDocument(String id,Api.CancellationToken token){return CompletableFuture.completedFuture(Api.Result.fail("UI_VIEWER","Контроль внешнего просмотра"));}public CompletionStage<Api.Result<Void>>openSource(Api.SourceRef source,Api.CancellationToken token){return openDocument("ui-document",token);}}
}
