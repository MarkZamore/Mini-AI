package ai.enterprise.bootstrap;

import ai.enterprise.contracts.Api;
import ai.enterprise.shell.PanelFactory;
import ai.enterprise.shell.DesktopShell;
import java.util.List;
import org.eclipse.swt.widgets.Composite;

/** Embeddable component: independent session, caller-supplied environment and composition. */
public final class EnterpriseAIComponent implements AutoCloseable {
    private final Api.Context context;
    private final List<PanelFactory> panels;
    private boolean closed;
    private EnterpriseAIComponent(Api.Environment environment,List<Api.ModuleFactory> modules,List<PanelFactory> panels) {
        this.context=new Api.Context(environment);
        this.panels=List.copyOf(panels);
        for(var module:modules) {
            try { module.register(context); context.diagnostic("Модуль "+module.id()+": зарегистрирован"); }
            catch(Throwable error) { var failure=Api.failure(error); context.diagnostic("Модуль "+module.id()+": "+failure.error().message()); }
        }
    }
    public static EnterpriseAIComponent create(Api.Environment environment,List<Api.ModuleFactory> modules,List<PanelFactory> panels) { return new EnterpriseAIComponent(environment,modules,panels); }
    public Api.Context context() { return context; }
    public List<PanelFactory> panels() { return panels; }
    public Composite attach(Composite host) { if(closed) throw new IllegalStateException("Component closed"); return DesktopShell.compose(host,context,panels); }
    @Override public synchronized void close() { if(!closed) { closed=true; context.close(); } }
}
