package ai.enterprise.shell;

import ai.enterprise.platform.WindowsPlatform;
import java.io.IOException;
import java.util.*;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.widgets.*;

/** Display-owned Fluent resources. Nothing is extracted or persisted. */
public final class UiTheme implements AutoCloseable {
    public static final String NAME="Mini-AI";
    private static final String KEY="enterpriseai.ui.theme.v1", BACKDROP="enterpriseai.ui.backdrop";
    private final Display display;
    private final List<Color> owned=new ArrayList<>();
    private final Set<Control> controls=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Control,int[]> roles=new IdentityHashMap<>();
    private final Set<Shell> shells=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Listener settings=event->refresh();
    private Color background,panel,field,border,accent,accentHover,accentPressed,accentText,onAccent;
    private Color text,muted,surfaceHover,surfacePressed,disabled,textDisabled,scrollThumb,scrollThumbHover;
    private Color node,isolatedNode,selectedNode,nodeBorder,selectedBorder,edge,selectedEdge,separator,separatorHover;
    private final Font bodyFont,captionFont,brandFont;
    private final Image[] icons;
    private Image backdrop,backdropTint;
    private boolean highContrast,closed;
    private final Color[][] nodeShades=new Color[7][64];
    private final Color[] isolatedShades=new Color[7],edgeShades=new Color[7],selectedEdgeShades=new Color[7];

