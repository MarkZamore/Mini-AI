package ai.enterprise.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/** SWT rendering adapter only. It owns no service, database, worker, or disk state. */
public final class GraphCanvas extends Composite {
    public record ViewNode(String id, String title, String status, int degree) {}
    public record ViewEdge(String a, String b, int aToB, int bToA, int evidenceCount) {}
    public interface DragListener { void pin(String id,double x,double y); void release(String id,double x,double y); }
    private final Canvas canvas;
    private final Label information;
    private List<ViewNode> nodes = List.of();
    private List<ViewEdge> edges = List.of();
    private final Map<String, GraphLayout.Point> positions = new HashMap<>();
    private final Map<String,ViewNode> nodeById=new HashMap<>();
    private final Map<String,Double> radii=new HashMap<>();
    private Consumer<String> openDocument = id -> {};
    private double zoom = 1.0, offsetX, offsetY;
    private double downX, downY, previousX, previousY;
    private double grabX,grabY,pinnedX,pinnedY;
    private String pressedNode, selectedNode;
    private boolean buttonDown, drag;
    private boolean cameraChanged;
    private DragListener dragListener=new DragListener(){public void pin(String id,double x,double y){}public void release(String id,double x,double y){}};
    private String busy = "Загрузка графа…";
    private int confirmedCount;

