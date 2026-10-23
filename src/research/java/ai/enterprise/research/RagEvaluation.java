package ai.enterprise.research;

import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.contracts.EvidenceModelServiceV1;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.platform.WindowsPlatform;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Research entry point using the complete, unchanged application composition.
 * It never opens the final set implicitly, writes into the corpus, or substitutes
 * generated answers. The caller must freeze the configuration before final use.
 */
public final class RagEvaluation {
    public static void main(String[] args) throws Exception {
        if(args.length<4)throw new IllegalArgumentException("root input.jsonl output.jsonl search|answer|dense [limit]");
        Path root=Path.of(args[0]).toAbsolutePath(),input=Path.of(args[1]),output=Path.of(args[2]);
        String mode=args[3];int maximum=args.length>4?Integer.parseInt(args[4]):Integer.MAX_VALUE;
        if(!Set.of("search","answer","dense").contains(mode))throw new IllegalArgumentException("Unknown mode");
        if(Files.exists(output))throw new IllegalArgumentException("Preserve earlier results: choose a new output file");
        Files.createDirectories(output.toAbsolutePath().getParent());
        var executor=Executors.newFixedThreadPool(4);
        try(var platform=new WindowsPlatform(root);
            var component=EnterpriseAIComponent.create(new Api.Environment(root,executor,platform),GeneratedCatalog.modules(),GeneratedCatalog.panels());
            var writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            Api.Context context=component.context();int number=0;
            for(String line:Files.readAllLines(input,StandardCharsets.UTF_8)) {
                if(line.isBlank())continue;if(number++>=maximum)break;
                var row=Json.object(line);String question=Json.string(row.get("question"));
                var result=new LinkedHashMap<String,Object>();result.put("id",row.get("id"));result.put("question",question);
                result.put("mode",mode);result.put("modules",GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).toList());
                result.put("human_verified",false);long started=System.nanoTime();
                if(mode.equals("search")) {
                    var found=context.require(Api.SearchServiceV1.class).searchForAssistant(question,24,List.of(),List.of(),new Api.CancellationToken()).toCompletableFuture().get(240,TimeUnit.SECONDS);
                    result.put("result",found);result.put("diagnostics",context.diagnostics());
                } else if(mode.equals("dense")) {
                    var found=context.require(EvidenceModelServiceV1.class).retrieve("local",question,24,List.of(),List.of(),new Api.CancellationToken()).toCompletableFuture().get(240,TimeUnit.SECONDS);
                    result.put("result",found.success()?Map.of("success",true,"value",found.value().stream().map(f->Map.of("fragment_id",f.fragmentId(),"document_id",f.documentId(),"score",f.score())).toList()):found);result.put("diagnostics",context.diagnostics());
                } else {
                    try(var session=new AssistantModule.Session(context)) {
                        var events=new CopyOnWriteArrayList<Map<String,Object>>();var firstText=new AtomicLong(-1);
                        var found=session.send(question,event->{
                            long elapsed=System.nanoTime()-started;
                            if((event.type().equals("token")||event.type().equals("done"))&&!event.text().isBlank())firstText.compareAndSet(-1,elapsed);
                            var record=new LinkedHashMap<String,Object>();record.put("atSeconds",elapsed/1e9);
                            record.put("type",event.type());record.put("text",event.text());record.put("error",event.error());events.add(record);
                        },new Api.CancellationToken()).toCompletableFuture().get(600,TimeUnit.SECONDS);
                        result.put("result",found);result.put("firstNonemptyTextSeconds",firstText.get()<0?null:firstText.get()/1e9);
                        result.put("events",events);result.put("history",session.history().stream().map(m->Map.of("role",m.role(),"text",m.text(),"sources",m.sources(),"state",m.state())).toList());result.put("diagnostics",context.diagnostics());
                    }
                }
                result.put("fullSeconds",(System.nanoTime()-started)/1e9);
                writer.write(Json.stringify(result));writer.newLine();writer.flush();
                System.out.println(Json.stringify(Map.of("case",number,"id",Objects.toString(row.get("id")),"mode",mode,"seconds",result.get("fullSeconds"))));
            }
        } finally {executor.shutdownNow();}
    }
}
