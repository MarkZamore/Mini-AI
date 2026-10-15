package ai.enterprise.graph;

import ai.enterprise.contracts.Api;
import ai.enterprise.shell.PanelFactory;
import ai.enterprise.shell.UiHost;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

public final class GraphPanelFactory implements PanelFactory {
    public String id() { return "graph"; }
    public String title() { return "Граф"; }
    public String slot() { return "graph"; }

    public Composite create(Composite parent, Api.Context context) {
        GraphCanvas view = new GraphCanvas(parent, SWT.NONE);
        UiHost host = UiHost.find(parent);
        Display display = parent.getDisplay();
        Api.CancellationToken lifetime = new Api.CancellationToken();
        view.addDisposeListener(event -> lifetime.cancel());
        context.own(lifetime::cancel);
        lifetime.onCancel(() -> ui(display, () -> { if (!view.isDisposed()) view.stopAnimation(); }));
        view.onSelectedDocument((id, title) -> { if (!lifetime.cancelled()) host.selectedDocument(id, title); });
        AtomicLong sourceOperations = new AtomicLong();
        view.onOpenDocument(documentId -> {
            if (lifetime.cancelled() || view.isDisposed()) return;
            String operationId = "graph-source-" + sourceOperations.incrementAndGet();
            var sources = context.find(Api.SourceViewerServiceV1.class);
            if (sources.isEmpty()) {
                host.error(operationId, new Api.ServiceError("SOURCES_UNAVAILABLE",
                        "Открытие оригиналов недоступно: модуль источников отсутствует."));
                return;
            }
            sources.get().openDocument(documentId, lifetime).whenComplete((result, failure) -> {
                if (lifetime.cancelled()) return;
                ui(display, () -> {
                    if (view.isDisposed() || lifetime.cancelled()) return;
                    Api.ServiceError error = failure != null ? Api.failure(failure).error()
                            : !result.success() ? result.error() : null;
                    if (error != null && !error.code().equals("CANCELLED")) host.error(operationId, error);
                });
            });
        });
        if (lifetime.cancelled()) return view;
        var graph = context.find(Api.GraphServiceV1.class);
        if (graph.isEmpty()) {
            view.stopLoading();
            host.error("graph-load", new Api.ServiceError("GRAPH_UNAVAILABLE",
                    "Граф недоступен: сервис графа не зарегистрирован."));
            return view;
        }
        graph.get().snapshot(lifetime).thenCompose(result -> {
            if (lifetime.cancelled()) return java.util.concurrent.CompletableFuture.completedFuture(
                    Api.Result.<Presentation>fail("CANCELLED", "Компонент завершён"));
            if (!result.success()) return java.util.concurrent.CompletableFuture.completedFuture(
                    Api.Result.<Presentation>fail(result.error().code(), result.error().message()));
            return context.async(lifetime, () -> {
                var snapshot = result.value();
                var links = snapshot.edges().stream().map(e -> new GraphGeometry.Link(e.sourceId(), e.targetId())).toList();
                var physics=new GraphPhysics(snapshot.nodes().stream().map(Api.GraphNode::documentId).toList(),links);
                var nodes = snapshot.nodes().stream().map(n -> new GraphCanvas.ViewNode(n.documentId(),
                        n.document().title(), n.degree())).toList();
                var edges = GraphTopology.aggregate(snapshot.edges()).stream().map(e -> new GraphCanvas.ViewEdge(
                        e.a(), e.b())).toList();
                return new Presentation(nodes, edges, physics);
            });
        }).whenComplete((result, failure) -> {
            if (lifetime.cancelled()) return;
            ui(display, () -> {
                if (view.isDisposed() || lifetime.cancelled()) return;
                if (failure != null || !result.success()) {
                    view.stopLoading();
                    Api.ServiceError error = failure != null ? Api.failure(failure).error() : result.error();
                    if (!error.code().equals("CANCELLED")) host.error("graph-load", error);
                }
                else {
                    Presentation p = result.value();
                    view.setGraph(p.nodes(), p.edges(), p.physics().snapshot());
                    var simulation=new GraphSimulation(p.physics());
                    context.own(simulation);
                    lifetime.onCancel(simulation::close);
                    long[] lastRevision={-1};
                    boolean[] pollQueued={false};
                    boolean[] initialSettled={false};
                    Runnable[] poll={null};
                    Runnable ensurePoll=()->{
                        if(!lifetime.cancelled()&&!view.isDisposed()&&!simulation.closed()&&!pollQueued[0]){pollQueued[0]=true;display.timerExec(33,poll[0]);}
                    };
                    poll[0]=()->{
                        pollQueued[0]=false;
                        if(lifetime.cancelled()||view.isDisposed()){simulation.close();return;}
                        if(simulation.failure()!=null){host.error("graph-physics",Api.failure(simulation.failure()).error());simulation.close();return;}
                        if(simulation.closed())return;
                        var frame=simulation.frame();
                        view.setPhysicsActive(frame.active());
                        if(frame.revision()!=lastRevision[0]){
                            lastRevision[0]=frame.revision();view.setPositions(frame.positions());
                            view.setData("enterpriseai.graph.physics.revision",frame.revision());
                            view.setData("enterpriseai.graph.physics.active",frame.active());
                        }
                        if(simulation.active())ensurePoll.run();
                        else if(!initialSettled[0]){initialSettled[0]=true;view.settled();}
                    };
                    lifetime.onCancel(() -> ui(display, () -> {
                        display.timerExec(-1, poll[0]);
                        pollQueued[0] = false;
                    }));
                    view.onDrag(new GraphCanvas.DragListener(){
                        public void pin(String id,double x,double y){if(!lifetime.cancelled()){simulation.pin(id,x,y);ensurePoll.run();}}
                        public void release(String id,double x,double y){if(!lifetime.cancelled()){simulation.release(id,x,y);ensurePoll.run();}}
                    });
                    ensurePoll.run();
                }
            });
        });
        return view;
    }
    private static void ui(Display display, Runnable action) {
        if (display.isDisposed()) return;
        try {
            if (Display.getCurrent() == display) action.run();
            else display.asyncExec(action);
        } catch (org.eclipse.swt.SWTException error) {
            if (error.code != SWT.ERROR_DEVICE_DISPOSED) throw error;
        }
    }
    private record Presentation(List<GraphCanvas.ViewNode> nodes, List<GraphCanvas.ViewEdge> edges,
                                GraphPhysics physics) {}
}
