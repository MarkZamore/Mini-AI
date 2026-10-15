package ai.enterprise.assistant;

import ai.enterprise.shell.UiTheme;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.*;

/** Rounded in-memory frame with a plain caption breaking the top border. */
public final class RoundedSourcesBlock extends Composite {
    private final Label title;
    private final Composite content;

    public RoundedSourcesBlock(Composite parent, UiTheme theme, String caption) {
        super(parent, SWT.DOUBLE_BUFFERED);
        theme.apply(this); setData("enterpriseai.ui.sources", Boolean.TRUE);
        title = new Label(this, SWT.NONE); theme.apply(title);
        title.setText(caption); title.setToolTipText(caption);
        title.setData("enterpriseai.ui.sources-title", Boolean.TRUE);
        content = new Composite(this, SWT.NONE); theme.apply(content);
        content.setData("enterpriseai.ui.sources-content", Boolean.TRUE);
        setLayout(new Layout() {
            protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache) {
                Point captionSize = title.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                return new Point(wHint == SWT.DEFAULT ? Math.max(240, captionSize.x + 48) : wHint,
                    hHint == SWT.DEFAULT ? 130 : hHint);
            }
            protected void layout(Composite composite, boolean flushCache) {
                var area = getClientArea();
                Point captionSize = title.computeSize(SWT.DEFAULT, SWT.DEFAULT, flushCache);
                title.setBounds(24, 0, Math.max(0, Math.min(captionSize.x, area.width - 48)), captionSize.y);
                content.setBounds(14, captionSize.y + 12, Math.max(0, area.width - 28),
                    Math.max(0, area.height - captionSize.y - 24));
            }
        });
        addPaintListener(event -> {
            var area = getClientArea(); int top = title.getBounds().height / 2;
            event.gc.setAdvanced(false); event.gc.setBackground(theme.panel()); event.gc.fillRectangle(area);
            event.gc.setForeground(theme.border());
            event.gc.drawRoundRectangle(0, top, Math.max(0, area.width - 1),
                Math.max(0, area.height - top - 1), 14, 14);
        });
        addListener(SWT.Resize, event -> { layout(true); redraw(); });
    }

    public Composite content() { return content; }
    public String getText() { return title.getText(); }
}
