package ai.enterprise.bootstrap;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.platform.WindowsPlatform;
import ai.enterprise.shell.DesktopShell;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.*;

/** Desktop entry point. All persistent application input lives relative to its actual launcher. */
public final class Main {
    private Main() {}
    public static void main(String[] args) {
        // Existing inherited OS streams, not filesystem paths. Keep CLI JSON Unicode exact
        // even when a Windows GUI executable has no console code page.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err),true,StandardCharsets.UTF_8));
        try {
            if(LegalResources.dispatch(args,new FileOutputStream(FileDescriptor.out))) return;
        } catch(java.io.IOException error) {
            System.err.println("EnterpriseAI: "+error.getMessage()); System.exit(1); return;
        }
        new MemoryLogging();
        Path root=WindowsPlatform.determineRoot();
        System.setProperty("swt.library.path",root.resolve("native").toString());
        var executor=Executors.newFixedThreadPool(4,r->{ var t=new Thread(r,"enterpriseai-task"); t.setDaemon(true); return t; });
        try(var platform=new WindowsPlatform(root);
            var component=EnterpriseAIComponent.create(new Api.Environment(root,executor,platform),GeneratedCatalog.modules(),GeneratedCatalog.panels())) {
            args=WindowsPlatform.commandLineArguments(args);
            var arguments=new ArrayList<>(List.of(args));
            if(!arguments.isEmpty()) {
                String name=arguments.removeFirst();
                if(name.equals("--command")) { if(arguments.isEmpty()) throw new Api.ServiceException("ARGUMENT","Не указана команда"); name=arguments.removeFirst(); }
                else if(name.startsWith("--")) name=name.substring(2);
                Object value;
                if(name.equals("ui-smoke")) { String notice=platform.prepareDesktopUi();if(!notice.isEmpty())component.context().diagnostic(notice);DesktopShell.run(component,6000,notice); value=Map.of("uiSmoke",true,"inputNotice",notice); }
                else if(name.equals("commands")) value=new TreeSet<>(component.context().commands());
                else if(name.equals("diagnostics")) value=component.context().diagnostics();
                else if(name.equals("status")) value=status(component.context());
                else value=component.context().execute(name,List.copyOf(arguments));
                System.out.println(Json.stringify(value));
            } else {String notice=platform.prepareDesktopUi();if(!notice.isEmpty())component.context().diagnostic(notice);DesktopShell.run(component,0,notice);}
        } catch(Throwable error) {
            executor.shutdownNow();
            var failure=Api.failure(error);
            if(args.length>0) { System.out.println(Json.stringify(failure)); System.exit(1); }
            else if(failure.error().code().equals("LONG_PATH_UI")) {System.err.println(failure.error().message());System.exit(1);}
            else DesktopShell.showStartupError(failure.error().message());
        }
    }
    private static Map<String,Object> status(Api.Context context) {
        var out=new LinkedHashMap<String,Object>();
        out.put("version",Api.VERSION);
        out.put("root",context.environment().root().toString());
        out.put("commands",new TreeSet<>(context.commands()));
        out.put("modules",GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).toList());
        out.put("panels",GeneratedCatalog.panels().stream().map(p->p.id()).toList());
        out.put("availableMemoryBytes",context.environment().platform().availableMemoryBytes());
        out.put("historyPersistence",false);
        out.put("diagnostics",context.diagnostics());
        return out;
    }
}