    public static UiTheme get(Display display) {
        Object existing=display.getData(KEY);
        if(existing instanceof UiTheme theme)return theme;
        var theme=new UiTheme(display);display.setData(KEY,theme);display.disposeExec(theme::close);return theme;
    }
    private UiTheme(Display display) {
        this.display=display;
        Font body=null,caption=null,heading=null;Image[] images=null;
        try {
            createPalette();
            String family=display.getFontList("Segoe UI Variable Text",true).length>0?"Segoe UI Variable Text":"Segoe UI";
            body=font(family,10.5f,SWT.NORMAL);caption=font(family,9f,SWT.NORMAL);
            String semibold=display.getFontList("Segoe UI Semibold",true).length>0?"Segoe UI Semibold":family;
            heading=font(semibold,15f,semibold.equals(family)?SWT.BOLD:SWT.NORMAL);
            try(var input=UiTheme.class.getResourceAsStream("/enterpriseai/ui/application.ico")) {
                if(input==null)throw new IOException("Отсутствует встроенная иконка приложения");
                var data=new ImageLoader().load(input);images=new Image[data.length];
                for(int i=0;i<data.length;i++)images[i]=new Image(display,data[i]);
            }
        } catch(IOException|RuntimeException|Error error) {
            if(images!=null)for(Image image:images)if(image!=null)image.dispose();
            if(heading!=null)heading.dispose();if(caption!=null)caption.dispose();if(body!=null)body.dispose();
            if(backdrop!=null)backdrop.dispose();if(backdropTint!=null)backdropTint.dispose();owned.forEach(Color::dispose);
            throw new IllegalStateException("Не удалось подготовить оформление "+NAME,error);
        }
        bodyFont=body;captionFont=caption;brandFont=heading;icons=images;display.addListener(SWT.Settings,settings);
    }
    /** SWT stores points: 10.5/9/15 pt correspond exactly to 14/12/20 DIP. */
    private Font font(String family,float points,int style) {
        var data=new FontData(family,1,style);data.height=points;return new Font(display,data);
    }
    private void createPalette() {
        highContrast=display.getHighContrast();int base=blend(0x202020,0x1C2330,.9);
        background=color(SWT.COLOR_WIDGET_BACKGROUND,0x202020);
        panel=color(SWT.COLOR_LIST_BACKGROUND,overlay(base,0xFFFFFF,0x0D));
        field=color(SWT.COLOR_LIST_BACKGROUND,overlay(base,0xFFFFFF,0x0D));
        border=color(SWT.COLOR_WIDGET_DARK_SHADOW,overlay(rgb(panel),0xFFFFFF,0x12));
        surfaceHover=color(SWT.COLOR_LIST_SELECTION,overlay(base,0xFFFFFF,0x15));
        surfacePressed=color(SWT.COLOR_WIDGET_DARK_SHADOW,overlay(base,0xFFFFFF,0x08));
        disabled=color(SWT.COLOR_WIDGET_BACKGROUND,overlay(base,0xFFFFFF,0x0A));
        text=color(SWT.COLOR_LIST_FOREGROUND,0xFFFFFF);
        muted=color(SWT.COLOR_WIDGET_FOREGROUND,overlay(base,0xFFFFFF,0xC5));
        textDisabled=color(SWT.COLOR_GRAY,overlay(base,0xFFFFFF,0x5D));
        scrollThumb=color(SWT.COLOR_WIDGET_FOREGROUND,overlay(rgb(panel),0xFFFFFF,0x8B));
        scrollThumbHover=color(SWT.COLOR_LIST_FOREGROUND,overlay(rgb(panel),0xFFFFFF,0xC5));
        int[] systemAccent=WindowsPlatform.accentColors();
        accent=color(SWT.COLOR_LIST_SELECTION,systemAccent[0]);accentHover=color(SWT.COLOR_LIST_SELECTION,blend(systemAccent[0],0xFFFFFF,.08));
        accentPressed=color(SWT.COLOR_LIST_SELECTION,blend(systemAccent[0],0,.12));accentText=color(SWT.COLOR_LIST_SELECTION,systemAccent[1]);
        onAccent=color(SWT.COLOR_LIST_SELECTION_TEXT,0x000000);
        node=color(SWT.COLOR_LIST_FOREGROUND,0x617180);isolatedNode=color(SWT.COLOR_WIDGET_FOREGROUND,0x617180);
        selectedNode=color(SWT.COLOR_LIST_SELECTION,0xAEC2D3);nodeBorder=color(SWT.COLOR_LIST_FOREGROUND,0x819AAF);
        selectedBorder=color(SWT.COLOR_LIST_SELECTION_TEXT,0xC6D8E7);edge=color(SWT.COLOR_WIDGET_FOREGROUND,0x455462);
        selectedEdge=color(SWT.COLOR_LIST_SELECTION,0x97AFC3);separator=color(SWT.COLOR_WIDGET_BACKGROUND,base);
        separatorHover=color(SWT.COLOR_LIST_SELECTION,overlay(base,0xFFFFFF,0x0C));prepareGraphPalette();
        if(!highContrast) {
            var data=new ImageData(256,256,24,new PaletteData(0xFF0000,0x00FF00,0x0000FF));
            // Canonical Windows BGRA with per-pixel alpha: pinned SWT's constant
            // alpha conversion swaps red/blue after converting a direct palette.
            var tintData=new ImageData(256,256,32,new PaletteData(0xFF00,0xFF0000,0xFF000000));
            tintData.alphaData=new byte[256*256];Arrays.fill(tintData.alphaData,(byte)230);
            for(int y=0;y<256;y++)for(int x=0;x<256;x++) {
                double t=(x+y)/510.0;
                int gradient=t<.5?blend(0x101B30,0x1C2330,t*2):blend(0x1C2330,0x282828,t*2-1);
                data.setPixel(x,y,blend(0x202020,gradient,.9));
                tintData.setPixel(x,y,tintData.palette.getPixel(rgbValue(gradient)));
            }
            backdrop=new Image(display,data);
            backdropTint=new Image(display,tintData);
        }
    }
    private Color[] semantics() {
        return new Color[]{background,panel,field,border,accent,accentHover,accentPressed,accentText,onAccent,text,muted,
            surfaceHover,surfacePressed,disabled,textDisabled,scrollThumb,scrollThumbHover,node,isolatedNode,selectedNode,nodeBorder,selectedBorder,edge,selectedEdge,separator,separatorHover};
    }
    private void refresh() {
        if(closed||display.isDisposed())return;
        boolean wasHighContrast=highContrast;
        Color[] previous=semantics();var previousOwned=new ArrayList<>(owned);owned.clear();
        Image previousBackdrop=backdrop,previousTint=backdropTint;backdrop=null;backdropTint=null;
        createPalette();Color[] current=semantics();
        for(Control control:List.copyOf(controls)) {
            if(control.isDisposed()){controls.remove(control);continue;}
            int[] role=wasHighContrast?roles.get(control):null;
            if(role==null)role=new int[]{roleOf(control.getBackground(),previous),roleOf(control.getForeground(),previous)};
            roles.put(control,role);
            if(role[0]>=0)control.setBackground(current[role[0]]);
            if(role[1]>=0)control.setForeground(current[role[1]]);
            if(control instanceof Link link)link.setLinkForeground(accentText);
            control.redraw();
        }
        for(Shell shell:List.copyOf(shells))if(!shell.isDisposed())WindowsVisuals.apply(shell,highContrast);
        previousOwned.forEach(Color::dispose);if(previousBackdrop!=null)previousBackdrop.dispose();
        if(previousTint!=null)previousTint.dispose();
    }
    private static int roleOf(Color color,Color[] palette) {
        for(int i=0;i<palette.length;i++)if(color.equals(palette[i]))return i;return -1;
    }
    private void prepareGraphPalette() {
        if(highContrast)return;
        for(int pulse=0;pulse<=6;pulse++) {
            isolatedShades[pulse]=pulse==0?isolatedNode:highlight(isolatedNode.getRGB(),pulse);
            edgeShades[pulse]=pulse==0?edge:highlight(edge.getRGB(),pulse);
            selectedEdgeShades[pulse]=pulse==0?selectedEdge:highlight(selectedEdge.getRGB(),pulse);
            for(int i=0;i<64;i++)nodeShades[pulse][i]=highlight(rgbValue(blend(0x617180,0x97AFC3,i/63.0)),pulse);
        }
    }
    private Color color(int system,int rgb) {
        if(highContrast)return display.getSystemColor(system);
        var color=new Color(display,rgbValue(rgb));owned.add(color);return color;
    }
    private static int rgb(Color color){var c=color.getRGB();return c.red<<16|c.green<<8|c.blue;}
    private static RGB rgbValue(int rgb){return new RGB((rgb>>16)&255,(rgb>>8)&255,rgb&255);}
    private static int overlay(int base,int top,int alpha){return blend(base,top,alpha/255.0);}
    private static int blend(int a,int b,double t) {
        int r=(int)Math.round(((a>>16)&255)+(((b>>16)&255)-((a>>16)&255))*t);
        int g=(int)Math.round(((a>>8)&255)+(((b>>8)&255)-((a>>8)&255))*t);
        int blue=(int)Math.round((a&255)+((b&255)-(a&255))*t);return r<<16|g<<8|blue;
    }
    public void apply(Control control) {
        if(controls.add(control))control.addDisposeListener(event->{controls.remove(control);roles.remove(control);});
        roles.put(control,new int[]{1,9});
        control.setFont(bodyFont);control.setBackground(panel);control.setForeground(text);
        if(control instanceof Link link)link.setLinkForeground(accentText);
    }
    /** Owned windows only; never decorate a foreign embedding host. */
    public void decorate(Shell shell) {
        apply(shell);shell.setBackground(background);shell.setImages(icons);
        if(shells.add(shell))shell.addDisposeListener(event->shells.remove(shell));
        installBackdrop(shell);WindowsVisuals.apply(shell,highContrast);
    }
    /** Child backdrop areas share the shell's diagonal rather than restart locally. */
    public void installBackdrop(Composite control) {
        if(Boolean.TRUE.equals(control.getData(BACKDROP)))return;control.setData(BACKDROP,Boolean.TRUE);
        if(controls.add(control))control.addDisposeListener(event->{controls.remove(control);roles.remove(control);});
        control.addPaintListener(event->paintBackdrop(control,event.gc));
        control.addListener(SWT.Resize,event->{if(!control.isDisposed())control.redraw();});
    }
    public void paintBackdrop(Control control,GC gc) {
        gc.setAdvanced(false);
        if(highContrast||backdrop==null){gc.setBackground(background);Point size=control.getSize();gc.fillRectangle(0,0,size.x,size.y);return;}
        Shell shell=control.getShell();Point origin=display.map(control,shell,0,0);Rectangle area=shell.getClientArea();
        if(area.width>0&&area.height>0) {
            boolean glass=control==shell&&backdropTint!=null&&Boolean.TRUE.equals(shell.getData("enterpriseai.ui.windows.clientBackdropConfigured"));
            if(glass) {
                // GDI black clears RGB and alpha in the DWM-extended gutters.
                // All native child controls stay inside the opaque content body.
                gc.setBackground(display.getSystemColor(SWT.COLOR_BLACK));gc.fillRectangle(area);
            }
            gc.drawImage(glass?backdropTint:backdrop,0,0,256,256,-origin.x,-origin.y,area.width,area.height);
        }
    }
    public Color background(){return background;} public Color panel(){return panel;} public Color field(){return field;}
    public Color border(){return border;} public Color surfaceHover(){return surfaceHover;} public Color surfacePressed(){return surfacePressed;}
    public Color disabled(){return disabled;} public Color textDisabled(){return textDisabled;} public Color onAccent(){return onAccent;}
    public Color scrollThumb(){return scrollThumb;} public Color scrollThumbHover(){return scrollThumbHover;}
    public Color accent(){return accent;} public Color accentHover(){return accentHover;} public Color accentPressed(){return accentPressed;}
    public Color accentText(){return accentText;} public Color text(){return text;} public Color muted(){return muted;}
    public Color node(){return node;} public boolean highContrast(){return highContrast;}
    public Color node(int degree,int maximumDegree){return node(degree,maximumDegree,0);}
    public Color node(int degree,int maximumDegree,int pulseLevel) {
        int pulse=Math.max(0,Math.min(6,pulseLevel));if(highContrast)return degree==0?isolatedNode:node;
        if(degree<=0)return isolatedShades[pulse];double t=Math.sqrt(Math.min(1.0,degree/(double)Math.max(1,maximumDegree)));
        return nodeShades[pulse][(int)Math.round(t*63)];
    }
    private Color highlight(RGB rgb,int level) {
        var color=new Color(display,rgbValue(blend(rgb.red<<16|rgb.green<<8|rgb.blue,0xFFFFFF,level/100.0)));owned.add(color);return color;
    }
    public Color isolatedNode(){return isolatedNode;} public Color selectedNode(){return selectedNode;} public Color nodeBorder(){return nodeBorder;}
    public Color selectedBorder(){return selectedBorder;} public Color edge(){return edge;}
    public Color edge(int level){return highContrast||level<=0?edge:edgeShades[Math.min(6,level)];}
    public Color selectedEdge(){return selectedEdge;}
    public Color selectedEdge(int level){return highContrast||level<=0?selectedEdge:selectedEdgeShades[Math.min(6,level)];}
    public Color separator(){return separator;} public Color separatorHover(){return separatorHover;}
    public Font bodyFont(){return bodyFont;} public Font captionFont(){return captionFont;} public Font brandFont(){return brandFont;}
    public void close() {
        if(closed)return;closed=true;if(!display.isDisposed())display.removeListener(SWT.Settings,settings);
        for(Image image:icons)if(image!=null&&!image.isDisposed())image.dispose();
        if(backdrop!=null&&!backdrop.isDisposed())backdrop.dispose();
        if(backdropTint!=null&&!backdropTint.isDisposed())backdropTint.dispose();
        bodyFont.dispose();captionFont.dispose();brandFont.dispose();owned.forEach(Color::dispose);controls.clear();roles.clear();shells.clear();
    }
}
