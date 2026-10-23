#include "support.hpp"
#include "llama.h"
#include "ggml-backend.h"
#include "json.h"
#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"
#include "chat.hpp"
#include "citations.hpp"
#include <thread>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <clocale>
#include <chrono>
#include <signal.h>
#ifdef WORKER_HAS_EVIDENCE
#include "evidence/evidence.hpp"
#endif

namespace worker {
class Worker {
    std::atomic<bool> stop{false},cancel{false};
    std::atomic<size_t> diagnostic_bytes{0};
    unsigned usage_frames=0;
    std::mutex state_mutex,output_mutex;
    bool hello_seen=false,ready=false;
    std::string active_id;
    std::thread job;
    std::unique_ptr<Package> package;
    std::unique_ptr<llama_model,decltype(&llama_model_free)> model{nullptr,llama_model_free};
    std::unique_ptr<llama_context,decltype(&llama_free)> context{nullptr,llama_free};
    std::unique_ptr<ChatTemplate> chat_program;
    std::string chat_template;
    const llama_vocab * vocabulary=nullptr;
    ggml_backend_dev_t gpu=nullptr;
    size_t gpu_free_before=0;

    void emit(const Json & frame,bool force=false) {
        if (stop.load() && !force) return;
        auto text=frame.dump();
        if (text.size()>max_frame) throw Failure("INTERNAL_ERROR","Output protocol frame exceeds limit");
        text.push_back('\n');
        std::lock_guard<std::mutex> lock(output_mutex);
        size_t offset=0;
        while (offset<text.size()) {
            DWORD n=0;
            if (!WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),text.data()+offset,DWORD(text.size()-offset),&n,nullptr) || !n) {
                stop.store(true); cancel.store(true);
                throw Failure("INTERNAL_ERROR","Output pipe is closed");
            }
            offset+=n;
        }
    }
    void error(const std::string & id,const std::string & code,const std::string & message,bool force=false) {
        Json frame={{"type","error"},{"code",code},{"message",message.substr(0,4096)}};
        if (!id.empty()) frame["requestId"]=id;
        emit(frame,force);
    }
    static void log(enum ggml_log_level,const char * text,void * user) {
        auto & self=*static_cast<Worker *>(user);
        constexpr size_t limit=48*1024;
        size_t n=strnlen_s(text,8192);
        size_t before=self.diagnostic_bytes.fetch_add(n);
        if (before>=limit) return;
        n=std::min(n,limit-before);
        DWORD ignored;
        WriteFile(GetStdHandle(STD_ERROR_HANDLE),text,DWORD(n),&ignored,nullptr);
    }
    static bool progress(float,void * user) { return !static_cast<Worker *>(user)->stop.load(); }
    static bool abort_generation(void * user) {
        auto & self=*static_cast<Worker *>(user);
        return self.stop.load() || self.cancel.load();
    }
    void startup() {
        try {
            try { package=std::make_unique<Package>(stop); }
            catch(const Failure & e) {
                if (e.code=="INVALID_REQUEST") throw Failure("INCOMPATIBLE_MODEL",e.what());
                throw;
            }
            package->load_runtime();
            llama_log_set(log,this);
            ggml_log_set(log,this);
            llama_backend_init();
            // Register only the specific, already hashed CPU/CUDA libraries, never PATH scans.
            bool haswell_in_inventory=false;
            for (const auto & dependency:package->dependencies)
                if(dependency.substr(dependency.find_last_of(L'\\')+1)==L"ggml-cpu-haswell.dll")haswell_in_inventory=true;
            for (const auto & dependency:package->dependencies) {
                const auto name=dependency.substr(dependency.find_last_of(L'\\')+1);
                const bool selected_cpu=(package->cpu_avx2 && haswell_in_inventory)
                    ? name==L"ggml-cpu-haswell.dll" : name==L"ggml-cpu-x64.dll";
                if (selected_cpu || (package->backend=="cuda" && name==L"ggml-cuda.dll")) {
                    if (!ggml_backend_load(utf8(dependency).c_str()))
                        throw Failure("INCOMPATIBLE_MODEL","Cannot initialize the inventoried compute backend");
                }
            }
            if (package->backend=="cuda") {
                unsigned index=0;
                for (size_t i=0;i<ggml_backend_dev_count();++i) {
                    auto candidate=ggml_backend_dev_get(i);
                    if (ggml_backend_dev_type(candidate)==GGML_BACKEND_DEVICE_TYPE_GPU &&
                        std::string(ggml_backend_reg_name(ggml_backend_dev_backend_reg(candidate)))=="CUDA" && index++==package->device) {gpu=candidate;break;}
                }
                if (!gpu) throw Failure("INCOMPATIBLE_MODEL","Requested CUDA device is unavailable; backend is not silently changed");
                size_t total=0; ggml_backend_dev_memory(gpu,&gpu_free_before,&total);
                if (package->gpu_budget>total || package->gpu_reserve>gpu_free_before || package->gpu_budget>gpu_free_before-package->gpu_reserve)
                    throw Failure("OUT_OF_MEMORY","Free CUDA memory is below the explicit budget plus reserve");
            }
            auto parameters=llama_model_default_params();
            ggml_backend_dev_t selected_devices[2]={gpu,nullptr};
            parameters.devices=selected_devices;
            parameters.n_gpu_layers=gpu?-1:0;
            parameters.split_mode=LLAMA_SPLIT_MODE_NONE;
            parameters.load_mode=LLAMA_LOAD_MODE_MMAP;
            parameters.check_tensors=true;
            parameters.use_extra_bufts=false;
            parameters.progress_callback=progress;
            parameters.progress_callback_user_data=this;
            auto path=utf8(package->weights);
            model.reset(llama_model_load_from_file(path.c_str(),parameters));
            if (!model) throw Failure("INCOMPATIBLE_MODEL","Engine could not load the verified model contents");
            vocabulary=llama_model_get_vocab(model.get());
            if (!vocabulary || llama_model_has_encoder(model.get())) throw Failure("INCOMPATIBLE_MODEL","A decoder-only text model is required");
            char architecture[256]{};
            if (llama_model_meta_val_str(model.get(),"general.architecture",architecture,sizeof(architecture))<1)
                throw Failure("INCOMPATIBLE_MODEL","Model architecture metadata is missing");
            if(std::string(architecture)!="qwen35") throw Failure("INCOMPATIBLE_MODEL","This worker profile has only Qwen3.5 text-architecture support validated for preparation");
            auto template_pointer=llama_model_chat_template(model.get(),nullptr);
            if (!template_pointer || strnlen_s(template_pointer,1024*1024)>=1024*1024)
                throw Failure("INCOMPATIBLE_MODEL","A bounded embedded chat template is required");
            chat_template=template_pointer;
            chat_program=std::make_unique<ChatTemplate>(chat_template);
            auto parameters_context=llama_context_default_params();
            parameters_context.n_ctx=package->context;
            parameters_context.n_batch=std::min(package->context,uint32_t(gpu?512:128));
            parameters_context.n_ubatch=parameters_context.n_batch;
            parameters_context.n_seq_max=1;
            parameters_context.n_threads=int32_t(package->threads);
            parameters_context.n_threads_batch=int32_t(package->threads);
            parameters_context.offload_kqv=gpu!=nullptr;
            parameters_context.op_offload=gpu!=nullptr;
            parameters_context.abort_callback=abort_generation;
            parameters_context.abort_callback_data=this;
            context.reset(llama_init_from_model(model.get(),parameters_context));
            if (!context) throw Failure("OUT_OF_MEMORY","Cannot allocate the requested CPU context");
            if (gpu) {
                size_t free=0,total=0; ggml_backend_dev_memory(gpu,&free,&total);
                if (free<package->gpu_reserve || (gpu_free_before>free && gpu_free_before-free>package->gpu_budget))
                    throw Failure("OUT_OF_MEMORY","CUDA allocation exceeded the explicit budget or free-memory reserve");
            }
            if (llama_n_ctx(context.get())<package->context)
                throw Failure("INCOMPATIBLE_MODEL","Engine did not allocate the requested context");
            // Exercise template compatibility before reporting ready, with no user-specified paths.
            (void) render_tokens(Json::array({{{"role","user"},{"content","Hello"}}}));
            if (!stop.load()) {
                {std::lock_guard<std::mutex> lock(state_mutex); ready=true;}
                emit({{"type","ready"},{"protocol",protocol}});
            }
        } catch(const std::bad_alloc &) {
            try { error("","OUT_OF_MEMORY","Insufficient memory during worker initialization"); } catch(...) {}
            stop.store(true);
        } catch(const Failure & e) {
            try { error("",e.code,e.what()); } catch(...) {}
            stop.store(true);
        } catch(const std::exception &) {
            try { error("","INCOMPATIBLE_MODEL","Model or embedded chat template is incompatible"); } catch(...) {}
            stop.store(true);
        }
        // Wake a blocked input read after terminal startup failure.
        if (stop.load()) CancelSynchronousIo(input_thread);
    }
    HANDLE input_thread=nullptr;
    std::vector<llama_token> tokenize(const std::string & text,bool add_special,bool parse_special) {
        if (text.empty()) return {};
        int n=llama_tokenize(vocabulary,text.data(),int(text.size()),nullptr,0,add_special,parse_special);
        if (n==INT32_MIN || n>0) throw Failure("INCOMPATIBLE_MODEL","Unexpected tokenizer result");
        if (n==0) return {};
        size_t count=size_t(-int64_t(n));
        if (count>package->context) throw Failure("CONTEXT_TOO_LARGE","Tokenized prompt exceeds package context");
        std::vector<llama_token> tokens(count);
        n=llama_tokenize(vocabulary,text.data(),int(text.size()),tokens.data(),int(tokens.size()),add_special,parse_special);
        if (n<0 || size_t(n)!=count) throw Failure("INTERNAL_ERROR","Tokenization failed");
        return tokens;
    }
    std::vector<llama_token> render_tokens(const Json & messages) {
        // Mark message strings as input so document/user token-like text never becomes control tokens.
        auto parts=chat_program->render(messages).parts;
        size_t bytes=0;
        std::vector<llama_token> result;
        for (auto & part:parts) {
            bytes+=part.val.size();
            if (bytes>2*max_frame) throw Failure("CONTEXT_TOO_LARGE","Rendered prompt exceeds buffer limit");
            auto tokens=tokenize(part.val,false,!part.is_input);
            if (result.size()+tokens.size()>package->context) throw Failure("CONTEXT_TOO_LARGE","Tokenized prompt exceeds package context");
            result.insert(result.end(),tokens.begin(),tokens.end());
        }
        return result;
    }
    std::string next_utf8(std::string & pending,bool final=false) {
        // Byte-fallback tokens may end inside a UTF-8 code point. Retain only that bounded suffix.
        size_t offset=0;
        while (offset<pending.size()) {
            auto c=static_cast<unsigned char>(pending[offset]);
            size_t count=c<0x80 ? 1 : c>=0xc2 && c<=0xdf ? 2 : c>=0xe0 && c<=0xef ? 3 : c>=0xf0 && c<=0xf4 ? 4 : 0;
            if (!count) throw Failure("INTERNAL_ERROR","Model emitted invalid UTF-8");
            if (offset+count>pending.size()) break;
            for(size_t j=1;j<count;++j) if((static_cast<unsigned char>(pending[offset+j])&0xc0)!=0x80)
                throw Failure("INTERNAL_ERROR","Model emitted invalid UTF-8");
            if (count>1) {
                auto b=static_cast<unsigned char>(pending[offset+1]);
                if ((c==0xe0 && b<0xa0)||(c==0xed && b>=0xa0)||(c==0xf0 && b<0x90)||(c==0xf4 && b>=0x90))
                    throw Failure("INTERNAL_ERROR","Model emitted invalid UTF-8");
            }
            offset+=count;
        }
        auto result=pending.substr(0,offset);
        pending.erase(0,offset);
        if ((final && !pending.empty()) || pending.size()>3) throw Failure("INTERNAL_ERROR","Model emitted incomplete UTF-8");
        return result;
    }
    void generate(Json request) {
        auto id=request["requestId"].get<std::string>();
        try {
            if (gpu) {
                // A resident process must recheck the reserve after other applications allocated VRAM.
                // The startup budget was admitted before allocation; global free memory is not a per-process quota.
                size_t free=0,total=0;ggml_backend_dev_memory(gpu,&free,&total);
                if(free<package->gpu_reserve)
                    throw Failure("OUT_OF_MEMORY","CUDA free-memory reserve is no longer available for this request");
            }
            CitationGate citations(request["messages"]);
            auto tokens=render_tokens(request["messages"]);
            auto maximum=request["maxTokens"].get<uint32_t>();
            if (tokens.empty() || tokens.size()+maximum>package->context) throw Failure("CONTEXT_TOO_LARGE","Prompt and requested answer exceed the package context");
            llama_memory_clear(llama_get_memory(context.get()),true);
            std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
            if (!sampler) throw std::bad_alloc();
            // v1 permits an empty text chunk. It carries no answer content,
            // but lets the unchanged app distinguish long prompt processing
            // from a stalled worker. Only completed compute batches/steps can
            // produce this progress signal; no independent keepalive thread.
            auto last_frame=std::chrono::steady_clock::now();
            auto report_progress=[&]() {
                auto now=std::chrono::steady_clock::now();
                if(!abort_generation(this) && now-last_frame>=std::chrono::seconds(10)) {
                    emit({{"type","token"},{"requestId",id},{"text",""}});
                    last_frame=now;
                }
            };
            size_t offset=0;
            while (offset<tokens.size() && !abort_generation(this)) {
                int count=int(std::min(size_t(128),tokens.size()-offset));
                auto batch=llama_batch_get_one(tokens.data()+offset,count);
                auto code=llama_decode(context.get(),batch);
                if (code!=0 && !abort_generation(this)) throw Failure(code==-2 ? "OUT_OF_MEMORY" : "INTERNAL_ERROR","CPU prompt evaluation failed");
                offset+=count;
                report_progress();
            }
            std::string pending;
            uint32_t generated_tokens=0;
            for (uint32_t index=0;index<maximum && !abort_generation(this);++index) {
                auto token=llama_sampler_sample(sampler.get(),context.get(),-1);
                if (llama_vocab_is_eog(vocabulary,token)) break;
                ++generated_tokens;
                std::array<char,512> piece{};
                int n=llama_token_to_piece(vocabulary,token,piece.data(),int(piece.size()),0,false);
                if (n<0) {
                    if (-int64_t(n)>32768) throw Failure("INCOMPATIBLE_MODEL","Vocabulary token exceeds output buffer limit");
                    std::vector<char> larger(size_t(-int64_t(n)));
                    n=llama_token_to_piece(vocabulary,token,larger.data(),int(larger.size()),0,false);
                    if (n<0) throw Failure("INTERNAL_ERROR","Token decoding failed");
                    pending.append(larger.data(),size_t(n));
                } else pending.append(piece.data(),size_t(n));
                auto text=next_utf8(pending);
                if (!text.empty() && !abort_generation(this)) {
                    auto safe=citations.push(text);
                    if(!safe.empty()) {
                        emit({{"type","token"},{"requestId",id},{"text",safe}});
                        last_frame=std::chrono::steady_clock::now();
                    }
                }
                if(index+1<maximum && !abort_generation(this)) {
                    auto batch=llama_batch_get_one(&token,1);
                    int code=llama_decode(context.get(),batch);
                    if(code!=0 && !abort_generation(this)) throw Failure(code==-2 ? "OUT_OF_MEMORY" : "INTERNAL_ERROR","CPU token evaluation failed");
                }
                report_progress();
            }
            if (!stop.load()) {
                if (usage_frames++<64) {
                    // Diagnostics only: generation JSONL v1 stays unchanged. Tokens exclude EOG, not text frames.
                    Json statistics={{"requestId",id},{"input_tokens",tokens.size()},
                        {"output_tokens",generated_tokens},{"finish_reason",cancel.load()?"cancel":generated_tokens>=maximum?"length":"eos"}};
                    if(gpu) {size_t free=0,total=0;ggml_backend_dev_memory(gpu,&free,&total);
                        statistics["gpu_free_bytes"]=free;statistics["gpu_initial_free_bytes"]=gpu_free_before;
                        statistics["gpu_observed_delta_bytes"]=gpu_free_before>free?gpu_free_before-free:0;}
                    auto usage=std::string("MINI_AI_USAGE ")+statistics.dump()+"\n";
                    DWORD written=0;WriteFile(GetStdHandle(STD_ERROR_HANDLE),usage.data(),DWORD(usage.size()),&written,nullptr);
                }
                if(cancel.load()) emit({{"type","cancelled"},{"requestId",id}});
                else {
                    (void)next_utf8(pending,true);
                    auto tail=citations.finish();
                    if(!tail.empty())emit({{"type","token"},{"requestId",id},{"text",tail}});
                    emit({{"type","done"},{"requestId",id}});
                }
            }
        } catch(const std::bad_alloc &) {
            try { if(cancel.load()) emit({{"type","cancelled"},{"requestId",id}}); else error(id,"OUT_OF_MEMORY","Insufficient memory during generation"); } catch(...) {}
        } catch(const Failure & e) {
            try { if(cancel.load()) emit({{"type","cancelled"},{"requestId",id}}); else error(id,e.code,e.what()); } catch(...) {}
        } catch(const std::exception &) {
            try { error(id,"INTERNAL_ERROR","Generation or chat template evaluation failed"); } catch(...) {}
        }
        {
            std::lock_guard<std::mutex> lock(state_mutex);
            active_id.clear();
        }
        if(stop.load()) CancelSynchronousIo(input_thread);
    }
    void frame(const Json & value) {
        auto type=string(value,"type",32);
        if (type=="hello") {
            keys(value,{"type","protocol"});
            if (hello_seen || string(value,"protocol",256)!=protocol) invalid("Unexpected handshake or protocol version");
            hello_seen=true;
            job=std::thread([this]{startup();});
        } else if (type=="generate") {
            keys(value,{"type","requestId","messages","maxTokens"});
            auto id=string(value,"requestId",128);
            auto & messages=value.at("messages");
            if (!messages.is_array() || messages.empty() || messages.size()>4096) invalid("Invalid messages array");
            for (auto & message:messages) {
                keys(message,{"role","content"});
                auto role=string(message,"role",16);
                if (role!="system" && role!="user" && role!="assistant") invalid("Unsupported message role");
                (void)string(message,"content",max_frame,true);
            }
            {
                std::lock_guard<std::mutex> lock(state_mutex);
                if (!ready || !active_id.empty()) invalid("Generate requires ready and no active request");
                integer(value,"maxTokens",1,package->context);
                active_id=id;
                cancel.store(false);
            }
            if (job.joinable()) job.join();
            job=std::thread([this,value]{generate(value);});
        } else if (type=="cancel") {
            keys(value,{"type","requestId"});
            auto id=string(value,"requestId",128);
            std::lock_guard<std::mutex> lock(state_mutex);
            if (!ready || active_id!=id || active_id.empty()) invalid("Cancel requires the active request ID");
            cancel.store(true);
        } else invalid("Unknown frame type or wrong protocol direction");
    }
    void stop_job() noexcept {
        stop.store(true); cancel.store(true);
        if (job.joinable()) {
            CancelSynchronousIo(static_cast<HANDLE>(job.native_handle()));
            job.join();
        }
    }
