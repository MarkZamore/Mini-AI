package ai.enterprise.graph;

import ai.enterprise.contracts.Api;
import ai.enterprise.shell.UiHost;
import ai.enterprise.shell.UiTheme;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;

/** Real SWT embedding shutdown; pending futures are controlled entirely in memory. */
public final class GraphPanelLifecycleTest {
    public static void main(String[] args)throws Exception {
        Path nativeDirectory=Path.of(args.length>0?args[0]:"src/build/native").toAbsolutePath();
        System.setProperty("swt.library.path",nativeDirectory.toString());
        var display=new Display();var shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(850,600);
        try {
            shell.open();
            pendingSnapshot(display,shell);
            activeGraphAndSource(display,shell);
            System.out.println("GraphPanelLifecycleTest: PASS — closed contexts keep the caller host alive and reject late graph/source/pulse updates");
        } finally {if(!shell.isDisposed())shell.dispose();display.dispose();}
    }

    private static void pendingSnapshot(Display display,Shell shell)throws Exception {
        var executor=Executors.newFixedThreadPool(2);var platform=new Platform();
        var context=new Api.Context(new Api.Environment(Path.of("."),executor,platform));
        var graph=new Graph();context.register(Api.GraphServiceV1.class,graph);
        var host=new Composite(shell,SWT.NONE);host.setLayout(new FillLayout());
        var recorder=new Host(display);UiHost.install(host,recorder);
        var view=(GraphCanvas)new GraphPanelFactory().create(host,context);shell.layout(true,true);
        try {
            check(graph.token!=null&&!graph.token.cancelled(),"Graph request is initially pending and active");
            context.close();
            check(!host.isDisposed()&&!view.isDisposed()&&!view.isEnabled(),"Context shutdown disables its view while preserving the foreign host");
            check(graph.token.cancelled()&&executor.isShutdown()&&platform.closes==1,"Context shutdown cancels the graph lifetime and caller resources");
            graph.future.complete(Api.Result.ok(snapshot()));pump(display,160);
            check(((List<?>)field(view,"nodes")).isEmpty()&&view.getData("enterpriseai.graph.physics.revision")==null,
                    "A late successful snapshot cannot populate or start a closed embedded component");
            check(recorder.errors.isEmpty()&&recorder.selections==0,"Late snapshot callbacks produce neither errors nor host selection updates");
            check(noPhysicsThread(),"Late snapshot completion creates no graph simulation thread");
        } finally {context.close();host.dispose();shell.layout(true,true);}
    }

    private static void activeGraphAndSource(Display display,Shell shell)throws Exception {
        var executor=Executors.newFixedThreadPool(2);var platform=new Platform();
        var context=new Api.Context(new Api.Environment(Path.of("."),executor,platform));
        var graph=new Graph();var sources=new Sources();
        context.register(Api.GraphServiceV1.class,graph);context.register(Api.SourceViewerServiceV1.class,sources);
        var host=new Composite(shell,SWT.NONE);host.setLayout(new FillLayout());
        var recorder=new Host(display);UiHost.install(host,recorder);
        var view=(GraphCanvas)new GraphPanelFactory().create(host,context);shell.layout(true,true);
        try {
            graph.future.complete(Api.Result.ok(snapshot()));
            await(display,()->((List<?>)uncheckedField(view,"nodes")).size()==2,2_000);
            Canvas canvas=(Canvas)field(view,"canvas");
            var positions=(Map<?,?>)field(view,"positions");var point=(GraphLayout.Point)positions.get("A");
            double zoom=(double)field(view,"zoom"),ox=(double)field(view,"offsetX"),oy=(double)field(view,"offsetY");
            int x=(int)Math.round(point.x()*zoom+ox),y=(int)Math.round(point.y()*zoom+oy);
            mouse(canvas,SWT.MouseDown,x,y);mouse(canvas,SWT.MouseUp,x,y);
            mouse(canvas,SWT.MouseDoubleClick,x,y);mouse(canvas,SWT.MouseUp,x,y);
            check(sources.calls==1&&sources.token!=null&&!sources.token.cancelled(),"Real double click starts one pending original-source request");
            var before=Map.copyOf((Map<?,?>)field(view,"positions"));
            Object revision=view.getData("enterpriseai.graph.physics.revision");int selections=recorder.selections;
            context.close();
            check(!host.isDisposed()&&!view.isDisposed()&&!view.isEnabled(),"Closing a live graph keeps its caller's SWT host intact");
            check(sources.token.cancelled()&&graph.token.cancelled(),"Closing a live graph cancels source and graph lifetimes");
            sources.future.complete(Api.Result.fail("LATE_SOURCE_ERROR","A late error must not open a dialog"));
            pump(display,220);
            check(before.equals(field(view,"positions"))&&java.util.Objects.equals(revision,view.getData("enterpriseai.graph.physics.revision")),
                    "Queued simulation polling cannot publish positions or revisions after context shutdown");
            check((int)canvas.getData("enterpriseai.graph.pulse.level")==0&&noPhysicsThread(),
                    "Context shutdown stops the colour controller and simulation while its host remains alive");
            mouse(canvas,SWT.MouseDown,x,y);mouse(canvas,SWT.MouseMove,x+20,y);mouse(canvas,SWT.MouseUp,x+20,y);
            mouse(canvas,SWT.MouseDoubleClick,x,y);mouse(canvas,SWT.MouseUp,x,y);view.fit();pump(display,60);
            check(before.equals(field(view,"positions"))&&sources.calls==1&&recorder.selections==selections&&recorder.errors.isEmpty(),
                    "A closed embedded view rejects drag, source, selection and late error callbacks");
            check(executor.isShutdown()&&platform.closes==1,"Caller resources close exactly once independently of its host window");
        } finally {context.close();host.dispose();shell.layout(true,true);}
    }

