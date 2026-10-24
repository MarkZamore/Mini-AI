#include "../support.hpp"
#include <iostream>

using namespace worker;
static unsigned passed=0;
static void check(bool value,const char * text) { if(!value)throw std::runtime_error(text);++passed; }
static void rejected(const Json & manifest,const std::map<std::string,std::string> & inventory,const char * text) {
    try { (void)adapter_spec(manifest,inventory); }
    catch(const Failure & failure) { check(failure.code=="INCOMPATIBLE_MODEL",text);return; }
    throw std::runtime_error(text);
}
int main() {
    try {
        const std::string base_hash(64,'a'),adapter_hash(64,'b');
        const std::string base_path="weights/base.gguf",path="adapters/selector-r8.gguf";
        const std::map<std::string,std::string> inventory={{base_path,base_hash},{path,adapter_hash}};
        Json manifest={{"schemaVersion",2},{"weights",{{"path",base_path},{"sha256",base_hash}}},
            {"adapter",{{"path",path},{"sha256",adapter_hash},{"baseWeightsSha256",base_hash}}}};
        auto spec=adapter_spec(manifest,inventory);
        check(spec.path==path&&spec.sha256==adapter_hash,"One exact inventoried adapter accepted");
        auto changed=manifest;changed.erase("adapter");
        check(adapter_spec(changed,inventory).path.empty(),"Existing v2 without adapter unchanged");
        changed["schemaVersion"]=1;
        check(adapter_spec(changed,inventory).path.empty(),"Existing v1 without adapter unchanged");
        changed=manifest;changed["schemaVersion"]=1;rejected(changed,inventory,"v1 adapter rejected");
        for (const char * name:{"path","sha256","baseWeightsSha256"}) {
            changed=manifest;changed["adapter"].erase(name);rejected(changed,inventory,"Missing adapter field rejected");
        }
        changed=manifest;changed["adapter"]["scale"]=2;rejected(changed,inventory,"Unknown scale rejected");
        changed=manifest;changed["adapter"]=Json::array();rejected(changed,inventory,"Array adapter rejected");
        for (const std::string invalid:std::vector<std::string>{"../selector.gguf","C:/selector.gguf","/selector.gguf","adapters/../selector.gguf",
                "runtime/selector.dll","adapters/sub/selector.gguf","adapters/Selector.gguf","adapters/con.gguf",
                "adapters/selector.txt","adapters/.selector.gguf",std::string("adapters/")+std::string(129,'a')+".gguf"}) {
            changed=manifest;changed["adapter"]["path"]=invalid;rejected(changed,inventory,"Unsafe/noncanonical adapter path rejected");
        }
        changed=manifest;changed["adapter"]["sha256"]=std::string(64,'c');rejected(changed,inventory,"Adapter hash must equal inventory");
        changed=manifest;changed["adapter"]["baseWeightsSha256"]=std::string(64,'c');rejected(changed,inventory,"Adapter bound to exact base hash");
        changed=manifest;changed["adapter"]["baseWeightsSha256"]=std::string(64,'A');rejected(changed,inventory,"Noncanonical hash rejected");
        auto broken_inventory=inventory;broken_inventory.erase(path);rejected(manifest,broken_inventory,"Missing adapter inventory rejected");
        broken_inventory=inventory;broken_inventory.erase(base_path);rejected(manifest,broken_inventory,"Missing base inventory rejected");
        broken_inventory=inventory;broken_inventory[base_path]=std::string(64,'c');rejected(manifest,broken_inventory,"Base inventory hash must agree");
        changed=manifest;changed["weights"]["path"]=path;changed["weights"]["sha256"]=adapter_hash;
        changed["adapter"]["baseWeightsSha256"]=adapter_hash;rejected(changed,inventory,"Adapter cannot be its own base");
        check(adapter_prepare_token(7,10)==7,"Actual valid BOS retained");
        check(adapter_prepare_token(-1,10)==0,"Absent BOS uses valid token zero");
        check(adapter_prepare_token(10,10)==0,"Out-of-range BOS cannot enter the decoder");
        try { (void)adapter_prepare_token(0,0);throw std::runtime_error("Empty vocabulary accepted"); }
        catch(const Failure & failure) {check(failure.code=="INCOMPATIBLE_MODEL","Empty vocabulary rejected before graph preparation");}
        std::cout<<"{\"checks\":"<<passed<<",\"status\":\"PASS\",\"model_or_backend_loaded\":false}\n";
        return 0;
    } catch(const std::exception & failure) {std::cerr<<failure.what()<<'\n';return 1;}
}