public:
    int run(const std::string & first_frame) {
        Handle input_thread_handle;
        HANDLE duplicate=nullptr;
        if (!DuplicateHandle(GetCurrentProcess(),GetCurrentThread(),GetCurrentProcess(),&duplicate,0,FALSE,DUPLICATE_SAME_ACCESS)) return 2;
        input_thread_handle=Handle(duplicate);
        input_thread=duplicate;
        std::string line;
        std::array<char,8192> buffer;
        try {
            frame(parse(first_frame));
            while (!stop.load()) {
                DWORD n=0;
                BOOL success=ReadFile(GetStdHandle(STD_INPUT_HANDLE),buffer.data(),DWORD(buffer.size()),&n,nullptr);
                if (!success || !n) {
                    if (!line.empty() && !stop.load()) invalid("Input closed inside a JSONL frame");
                    break;
                }
                for(DWORD i=0;i<n && !stop.load();++i) {
                    char c=buffer[i];
                    if(c=='\n') {
                        if(!line.empty() && line.back()=='\r') line.pop_back();
                        frame(parse(line));
                        line.clear();
                    } else {
                        if(line.size()>=max_frame) invalid("Input JSONL line exceeds 8 MiB");
                        line.push_back(c);
                    }
                }
            }
            stop_job();
            return 0;
        } catch(const std::exception & e) {
            std::string id;
            {std::lock_guard<std::mutex> lock(state_mutex); id=active_id;}
            stop_job();
            auto failure=dynamic_cast<const Failure *>(&e);
            try { error(id,failure ? failure->code : "INVALID_REQUEST",failure ? e.what() : "Invalid request",true); } catch(...) {}
            return 2;
        }
    }
    ~Worker() { stop_job(); }
};
}

