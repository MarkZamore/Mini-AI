#include "support.hpp"
#include <intrin.h>
#include <cwctype>
#include <cctype>

namespace worker {
static std::wstring relative_path(const std::string & text) {
    if (text.empty() || text.size()>32760 || text.front()=='/' || text.back()=='/' || text.find('\\')!=std::string::npos)
        throw Failure("INCOMPATIBLE_MODEL","Invalid package relative path");
    for (unsigned char c:text) if (c<32 || c==':' || c=='<' || c=='>' || c=='\"' || c=='|' || c=='?' || c=='*')
        throw Failure("INCOMPATIBLE_MODEL","Invalid package path character");
    auto path=wide(text);
    size_t start=0;
    while (start<path.size()) {
        size_t end=path.find(L'/',start);
        if (end==std::wstring::npos) end=path.size();
        std::wstring part=path.substr(start,end-start);
        if (part.empty() || part==L"." || part==L".." || part.back()==L'.' || part.back()==L' ')
            throw Failure("INCOMPATIBLE_MODEL","Invalid package path segment");
        std::wstring stem=part.substr(0,part.find(L'.'));
        std::transform(stem.begin(),stem.end(),stem.begin(),[](wchar_t c){return std::towupper(c);});
        if (stem==L"CON" || stem==L"PRN" || stem==L"AUX" || stem==L"NUL" ||
            (stem.size()==4 && (stem.substr(0,3)==L"COM" || stem.substr(0,3)==L"LPT") &&
             ((stem[3]>=L'1' && stem[3]<=L'9') || stem[3]==L'\u00b9' || stem[3]==L'\u00b2' || stem[3]==L'\u00b3')))
            throw Failure("INCOMPATIBLE_MODEL","Reserved Windows device path");
        start=end+1;
    }
    std::replace(path.begin(),path.end(),L'/',L'\\');
    return path;
}
Handle Package::open(const std::string & relative) const {
    if (inventory_verified && relative!="worker.json" && !verified_assets.count(relative))
        throw Failure("INCOMPATIBLE_MODEL","Requested asset is not in the verified inventory");
    auto path=root+L"\\"+relative_path(relative);
    for (size_t pos=root.size()+1;;) {
        auto next=path.find(L'\\',pos);
        auto prefix=next==std::wstring::npos ? path : path.substr(0,next);
        DWORD attributes=GetFileAttributesW(prefix.c_str());
        if (attributes==INVALID_FILE_ATTRIBUTES || (attributes & FILE_ATTRIBUTE_REPARSE_POINT))
            throw Failure("INCOMPATIBLE_MODEL","Missing file or reparse point in package");
        if (next==std::wstring::npos) { if(attributes & FILE_ATTRIBUTE_DIRECTORY) throw Failure("INCOMPATIBLE_MODEL","Expected ordinary file"); break; }
        pos=next+1;
    }
    Handle handle(CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_SEQUENTIAL_SCAN,nullptr));
    if (handle.value==INVALID_HANDLE_VALUE || GetFileType(handle.value)!=FILE_TYPE_DISK)
        throw Failure("INCOMPATIBLE_MODEL","Cannot open ordinary package file read-only");
    if (!contained(root,final_path(handle.value))) throw Failure("INCOMPATIBLE_MODEL","Package file escapes root");
    return handle;
}
static bool hash_text(const std::string & hash) {
    return hash.size()==64 && std::all_of(hash.begin(),hash.end(),[](char c){return (c>='0' && c<='9') || (c>='a' && c<='f');});
}
static void check_inventory_tree(const std::wstring & directory,const std::wstring & executable,size_t & count,
                                 const std::set<std::wstring> & allowed_dlls,size_t depth=0) {
    if (depth>64) throw Failure("INCOMPATIBLE_MODEL","Package directory nesting exceeds limit");
    WIN32_FIND_DATAW item{};
    HANDLE search=FindFirstFileW((directory+L"\\*").c_str(),&item);
    if (search==INVALID_HANDLE_VALUE) throw Failure("INCOMPATIBLE_MODEL","Cannot enumerate package");
    struct Guard { HANDLE h; ~Guard(){FindClose(h);} } guard{search};
    do {
        if (wcscmp(item.cFileName,L".")==0 || wcscmp(item.cFileName,L"..")==0) continue;
        if (++count>16384) throw Failure("INCOMPATIBLE_MODEL","Package file count exceeds limit");
        std::wstring name=item.cFileName;
        std::transform(name.begin(),name.end(),name.begin(),[](wchar_t c){return std::towlower(c);});
        if (item.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) throw Failure("INCOMPATIBLE_MODEL","Reparse points are prohibited in package");
        auto path=directory+L"\\"+item.cFileName;
        if (item.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            if (name==L"native") throw Failure("MODEL_PACKAGE_NOT_SELF_CONTAINED","model/native is prohibited; v2 DLLs use runtime/");
            check_inventory_tree(path,executable,count,allowed_dlls,depth+1);
        } else if ((name.size()>=4 && name.substr(name.size()-4)==L".dll" && !allowed_dlls.count(path)) ||
                   (name.size()>=4 && name.substr(name.size()-4)==L".exe" && !equal_path(path,executable)))
            throw Failure("MODEL_PACKAGE_NOT_SELF_CONTAINED","Package contains a DLL or additional executable");
    } while (FindNextFileW(search,&item));
    if (GetLastError()!=ERROR_NO_MORE_FILES) throw Failure("INCOMPATIBLE_MODEL","Cannot enumerate package");
}
static bool haswell_isa() {
    int cpu[4];__cpuid(cpu,0);if(cpu[0]<7)return false;
    __cpuid(cpu,1);
    constexpr unsigned ecx=(1u<<0)|(1u<<9)|(1u<<12)|(1u<<19)|(1u<<20)|(1u<<23)|(1u<<27)|(1u<<28)|(1u<<29);
    if((unsigned(cpu[2])&ecx)!=ecx||(_xgetbv(0)&6)!=6)return false;
    __cpuidex(cpu,7,0);constexpr unsigned ebx=(1u<<3)|(1u<<5)|(1u<<8);
    if((unsigned(cpu[1])&ebx)!=ebx)return false;
    __cpuid(cpu,int(0x80000000u));if(unsigned(cpu[0])<0x80000001u)return false;
    __cpuid(cpu,int(0x80000001u));return (unsigned(cpu[2])&(1u<<5))!=0;
}
AdapterSpec adapter_spec(const Json & data,const std::map<std::string,std::string> & inventory) {
    try {
    if (!data.contains("adapter")) return {};
    if (integer(data,"schemaVersion",1,2)!=2) throw Failure("INCOMPATIBLE_MODEL","Adapters require package schema 2");
    const auto & asset=data.at("adapter");
    keys(asset,{"path","sha256","baseWeightsSha256"});
    auto path=string(asset,"path",32760),hash=string(asset,"sha256",64);
    (void)relative_path(path);
    const std::string prefix="adapters/";
    if (path.rfind(prefix,0)!=0 || path.size()<prefix.size()+6 || path.size()>prefix.size()+133 ||
        path.substr(path.size()-5)!=".gguf" || path.find('/',prefix.size())!=std::string::npos)
        throw Failure("INCOMPATIBLE_MODEL","Adapter requires a canonical adapters/*.gguf path");
    const auto filename=path.substr(prefix.size(),path.size()-prefix.size()-5);
    if (filename.empty() || filename.size()>128 || !((filename[0]>='a'&&filename[0]<='z')||(filename[0]>='0'&&filename[0]<='9')) ||
        std::any_of(filename.begin(),filename.end(),[](char c){return !((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c=='-');}))
        throw Failure("INCOMPATIBLE_MODEL","Adapter requires a canonical lowercase filename");
    const auto & base=data.at("weights");
    const auto base_path=string(base,"path",32760),base_hash=string(base,"sha256",64);
    const auto binding=string(asset,"baseWeightsSha256",64);
    if (!hash_text(hash) || !hash_text(binding) || binding!=base_hash || path==base_path ||
        !inventory.count(base_path) || inventory.at(base_path)!=base_hash ||
        !inventory.count(path) || inventory.at(path)!=hash)
        throw Failure("INCOMPATIBLE_MODEL","Adapter and exact base weights must match the hashed inventory");
    return {path,hash};
    } catch(const Failure & failure) {
        if(failure.code=="INVALID_REQUEST") throw Failure("INCOMPATIBLE_MODEL",failure.what());
        throw;
    }
}
Package::Package(const std::atomic<bool> & stop) {
    cpu_avx2=haswell_isa();
#ifdef WORKER_TARGET_AVX2
    // Run before any engine call. /arch:AVX2 also enables FMA/F16C and
    // compiler-generated integer instructions; verify the complete profile.
    int cpu[4];
    __cpuid(cpu,0);
    if(cpu[0]<7) throw Failure("INCOMPATIBLE_MODEL","This worker requires the AVX2 CPU profile");
    __cpuid(cpu,1);
    constexpr unsigned ecx_required=(1u<<0)|(1u<<9)|(1u<<12)|(1u<<19)|(1u<<20)|(1u<<23)|(1u<<27)|(1u<<28)|(1u<<29);
    if((unsigned(cpu[2])&ecx_required)!=ecx_required || (_xgetbv(0)&6)!=6)
        throw Failure("INCOMPATIBLE_MODEL","AVX2 profile requires OS AVX state, SSE3/SSSE3/SSE4, POPCNT, FMA and F16C");
    __cpuidex(cpu,7,0);
    constexpr unsigned ebx_required=(1u<<3)|(1u<<5)|(1u<<8);
    if((unsigned(cpu[1])&ebx_required)!=ebx_required)
        throw Failure("INCOMPATIBLE_MODEL","AVX2 profile requires AVX2, BMI1 and BMI2");
    __cpuid(cpu,int(0x80000000u));
    if(unsigned(cpu[0])<0x80000001u) throw Failure("INCOMPATIBLE_MODEL","AVX2 profile requires LZCNT");
    __cpuid(cpu,int(0x80000001u));
    if((unsigned(cpu[2])&(1u<<5))==0) throw Failure("INCOMPATIBLE_MODEL","AVX2 profile requires LZCNT");
#endif
    auto self=executable_path();
    Handle self_handle(CreateFileW(self.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr));
    if (self_handle.value==INVALID_HANDLE_VALUE) throw Failure("INCOMPATIBLE_MODEL","Cannot open worker executable");
    self=final_path(self_handle.value);
    root=self.substr(0,self.find_last_of(L'\\'));
    if (!equal_path(self.substr(self.find_last_of(L'\\')+1),L"inference-worker.exe")) throw Failure("INCOMPATIBLE_MODEL","Unexpected executable name");
    Handle manifest=open("worker.json");
    auto data=parse(read_small(manifest.value,1024*1024));
    schema_version=unsigned(integer(data,"schemaVersion",1,2));
    if (schema_version==1)
        keys(data,{"schemaVersion","protocol","executable","engine","revision","architecture","supportedIsa","weights","files","precision","contextTokens","threads","minFreeMemoryBytes","maxProcessMemoryBytes"});
    else {
        Json copy=data; copy.erase("evidence"); copy.erase("adapter");
        keys(copy,{"schemaVersion","protocol","executable","engine","revision","architecture","supportedIsa","weights","files","precision","contextTokens","threads","minFreeMemoryBytes","maxProcessMemoryBytes","backend","dependencies"});
        auto & profile=data.at("backend");
        keys(profile,{"kind","device","gpuMemoryBudgetBytes","reserveFreeGpuMemoryBytes"});
        backend=string(profile,"kind",16); device=unsigned(integer(profile,"device",0,15));
        gpu_budget=integer(profile,"gpuMemoryBudgetBytes",0,9007199254740991ULL);
        gpu_reserve=integer(profile,"reserveFreeGpuMemoryBytes",0,9007199254740991ULL);
        if (!(backend=="cpu" && !device && !gpu_budget && !gpu_reserve) &&
            !(backend=="cuda" && gpu_budget>0 && gpu_reserve>=536870912ULL))
            throw Failure("INCOMPATIBLE_MODEL","Invalid explicit CPU/CUDA budget profile");
    }
    if (string(data,"protocol",256)!=protocol ||
        string(data,"executable",256)!="inference-worker.exe" || string(data,"engine",256)!="llama.cpp" ||
        string(data,"architecture",256)!="windows-x64") throw Failure("INCOMPATIBLE_MODEL","Unsupported package contract");
    // Both engine commit and preparation patch identity must be explicit in revision.
    if (schema_version==2 && string(data,"revision",512)!=WORKER_ENGINE_REVISION)
        throw Failure("INCOMPATIBLE_MODEL","Package engine revision differs from worker");
    if (!data["supportedIsa"].is_array() || data["supportedIsa"].empty() || data["supportedIsa"].size()>32)
        throw Failure("INCOMPATIBLE_MODEL","Invalid supportedIsa");
    std::set<std::string> isa;
    for (auto & item:data["supportedIsa"]) {
#ifdef WORKER_TARGET_AVX2
        if (!item.is_string() || !isa.insert(item.get<std::string>()).second ||
            (item.get<std::string>()!="x86-64" && item.get<std::string>()!="avx2"))
            throw Failure("INCOMPATIBLE_MODEL","This worker requires the explicit x86-64/avx2 package profile");
#else
        if (!item.is_string() || !isa.insert(item.get<std::string>()).second || item.get<std::string>()!="x86-64")
            throw Failure("INCOMPATIBLE_MODEL","This baseline worker requires supportedIsa x86-64 only");
#endif
    }
#ifdef WORKER_TARGET_AVX2
    if(isa!=std::set<std::string>{"x86-64","avx2"})
        throw Failure("INCOMPATIBLE_MODEL","AVX2 worker and manifest profile differ");
#endif
    context=uint32_t(integer(data,"contextTokens",1,1048576));
    threads=uint32_t(integer(data,"threads",1,256));
    precision=string(data,"precision",128);
    minimum_memory=integer(data,"minFreeMemoryBytes",1,9007199254740991ULL);
    maximum_memory=integer(data,"maxProcessMemoryBytes",1,9007199254740991ULL);
    if (maximum_memory>=minimum_memory) throw Failure("INCOMPATIBLE_MODEL","Package has no physical-memory reserve");
    if (available_memory()<minimum_memory) throw Failure("OUT_OF_MEMORY","Not enough free physical memory for this package");
    keys(data["weights"],{"path","sha256"});
    auto weights_relative=string(data["weights"],"path",32760);
    auto weights_hash=string(data["weights"],"sha256",64);
    if (!hash_text(weights_hash)) throw Failure("INCOMPATIBLE_MODEL","Invalid weights hash");
    if (!data["files"].is_array() || data["files"].size()<2 || data["files"].size()>1024)
        throw Failure("INCOMPATIBLE_MODEL","Invalid package inventory");
    std::vector<std::wstring> paths;
    bool found_self=false,found_weights=false;
    std::map<std::string,std::string> inventory;
    for (auto & entry:data["files"]) {
        keys(entry,{"path","sha256"});
        auto name=string(entry,"path",32760),hash=string(entry,"sha256",64);
        if (!inventory.emplace(name,hash).second) throw Failure("INCOMPATIBLE_MODEL","Duplicate inventory path");
        if (!hash_text(hash)) throw Failure("INCOMPATIBLE_MODEL","Invalid inventory hash");
        auto handle=open(name);
        auto resolved=final_path(handle.value);
        for (const auto & previous:paths) if (equal_path(previous,resolved)) throw Failure("INCOMPATIBLE_MODEL","Duplicate inventory path");
        paths.push_back(resolved);
        if (sha256(handle.value,stop)!=hash) throw Failure("INCOMPATIBLE_MODEL","Package SHA-256 mismatch");
        if (equal_path(resolved,self)) { if(name!="inference-worker.exe") throw Failure("INCOMPATIBLE_MODEL","Noncanonical executable path"); found_self=true; }
        if (name==weights_relative) {
            if (hash!=weights_hash) throw Failure("INCOMPATIBLE_MODEL","Conflicting weights hashes");
            LARGE_INTEGER zero{}; SetFilePointerEx(handle.value,zero,nullptr,FILE_BEGIN);
            std::array<char,4> magic{}; DWORD n=0;
            if (!ReadFile(handle.value,magic.data(),4,&n,nullptr) || n!=4 || std::string(magic.data(),4)!="GGUF")
                throw Failure("INCOMPATIBLE_MODEL","This engine requires actual GGUF model contents");
            weights=resolved; found_weights=true;
        }
        open_files.push_back(std::move(handle));
    }
    if (!found_self || !found_weights) throw Failure("INCOMPATIBLE_MODEL","Executable and weights must be inventoried");
    const auto selected_adapter=adapter_spec(data,inventory);
    if (!selected_adapter.path.empty()) {
        auto handle=open(selected_adapter.path);
        std::array<char,4> magic{}; DWORD n=0;
        if (!ReadFile(handle.value,magic.data(),4,&n,nullptr) || n!=4 || std::string(magic.data(),4)!="GGUF")
            throw Failure("INCOMPATIBLE_MODEL","Adapter requires actual GGUF contents");
        adapter=final_path(handle.value);
        open_files.push_back(std::move(handle));
    }
    std::set<std::wstring> allowed_dlls;
    if (schema_version==2) {
        if (!data["dependencies"].is_array() || data["dependencies"].size()>64)
            throw Failure("INCOMPATIBLE_MODEL","Invalid DLL inventory");
        for (auto & dependency:data["dependencies"]) {
            keys(dependency,{"path","sha256"});
            auto path=string(dependency,"path",32760),hash=string(dependency,"sha256",64);
            std::string lowercase=path;std::transform(lowercase.begin(),lowercase.end(),lowercase.begin(),[](unsigned char ch){return char(std::tolower(ch));});
            if (path.rfind("runtime/",0)!=0 || path.find('/',8)!=std::string::npos ||
                lowercase!=path ||
                path.size()<13 || path.substr(path.size()-4)!=".dll" || !inventory.count(path) || inventory.at(path)!=hash ||
                path=="runtime/nvcuda.dll" || path.find("api-ms-")!=std::string::npos || path.find("ext-ms-")!=std::string::npos)
                throw Failure("INCOMPATIBLE_MODEL","DLL must be a hashed runtime/*.dll inventory entry");
            auto handle=open(path); auto resolved=final_path(handle.value);
            if (!allowed_dlls.insert(resolved).second) throw Failure("INCOMPATIBLE_MODEL","Duplicate dependency");
            dependencies.push_back(resolved);
        }
        if (data.contains("evidence")) {
            const auto & evidence=data.at("evidence");
            auto check_asset=[&](const Json & asset) {
                keys(asset,{"path","sha256"}); auto p=string(asset,"path",32760);
                if (!inventory.count(p) || inventory.at(p)!=string(asset,"sha256",64))
                    throw Failure("INCOMPATIBLE_MODEL","Evidence asset must be inventoried");
            };
            Json copy=evidence; copy.erase("reranker");
            keys(copy,{"embedder","tokenizerDirectory","index"});
            check_asset(evidence.at("embedder")); check_asset(evidence.at("index"));
            if (evidence.contains("reranker")) check_asset(evidence.at("reranker"));
            (void)relative_path(string(evidence,"tokenizerDirectory",32760));
        }
    }
    size_t tree_count=0; check_inventory_tree(root,self,tree_count,allowed_dlls);
    open_files.push_back(std::move(manifest));
    open_files.push_back(std::move(self_handle));
    verified_assets=std::move(inventory);inventory_verified=true;
}

void Package::load_runtime() const {
    if (schema_version!=2) throw Failure("INCOMPATIBLE_MODEL","This dynamic worker requires package schema 2");
    auto directory=root+L"\\runtime";
    if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32|LOAD_LIBRARY_SEARCH_USER_DIRS) ||
        !AddDllDirectory(directory.c_str())) throw Failure("INCOMPATIBLE_MODEL","Cannot set bounded runtime DLL search path");
    // Load only after all hashes have passed. Delay-imports prevent loader work before validation.
    for (const wchar_t * name:{L"ggml-base.dll",L"ggml.dll",L"llama.dll"}) {
        auto path=directory+L"\\"+name;
        bool found=false; for(const auto & item:dependencies) if(equal_path(path,item)) found=true;
        if (!found || !LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32|LOAD_LIBRARY_SEARCH_USER_DIRS))
            throw Failure("INCOMPATIBLE_MODEL","Cannot load inventoried engine runtime");
    }
}
}
