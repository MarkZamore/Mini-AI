package ai.enterprise.tests;

import ai.enterprise.contracts.*;
import ai.enterprise.database.DatabaseModule;
import ai.enterprise.documents.DocumentsModule;
import ai.enterprise.search.SearchModule;
import ai.enterprise.graph.GraphModule;
import ai.enterprise.inference.InferenceModule;
import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.sources.SourcesModule;
import ai.enterprise.platform.WindowsPlatform;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real v2 worker, all seven application modules registered, no graph-specific operations. */
public final class PersistentGenerationTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void tree(Path source,Path target)throws Exception {
        if(!Files.exists(source))return;
        try(var paths=Files.walk(source)) {
            for(Path item:paths.toList()) {
                Path destination=target.resolve(source.relativize(item).toString());
                if(Files.isDirectory(item))Files.createDirectories(destination);
                else if(Files.isRegularFile(item)&&!Files.exists(destination)) {
                    Files.createDirectories(destination.getParent());
                    if(item.getFileName().toString().equals("worker.json"))Files.copy(item,destination);
                    else Files.createLink(destination,item);
                }
            }
        }
    }
    private static Map<String,Object> generate(Api.InferenceServiceV1 service,String id,String question,boolean cancel)throws Exception {
        long started=System.nanoTime();AtomicLong first=new AtomicLong();StringBuilder text=new StringBuilder();
        var token=new Api.CancellationToken();
        var result=service.generate(new Api.GenerationRequest(id,List.of(new Api.ModelMessage("user",question)),cancel?1024:24),event->{
            if(event.type().equals("token")&&!event.text().isEmpty()){
                first.compareAndSet(0,System.nanoTime());text.append(event.text());if(cancel)token.cancel();
            }
        },token).toCompletableFuture().get(180,TimeUnit.SECONDS);
        var output=new LinkedHashMap<String,Object>();output.put("id",id);output.put("success",result.success());
        output.put("error",result.success()?"":result.error().code());output.put("text",text.toString());
        output.put("wall_seconds",(System.nanoTime()-started)/1e9);output.put("wall_to_first_nonempty_seconds",first.get()==0?-1:(first.get()-started)/1e9);
        return output;
    }
    public static void main(String[] args)throws Exception {
        Path project=Path.of(args[0]).toAbsolutePath(),packageRoot=Path.of(args[1]).toAbsolutePath(),scratch=Path.of(args[2]).toAbsolutePath();
        if(scratch.equals(project)||scratch.startsWith(project.resolve("db"))||scratch.startsWith(project.resolve("model")))throw new IllegalArgumentException("Use an isolated build fixture");
        for(String name:List.of("db","docs"))tree(project.resolve(name),scratch.resolve(name));
        tree(packageRoot,scratch.resolve("model"));tree(project.resolve("src/build/native"),scratch.resolve("native"));
        List<Process> launches=new CopyOnWriteArrayList<>();
        var nativePlatform=new WindowsPlatform(scratch);
        Api.Platform platform=new Api.Platform(){
            public Path executablePath(){return scratch.resolve("Mini-AI.exe");}
            public long availableMemoryBytes(){return nativePlatform.availableMemoryBytes();}
            public boolean cpuSupports(String isa){return nativePlatform.cpuSupports(isa);}
            public void openWith(Path path){throw new AssertionError();}
            public Process launchWorker(Path exe,Path directory,long bytes) {
                Process process=nativePlatform.launchWorker(exe,directory,bytes);launches.add(process);return process;
            }
        };
        var observations=new ArrayList<Map<String,Object>>();
        try(nativePlatform;var context=new Api.Context(new Api.Environment(scratch,Executors.newFixedThreadPool(4),platform))){
            List<Api.ModuleFactory> modules=List.of(new DatabaseModule(),new DocumentsModule(),new SearchModule(),new GraphModule(),new InferenceModule(),new AssistantModule(),new SourcesModule());
            modules.forEach(module->module.register(context));check(modules.size()==7,"Full current module composition");
            var service=context.require(Api.InferenceServiceV1.class);
            var first=generate(service,"persistent-one","Ответь одним числом: 2 + 2 =",false);observations.add(first);
            check((boolean)first.get("success")&&first.get("text").toString().contains("4"),"First real generation succeeds");
            check(launches.size()==1,"First request launched exactly one worker");
            var second=generate(service,"persistent-two","Ответь одним числом: 3 + 3 =",false);observations.add(second);
            check((boolean)second.get("success")&&second.get("text").toString().contains("6"),"Second independent request succeeds");
            check(launches.size()==1&&launches.getFirst().isAlive(),"V2 reuses the same live worker only after done");
            var cancelled=generate(service,"persistent-cancel","Перечисляй целые числа от одного до тысячи, по одному в строке.",true);observations.add(cancelled);
            check(!(boolean)cancelled.get("success")&&cancelled.get("error").equals("CANCELLED"),"Cancellation reaches a terminal error");
            var afterCancel=generate(service,"persistent-after-cancel","Ответь одним числом: 2 + 2 =",false);observations.add(afterCancel);
            check((boolean)afterCancel.get("success")&&launches.size()==2&&!launches.getFirst().isAlive(),"Cancel invalidates worker; next request launches fresh");
            Path manifestPath=scratch.resolve("model/worker.json");var manifest=Json.object(Files.readString(manifestPath));
            boolean locked=false;try{Files.writeString(manifestPath,Json.stringify(manifest));}catch(java.io.IOException expected){locked=true;}
            check(locked,"Native holds the verified manifest read-only and denies mutation while resident");
            launches.getLast().destroyForcibly(); // External worker failure releases its immutable package handles.
            manifest.put("threads",Json.integer(manifest.get("threads"))-1);Files.writeString(manifestPath,Json.stringify(manifest));
            var changed=generate(service,"persistent-changed","Ответь одним числом: 2 + 2 =",false);observations.add(changed);
            check((boolean)changed.get("success")&&launches.size()==3&&!launches.get(1).isAlive(),"Changed manifest after external failure revalidates a fresh process");
            launches.getLast().destroyForcibly();
            var weights=new LinkedHashMap<>(Json.object(Json.stringify(manifest.get("weights"))));weights.put("sha256","0".repeat(64));manifest.put("weights",weights);Files.writeString(manifestPath,Json.stringify(manifest));
            var rejected=generate(service,"persistent-rejected","2 + 2",false);observations.add(rejected);
            check(!(boolean)rejected.get("success")&&launches.size()==3&&!launches.getLast().isAlive(),"Changed invalid manifest cannot reuse previously verified process");
        }
        check(launches.stream().noneMatch(Process::isAlive),"Context close releases every worker");
        Files.writeString(scratch.resolve("result.json"),Json.stringify(Map.of("success",true,"assertions",checks,"observations",observations,"worker_launches",launches.size(),"scope","Full composition real v2 transport with WindowsPlatform Job Object; reuse/cancel/integrity; no corpus answer quality claim")));
        System.out.println("Persistent generation checks passed: "+checks);
    }
}
