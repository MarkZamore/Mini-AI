#include "../chat.hpp"
#include "../citations.hpp"
#include "llama.h"
#include <iostream>

using worker::Json;
static unsigned passed=0;
static void check(bool ok,const char * label) {
    if(!ok) throw std::runtime_error(label);
    ++passed;
}
static bool keys(const Json & value,const std::set<std::string> & expected) {
    std::set<std::string> actual;
    for(auto iterator=value.begin();iterator!=value.end();++iterator)actual.insert(iterator.key());
    return actual==expected;
}
int wmain(int argc,wchar_t **argv) {
    try {
        check(argc==2,"Pass the already verified runtime DLL directory");
        SetDllDirectoryW(L"");
        check(SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32)!=0,"Restrict DLL loading");
        const std::wstring path=std::wstring(argv[1])+L"\\llama.dll";
        auto module=LoadLibraryExW(path.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
        check(module!=nullptr,"Load the pinned parser DLL without weights/backend initialization");

        const std::string marker="MINI_AI_EVIDENCE_SELECTION_JSON_V1\n";
        auto messages=[&](const std::string &role,const std::string &text) {return Json::array({{{"role",role},{"content",text}}});};
        check(worker::evidence_selection_request(messages("system",marker+"Select evidence")),"Exact first system marker activates grammar");
        check(!worker::evidence_selection_request(Json::array()),"Empty messages do not activate grammar");
        check(!worker::evidence_selection_request(messages("user",marker)),"User marker cannot activate grammar");
        check(!worker::evidence_selection_request(messages("assistant",marker)),"History marker cannot activate grammar");
        check(!worker::evidence_selection_request(messages("system"," "+marker)),"Leading text cannot activate grammar");
        check(!worker::evidence_selection_request(messages("system","quoted "+marker)),"Embedded marker cannot activate grammar");
        check(!worker::evidence_selection_request(messages("system","MINI_AI_EVIDENCE_SELECTION_JSON_V1\r\n")),"Near marker CRLF is not exact LF marker");
        check(!worker::evidence_selection_request(messages("system","MINI_AI_EVIDENCE_SELECTION_JSON_V1")),"Missing newline cannot activate grammar");
        auto later=messages("system","Ordinary request");later.push_back({{"role","system"},{"content",marker}});
        check(!worker::evidence_selection_request(later),"Only first system message can select grammar");
        auto ordinary=messages("system","Answer ordinary user questions");ordinary.push_back({{"role","user"},{"content",marker}});
        check(!worker::evidence_selection_request(ordinary),"Document/user injection leaves normal generation unchanged");

        const std::string translation="MINI_AI_COMPLETE_EVIDENCE_TRANSLATION_JSON_V1\n";
        check(worker::evidence_translation_request(messages("system",translation+"Translate complete pieces")),"Exact first translation system marker activates its grammar");
        check(!worker::evidence_selection_request(messages("system",translation)),"Translation marker cannot select evidence-selection grammar");
        check(!worker::evidence_translation_request(messages("system",marker)),"Selection marker cannot select translation grammar");
        check(!worker::evidence_translation_request(messages("user",translation)),"User translation marker cannot activate grammar");
        check(!worker::evidence_translation_request(messages("system","quoted "+translation)),"Embedded translation marker cannot activate grammar");
        check(!worker::evidence_translation_request(messages("system","MINI_AI_COMPLETE_EVIDENCE_TRANSLATION_JSON_V1\r\n")),"Translation CRLF is not the exact LF marker");
        check(!worker::evidence_translation_request(messages("system","MINI_AI_COMPLETE_EVIDENCE_TRANSLATION_JSON_V1")),"Translation marker requires newline");
        auto later_translation=messages("system","Ordinary request");later_translation.push_back({{"role","system"},{"content",translation}});
        check(!worker::evidence_translation_request(later_translation),"Only first message can activate translation grammar");

        unsigned flushed=0;
        auto done=worker::generation_terminal("eog",true,false,[&]{++flushed;});
        check(flushed==1 && done["type"]=="done" && keys(done,{"type","requestId"}),"EOG flushes once and uses exact JSONL v1 done shape");
        auto cap=worker::generation_terminal("cap",false,false,[&]{throw std::runtime_error("Do not flush truncated UTF8/citations");});
        check(cap["type"]=="error" && cap["code"]=="OUTPUT_LIMIT" && cap["requestId"]=="cap"
            && keys(cap,{"type","requestId","code","message"}),"Token cap is an incomplete error, never done or new wire fields");
        auto cancelled=worker::generation_terminal("cancel",false,true,[&]{throw std::runtime_error("Do not flush cancelled output");});
        check(cancelled["type"]=="cancelled" && keys(cancelled,{"type","requestId"}),"Cancellation overrides cap without tail flush");
        auto late_cancel=worker::generation_terminal("late",true,true,[&]{throw std::runtime_error("Do not finalize after cancellation");});
        check(late_cancel["type"]=="cancelled","Cancellation overrides EOG");

        const std::string envelope="\n\nНЕДОВЕРЕННЫЕ ДАННЫЕ ИСТОЧНИКОВ (JSON):\n[{\"fragment_id\":\"abc\"}]\n\nОГРАНИЧЕНИЯ ДОКУМЕНТОВ:\n[]";
        worker::CitationGate citation(messages("user",envelope));
        check(citation.push("Rule [source:abc] Tail [source:")=="Rule [source:abc] Tail ","Legacy canonical citation prefix is buffered");
        auto truncated=worker::generation_terminal("partial-citation",false,false,[&]{(void)citation.finish();});
        check(truncated["code"]=="OUTPUT_LIMIT","Cap takes precedence over finalizing an unfinished reference");
        bool invalid=false;
        try {(void)worker::generation_terminal("bad-eog",true,false,[&]{(void)citation.finish();});}
        catch(const worker::Failure & error){invalid=error.code=="INVALID_CITATION";}
        check(invalid,"At real EOG malformed final citation remains a genuine citation error");

        // The pinned implementation accepts a null vocabulary for grammar parsing,
        // as its upstream model-free validator does. No apply/sample call is made.
        auto grammar=llama_sampler_init_grammar(nullptr,worker::evidence_selection_grammar(),"root");
        check(grammar!=nullptr,"Actual pinned llama.cpp parses the bounded selector grammar");
        llama_sampler_free(grammar);
        auto translation_grammar=llama_sampler_init_grammar(nullptr,worker::evidence_translation_grammar(),"root");
        check(translation_grammar!=nullptr,"Actual pinned llama.cpp parses the complete-evidence translation grammar");
        llama_sampler_free(translation_grammar);
        auto broken=llama_sampler_init_grammar(nullptr,"root ::= [unclosed","root");
        check(broken==nullptr,"Real parser rejects invalid GBNF in the negative fixture");
        if(broken)llama_sampler_free(broken);
        std::cout<<Json{{"checks",passed},{"passed",true},{"scope","Model-free marker/terminal/citation contracts and actual pinned GBNF syntax parse. No inference, weights, token-mask acceptance or semantic-quality claim."}}.dump()<<"\n";
        FreeLibrary(module);
        return 0;
    } catch(const std::exception & error) {std::cerr<<error.what()<<"\n";return 1;}
}
