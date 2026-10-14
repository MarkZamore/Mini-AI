package ai.enterprise.graph;

import ai.enterprise.contracts.Json;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;

/** Real SWT events; only an owned test window, RAM callbacks and optional report output. */
public final class GraphCanvasTest {
    private record Command(String operation,String id,double x,double y) {}
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
        view.onDrag(new GraphCanvas.DragListener(){
            public void pin(String id,double x,double y){commands.add(new Command("pin",id,x,y));}
            public void release(String id,double x,double y){commands.add(new Command("release",id,x,y));}
        });
        view.onOpenDocument(opened::add);
        var positions=Map.of("A",new GraphLayout.Point(-60,0),"B",new GraphLayout.Point(60,0));
        var nodes=List.of(new GraphCanvas.ViewNode("A","А","complete",1),new GraphCanvas.ViewNode("B","Б","partial",1));
        try {
            shell.open();shell.layout(true,true);while(display.readAndDispatch()){}
            view.setGraph(nodes,List.of(new GraphCanvas.ViewEdge("A","B",1,0,1)),positions,1,0);
            Canvas canvas=(Canvas)field(view,"canvas");
            double zoom=(double)field(view,"zoom"),ox=(double)field(view,"offsetX"),oy=(double)field(view,"offsetY");
            int ax=(int)Math.round(-60*zoom+ox),ay=(int)Math.round(oy);
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseMove,ax+25,ay+15);mouse(canvas,SWT.MouseUp,ax+65,ay+30);
            var release=commands.getLast();
            check(release.operation.equals("release")&&Math.abs(release.x-(-60+65/zoom))<1e-6&&Math.abs(release.y-30/zoom)<1e-6,"Final MouseUp coordinates must survive a coalesced movement");
            check(opened.isEmpty(),"A drag must not open a document");
            view.setGraph(nodes,List.of(),positions,0,0);
            mouse(canvas,SWT.MouseDown,ax,ay);canvas.notifyListeners(SWT.FocusOut,new Event());released(view,commands,"FocusOut");
            mouse(canvas,SWT.MouseDown,ax,ay);shell.notifyListeners(SWT.Deactivate,new Event());released(view,commands,"Deactivate");
            mouse(canvas,SWT.MouseDown,ax,ay);check(display.getFocusControl()==canvas,"Canvas focus enables Escape");
            var escape=new Event();escape.keyCode=SWT.ESC;canvas.notifyListeners(SWT.KeyDown,escape);released(view,commands,"Escape");
            mouse(canvas,SWT.MouseDown,ax,ay);mouse(canvas,SWT.MouseUp,ax,ay);check(opened.equals(List.of("A")),"A stationary click opens exactly its document");
            var wheel=new Event();wheel.count=2;wheel.x=300;wheel.y=250;canvas.notifyListeners(SWT.MouseWheel,wheel);
            double userZoom=(double)field(view,"zoom"),userOx=(double)field(view,"offsetX"),userOy=(double)field(view,"offsetY");
            view.setPositions(Map.of("A",new GraphLayout.Point(-85,10),"B",new GraphLayout.Point(80,15)));
            check(userZoom==(double)field(view,"zoom")&&userOx==(double)field(view,"offsetX")&&userOy==(double)field(view,"offsetY"),"Physics frames must preserve the user camera");
            System.out.println("GraphCanvas gestures, focus, click and camera: PASS");
            if(args.length>1)benchmark(view,canvas,Path.of(args[1]),args.length>2?Path.of(args[2]):null);
            view.setGraph(nodes,List.of(),positions,0,0);
            zoom=(double)field(view,"zoom");ox=(double)field(view,"offsetX");oy=(double)field(view,"offsetY");
            mouse(canvas,SWT.MouseDown,(int)Math.round(-60*zoom+ox),(int)Math.round(oy));view.dispose();
            check(commands.getLast().operation.equals("release"),"Disposal releases a pinned document");
            System.out.println("GraphCanvasTest: PASS");
        } finally {if(!shell.isDisposed())shell.dispose();display.dispose();}
    }
    private static void benchmark(GraphCanvas view,Canvas canvas,Path fixture,Path reportPath)throws Exception {
        var snapshot=Json.object(Files.readString(fixture));var rows=(List<?>)snapshot.get("nodes");var edgeRows=(List<?>)snapshot.get("edges");
        var nodes=rows.stream().map(row->{var n=Json.map(row);var d=Json.map(n.get("document"));return new GraphCanvas.ViewNode(Json.string(d.get("document_id")),Json.string(d.get("title")),Json.string(d.get("status")),((Number)n.get("degree")).intValue());}).toList();
        var links=edgeRows.stream().map(row->{var e=Json.map(row);return new GraphGeometry.Link(Json.string(e.get("source_id")),Json.string(e.get("target_id")));}).toList();
        var physics=new GraphPhysics(nodes.stream().map(GraphCanvas.ViewNode::id).toList(),links);
        for(int i=0;i<1000&&!physics.sleeping();i++)physics.step(1.0/60,()->false);
        var counts=new LinkedHashMap<String,int[]>();
        for(var link:links){String a=link.sourceId(),b=link.targetId();boolean forward=a.compareTo(b)<=0;String low=forward?a:b,high=forward?b:a;var count=counts.computeIfAbsent(low+'\0'+high,key->new int[3]);count[forward?0:1]++;count[2]++;}
        var edges=counts.entrySet().stream().map(entry->{String[] pair=entry.getKey().split("\u0000",-1);int[] c=entry.getValue();return new GraphCanvas.ViewEdge(pair[0],pair[1],c[0],c[1],c[2]);}).toList();
        view.setGraph(nodes,edges,physics.snapshot(),links.size(),0);
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
