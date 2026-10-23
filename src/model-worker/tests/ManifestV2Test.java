package ai.enterprise.tests;
import ai.enterprise.contracts.Json;
import ai.enterprise.inference.WorkerManifest;
import java.nio.file.*;
import java.util.*;

/** Manifest acceptance/rejection uses the full real packages, without launching a model. */
public final class ManifestV2Test {
    private static int assertions;
    private static Map<String,Object> read(Path root) throws Exception {
        return Json.object(Files.readString(root.resolve("worker.json")));
    }
    private static void reject(Map<String,Object> value,Path root,String label) throws Exception {
        boolean failed=false;
        try {WorkerManifest.verify(value,root,Set.of("x86-64","avx2"),64L<<30,()->false);}
        catch(RuntimeException expected){failed=true;}
        if(!failed)throw new AssertionError(label); assertions++;
    }
    public static void main(String[] args) throws Exception {
        Path v1=Path.of(args[0]),v2=Path.of(args[1]);
        WorkerManifest.verify(read(v1),v1,Set.of("x86-64","avx2"),64L<<30,()->false); assertions++;
        var good=read(v2);
        WorkerManifest.verify(good,v2,Set.of("x86-64","avx2"),64L<<30,()->false); assertions++;
        var cpu=new LinkedHashMap<>(good);cpu.put("backend",Map.of("kind","cpu","device",0,"gpuMemoryBudgetBytes",0,"reserveFreeGpuMemoryBytes",0));
        WorkerManifest.verify(cpu,v2,Set.of("x86-64","avx2"),64L<<30,()->false);assertions++;
        var bad=new LinkedHashMap<>(good);bad.put("schemaVersion",1);reject(bad,v2,"v1 cannot inherit GPU permissions");
        bad=new LinkedHashMap<>(good);bad.put("dependencies",List.of());reject(bad,v2,"unlisted runtime DLL");
        bad=new LinkedHashMap<>(good);bad.put("unexpected",true);reject(bad,v2,"unknown root field");
        bad=new LinkedHashMap<>(good);bad.put("backend",Map.of("kind","cuda","device",0,"gpuMemoryBudgetBytes",1,"reserveFreeGpuMemoryBytes",0));reject(bad,v2,"GPU reserve required");
        bad=new LinkedHashMap<>(good);bad.put("backend",Map.of("kind","cpu","device",1,"gpuMemoryBudgetBytes",0,"reserveFreeGpuMemoryBytes",0));reject(bad,v2,"CPU device profile bounded");
        bad=new LinkedHashMap<>(good);bad.put("weights",Map.of("path","../escape.gguf","sha256","a".repeat(64)));reject(bad,v2,"traversal rejected");
        var deps=new ArrayList<Object>((List<?>)good.get("dependencies"));deps.add(deps.getFirst());
        bad=new LinkedHashMap<>(good);bad.put("dependencies",deps);reject(bad,v2,"duplicate DLL rejected");
        System.out.println(Json.stringify(Map.of("success",true,"assertions",assertions,"scope","manifest v1/v2, no model generation")));
    }
}