    public GraphCanvas(Composite parent, int style) {
        super(parent, style);
        setLayout(new GridLayout(1, false));
        Composite toolbar = new Composite(this, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout toolbarLayout = new GridLayout(2, false);
        toolbarLayout.marginWidth = toolbarLayout.marginHeight = 0;
        toolbar.setLayout(toolbarLayout);
        Button fit = new Button(toolbar, SWT.PUSH);
        fit.setText("Весь граф");
        fit.addListener(SWT.Selection, e -> fit());
        Label hint = new Label(toolbar, SWT.WRAP);
        hint.setText("Магнит к центру · Узлы движутся вместе со связями · Колесо — масштаб · Нажатие — оригинал");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        canvas = new Canvas(this, SWT.DOUBLE_BUFFERED);
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        canvas.setBackground(getDisplay().getSystemColor(SWT.COLOR_WHITE));
        information = new Label(this, SWT.WRAP);
        information.setText(busy);
        GridData informationData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        informationData.heightHint = 42;
        information.setLayoutData(informationData);
        canvas.addPaintListener(this::paint);
        canvas.addListener(SWT.Resize, e -> { if (!cameraChanged && !buttonDown) fit(); });
        canvas.addListener(SWT.MouseWheel, e -> {
            cameraChanged=true;
            double old = zoom;
            zoom = Math.max(0.04, Math.min(8.0, zoom * Math.pow(1.12, e.count)));
            offsetX = e.x - (e.x - offsetX) * zoom / old;
            offsetY = e.y - (e.y - offsetY) * zoom / old;
            canvas.redraw();
        });
        canvas.addListener(SWT.MouseDown, e -> {
            if (e.button != 1) return;
            canvas.setFocus();
            buttonDown = true;
            drag = false;
            downX = previousX = e.x;
            downY = previousY = e.y;
            pressedNode = hit(e.x, e.y);
            if(pressedNode!=null){
                GraphLayout.Point p=positions.get(pressedNode);
                pinnedX=p.x();pinnedY=p.y();
                grabX=p.x()-(e.x-offsetX)/zoom;grabY=p.y()-(e.y-offsetY)/zoom;
                dragListener.pin(pressedNode,pinnedX,pinnedY);
            }
            canvas.setCapture(true);
        });
        canvas.addListener(SWT.MouseMove, e -> {
            if (!buttonDown) {
                String hovered = hit(e.x, e.y);
                canvas.setToolTipText(hovered == null ? null : nodeDescription(hovered));
                return;
            }
            drag |= GraphGeometry.isDrag(downX, downY, e.x, e.y);
            if (drag) {
                double dx = e.x - previousX, dy = e.y - previousY;
                if (pressedNode == null) { offsetX += dx; offsetY += dy; cameraChanged=true; }
                else {
                    cameraChanged=true;
                    pinnedX=(e.x-offsetX)/zoom+grabX;pinnedY=(e.y-offsetY)/zoom+grabY;
                    positions.put(pressedNode,new GraphLayout.Point(pinnedX,pinnedY));
                    dragListener.pin(pressedNode,pinnedX,pinnedY);
                }
                canvas.redraw();
            }
            previousX = e.x; previousY = e.y;
        });
        canvas.addListener(SWT.MouseUp, e -> {
            if (e.button != 1 || !buttonDown) return;
            drag|=GraphGeometry.isDrag(downX,downY,e.x,e.y);
            if(drag&&pressedNode!=null){pinnedX=(e.x-offsetX)/zoom+grabX;pinnedY=(e.y-offsetY)/zoom+grabY;positions.put(pressedNode,new GraphLayout.Point(pinnedX,pinnedY));}
            else if(drag){offsetX+=e.x-previousX;offsetY+=e.y-previousY;cameraChanged=true;}
            buttonDown = false;
            canvas.setCapture(false);
            if(pressedNode!=null)dragListener.release(pressedNode,pinnedX,pinnedY);
            if (!drag && pressedNode != null && pressedNode.equals(hit(e.x, e.y))) {
                selectedNode = pressedNode;
                information.setText(nodeDescription(selectedNode));
                canvas.redraw();
                openDocument.accept(selectedNode);
            }
            pressedNode = null;
        });
        canvas.addListener(SWT.KeyDown, e -> {
            if (e.keyCode == SWT.ESC) cancelGesture();
            if (e.keyCode == SWT.HOME) fit();
        });
        canvas.addListener(SWT.FocusOut,e->cancelGesture());
        org.eclipse.swt.widgets.Listener deactivate=e->{if(!canvas.isDisposed())cancelGesture();};
        var hostShell=getShell();
        hostShell.addListener(SWT.Deactivate,deactivate);
        addDisposeListener(e->{if(!canvas.isDisposed())cancelGesture();if(!hostShell.isDisposed())hostShell.removeListener(SWT.Deactivate,deactivate);});
    }

    public void onOpenDocument(Consumer<String> action) { openDocument = action; }
    private void cancelGesture(){
        if(buttonDown){if(pressedNode!=null)dragListener.release(pressedNode,pinnedX,pinnedY);buttonDown=false;pressedNode=null;canvas.setCapture(false);}
    }
    public void onDrag(DragListener listener){dragListener=listener;}
    /** Consume the latest physics frame; the camera never moves with a physics update. */
    public void setPositions(Map<String,GraphLayout.Point> frame){
        positions.clear();positions.putAll(frame);
        if(buttonDown&&pressedNode!=null)positions.put(pressedNode,new GraphLayout.Point(pinnedX,pinnedY));
        canvas.redraw();
    }

    public void setGraph(List<ViewNode> documents, List<ViewEdge> relationships,
                         Map<String, GraphLayout.Point> layout, int confirmedEdges, long unresolvedCount) {
        nodes = List.copyOf(documents);
        edges = List.copyOf(relationships);
        positions.clear(); positions.putAll(layout);
        nodeById.clear();radii.clear();
        for(ViewNode node:nodes){nodeById.put(node.id(),node);radii.put(node.id(),GraphGeometry.radius(node.degree()));}
        confirmedCount = confirmedEdges;
        selectedNode = null;
        cameraChanged=false;
        busy = null;
        long isolated = nodes.stream().filter(n -> n.degree() == 0).count();
        information.setText("Документов: " + nodes.size() + " · Подтверждений связей: " + confirmedCount
                + " · Линий: " + edges.size() + " · Без связей: " + isolated
                + "\nНеподтверждённых ссылок: " + unresolvedCount + ". Они не изображаются как связи.");
        fit();
    }

    public void setMessage(String message) {
        information.setText(message);
        if (nodes.isEmpty()) busy = message;
        canvas.redraw();
    }

    public void fit() {
        if (positions.isEmpty() || canvas.isDisposed()) return;
        Rectangle area = canvas.getClientArea();
        if (area.width < 2 || area.height < 2) return;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (ViewNode n : nodes) {
            GraphLayout.Point p = positions.get(n.id());
            if (p == null) continue;
            double r = GraphGeometry.radius(n.degree());
            minX = Math.min(minX, p.x() - r); maxX = Math.max(maxX, p.x() + r);
            minY = Math.min(minY, p.y() - r); maxY = Math.max(maxY, p.y() + r);
        }
        zoom = Math.max(0.04, Math.min(2.0, Math.min((area.width - 40.0) / Math.max(1.0, maxX - minX),
                (area.height - 40.0) / Math.max(1.0, maxY - minY))));
        offsetX = area.width / 2.0 - (minX + maxX) * zoom / 2.0;
        offsetY = area.height / 2.0 - (minY + maxY) * zoom / 2.0;
        canvas.redraw();
    }

    private void paint(PaintEvent e) {
        GC gc = e.gc;
        // Ordinary GDI keeps a 599-node interactive frame below the 33 ms UI budget.
        // Calling setAntialias(OFF) still initializes the much slower SWT GDI+ backend.
        gc.setAdvanced(false);
        Rectangle viewport=canvas.getClientArea();
        if (busy != null) { gc.drawText(busy, 20, 20, true); return; }
        gc.setLineWidth(1);
        for (ViewEdge edge : edges) {
            GraphLayout.Point a = positions.get(edge.a()), b = positions.get(edge.b());
            if (a == null || b == null) continue;
            gc.setForeground(getDisplay().getSystemColor(
                    edge.a().equals(selectedNode) || edge.b().equals(selectedNode) ? SWT.COLOR_DARK_BLUE : SWT.COLOR_GRAY));
            int ax = screenX(a.x()), ay = screenY(a.y()), bx = screenX(b.x()), by = screenY(b.y());
            int edgeMargin=(int)Math.ceil(Math.max(radiusFor(edge.a()),radiusFor(edge.b()))*zoom)+10;
            if(Math.max(ax,bx)+edgeMargin<0||Math.min(ax,bx)-edgeMargin>viewport.width
                    ||Math.max(ay,by)+edgeMargin<0||Math.min(ay,by)-edgeMargin>viewport.height)continue;
            if (edge.a().equals(edge.b())) {
                int radius = (int) Math.round(radiusFor(edge.a()) * zoom);
                gc.drawArc(ax - radius, ay - radius * 2, Math.max(6, radius * 2), Math.max(6, radius * 2), 0, 300);
                continue;
            }
            gc.drawLine(ax, ay, bx, by);
            if (zoom > 0.35 || edge.a().equals(selectedNode) || edge.b().equals(selectedNode)) {
                if (edge.aToB() > 0) arrow(gc, ax, ay, bx, by, radiusFor(edge.b()) * zoom);
                if (edge.bToA() > 0) arrow(gc, bx, by, ax, ay, radiusFor(edge.a()) * zoom);
                if (edge.evidenceCount() > 1) {
                    gc.drawText(Integer.toString(edge.evidenceCount()), (ax + bx) / 2, (ay + by) / 2, true);
                }
            }
        }
        for (ViewNode node : nodes) {
            GraphLayout.Point p = positions.get(node.id());
            if (p == null) continue;
            int r = Math.max(2, (int) Math.round(GraphGeometry.radius(node.degree()) * zoom));
            int x = screenX(p.x()), y = screenY(p.y());
            boolean selected = node.id().equals(selectedNode) || (buttonDown&&node.id().equals(pressedNode));
            if(!selected&&(x+r<0||x-r>viewport.width||y+r<0||y-r>viewport.height))continue;
            gc.setBackground(getDisplay().getSystemColor(selected ? SWT.COLOR_YELLOW
                    : node.degree() == 0 ? SWT.COLOR_WIDGET_LIGHT_SHADOW : SWT.COLOR_LIST_SELECTION));
            gc.setForeground(getDisplay().getSystemColor(selected ? SWT.COLOR_DARK_BLUE : SWT.COLOR_DARK_GRAY));
            gc.setLineWidth(selected ? 2 : 1);
            gc.fillOval(x - r, y - r, r * 2, r * 2);
            gc.drawOval(x - r, y - r, r * 2, r * 2);
            if (selected) {
                String title = node.title().length() > 70 ? node.title().substring(0, 67) + "…" : node.title();
                Point size = gc.textExtent(title);
                gc.drawText(title, x - size.x / 2, y + r + 3, true);
            }
        }
    }

    private String hit(double screenX, double screenY) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            ViewNode n = nodes.get(i);
            GraphLayout.Point p = positions.get(n.id());
            if (p != null && Math.hypot(screenX - screenX(p.x()), screenY - screenY(p.y()))
                    <= Math.max(4.0, GraphGeometry.radius(n.degree()) * zoom)) return n.id();
        }
        return null;
    }

    private String nodeDescription(String id) {
        ViewNode n=nodeById.get(id);
        if(n!=null){
            return n.title() + "\nУникальных соседей: " + n.degree() + " · Извлечение: " + n.status()
                    + " · Машинный текст может требовать сверки с оригиналом";
        }
        return id;
    }
    private double radiusFor(String id) {
        return radii.getOrDefault(id,16.0);
    }
    private int screenX(double x) { return (int) Math.round(x * zoom + offsetX); }
    private int screenY(double y) { return (int) Math.round(y * zoom + offsetY); }
    private void arrow(GC gc, double ax, double ay, double bx, double by, double targetRadius) {
        double distance = Math.hypot(bx - ax, by - ay);
        if (distance < 1.0) return;
        double ux = (bx - ax) / distance, uy = (by - ay) / distance;
        double tipX = bx - ux * targetRadius, tipY = by - uy * targetRadius;
        double length = 7.0;
        gc.drawLine((int) tipX, (int) tipY, (int) (tipX - ux * length - uy * length / 2),
                (int) (tipY - uy * length + ux * length / 2));
        gc.drawLine((int) tipX, (int) tipY, (int) (tipX - ux * length + uy * length / 2),
                (int) (tipY - uy * length - ux * length / 2));
    }
}
