package ai.enterprise.graph;

import ai.enterprise.contracts.Json;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;

/** Real SWT events; only an owned test window, RAM callbacks and optional report output. */
public final class GraphCanvasTest {
    private record Command(String operation,String id,double x,double y) {}
    private record Selection(String id,String title) {}
    public static void main(String[] args)throws Exception {
        Path candidate=args.length>0?Path.of(args[0]).toAbsolutePath():Path.of("src/build/native").toAbsolutePath();
        Path libraries=candidate;
        if(!Files.isRegularFile(libraries.resolve("swt-win32-4974r10.dll"))){
            while(candidate!=null&&!candidate.getFileName().toString().equals("build"))candidate=candidate.getParent();
            if(candidate==null)throw new IllegalArgumentException("Pass the native library directory or an owned build fixture path");
            libraries=candidate.resolve("native");
        }
        System.setProperty("swt.library.path",libraries.toString());
        Display display=new Display();Shell shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(900,650);
        var view=new GraphCanvas(shell,SWT.NONE);var commands=new ArrayList<Command>();var opened=new ArrayList<String>();
        var selected=new ArrayList<Selection>();view.onSelectedDocument((id,title)->selected.add(new Selection(id,title)));
        view.onDrag(new GraphCanvas.DragListener(){
            public void pin(String id,double x,double y){commands.add(new Command("pin",id,x,y));}
            public void release(String id,double x,double y){commands.add(new Command("release",id,x,y));}
        });
        view.onOpenDocument(opened::add);
        var positions=Map.of("A",new GraphLayout.Point(-60,0),"B",new GraphLayout.Point(60,0));
        String fullTitle="Документ А: длинное название с пробелами и казахскими буквами Ә Ғ Қ Ң Ө Ұ Ү Һ І";
        var nodes=List.of(new GraphCanvas.ViewNode("A",fullTitle,1),new GraphCanvas.ViewNode("B","Б",1));
        try {
            shell.open();shell.layout(true,true);while(display.readAndDispatch()){}
            view.setGraph(nodes,List.of(new GraphCanvas.ViewEdge("A","B")),positions);
            Canvas canvas=(Canvas)field(view,"canvas");
            Canvas fitButton=(Canvas)field(view,"fitButton");
            var viewport=canvas.getClientArea();var fitBounds=fitButton.getBounds();
            check(view.getChildren().length==1&&fitButton.getParent()==canvas,"Graph uses its complete viewport and an overlay button, without a toolbar row");
            check(fitBounds.width==60&&fitBounds.height==30&&fitBounds.x==viewport.width-72&&fitBounds.y==12,"Graph button is compact and inset in the upper-right viewport corner");
            check(Boolean.TRUE.equals(fitButton.getData("enterpriseai.ui.graph-fit")),"Overlay exposes the stable graph-fit automation marker");
            mouse(fitButton,SWT.MouseDown,20,15);mouse(fitButton,SWT.MouseUp,20,15);
            check(commands.isEmpty()&&opened.isEmpty()&&selected.getLast().id()==null,"Overlay click fits the view without selecting, opening or pinning a document");
            var originalCanvasSize=canvas.getSize();canvas.setSize(45,20);
            fitBounds=fitButton.getBounds();viewport=canvas.getClientArea();
            check(fitBounds.x>=0&&fitBounds.y>=0&&fitBounds.x+fitBounds.width<=viewport.width&&fitBounds.y+fitBounds.height<=viewport.height,"Overlay remains inside a viewport smaller than its normal bounds");
            canvas.setSize(originalCanvasSize);
            double zoom=(double)field(view,"zoom"),ox=(double)field(view,"offsetX"),oy=(double)field(view,"offsetY");
            int ax=(int)Math.round(-60*zoom+ox),ay=(int)Math.round(oy);
            mouse(canvas,SWT.MouseDown,ax,ay);
            check(display.getFocusControl()==canvas,"Clicking a node focuses the viewport itself rather than its overlay button");
            check(commands.isEmpty(),"Pressing a node must not wake or pin physics");
            mouse(canvas,SWT.MouseMove,ax+3,ay+2);mouse(canvas,SWT.MouseUp,ax,ay);
            check(commands.isEmpty()&&opened.isEmpty(),"A stationary single click only selects");
            check(selected.getLast().equals(new Selection("A",fullTitle)),"Selection sends the complete title to the host footer");
            canvas.setSize(originalCanvasSize.x,originalCanvasSize.y-28);
            check(zoom==(double)field(view,"zoom")&&ox==(double)field(view,"offsetX")&&oy==(double)field(view,"offsetY")
                            &&positions.equals(field(view,"positions")),
                    "Resizing for the first footer selection preserves the camera and world pose before a second click");
            canvas.setSize(originalCanvasSize);
            mouse(canvas,SWT.MouseDoubleClick,ax,ay);
            check(opened.isEmpty(),"Double-click waits for a valid non-drag final MouseUp");
            mouse(canvas,SWT.MouseUp,ax,ay);mouse(canvas,SWT.MouseUp,ax,ay);
            check(opened.equals(List.of("A"))&&commands.isEmpty(),"Win32 double-click without a second MouseDown opens exactly once and does not wake physics");
            opened.clear();
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseMove,ax+25,ay+15);mouse(canvas,SWT.MouseUp,ax+65,ay+30);
            var release=commands.getLast();
            check(release.operation.equals("release")&&Math.abs(release.x-(-60+65/zoom))<1e-6&&Math.abs(release.y-30/zoom)<1e-6,"Final MouseUp coordinates must survive a coalesced movement");
            check(opened.isEmpty(),"A drag must not open a document");
            view.setGraph(nodes,List.of(),positions);
            int previousCommands=commands.size();
            mouse(canvas,SWT.MouseDown,ax,ay);canvas.notifyListeners(SWT.FocusOut,new Event());
            check(commands.size()==previousCommands,"Cancelling a press that never became a drag does not release or wake physics");
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseMove,ax+25,ay);canvas.notifyListeners(SWT.FocusOut,new Event());released(view,commands,"FocusOut");
            view.setGraph(nodes,List.of(),positions);
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseMove,ax+25,ay);shell.notifyListeners(SWT.Deactivate,new Event());released(view,commands,"Deactivate");
            view.setGraph(nodes,List.of(),positions);
            mouse(canvas,SWT.MouseDown,ax,ay);check(display.getFocusControl()==canvas,"Canvas focus enables Escape");mouse(canvas,SWT.MouseMove,ax+25,ay);
            var escape=new Event();escape.keyCode=SWT.ESC;canvas.notifyListeners(SWT.KeyDown,escape);released(view,commands,"Escape");
            view.setGraph(nodes,List.of(),positions);
            previousCommands=commands.size();
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseDoubleClick,ax,ay);mouse(canvas,SWT.MouseMove,ax+30,ay);mouse(canvas,SWT.MouseMove,ax,ay);mouse(canvas,SWT.MouseUp,ax,ay);
            check(opened.isEmpty()&&commands.size()>previousCommands,"Dragging on a double-click suppresses opening even if the pointer returns");
            view.setGraph(nodes,List.of(),positions);
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseUp,ax,ay);
            previousCommands=commands.size();
            mouse(canvas,SWT.MouseDown,8,8);mouse(canvas,SWT.MouseUp,8,8);
            check(selected.getLast().equals(new Selection(null,""))&&commands.size()==previousCommands,"Empty-space click clears the footer without waking physics");
            var wheel=new Event();wheel.count=2;wheel.x=300;wheel.y=250;canvas.notifyListeners(SWT.MouseWheel,wheel);
            double userZoom=(double)field(view,"zoom"),userOx=(double)field(view,"offsetX"),userOy=(double)field(view,"offsetY");
            view.setPositions(Map.of("A",new GraphLayout.Point(-85,10),"B",new GraphLayout.Point(80,15)));
            check(userZoom==(double)field(view,"zoom")&&userOx==(double)field(view,"offsetX")&&userOy==(double)field(view,"offsetY"),"Physics frames must preserve the user camera");
            mouse(canvas,SWT.MouseDown,8,8);mouse(canvas,SWT.MouseMove,50,35);mouse(canvas,SWT.MouseUp,50,35);view.fit();canvas.notifyListeners(SWT.Resize,new Event());
            check(commands.size()==previousCommands,"Wheel, pan, fit and resize must not issue any physics command");
            view.setGraph(nodes,List.of(),positions);
            zoom=(double)field(view,"zoom");ox=(double)field(view,"offsetX");oy=(double)field(view,"offsetY");ax=(int)Math.round(-60*zoom+ox);ay=(int)Math.round(oy);
            mouse(canvas,SWT.MouseDown,ax,ay);view.setPositions(Map.of("A",new GraphLayout.Point(-58,0),"B",new GraphLayout.Point(60,0)));
            check(((Map<?,?>)field(view,"positions")).get("A").equals(new GraphLayout.Point(-58,0)),"A press without a drag does not freeze the physics pose");
            mouse(canvas,SWT.MouseUp,ax,ay);
            check(commands.size()==previousCommands&&opened.isEmpty(),"A selection during settling remains passive");
            System.out.println("GraphCanvas gestures, footer selection, double click and passive camera: PASS");
            degreeColours(view,canvas);
            pulseShape();
            if(args.length>1)benchmark(view,canvas,Path.of(args[1]),args.length>2?Path.of(args[2]):null);
            view.setGraph(nodes,List.of(),positions);
            pulseLifecycle(view,canvas);
            view.setGraph(nodes,List.of(),positions);
            zoom=(double)field(view,"zoom");ox=(double)field(view,"offsetX");oy=(double)field(view,"offsetY");
            mouse(canvas,SWT.MouseDown,(int)Math.round(-60*zoom+ox),(int)Math.round(oy));mouse(canvas,SWT.MouseMove,(int)Math.round(-60*zoom+ox)+25,(int)Math.round(oy));view.dispose();
            check(commands.getLast().operation.equals("release"),"Disposal releases a pinned document");
            int disposedLevel=(int)field(view,"pulseLevel");
            pump(display,600);
            check((int)field(view,"pulseLevel")==disposedLevel,
                    "Disposal cancels heartbeat timers without subsequent colour callbacks");
            activePulseDisposal(shell,nodes,positions);
            System.out.println("GraphCanvasTest: PASS");
        } finally {if(!shell.isDisposed())shell.dispose();display.dispose();}
    }
    private static void activePulseDisposal(Shell shell,List<GraphCanvas.ViewNode> nodes,
                                            Map<String,GraphLayout.Point> positions)throws Exception {
        Display display=shell.getDisplay();
        if(display.getHighContrast())return;
        var disposable=new GraphCanvas(shell,SWT.NONE);
        shell.layout(true,true);disposable.setGraph(nodes,List.of(),positions);
        Canvas canvas=(Canvas)field(disposable,"canvas");
        GraphPulse pulse=(GraphPulse)field(disposable,"pulse");
        setField(pulse,"animationAllowed",true);disposable.setPhysicsActive(false);
        startPulseNow(pulse,display,canvas);
        check((int)field(disposable,"pulseLevel")>0,"Disposal fixture has a genuinely running colour animation");
        disposable.dispose();pump(display,600);
        check((int)field(disposable,"pulseLevel")==0,
                "Disposing an actively pulsing view clears its colour and prevents queued animation callbacks");
    }
    private static void pulseLifecycle(GraphCanvas view,Canvas canvas)throws Exception {
        Display display=canvas.getDisplay();
        if(display.getHighContrast()) {
            System.out.println("GraphPulse actual colour timer: SKIPPED because Windows high contrast is enabled; pure waveform checks passed");
            return;
        }
        GraphPulse pulse=(GraphPulse)field(view,"pulse");
        // Override this instance's preference in the fixture only; Windows settings are never changed.
        setField(pulse,"animationAllowed",true);
        view.getShell().forceActive();
        view.setPhysicsActive(false);pulse.refresh();
        var initialPositions=Map.copyOf((Map<?,?>)field(view,"positions"));
        var initialRadii=Map.copyOf((Map<?,?>)field(view,"radii"));
        double initialZoom=(double)field(view,"zoom"),initialX=(double)field(view,"offsetX"),initialY=(double)field(view,"offsetY");
        awaitLevel(display,canvas,9_000);
        var observed=new ArrayList<Integer>();
        long until=System.nanoTime()+700_000_000L;
        while(System.nanoTime()<until) {
            int level=(int)canvas.getData("enterpriseai.graph.pulse.level");
            if(observed.isEmpty()||observed.getLast()!=level)observed.add(level);
            while(display.readAndDispatch()){}
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
        long peaks=observed.stream().filter(level->level==6).count();
        check(peaks==2&&observed.getLast()==0&&observed.stream().allMatch(level->level>=0&&level<=6),
                "The real eight-second timer renders two bounded illumination peaks and returns to the base palette");
        check(initialPositions.equals(field(view,"positions"))&&initialRadii.equals(field(view,"radii"))
                        &&initialZoom==(double)field(view,"zoom")&&initialX==(double)field(view,"offsetX")&&initialY==(double)field(view,"offsetY"),
                "Actual heartbeat frames keep world coordinates, node radii and the camera exactly unchanged");
        startPulseNow(pulse,display,canvas);
        view.setPhysicsActive(true);
        check((int)canvas.getData("enterpriseai.graph.pulse.level")==0,"Active physics immediately clears an ongoing colour pulse");
        pump(display,48);
        check((int)canvas.getData("enterpriseai.graph.pulse.level")==0,"Active physics keeps heartbeat callbacks suspended");
        view.setPhysicsActive(false);startPulseNow(pulse,display,canvas);
        double zoom=(double)field(view,"zoom"),ox=(double)field(view,"offsetX"),oy=(double)field(view,"offsetY");
        int x=(int)Math.round(-60*zoom+ox),y=(int)Math.round(oy);
        mouse(canvas,SWT.MouseDown,x,y);mouse(canvas,SWT.MouseMove,x+15,y);
        check((int)canvas.getData("enterpriseai.graph.pulse.level")==0,"A real node drag immediately clears the pulse");
        pump(display,48);
        check((int)canvas.getData("enterpriseai.graph.pulse.level")==0,"Holding a node drag keeps the colour animation suspended");
        mouse(canvas,SWT.MouseUp,x+15,y);
        setField(pulse,"animationAllowed",false);pulse.refresh();
        var begin=GraphPulse.class.getDeclaredMethod("begin");begin.setAccessible(true);begin.invoke(pulse);
        pump(display,64);
        check((int)canvas.getData("enterpriseai.graph.pulse.level")==0,
                "A disabled animation preference prevents timer-start illumination without changing Windows settings");
        System.out.println("GraphPulse SWT timer, static geometry, physics/drag pause and reduced motion fixture: PASS");
    }
    private static void startPulseNow(GraphPulse pulse,Display display,Canvas canvas)throws Exception {
        // Deliver the already-tested timer action directly for bounded transition tests.
        var begin=GraphPulse.class.getDeclaredMethod("begin");begin.setAccessible(true);begin.invoke(pulse);
        awaitLevel(display,canvas,500);
    }
    private static void awaitLevel(Display display,Canvas canvas,long timeoutMilliseconds) {
        long deadline=System.nanoTime()+timeoutMilliseconds*1_000_000L;
        while((int)canvas.getData("enterpriseai.graph.pulse.level")==0) {
            if(System.nanoTime()>deadline)throw new AssertionError("Owned active idle viewport did not produce the expected colour pulse");
            while(display.readAndDispatch()){}
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
    }
    private static void pump(Display display,long milliseconds) {
        long deadline=System.nanoTime()+milliseconds*1_000_000L;
        while(System.nanoTime()<deadline) {
            while(display.readAndDispatch()){}
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
    }
    private static void setField(Object object,String name,Object value)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void pulseShape() {
        check(GraphPulse.levelAt(0)==0&&GraphPulse.levelAt(550_000_000L)==0&&GraphPulse.levelAt(8_000_000_000L)==0,
                "The heartbeat has zero illumination outside its 550 ms window");
        check(GraphPulse.levelAt(110_000_000L)==6&&GraphPulse.levelAt(300_000_000L)==6
                        &&GraphPulse.levelAt(200_000_000L)<6,
                "The heartbeat has two distinct illumination peaks around 110 and 300 ms");
        int previous=0;
        for(int milliseconds=0;milliseconds<=550;milliseconds++) {
            int level=GraphPulse.levelAt(milliseconds*1_000_000L);
            check(level>=0&&level<=6&&Math.abs(level-previous)<=1,"Heartbeat illumination remains softly quantized within six percent");
            previous=level;
        }
        System.out.println("GraphPulse rare double illumination amplitude and timing: PASS");
    }
    private static void degreeColours(GraphCanvas view,Canvas canvas)throws Exception {
        var nodes=List.of(new GraphCanvas.ViewNode("isolated","Изолированный",0),
                new GraphCanvas.ViewNode("few","Один сосед",1),new GraphCanvas.ViewNode("many","Девять соседей",9));
        var positions=Map.of("isolated",new GraphLayout.Point(-90,0),"few",new GraphLayout.Point(0,0),
                "many",new GraphLayout.Point(90,0));
        view.setGraph(nodes,List.of(),positions);
        var before=paintCentres(view,canvas,positions);
        if(!canvas.getDisplay().getHighContrast()) {
            RGB low=before.get("few"),high=before.get("many");
            check(!before.get("isolated").equals(low),"An isolated node has its separate quiet shade");
            check(high.blue-high.red>low.blue-low.red&&colourRange(high)>colourRange(low),
                    "A higher unique-neighbor degree renders a more prominent purple shade");
        }
        double zoom=(double)field(view,"zoom"),ox=(double)field(view,"offsetX"),oy=(double)field(view,"offsetY");
        int x=(int)Math.round(90*zoom+ox),y=(int)Math.round(oy);
        mouse(canvas,SWT.MouseDown,x,y);mouse(canvas,SWT.MouseUp,x,y);
        check(paintCentres(view,canvas,positions).equals(before),
                "Selecting a node changes its outline while preserving every node's degree-based fill");
        System.out.println("GraphCanvas degree colour and selection preservation: PASS");
    }
    private static int colourRange(RGB rgb) {
        return Math.max(rgb.red,Math.max(rgb.green,rgb.blue))-Math.min(rgb.red,Math.min(rgb.green,rgb.blue));
    }
    private static Map<String,RGB> paintCentres(GraphCanvas view,Canvas canvas,Map<String,GraphLayout.Point> positions)throws Exception {
        var area=canvas.getClientArea();var image=new Image(canvas.getDisplay(),area.width,area.height);var gc=new GC(image);
        try {
            gc.setBackground(canvas.getBackground());gc.fillRectangle(area);
            var paint=new Event();paint.gc=gc;paint.width=area.width;paint.height=area.height;canvas.notifyListeners(SWT.Paint,paint);
            var data=image.getImageData();var result=new LinkedHashMap<String,RGB>();
            double zoom=(double)field(view,"zoom"),ox=(double)field(view,"offsetX"),oy=(double)field(view,"offsetY");
            for(var entry:positions.entrySet()) {
                int x=(int)Math.round(entry.getValue().x()*zoom+ox),y=(int)Math.round(entry.getValue().y()*zoom+oy);
                result.put(entry.getKey(),data.palette.getRGB(data.getPixel(x,y)));
            }
            return result;
        } finally {gc.dispose();image.dispose();}
    }
    private static void benchmark(GraphCanvas view,Canvas canvas,Path fixture,Path reportPath)throws Exception {
        var snapshot=Json.object(Files.readString(fixture));var rows=(List<?>)snapshot.get("nodes");var edgeRows=(List<?>)snapshot.get("edges");
        var nodes=rows.stream().map(row->{var n=Json.map(row);var d=Json.map(n.get("document"));return new GraphCanvas.ViewNode(Json.string(d.get("document_id")),Json.string(d.get("title")),((Number)n.get("degree")).intValue());}).toList();
        var links=edgeRows.stream().map(row->{var e=Json.map(row);return new GraphGeometry.Link(Json.string(e.get("source_id")),Json.string(e.get("target_id")));}).toList();
        var physics=new GraphPhysics(nodes.stream().map(GraphCanvas.ViewNode::id).toList(),links);
        for(int i=0;i<1000&&!physics.sleeping();i++)physics.step(1.0/60,()->false);
        var pairs=new LinkedHashSet<String>();
        for(var link:links){String a=link.sourceId(),b=link.targetId();boolean forward=a.compareTo(b)<=0;pairs.add((forward?a:b)+'\0'+(forward?b:a));}
        var edges=pairs.stream().map(pair->{String[] ids=pair.split("\u0000",-1);return new GraphCanvas.ViewEdge(ids[0],ids[1]);}).toList();
        view.setGraph(nodes,edges,physics.snapshot());
        var samples=new ArrayList<Double>();var area=canvas.getClientArea();Image buffer=new Image(canvas.getDisplay(),area.width,area.height);GC gc=new GC(buffer);
        try {for(int i=0;i<90;i++){var paint=new Event();paint.gc=gc;paint.width=canvas.getClientArea().width;paint.height=canvas.getClientArea().height;long before=System.nanoTime();canvas.notifyListeners(SWT.Paint,paint);if(i>=30)samples.add((System.nanoTime()-before)/1_000_000.0);}}
        finally {gc.dispose();buffer.dispose();}
        samples.sort(Double::compare);var report=new LinkedHashMap<String,Object>();report.put("documents",nodes.size());report.put("visualEdges",edges.size());report.put("paintMedianMs",samples.get(samples.size()/2));report.put("paintP95Ms",samples.get((int)Math.ceil(samples.size()*.95)-1));report.put("includes","SWT paint callback into RAM GDI buffer at the owned Canvas size; screen presentation and full application frame scheduling are separate");
        if(reportPath!=null)Files.writeString(reportPath,Json.stringify(report));System.out.println(Json.stringify(report));
    }
    private static void released(Object view,List<Command> commands,String event)throws Exception {check(!(boolean)field(view,"buttonDown")&&field(view,"pressedNode")==null&&commands.getLast().operation.equals("release"),event+" leaves no pinned gesture");}
    private static Object field(Object object,String name)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void mouse(Canvas canvas,int type,int x,int y){var event=new Event();event.button=1;event.x=x;event.y=y;canvas.notifyListeners(type,event);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
