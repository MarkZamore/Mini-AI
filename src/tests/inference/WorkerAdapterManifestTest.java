package ai.enterprise.tests;

import ai.enterprise.inference.WorkerManifest;
import ai.enterprise.contracts.Json;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Model-free security regression; executed only with the complete seven-module classpath. */
public final class WorkerAdapterManifestTest {
    private static int checks;
    private static Path model;
    private static final String BASE="weights/base.gguf", ADAPTER="adapters/selector-r8.gguf";
    public static void main(String[] args) throws Exception {
        check(ai.enterprise.generated.GeneratedCatalog.modules().size()==7,"Full seven-module catalog");
        model=Path.of(args[0]).toAbsolutePath().normalize();
        if(Files.exists(model))throw new IllegalArgumentException("Preserve previous test directory");
        Files.createDirectories(model.resolve("weights"));Files.createDirectories(model.resolve("adapters"));
        Files.write(model.resolve("inference-worker.exe"),pe());
        byte[] gguf="GGUF model-free contract fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(model.resolve(BASE),gguf);Files.write(model.resolve(ADAPTER),gguf);
        Map<String,Object> valid=manifest();
        verify(valid);check(true,"Exact v2 adapter and actual SHA accepted");
        var changed=copy(valid);changed.remove("adapter");verify(changed);check(true,"v2 without adapter unchanged");
        changed.remove("backend");changed.remove("dependencies");changed.put("schemaVersion",1);verify(changed);check(true,"v1 without adapter unchanged");
        changed.put("adapter",valid.get("adapter"));reject(changed,"v1 adapter rejected");
        for(String key:List.of("path","sha256","baseWeightsSha256")){
            changed=copy(valid);adapter(changed).remove(key);reject(changed,"Missing field "+key);
        }
        changed=copy(valid);adapter(changed).put("scale",2);reject(changed,"Unknown field rejected");
        changed=copy(valid);changed.put("adapter",List.of());reject(changed,"Non-object adapter rejected");
        for(String path:List.of("../selector.gguf","C:/selector.gguf","/selector.gguf","adapters/../selector.gguf",
                "runtime/selector.dll","adapters/sub/selector.gguf","adapters/Selector.gguf","adapters/con.gguf",
                "adapters/selector.txt","adapters/.selector.gguf","adapters/"+"a".repeat(129)+".gguf")){
            changed=copy(valid);adapter(changed).put("path",path);reject(changed,"Unsafe/noncanonical path "+path);
        }
        changed=copy(valid);adapter(changed).put("sha256","c".repeat(64));reject(changed,"SHA differs from inventory");
        changed=copy(valid);adapter(changed).put("baseWeightsSha256","c".repeat(64));reject(changed,"Wrong exact base");
        changed=copy(valid);adapter(changed).put("baseWeightsSha256","A".repeat(64));reject(changed,"Noncanonical binding hash");
        changed=copy(valid);files(changed).removeIf(x->ADAPTER.equals(x.get("path")));reject(changed,"Adapter absent from inventory");
        changed=copy(valid);adapter(changed).put("path",BASE);reject(changed,"Adapter cannot be base");
        Files.writeString(model.resolve(ADAPTER),"different bytes");reject(valid,"Actual adapter SHA checked");
        changed=manifest();reject(changed,"Correct hash but non-GGUF adapter rejected");
        Files.write(model.resolve(ADAPTER),gguf);
        Files.writeString(model.resolve(BASE),"not a GGUF");changed=manifest();reject(changed,"Bound base must contain GGUF");
        Files.write(model.resolve(BASE),gguf);
        Path dll=model.resolve("extra.dll");Files.write(dll,new byte[]{1});changed=manifest();files(changed).add(spec("extra.dll"));
        reject(changed,"Adapter does not relax unapproved DLL guard");Files.delete(dll);
        try {WorkerManifest.verify(valid,model,Set.of("x86-64"),1L<<30,()->true);throw new AssertionError("Cancellation ignored");}
        catch(java.util.concurrent.CancellationException expected){check(true,"Cancellation unchanged");}
        System.out.println(Json.stringify(Map.of("status","PASS","checks",checks,"full_modules",7,"model_or_process_launched",false)));
    }
    private static Map<String,Object> manifest() throws Exception {
        var base=spec(BASE);var adapter=spec(ADAPTER);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion",2);result.put("protocol","enterpriseai.inference.v1");result.put("executable","inference-worker.exe");
        result.put("engine","llama.cpp");result.put("revision","model-free-contract");result.put("architecture","windows-x64");
        result.put("supportedIsa",List.of("x86-64"));result.put("weights",base);result.put("files",new ArrayList<>(List.of(spec("inference-worker.exe"),base,adapter)));
        result.put("precision","Q6_K");result.put("contextTokens",16384);result.put("threads",1);result.put("minFreeMemoryBytes",1L<<20);result.put("maxProcessMemoryBytes",1L<<19);
        result.put("backend",Map.of("kind","cpu","device",0,"gpuMemoryBudgetBytes",0,"reserveFreeGpuMemoryBytes",0));result.put("dependencies",List.of());
        result.put("adapter",new LinkedHashMap<>(Map.of("path",ADAPTER,"sha256",adapter.get("sha256"),"baseWeightsSha256",base.get("sha256"))));return result;
    }
    private static Map<String,Object> spec(String path)throws Exception {return Map.of("path",path,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(model.resolve(path)))));}
    private static Map<String,Object> copy(Map<String,Object> value){return Json.object(Json.stringify(value));}
    @SuppressWarnings("unchecked") private static Map<String,Object> adapter(Map<String,Object> value){return (Map<String,Object>)value.get("adapter");}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> files(Map<String,Object> value){return (List<Map<String,Object>>)value.get("files");}
    private static void verify(Map<String,Object> value)throws Exception {WorkerManifest.verify(value,model,Set.of("x86-64"),1L<<30,()->false);}
    private static void reject(Map<String,Object> value,String label)throws Exception {
        try{verify(value);}catch(IllegalArgumentException|ai.enterprise.contracts.Api.ServiceException expected){check(true,label);return;}
        throw new AssertionError("Expected rejection: "+label);
    }
    private static void check(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
    private static byte[] pe(){
        byte[] bytes=new byte[1024];var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0,(short)0x5a4d);b.putInt(60,0x80);b.putInt(0x80,0x4550);b.putShort(0x84,(short)0x8664);b.putShort(0x86,(short)1);b.putShort(0x94,(short)240);b.putShort(0x96,(short)2);
        int o=0x98;b.putShort(o,(short)0x20b);b.putLong(o+24,0x140000000L);b.putInt(o+60,512);b.putShort(o+68,(short)3);b.putInt(o+108,16);b.putInt(o+120,0x1000);b.putInt(o+124,40);
        int s=o+240;b.putInt(s+8,512);b.putInt(s+12,0x1000);b.putInt(s+16,512);b.putInt(s+20,512);b.putInt(0x20c,0x1080);
        byte[] name="kernel32.dll".getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(name,0,bytes,0x280,name.length);return bytes;
    }
}
