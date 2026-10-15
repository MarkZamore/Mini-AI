package ai.enterprise.graph;

import ai.enterprise.shell.SoftButton;
import ai.enterprise.shell.UiHost;
import ai.enterprise.shell.UiTheme;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/** SWT rendering and gestures only. Camera changes never issue physics commands. */
public final class GraphCanvas extends Composite {
    public record ViewNode(String id, String title, int degree) {}
    public record ViewEdge(String a, String b) {}
    public interface DragListener {
        void pin(String id, double x, double y);
        void release(String id, double x, double y);
    }

    private final Canvas canvas;
    private final SoftButton fitButton;
    private final UiTheme theme;
    private final GraphPulse pulse;
    private List<ViewNode> nodes = List.of();
    private List<ViewEdge> edges = List.of();
    private final Map<String, GraphLayout.Point> positions = new HashMap<>();
    private final Map<String, ViewNode> nodeById = new HashMap<>();
    private final Map<String, Double> radii = new HashMap<>();
    private int maxDegree, pulseLevel;
    private boolean physicsActive = true, stopped;
    private Consumer<String> openDocument = id -> {};
    private BiConsumer<String, String> selectDocument = (id, title) -> {};
    private DragListener dragListener = new DragListener() {
        public void pin(String id, double x, double y) {}
        public void release(String id, double x, double y) {}
    };
    private double zoom = 1.0, offsetX, offsetY;
    private double downX, downY, previousX, previousY;
    private double grabX, grabY, pinnedX, pinnedY;
    private String pressedNode, selectedNode, doubleClickNode;
    private boolean buttonDown, drag, cameraChanged;
    private String busy = "Загрузка графа…";

    public GraphCanvas(Composite parent, int style) {
        super(parent, style);
        theme = UiHost.find(parent).theme();
        theme.apply(this);
        setData("enterpriseai.ui.graph", "panel");
        setLayout(new FillLayout());
        canvas = new Canvas(this, SWT.DOUBLE_BUFFERED);
        theme.apply(canvas);
        canvas.setBackground(theme.panel());
        canvas.setData("enterpriseai.ui.graph", "canvas");
        fitButton = new SoftButton(canvas, theme, "Граф", false);
        fitButton.setData("enterpriseai.ui.graph", "fit");
        fitButton.setData("enterpriseai.ui.graph-fit", Boolean.TRUE);
        fitButton.addListener(SWT.Selection, event -> fit());
        canvas.setData("enterpriseai.graph.pulse.level", 0);
        pulse = new GraphPulse(canvas,
                () -> !nodes.isEmpty() && !physicsActive && !(buttonDown && drag && pressedNode != null),
                level -> {
                    pulseLevel = level;
                    if (!canvas.isDisposed()) {
                        canvas.setData("enterpriseai.graph.pulse.level", level);
                        canvas.redraw();
                    }
                });
        canvas.addPaintListener(this::paint);
        canvas.addListener(SWT.Resize, event -> {
            positionFitButton();
            if (!cameraChanged && !buttonDown) fitBounds();
            pulse.refresh();
        });
        positionFitButton();
        canvas.addListener(SWT.MouseWheel, event -> {
            if (stopped) return;
            cameraChanged = true;
            double old = zoom;
            zoom = Math.max(0.04, Math.min(8.0, zoom * Math.pow(1.12, event.count)));
            offsetX = event.x - (event.x - offsetX) * zoom / old;
            offsetY = event.y - (event.y - offsetY) * zoom / old;
            canvas.redraw();
        });
        canvas.addListener(SWT.MouseDown, event -> {
            if (event.button == 1) beginGesture(event.x, event.y);
        });
        canvas.addListener(SWT.MouseDoubleClick, event -> {
            if (stopped || event.button != 1) return;
            // Win32's second press can arrive as MouseDoubleClick without MouseDown.
            if (!buttonDown) beginGesture(event.x, event.y);
            if (!drag && pressedNode != null && pressedNode.equals(hit(event.x, event.y))) {
                doubleClickNode = pressedNode;
            }
        });
        canvas.addListener(SWT.MouseMove, event -> {
            if (stopped) return;
            if (!buttonDown) {
                String hovered = hit(event.x, event.y);
                canvas.setToolTipText(hovered == null ? null : nodeById.get(hovered).title());
                return;
            }
            updateDrag(event.x, event.y);
            if (drag) moveGesture(event.x, event.y);
            previousX = event.x;
            previousY = event.y;
        });
        canvas.addListener(SWT.MouseUp, event -> {
            if (event.button != 1 || !buttonDown) return;
            updateDrag(event.x, event.y);
            if (drag) moveGesture(event.x, event.y);
            buttonDown = false;
            canvas.setCapture(false);
            if (drag && pressedNode != null) {
                dragListener.release(pressedNode, pinnedX, pinnedY);
            } else if (!drag) {
                String releasedNode = hit(event.x, event.y);
                if (pressedNode != null && pressedNode.equals(releasedNode)) {
                    select(pressedNode);
                    if (pressedNode.equals(doubleClickNode)) openDocument.accept(pressedNode);
                } else if (pressedNode == null && releasedNode == null) select(null);
            }
            pressedNode = doubleClickNode = null;
            pulse.refresh();
            canvas.redraw();
        });
        canvas.addListener(SWT.KeyDown, event -> {
            if (stopped) return;
            if (event.keyCode == SWT.ESC) cancelGesture();
            if (event.keyCode == SWT.HOME) fit();
        });
        canvas.addListener(SWT.FocusOut, event -> cancelGesture());
        var hostShell = getShell();
        org.eclipse.swt.widgets.Listener deactivate = event -> {
            if (!canvas.isDisposed()) cancelGesture();
        };
        hostShell.addListener(SWT.Deactivate, deactivate);
        addDisposeListener(event -> {
            pulse.close();
            if (!canvas.isDisposed()) cancelGesture();
            if (!hostShell.isDisposed()) hostShell.removeListener(SWT.Deactivate, deactivate);
        });
    }

