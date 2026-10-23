package ai.enterprise.tests;

import ai.enterprise.contracts.*;
import ai.enterprise.database.DatabaseModule;
import ai.enterprise.documents.DocumentsModule;
import ai.enterprise.search.SearchModule;
import ai.enterprise.graph.GraphModule;
import ai.enterprise.inference.InferenceModule;
import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.sources.SourcesModule;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** All seven modules remain registered. Two metadata identities share unchanged real corpus bytes. */
public final class CorpusIdentityIntegrationTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void linkTree(Path source,Path target)throws Exception {
        if(!Files.exists(source))return;
        try(var paths=Files.walk(source)) {
            for(Path item:paths.toList()) {
                Path destination=target.resolve(source.relativize(item).toString());
                if(Files.isDirectory(item))Files.createDirectories(destination);
                else if(Files.isRegularFile(item)&&!Files.exists(destination)) {
                    Files.createDirectories(destination.getParent());Files.createLink(destination,item);
                }
            }
        }
    }
    private static void prepare(Path project,Path target)throws Exception {
        Files.createDirectories(target.resolve("db"));
        for(String name:List.of("index.sqlite","catalog.json","report.json","README.md")) {
            Path source=project.resolve("db").resolve(name),destination=target.resolve("db").resolve(name);
            if(Files.isRegularFile(source)&&!Files.exists(destination))Files.createLink(destination,source);
        }
        for(String name:List.of("documents","artifacts","schema"))linkTree(project.resolve("db").resolve(name),target.resolve("db").resolve(name));
        linkTree(project.resolve("docs"),target.resolve("docs"));
        linkTree(project.resolve("model"),target.resolve("model"));
        linkTree(project.resolve("src/build/native"),target.resolve("native"));
    }
    private static Api.Context context(Path root) {
        Api.Platform platform=new Api.Platform() {
            public Path executablePath(){return root.resolve("Mini-AI.exe");}
            public long availableMemoryBytes(){return 64L<<30;}
            public void openWith(Path original){throw new AssertionError("No external viewer in this test");}
            public Process launchWorker(Path exe,Path dir,long bytes){throw new AssertionError("No neural generation is needed for identity controls");}
        };
        var context=new Api.Context(new Api.Environment(root,Executors.newFixedThreadPool(4),platform));
        List<Api.ModuleFactory> modules=List.of(new DatabaseModule(),new DocumentsModule(),new SearchModule(),new GraphModule(),new InferenceModule(),new AssistantModule(),new SourcesModule());
        modules.forEach(module->module.register(context));
        check(modules.size()==7,"All current application modules registered");
        return context;
    }
    private static void descriptor(Path root,String base,String version)throws Exception {
        Files.writeString(root.resolve("db/base.json"),Json.stringify(Map.of("base_id",base,"content_version",version)));
    }
    public static void main(String[] args)throws Exception {
        Path project=Path.of(args[0]).toAbsolutePath(),scratch=Path.of(args[1]).toAbsolutePath();
        if(scratch.startsWith(project.resolve("db"))||scratch.equals(project))throw new IllegalArgumentException("Use an isolated build directory");
        Path alpha=scratch.resolve("alpha"),beta=scratch.resolve("beta");prepare(project,alpha);prepare(project,beta);
        var token=new Api.CancellationToken();
        try(var a=context(alpha);var b=context(beta)) {
            var identities=a.require(CorpusIdentityServiceV1.class);
            var original=identities.current(token);check(original.success()&&original.value().baseId().equals("local"),"Missing optional descriptor preserves local compatibility");
            String version=original.value().contentVersion();check(version.matches("[a-f0-9]{64}"),"Version is actual index SHA-256");
            descriptor(alpha,"alpha",version);descriptor(beta,"beta",version);
            var left=Api.await(a.require(Api.SearchServiceV1.class).searchForAssistant("учебный план",2,List.of(),List.of(),token));
            var right=Api.await(b.require(Api.SearchServiceV1.class).searchForAssistant("учебный план",2,List.of(),List.of(),token));
            check(!left.isEmpty()&&!right.isEmpty(),"Both complete contexts search the real unchanged index");
            var x=left.getFirst().fragment();var y=right.getFirst().fragment();
            check(x.fragmentId().equals(y.fragmentId())&&x.text().equals(y.text())&&x.sourceRefs().equals(y.sourceRefs()),"Raw source identities, text and locators preserved");
            check(!x.fields().get("fragment_key").equals(y.fields().get("fragment_key"))&&!x.fields().get("source_keys").equals(y.fields().get("source_keys")),"Identical raw IDs cannot collide across base IDs");
            check(x.fields().get("content_version").equals(version),"Namespaced evidence carries the checked content version");
            var session=a.require(Api.AssistantSessionServiceV1.class);
            var result=session.send("Какие условия участия предусмотрены для qzxcorpusunseen?",event->{},token).toCompletableFuture().get(30,TimeUnit.SECONDS);
            check(result.success()&&!session.history().isEmpty(),"Full assistant session can bind its first corpus identity");
            var history=session.history();descriptor(alpha,"beta",version);
            var changed=session.send("Какие условия предусмотрены теперь?",event->{},new Api.CancellationToken()).toCompletableFuture().get(10,TimeUnit.SECONDS);
            check(!changed.success()&&changed.error().code().equals("CORPUS_CHANGED")&&session.history().equals(history),"Changed base cannot append to or reuse the old history");
            Files.writeString(alpha.resolve("db/base.json"),Json.stringify(Map.of("base_id","alpha","content_version",version,"organization_policy","forbidden")));
            var invalid=identities.current(token);check(!invalid.success()&&invalid.error().code().equals("CORPUS_INVALID"),"Unknown descriptor fields rejected");
            descriptor(alpha,"alpha","0".repeat(64));var mismatch=identities.current(token);
            check(!mismatch.success()&&mismatch.error().code().equals("CORPUS_MISMATCH"),"False content version rejected");
            descriptor(alpha,"../escape",version);check(!identities.current(token).success(),"Invalid base identity rejected");
            Files.delete(alpha.resolve("db/base.json"));check(identities.current(token).value().baseId().equals("local"),"Optional metadata absence restores explicit local default");
            var cancelled=new Api.CancellationToken();cancelled.cancel();check(identities.current(cancelled).error().code().equals("CANCELLED"),"Identity hashing respects cancellation");
        }
        var report=Map.of("success",true,"assertions",checks,"modules",List.of("database","documents","search","graph","inference","assistant","sources"),
            "scope","Technical identity and session isolation only; same real corpus under two synthetic base IDs; no independent second-domain quality claim; no graph-specific checks or generation");
        Files.writeString(scratch.resolve("result.json"),Json.stringify(report));System.out.println(Json.stringify(report));
    }
}
