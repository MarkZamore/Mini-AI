package ai.enterprise.shell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;

/** Display-owned visual resources. Nothing is extracted or persisted. */
public final class UiTheme implements AutoCloseable {
    public static final String NAME = "Mini-AI";
    private static final String KEY = "enterpriseai.ui.theme.v1";
    private final Display display;
    private final List<Color> owned = new ArrayList<>();
    private final Color background, panel, field, border, accent, accentHover, text, muted;
    private final Color node, isolatedNode, selectedNode, nodeBorder, selectedBorder, edge, selectedEdge;
    private final Color separator, separatorHover;
    private final Font bodyFont, brandFont;
    private final Image[] icons;
    private final boolean highContrast;
    private final Color[][] nodeShades = new Color[7][64];
    private final Color[] isolatedShades = new Color[7], edgeShades = new Color[7], selectedEdgeShades = new Color[7];
    private boolean closed;

    public static UiTheme get(Display display) {
        Object existing = display.getData(KEY);
        if (existing instanceof UiTheme theme) return theme;
        var theme = new UiTheme(display);
        display.setData(KEY, theme);
        display.disposeExec(theme::close);
        return theme;
    }
    private UiTheme(Display display) {
        this.display = display;
        boolean hc = highContrast = display.getHighContrast();
        background = color(hc, SWT.COLOR_WIDGET_BACKGROUND, 0xF0EBDD);
        panel = color(hc, SWT.COLOR_LIST_BACKGROUND, 0xF7F3E7);
        field = color(hc, SWT.COLOR_LIST_BACKGROUND, 0xFAF8F0);
        border = color(hc, SWT.COLOR_WIDGET_DARK_SHADOW, 0xD8D0B8);
        accent = color(hc, SWT.COLOR_LIST_SELECTION, 0xA8996F);
        accentHover = color(hc, SWT.COLOR_LIST_SELECTION, 0x93845B);
        text = color(hc, SWT.COLOR_LIST_FOREGROUND, 0x534E43);
        muted = color(hc, SWT.COLOR_WIDGET_FOREGROUND, 0x867E69);
        node = color(hc, SWT.COLOR_LIST_BACKGROUND, 0xD2CADE);
        isolatedNode = color(hc, SWT.COLOR_WIDGET_BACKGROUND, 0xE2DDE9);
        selectedNode = color(hc, SWT.COLOR_LIST_SELECTION, 0x9F81C3);
        nodeBorder = color(hc, SWT.COLOR_LIST_FOREGROUND, 0xB4A6C3);
        selectedBorder = color(hc, SWT.COLOR_LIST_SELECTION_TEXT, 0x79668F);
        edge = color(hc, SWT.COLOR_LIST_FOREGROUND, 0xB8B0C6);
        selectedEdge = color(hc, SWT.COLOR_LIST_SELECTION, 0x9988AF);
        separator = color(hc, SWT.COLOR_WIDGET_DARK_SHADOW, 0xDDD4BB);
        separatorHover = color(hc, SWT.COLOR_LIST_SELECTION, 0xC9BC95);
        Font createdBody = null, createdBrand = null;
        Image[] createdIcons = null;
        try {
            prepareGraphPalette();
            createdBody = new Font(display, "Segoe UI", 10, SWT.NORMAL);
            createdBrand = new Font(display, "Segoe UI", 25, SWT.BOLD);
            try (var input = UiTheme.class.getResourceAsStream("/enterpriseai/ui/application.ico")) {
                if (input == null) throw new IOException("Отсутствует встроенная иконка приложения");
                var data = new ImageLoader().load(input);
                createdIcons = new Image[data.length];
                for (int i = 0; i < data.length; i++) createdIcons[i] = new Image(display, data[i]);
            }
        } catch (IOException | RuntimeException | Error error) {
            if (createdIcons != null) for (Image image : createdIcons) if (image != null) image.dispose();
            if (createdBrand != null) createdBrand.dispose();
            if (createdBody != null) createdBody.dispose();
            owned.forEach(Color::dispose);
            throw new IllegalStateException("Не удалось загрузить встроенную иконку " + NAME, error);
        }
        bodyFont = createdBody; brandFont = createdBrand; icons = createdIcons;
    }
    /** Allocate the bounded graph palette before painting starts. */
    private void prepareGraphPalette() {
        if (highContrast) return;
        RGB isolatedRgb = isolatedNode.getRGB(), edgeRgb = edge.getRGB(), selectedEdgeRgb = selectedEdge.getRGB();
        for (int pulse = 0; pulse <= 6; pulse++) {
            isolatedShades[pulse] = pulse == 0 ? isolatedNode : highlight(isolatedRgb, pulse);
            edgeShades[pulse] = pulse == 0 ? edge : highlight(edgeRgb, pulse);
            selectedEdgeShades[pulse] = pulse == 0 ? selectedEdge : highlight(selectedEdgeRgb, pulse);
            for (int index = 0; index < 64; index++) {
                double t = index / 63.0;
                var rgb = new RGB((int)Math.round(210 + (159 - 210) * t),
                        (int)Math.round(202 + (129 - 202) * t), (int)Math.round(222 + (195 - 222) * t));
                nodeShades[pulse][index] = highlight(rgb, pulse);
            }
        }
    }
    private Color color(boolean highContrast, int system, int rgb) {
        if (highContrast) return display.getSystemColor(system);
        var value = new Color(display, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        owned.add(value); return value;
    }
    public void apply(Control control) {
        control.setFont(bodyFont); control.setBackground(panel); control.setForeground(text);
    }
    /** Call only for owned windows, never for a foreign embedding host. */
    public void decorate(Shell shell) { apply(shell); shell.setBackground(background); shell.setImages(icons); }
    public Color background() { return background; }
    public Color panel() { return panel; }
    public Color field() { return field; }
    public Color border() { return border; }
    public Color accent() { return accent; }
    public Color accentHover() { return accentHover; }
    public Color text() { return text; }
    public Color muted() { return muted; }
    public Color node() { return node; }
    public boolean highContrast() { return highContrast; }
    /** Degree prominence stays intact during the common, geometry-free heartbeat highlight. */
    public Color node(int degree,int maximumDegree) { return node(degree,maximumDegree,0); }
    public Color node(int degree,int maximumDegree,int pulseLevel) {
        int pulse=Math.max(0,Math.min(6,pulseLevel));
        if(highContrast)return degree==0?isolatedNode:node;
        if(degree<=0)return isolatedShades[pulse];
        double fraction=Math.sqrt(Math.min(1.0,degree/(double)Math.max(1,maximumDegree)));
        int index=(int)Math.round(fraction*63);
        return nodeShades[pulse][index];
    }
    private Color highlight(RGB rgb,int level){
        double t=level/100.0;
        var value=new Color(display,(int)Math.round(rgb.red+(255-rgb.red)*t),(int)Math.round(rgb.green+(255-rgb.green)*t),(int)Math.round(rgb.blue+(255-rgb.blue)*t));
        owned.add(value);return value;
    }
    public Color isolatedNode() { return isolatedNode; }
    public Color selectedNode() { return selectedNode; }
    public Color nodeBorder() { return nodeBorder; }
    public Color selectedBorder() { return selectedBorder; }
    public Color edge() { return edge; }
    public Color edge(int level) {
        if(highContrast||level<=0)return edge;
        return edgeShades[Math.min(6,level)];
    }
    public Color selectedEdge() { return selectedEdge; }
    public Color selectedEdge(int level) {
        if(highContrast||level<=0)return selectedEdge;
        return selectedEdgeShades[Math.min(6,level)];
    }
    public Color separator() { return separator; }
    public Color separatorHover() { return separatorHover; }
    public Font bodyFont() { return bodyFont; }
    public Font brandFont() { return brandFont; }
    public void close() {
        if (closed) return; closed = true;
        for (Image image : icons) if (image != null && !image.isDisposed()) image.dispose();
        bodyFont.dispose(); brandFont.dispose(); owned.forEach(Color::dispose);
    }
}