    public void onOpenDocument(Consumer<String> action) { openDocument = action; }
    public void onSelectedDocument(BiConsumer<String, String> action) { selectDocument = action; }
    public void onDrag(DragListener listener) { dragListener = listener; }

    public void setPhysicsActive(boolean active) {
        if (stopped) return;
        if (physicsActive != active) { physicsActive = active; pulse.refresh(); }
    }

    /** Stop this component without disposing a caller-owned SWT host. UI-thread only. */
    public void stopAnimation() {
        stopped = true;
        pulse.close();
        cancelGesture();
        setEnabled(false);
    }

    /** A viewport child receives its own events, so the button cannot select a node. */
    private void positionFitButton() {
        Rectangle area = canvas.getClientArea();
        int width = Math.min(60, area.width), height = Math.min(30, area.height);
        fitButton.setBounds(Math.max(0, area.width - width - 12),
                Math.min(12, Math.max(0, area.height - height)), width, height);
        fitButton.setVisible(width > 0 && height > 0);
    }

    private void beginGesture(int x, int y) {
        if (stopped) return;
        if (buttonDown) cancelGesture();
        // Composite.setFocus() prefers its button child; node gestures need Canvas keys.
        canvas.forceFocus();
        buttonDown = true;
        drag = false;
        doubleClickNode = null;
        downX = previousX = x;
        downY = previousY = y;
        pressedNode = hit(x, y);
        if (pressedNode != null) {
            GraphLayout.Point point = positions.get(pressedNode);
            pinnedX = point.x();
            pinnedY = point.y();
            grabX = point.x() - (x - offsetX) / zoom;
            grabY = point.y() - (y - offsetY) / zoom;
        }
        canvas.setCapture(true);
    }

    private void moveGesture(int x, int y) {
        cameraChanged = true;
        if (pressedNode == null) {
            offsetX += x - previousX;
            offsetY += y - previousY;
        } else {
            pinnedX = (x - offsetX) / zoom + grabX;
            pinnedY = (y - offsetY) / zoom + grabY;
            positions.put(pressedNode, new GraphLayout.Point(pinnedX, pinnedY));
            dragListener.pin(pressedNode, pinnedX, pinnedY);
        }
        canvas.redraw();
    }

    private void updateDrag(int x, int y) {
        if (!drag && GraphGeometry.isDrag(downX, downY, x, y)) {
            drag = true;
            previousX = downX;
            previousY = downY;
            pulse.refresh();
        }
    }

    private void select(String id) {
        // Showing or hiding the fixed footer must not refit the second-click target.
        cameraChanged = true;
        selectedNode = id;
        selectDocument.accept(id, id == null ? "" : nodeById.get(id).title());
    }

    private void cancelGesture() {
        if (!buttonDown) return;
        if (drag && pressedNode != null) dragListener.release(pressedNode, pinnedX, pinnedY);
        buttonDown = false;
        pressedNode = doubleClickNode = null;
        canvas.setCapture(false);
        pulse.refresh();
        canvas.redraw();
    }

    /** Consume a physics frame without changing the camera or creating a drag. */
    public void setPositions(Map<String, GraphLayout.Point> frame) {
        if (stopped) return;
        positions.clear();
        positions.putAll(frame);
        if (buttonDown && drag && pressedNode != null) {
            positions.put(pressedNode, new GraphLayout.Point(pinnedX, pinnedY));
        }
        canvas.redraw();
    }

    public void setGraph(List<ViewNode> documents, List<ViewEdge> relationships,
                         Map<String, GraphLayout.Point> layout) {
        if (stopped) return;
        cancelGesture();
        nodes = List.copyOf(documents);
        edges = List.copyOf(relationships);
        positions.clear();
        positions.putAll(layout);
        nodeById.clear();
        radii.clear();
        maxDegree = 0;
        for (ViewNode node : nodes) {
            nodeById.put(node.id(), node);
            radii.put(node.id(), GraphGeometry.radius(node.degree()));
            maxDegree = Math.max(maxDegree, node.degree());
        }
        select(null);
        cameraChanged = false;
        busy = null;
        fitBounds();
        pulse.refresh();
    }

