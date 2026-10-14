package ai.enterprise.graph;

import ai.enterprise.contracts.Api;
import ai.enterprise.shell.PanelFactory;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

public final class GraphPanelFactory implements PanelFactory {
    public String id() { return "graph"; }
    public String title() { return "Граф документов"; }
    public String slot() { return "graph"; }

    public Composite create(Composite parent, Api.Context context) {
        GraphCanvas view = new GraphCanvas(parent, SWT.NONE);
        Display display = parent.getDisplay();
        Api.CancellationToken lifetime = new Api.CancellationToken();
        view.addDisposeListener(event -> lifetime.cancel());
        view.onOpenDocument(documentId -> {
            var sources = context.find(Api.SourceViewerServiceV1.class);
            if (sources.isEmpty()) {
                view.setMessage("Открытие оригиналов недоступно: модуль источников отсутствует");
                return;
            }
            view.setMessage("Проверка версии оригинала…");
            sources.get().openDocument(documentId, lifetime).whenComplete((result, failure) -> {
                if (display.isDisposed()) return;
                display.asyncExec(() -> {
                    if (view.isDisposed()) return;
                    if (failure != null) view.setMessage("Ошибка открытия: " + failure.getMessage());
                    else if (!result.success()) view.setMessage(result.error().message());
                    else view.setMessage("Оригинал передан в диалог Windows. Внешняя программа может сохранять собственные данные.");
                });
            });
        });
        var graph = context.find(Api.GraphServiceV1.class);
        if (graph.isEmpty()) { view.setMessage("Граф недоступен: сервис графа не зарегистрирован"); return view; }
        graph.get().snapshot(lifetime).thenCompose(result -> {
            if (!result.success()) return java.util.concurrent.CompletableFuture.completedFuture(
                    Api.Result.<Presentation>fail(result.error().code(), result.error().message()));
            return context.async(lifetime, () -> {
                var snapshot = result.value();
                var links = snapshot.edges().stream().map(e -> new GraphGeometry.Link(e.sourceId(), e.targetId())).toList();
                var physics=new GraphPhysics(snapshot.nodes().stream().map(Api.GraphNode::documentId).toList(),links);
                var nodes = snapshot.nodes().stream().map(n -> new GraphCanvas.ViewNode(n.documentId(),
                        n.document().title(), n.document().status(), n.degree())).toList();
                var edges = GraphTopology.aggregate(snapshot.edges()).stream().map(e -> new GraphCanvas.ViewEdge(
                        e.a(), e.b(), e.aToB(), e.bToA(), e.evidenceIds().size())).toList();
                return new Presentation(nodes, edges, physics, snapshot.edges().size(), snapshot.unresolvedCount());
            });
        }).whenComplete((result, failure) -> {
            if (display.isDisposed()) return;
            display.asyncExec(() -> {
                if (view.isDisposed()) return;
                if (failure != null) view.setMessage("Ошибка графа: " + failure.getMessage());
                else if (!result.success()) view.setMessage(result.error().message());
                else {
                    Presentation p = result.value();
                    view.setGraph(p.nodes(), p.edges(), p.physics().snapshot(), p.confirmedEdges(), p.unresolvedCount());
                    var simulation=new GraphSimulation(p.physics());
                    context.own(simulation);
                    lifetime.onCancel(simulation::close);
                    long[] lastRevision={-1};
                    boolean[] pollQueued={false};
                    Runnable[] poll={null};
                    Runnable ensurePoll=()->{
                        if(!view.isDisposed()&&!simulation.closed()&&!pollQueued[0]){pollQueued[0]=true;display.timerExec(33,poll[0]);}
                    };
                    poll[0]=()->{
                        pollQueued[0]=false;
                        if(view.isDisposed()){simulation.close();return;}
                        if(simulation.failure()!=null){view.setMessage("Ошибка физики графа: "+simulation.failure().getMessage());simulation.close();return;}
                        var frame=simulation.frame();
                        if(frame.revision()!=lastRevision[0]){lastRevision[0]=frame.revision();view.setPositions(frame.positions());}
                        if(simulation.active())ensurePoll.run();
                    };
                    view.onDrag(new GraphCanvas.DragListener(){
                        public void pin(String id,double x,double y){simulation.pin(id,x,y);ensurePoll.run();}
                        public void release(String id,double x,double y){simulation.release(id,x,y);ensurePoll.run();}
                    });
                    ensurePoll.run();
                }
            });
        });
        return view;
    }
    private record Presentation(List<GraphCanvas.ViewNode> nodes, List<GraphCanvas.ViewEdge> edges,
                                GraphPhysics physics, int confirmedEdges,
                                long unresolvedCount) {}
}
