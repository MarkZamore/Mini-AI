#include "evidence.hpp"
#include <onnxruntime_cxx_api.h>
#include <sentencepiece_processor.h>
#include <fstream>
#include <numeric>
#include <thread>
#include <unordered_set>

namespace worker {
namespace {
constexpr const char * evidence_protocol="enterpriseai.evidence.v1";
std::string read_line() {
    std::string line; unsigned char b; DWORD n;
    while(ReadFile(GetStdHandle(STD_INPUT_HANDLE),&b,1,&n,nullptr)&&n) {
        if(b=='\n') {if(!line.empty()&&line.back()=='\r')line.pop_back();return line;}
        if(line.size()>=max_frame)invalid("Evidence frame exceeds 8 MiB");
        line.push_back(char(b));
    }
    return {};
}
void emit(const Json & frame,std::mutex & mutex) {
    auto text=frame.dump();if(text.size()>max_frame)invalid("Evidence response exceeds 8 MiB");text+='\n';
    std::lock_guard<std::mutex> lock(mutex);size_t offset=0;
    while(offset<text.size()){DWORD n=0;if(!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),text.data()+offset,DWORD(text.size()-offset),&n,nullptr)||!n)throw Failure("INTERNAL_ERROR","Evidence output closed");offset+=n;}
}
std::string data_file(const Package & p,const std::string & name,size_t maximum=1024*1024) {
    auto file=p.open(name);return read_small(file.value,maximum);
}
struct Tokenizer {
    sentencepiece::SentencePieceProcessor processor;
    size_t maximum;
    explicit Tokenizer(const Package & p,const std::string & directory,size_t limit):maximum(limit) {
        auto bytes=data_file(p,directory+"/sentencepiece.bpe.model",16*1024*1024);
        auto status=processor.LoadFromSerializedProto(bytes);
        if(!status.ok())throw Failure("INCOMPATIBLE_MODEL","Cannot load verified SentencePiece model");
    }
    std::vector<int64_t> pieces(const std::string & text) const {
        // Hugging Face splits added special tokens before the SentencePiece
        // normalizer. Encoding their literal spelling as ordinary text changes
        // both token IDs and the embedding, even though normal prose agrees.
        const std::array<std::pair<const char *,int64_t>,5> special{{
            {"<s>",0},{"<pad>",1},{"</s>",2},{"<unk>",3},{"<mask>",250001}}};
        std::vector<int64_t> result;
        auto append=[&](size_t begin,size_t end){
            if(begin==end)return;
            std::vector<int> ids;auto status=processor.Encode(text.substr(begin,end-begin),&ids);
            if(!status.ok())invalid("SentencePiece encoding failed");
            for(auto id:ids)result.push_back(id==processor.unk_id()?3:int64_t(id)+1);
        };
        size_t offset=0;
        while(offset<text.size()){
            size_t next=std::string::npos,chosen=0;
            for(size_t i=0;i<special.size();++i){auto position=text.find(special[i].first,offset);
                if(position<next){next=position;chosen=i;}}
            if(next==std::string::npos){append(offset,text.size());break;}
            append(offset,next);result.push_back(special[chosen].second);
            offset=next+std::char_traits<char>::length(special[chosen].first);
        }
        return result;
    }
    std::vector<int64_t> single(const std::string & text)const {
        auto ids=pieces(text);if(ids.size()>maximum-2)ids.resize(maximum-2);
        ids.insert(ids.begin(),0);ids.push_back(2);return ids;
    }
    std::vector<int64_t> pair(const std::string & query,const std::string & passage)const {
        auto a=pieces(query),b=pieces(passage);
        while(a.size()+b.size()>maximum-4){if(a.size()>b.size())a.pop_back();else b.pop_back();}
        a.insert(a.begin(),0);a.push_back(2);a.push_back(2);a.insert(a.end(),b.begin(),b.end());a.push_back(2);return a;
    }
};
class Model {
    Ort::Session session{nullptr};
    std::vector<std::string> names;
    std::vector<const char *> inputs;
    std::string output;
public:
    Model(Ort::Env & env,const Package & p,const std::string & path) {
        Ort::SessionOptions options;options.SetIntraOpNumThreads(4);options.SetInterOpNumThreads(1);
        options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        options.SetLogSeverityLevel(4);options.DisableProfiling();
        auto locked=p.open(path);auto full=final_path(locked.value);
        session=Ort::Session(env,full.c_str(),options);
        Ort::AllocatorWithDefaultOptions allocator;
        for(size_t i=0;i<session.GetInputCount();++i){auto n=session.GetInputNameAllocated(i,allocator);names.emplace_back(n.get());}
        for(auto & n:names){if(n!="input_ids"&&n!="attention_mask"&&n!="token_type_ids")throw Failure("INCOMPATIBLE_MODEL","Unsupported ONNX input");inputs.push_back(n.c_str());}
        auto n=session.GetOutputNameAllocated(0,allocator);output=n.get();
    }
    std::vector<float> run(const std::vector<int64_t> & ids,bool embedding) {
        auto memory=Ort::MemoryInfo::CreateCpu(OrtArenaAllocator,OrtMemTypeDefault);
        std::vector<int64_t> mask(ids.size(),1),types(ids.size(),0),tokens=ids;
        std::array<int64_t,2> shape{1,int64_t(ids.size())};std::vector<Ort::Value> values;
        for(const auto & name:names){auto & data=name=="input_ids"?tokens:name=="attention_mask"?mask:types;
            values.push_back(Ort::Value::CreateTensor<int64_t>(memory,data.data(),data.size(),shape.data(),shape.size()));}
        const char * outputs[]{output.c_str()};Ort::RunOptions run_options;
        auto result=session.Run(run_options,inputs.data(),values.data(),values.size(),outputs,1);
        auto info=result[0].GetTensorTypeAndShapeInfo();auto dimensions=info.GetShape();
        if(info.GetElementType()!=ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)throw Failure("INCOMPATIBLE_MODEL","FP32 ONNX output required");
        const float * data=result[0].GetTensorData<float>();
        if(!embedding){if(info.GetElementCount()!=1)throw Failure("INCOMPATIBLE_MODEL","One reranker logit required");return {data[0]};}
        std::vector<float> vector(384,0.f);
        if(dimensions.size()==2&&dimensions[0]==1&&dimensions[1]==384)std::copy(data,data+384,vector.begin());
        else if(dimensions.size()==3&&dimensions[0]==1&&dimensions[1]==int64_t(ids.size())&&dimensions[2]==384){
            for(size_t i=0;i<ids.size();++i)for(size_t d=0;d<384;++d)vector[d]+=data[i*384+d]/float(ids.size());
        }else throw Failure("INCOMPATIBLE_MODEL","Expected E5 mean embedding dimension 384");
        double norm=0;for(float v:vector){if(!std::isfinite(v))throw Failure("INTERNAL_ERROR","Non-finite embedding");norm+=double(v)*v;}
        if(norm<1e-20)throw Failure("INTERNAL_ERROR","Empty embedding");for(float & v:vector)v=float(v/std::sqrt(norm));return vector;
    }
};
struct Entry{std::string id,document;std::vector<std::string> sections;};
class Evidence {
    std::atomic<bool> & cancel;
    Package package;
    std::unique_ptr<Ort::Env> env;
    Json settings,index;
    std::unique_ptr<Tokenizer> embed_tokenizer,rank_tokenizer;
    std::unique_ptr<Model> embedder,ranker;
    std::vector<Entry> entries;
    std::vector<float> vectors;
public:
    Evidence(std::atomic<bool> & stop,std::atomic<bool> & cancellation):cancel(cancellation),package(stop) {
        package.load_runtime();env=std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_ERROR,"Mini-AI-evidence");env->DisableTelemetryEvents();
        auto manifest=parse(data_file(package,"worker.json"));
        if(!manifest.contains("evidence"))throw Failure("EVIDENCE_UNAVAILABLE","Evidence model is not configured");
        settings=manifest.at("evidence");auto directory=string(settings,"tokenizerDirectory",32760);
        embed_tokenizer=std::make_unique<Tokenizer>(package,directory+"/embedder",256);
        embedder=std::make_unique<Model>(*env,package,string(settings.at("embedder"),"path",32760));
        index=parse(data_file(package,string(settings.at("index"),"path",32760)));
        if(integer(index,"schemaVersion",1,1)!=1||integer(index,"dimensions",384,384)!=384)invalid("Invalid evidence index");
        if(string(index,"embedderSha256",64)!=string(settings.at("embedder"),"sha256",64))
            throw Failure("MODEL_HASH_MISMATCH","Evidence index belongs to another embedding model");
        auto rows=size_t(integer(index,"rows",1,500000));
        auto metadata=data_file(package,string(index,"fragments",32760),128*1024*1024);
        size_t offset=0;while(offset<metadata.size()){
            auto end=metadata.find('\n',offset);if(end==std::string::npos)end=metadata.size();
            if(end>offset){auto item=parse(metadata.substr(offset,end-offset));Entry entry{string(item,"fragment_id",128),string(item,"document_id",128),{}};
                if(item.contains("sections"))for(auto & s:item.at("sections"))entry.sections.push_back(s.get<std::string>());entries.push_back(std::move(entry));}
            offset=end+1;
        }
        if(entries.size()!=rows)invalid("Evidence row inventory mismatch");
        auto data=package.open(string(index,"vectors",32760));LARGE_INTEGER length{};
        if(!GetFileSizeEx(data.value,&length)||uint64_t(length.QuadPart)!=uint64_t(rows)*384*sizeof(float))invalid("Evidence vector size mismatch");
        vectors.resize(rows*384);size_t bytes=vectors.size()*sizeof(float),read=0;
        while(read<bytes){DWORD n=0;if(!ReadFile(data.value,reinterpret_cast<char*>(vectors.data())+read,DWORD(std::min(bytes-read,size_t(1<<20))),&n,nullptr)||!n)invalid("Cannot read vector matrix");read+=n;}
        for(float value:vectors)if(!std::isfinite(value))invalid("Invalid index vector");
    }
    Json execute(const Json & request) {
        auto type=string(request,"type",32);
        if(type=="embed"){
            keys(request,{"type","requestId","texts","queries"});if(!request.at("texts").is_array()||request.at("texts").size()>32||!request.at("queries").is_boolean())invalid("Invalid embedding batch");
            Json output=Json::array();for(const auto & text:request.at("texts")){if(cancel.load())throw Failure("CANCELLED","Evidence operation cancelled");
                if(!text.is_string()||text.get_ref<const std::string&>().size()>262144)invalid("Evidence text exceeds limit");
                output.push_back(embedder->run(embed_tokenizer->single(std::string(request.at("queries").get<bool>()?"query: ":"passage: ")+text.get<std::string>()),true));}
            return {{"vectors",output}};
        }
        if(type=="retrieve"){
            keys(request,{"type","requestId","baseId","query","limit","documentIds","sections"});
            if(string(request,"baseId",128)!=index.value("baseId",std::string("local")))throw Failure("BASE_MISMATCH","Evidence index belongs to another base");
            auto limit=size_t(integer(request,"limit",1,300));auto query=string(request,"query",131000);
            std::unordered_set<std::string> documents,sections;
            for(const auto & id:request.at("documentIds"))documents.insert(id.get<std::string>());
            for(const auto & section:request.at("sections"))sections.insert(section.get<std::string>());
            auto q=embedder->run(embed_tokenizer->single("query: "+query),true);std::vector<std::pair<float,size_t>> scores;
            double threshold=index.value("minScore",1.0);
            if(!std::isfinite(threshold)||threshold< -1||threshold>1)invalid("Invalid evidence relevance threshold");
            for(size_t i=0;i<entries.size();++i){if((i&255)==0&&cancel.load())throw Failure("CANCELLED","Evidence operation cancelled");
                if(!documents.empty()&&!documents.count(entries[i].document))continue;
                if(!sections.empty()&&std::none_of(entries[i].sections.begin(),entries[i].sections.end(),[&](const std::string & s){return sections.count(s)>0;}))continue;
                const float * v=vectors.data()+i*384;float score=0;for(size_t d=0;d<384;++d)score+=q[d]*v[d];
                if(score>=threshold)scores.emplace_back(score,i);
            }
            auto count=std::min(limit,scores.size());std::partial_sort(scores.begin(),scores.begin()+count,scores.end(),[](auto a,auto b){return a.first==b.first?a.second<b.second:a.first>b.first;});
            Json hits=Json::array();for(size_t i=0;i<count;++i){const auto & entry=entries[scores[i].second];hits.push_back({{"fragmentId",entry.id},{"documentId",entry.document},{"score",scores[i].first}});}return {{"hits",hits}};
        }
        if(type=="rerank"){
            keys(request,{"type","requestId","query","candidates"});if(!settings.contains("reranker"))throw Failure("RERANKER_UNAVAILABLE","Reranker is not configured");
            if(!request.at("candidates").is_array()||request.at("candidates").size()>128)invalid("Invalid reranking batch");
            if(!ranker){rank_tokenizer=std::make_unique<Tokenizer>(package,string(settings,"tokenizerDirectory",32760)+"/reranker",256);ranker=std::make_unique<Model>(*env,package,string(settings.at("reranker"),"path",32760));}
            auto query=string(request,"query",131000);Json hits=Json::array();std::set<std::string> seen;
            for(const auto & candidate:request.at("candidates")){if(cancel.load())throw Failure("CANCELLED","Evidence operation cancelled");keys(candidate,{"fragmentId","documentId","text"});
                auto id=string(candidate,"fragmentId",128);if(!seen.insert(id).second)invalid("Duplicate reranking ID");
                auto logit=ranker->run(rank_tokenizer->pair(query,string(candidate,"text",131000)),false)[0];if(!std::isfinite(logit))invalid("Non-finite reranker score");
                hits.push_back({{"fragmentId",id},{"documentId",string(candidate,"documentId",128)},{"score",logit}});}
            return {{"hits",hits}};
        }
        if(type=="tokenize"){
            keys(request,{"type","requestId","text","pair","reranker"});bool rank=request.at("reranker").get<bool>();
            auto & tokenizer=rank?rank_tokenizer:embed_tokenizer;if(!tokenizer)tokenizer=std::make_unique<Tokenizer>(package,string(settings,"tokenizerDirectory",32760)+"/reranker",256);
            auto text=string(request,"text",131000,true),pair=string(request,"pair",131000,true);
            return {{"ids",pair.empty()?tokenizer->single(text):tokenizer->pair(text,pair)}};
        }
        invalid("Unknown evidence operation");
    }
};
}
int run_evidence_stdio(const Json & hello) {
    std::mutex output;std::atomic<bool> stop{false},cancel{false},busy{false};std::thread job;std::string active;
    // The worker thread borrows the sessions and vector matrix. Keep their
    // owner outside the try so protocol exceptions join the thread before
    // destroying the models during stack unwinding.
    std::unique_ptr<Evidence> evidence;
    try {
        keys(hello,{"type","protocol"});if(string(hello,"type",32)!="hello"||string(hello,"protocol",128)!=evidence_protocol)invalid("Invalid evidence handshake");
        evidence=std::make_unique<Evidence>(stop,cancel);emit({{"type","ready"},{"protocol",evidence_protocol}},output);
        for(;;){auto line=read_line();if(line.empty())break;auto request=parse(line);auto type=string(request,"type",32);auto id=string(request,"requestId",128);
            if(type=="cancel"){keys(request,{"type","requestId"});if(busy.load()&&id==active)cancel.store(true);continue;}
            if(busy.load()){emit({{"type","error"},{"requestId",id},{"code","BUSY"},{"message","Evidence operation already active"}},output);continue;}
            if(job.joinable())job.join();active=id;cancel.store(false);busy.store(true);
            job=std::thread([&,request,id]{try{auto result=evidence->execute(request);if(cancel.load())throw Failure("CANCELLED","Evidence operation cancelled");busy.store(false);emit({{"type","result"},{"requestId",id},{"result",result}},output);}
                catch(const Failure & e){busy.store(false);emit({{"type","error"},{"requestId",id},{"code",e.code},{"message",std::string(e.what()).substr(0,4096)}},output);}
                catch(const std::exception & e){busy.store(false);emit({{"type","error"},{"requestId",id},{"code","EVIDENCE_FAILURE"},{"message",std::string(e.what()).substr(0,4096)}},output);}});
        }
        cancel.store(true);stop.store(true);if(job.joinable())job.join();return 0;
    }catch(const std::exception & e){cancel.store(true);stop.store(true);if(job.joinable())job.join();try{emit({{"type","error"},{"code","EVIDENCE_STARTUP"},{"message",std::string(e.what()).substr(0,4096)}},output);}catch(...){}return 2;}
}
}