    public void stopLoading() { busy = null; canvas.redraw(); }

    /** Initial settling gets one final camera fit, unless the user has moved it. */
    public void settled() { if (!stopped && !cameraChanged && !buttonDown) fitBounds(); }

    public void fit() {
        if (stopped) return;
        cameraChanged = true;
        fitBounds();
    }

    private void fitBounds() {
        if (positions.isEmpty() || canvas.isDisposed()) return;
        Rectangle area = canvas.getClientArea();
        if (area.width < 2 || area.height < 2) return;
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (ViewNode node : nodes) {
            GraphLayout.Point point = positions.get(node.id());
            if (point == null) continue;
            double radius = GraphGeometry.radius(node.degree());
            minX = Math.min(minX, point.x() - radius);
            maxX = Math.max(maxX, point.x() + radius);
            minY = Math.min(minY, point.y() - radius);
            maxY = Math.max(maxY, point.y() + radius);
        }
        if (!Double.isFinite(minX)) return;
        zoom = Math.max(0.04, Math.min(2.0, Math.min((area.width - 40.0) / Math.max(1.0, maxX - minX),
                (area.height - 40.0) / Math.max(1.0, maxY - minY))));
        offsetX = area.width / 2.0 - (minX + maxX) * zoom / 2.0;
        offsetY = area.height / 2.0 - (minY + maxY) * zoom / 2.0;
        canvas.redraw();
    }

    private void paint(PaintEvent event) {
        GC gc = event.gc;
        // Ordinary GDI avoids initializing a slower GDI+ backend for every graph.
        gc.setAdvanced(false);
        Rectangle viewport = canvas.getClientArea();
        if (busy != null) {
            gc.setForeground(theme.muted());
            var extent = gc.textExtent(busy);
            gc.drawText(busy, Math.max(0, (viewport.width - extent.x) / 2),
                    Math.max(0, (viewport.height - extent.y) / 2), true);
            return;
        }
        gc.setLineWidth(1);
        for (ViewEdge edge : edges) {
            GraphLayout.Point a = positions.get(edge.a()), b = positions.get(edge.b());
            if (a == null || b == null) continue;
            gc.setForeground(edge.a().equals(selectedNode) || edge.b().equals(selectedNode)
                    ? theme.selectedEdge(pulseLevel) : theme.edge(pulseLevel));
            int ax = screenX(a.x()), ay = screenY(a.y()), bx = screenX(b.x()), by = screenY(b.y());
            int edgeMargin = (int) Math.ceil(Math.max(radiusFor(edge.a()), radiusFor(edge.b())) * zoom) + 10;
            if (Math.max(ax, bx) + edgeMargin < 0 || Math.min(ax, bx) - edgeMargin > viewport.width
                    || Math.max(ay, by) + edgeMargin < 0 || Math.min(ay, by) - edgeMargin > viewport.height) continue;
            if (edge.a().equals(edge.b())) {
                int radius = (int) Math.round(radiusFor(edge.a()) * zoom);
                gc.drawArc(ax - radius, ay - radius * 2, Math.max(6, radius * 2), Math.max(6, radius * 2), 0, 300);
            } else gc.drawLine(ax, ay, bx, by);
        }
        for (ViewNode node : nodes) {
            GraphLayout.Point point = positions.get(node.id());
            if (point == null) continue;
            int radius = Math.max(2, (int) Math.round(GraphGeometry.radius(node.degree()) * zoom));
            int x = screenX(point.x()), y = screenY(point.y());
            boolean selected = node.id().equals(selectedNode) || (buttonDown && drag && node.id().equals(pressedNode));
            if (!selected && (x + radius < 0 || x - radius > viewport.width
                    || y + radius < 0 || y - radius > viewport.height)) continue;
            gc.setBackground(theme.node(node.degree(), maxDegree, pulseLevel));
            gc.setForeground(selected ? theme.selectedBorder() : theme.nodeBorder());
            gc.setLineWidth(selected ? 3 : 1);
            gc.fillOval(x - radius, y - radius, radius * 2, radius * 2);
            gc.drawOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }

    private String hit(double x, double y) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            ViewNode node = nodes.get(i);
            GraphLayout.Point point = positions.get(node.id());
            if (point != null && Math.hypot(x - screenX(point.x()), y - screenY(point.y()))
                    <= Math.max(4.0, GraphGeometry.radius(node.degree()) * zoom)) return node.id();
        }
        return null;
    }

    private double radiusFor(String id) { return radii.getOrDefault(id, 16.0); }
    private int screenX(double x) { return (int) Math.round(x * zoom + offsetX); }
    private int screenY(double y) { return (int) Math.round(y * zoom + offsetY); }
}
