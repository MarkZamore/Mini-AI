package ai.enterprise.shell;

import ai.enterprise.contracts.Api;
import org.eclipse.swt.widgets.Composite;

/** UI SPI, kept outside the GUI-free contracts and module service code. */
public interface PanelFactory {
    String id();
    String title();
    /** assistant=left, graph=right; other panels use center. */
    String slot();
    Composite create(Composite parent, Api.Context context);
}