    private static Api.GraphSnapshot snapshot() {
        var nodes=List.of("A","B").stream().map(id->new Api.GraphNode(new Api.DocumentSummary(id,
                "Документ "+id,"sha","","","complete",List.of(),List.of()),0,16)).toList();
        return new Api.GraphSnapshot(nodes,List.of(),0);
    }
    private static boolean noPhysicsThread() {
        return Thread.getAllStackTraces().keySet().stream().noneMatch(thread->thread.isAlive()&&thread.getName().equals("enterpriseai-graph-physics"));
    }
    private static void await(Display display,BooleanSupplier condition,long milliseconds) {
        long deadline=System.nanoTime()+milliseconds*1_000_000L;
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline)throw new AssertionError("Embedded graph setup timed out");
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
    private static void mouse(Canvas canvas,int type,int x,int y) {var event=new Event();event.button=1;event.x=x;event.y=y;canvas.notifyListeners(type,event);}
    private static Object field(Object object,String name)throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static Object uncheckedField(Object object,String name) {try{return field(object,name);}catch(Exception error){throw new RuntimeException(error);}}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    private static final class Host implements UiHost {
        final UiTheme theme;final List<Api.ServiceError> errors=new ArrayList<>();int selections;
        Host(Display display){theme=UiTheme.get(display);}
        public UiTheme theme(){return theme;}
        public void error(String id,Api.ServiceError error){errors.add(error);}
        public void selectedDocument(String id,String title){selections++;}
    }
    private static final class Graph implements Api.GraphServiceV1 {
        final CompletableFuture<Api.Result<Api.GraphSnapshot>> future=new CompletableFuture<>();Api.CancellationToken token;
        public CompletionStage<Api.Result<Api.GraphSnapshot>> snapshot(Api.CancellationToken token){this.token=token;return future;}
        public CompletionStage<Api.Result<List<Api.GraphEdge>>> neighbors(String id,String relation,int limit,Api.CancellationToken token){throw new UnsupportedOperationException();}
    }
    private static final class Sources implements Api.SourceViewerServiceV1 {
        final CompletableFuture<Api.Result<Void>> future=new CompletableFuture<>();Api.CancellationToken token;int calls;
        public CompletionStage<Api.Result<Void>> openDocument(String id,Api.CancellationToken token){calls++;this.token=token;return future;}
        public CompletionStage<Api.Result<Void>> openSource(Api.SourceRef ref,Api.CancellationToken token){throw new UnsupportedOperationException();}
    }
    private static final class Platform implements Api.Platform {
        int closes;
        public Path executablePath(){return Path.of("миниИИ.exe").toAbsolutePath();}
        public long availableMemoryBytes(){return 0;}
        public void openWith(Path original){throw new AssertionError("No external program belongs to this in-memory fixture");}
        public Process launchWorker(Path executable,Path directory,long maxMemory){throw new AssertionError("No inference worker belongs to this UI fixture");}
        public void close(){closes++;}
    }
}