static LONG WINAPI unhandled(EXCEPTION_POINTERS *) { ExitProcess(2); return EXCEPTION_EXECUTE_HANDLER; }
int wmain(int argc,wchar_t ** argv) {
    if(argc!=2 || wcscmp(argv[1],L"--stdio")!=0) return 2;
    SetErrorMode(SEM_FAILCRITICALERRORS|SEM_NOGPFAULTERRORBOX|SEM_NOOPENFILEERRORBOX);
    SetUnhandledExceptionFilter(unhandled);
    _set_abort_behavior(0,_WRITE_ABORT_MSG|_CALL_REPORTFAULT);
    _set_error_mode(_OUT_TO_STDERR);
    SetDllDirectoryW(L"");
    SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32);
    std::setlocale(LC_NUMERIC,"C");
    try {
        // Reading one byte at a time here consumes only hello, so either protocol retains all subsequent stdin bytes.
        std::string first;
        for (;;) {char ch=0;DWORD count=0;if(!ReadFile(GetStdHandle(STD_INPUT_HANDLE),&ch,1,&count,nullptr)||!count)return 2;
            if(ch=='\n')break;if(first.size()>=4096)return 2;first.push_back(ch);}
        if(!first.empty()&&first.back()=='\r')first.pop_back();
        auto hello=worker::parse(first);
        if(hello.value("protocol","")=="enterpriseai.evidence.v1") {
#ifdef WORKER_HAS_EVIDENCE
            return worker::run_evidence_stdio(hello);
#else
            const char message[]="{\"type\":\"error\",\"code\":\"INCOMPATIBLE_MODEL\",\"message\":\"Evidence runtime is not included in this preparation build\"}\n";
            DWORD written=0;WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),message,sizeof(message)-1,&written,nullptr);return 2;
#endif
        }
        worker::Worker instance; return instance.run(first);
    }
    catch(...) { return 2; }
}
