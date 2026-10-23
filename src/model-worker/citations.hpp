#pragma once
#include "support.hpp"

namespace worker {
// This validates references; it never invents, repairs or appends a citation.
// Generic generation without the application's structured RAG envelope remains generic.
class CitationGate {
    bool rag=false;
    std::set<std::string> allowed;
    std::string pending;
    size_t valid_citations=0;
    static bool hex64(const std::string & value) {
        return value.size()==64 && std::all_of(value.begin(),value.end(),[](unsigned char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F');});
    }
    static bool source_prefix(const std::string & value) {
        const std::string prefix="[source:";
        if(value.size()<prefix.size())return false;
        for(size_t i=0;i<prefix.size();++i){char c=value[i];if(c>='A'&&c<='Z')c=static_cast<char>(c-'A'+'a');if(c!=prefix[i])return false;}
        return true;
    }
    void check_closed() {
        auto inner=pending.substr(1,pending.size()-2);
        if(source_prefix(pending)) {
            if(pending.rfind("[source:",0)!=0)throw Failure("INVALID_CITATION","Source reference must use the canonical lowercase marker");
            auto id=inner.substr(7);
            if(allowed.find(id)==allowed.end()) throw Failure("INVALID_CITATION","Model produced a source ID outside the supplied fragments");
            ++valid_citations;
        } else if(hex64(inner) && allowed.find(inner)==allowed.end()) {
            throw Failure("INVALID_CITATION","Model produced an unknown bare source hash");
        }
    }
public:
    explicit CitationGate(const Json & messages) {
        const std::string start="\n\nНЕДОВЕРЕННЫЕ ДАННЫЕ ИСТОЧНИКОВ (JSON):\n";
        const std::string end="\n\nОГРАНИЧЕНИЯ ДОКУМЕНТОВ:\n";
        for(auto iterator=messages.rbegin();iterator!=messages.rend();++iterator) {
            if(iterator->at("role")!="user") continue;
            auto text=iterator->at("content").get<std::string>();
            // The app appends its real envelope after the raw user query. Document newlines
            // are JSON-escaped, so a quoted marker inside a document cannot replace it.
            auto begin=text.rfind(start),finish=text.rfind(end);
            if(begin==std::string::npos || finish==std::string::npos || finish<=begin+start.size()) return;
            try {
                auto data=parse("{\"sources\":"+text.substr(begin+start.size(),finish-begin-start.size())+"}");
                auto & sources=data.at("sources");
                if(!sources.is_array() || sources.size()>4096) throw Failure("INVALID_CONTEXT","Invalid source inventory in RAG context");
                for(auto & source:sources) {
                    auto id=string(source,"fragment_id",1024);
                    if(id.size()>1024 || id.find_first_of("[]\r\n")!=std::string::npos) throw Failure("INVALID_CONTEXT","Source ID cannot be represented by the citation contract");
                    allowed.insert(id);
                }
                rag=true;
            } catch(const Failure & error) {throw Failure("INVALID_CONTEXT",error.what());}
            catch(const std::exception &) {throw Failure("INVALID_CONTEXT","Malformed source context");}
            return;
        }
    }
    std::string push(const std::string & text) {
        if(!rag) return text;
        std::string safe;
        for(char c:text) {
            if(pending.empty()) {
                if(c=='[') pending.push_back(c);else safe.push_back(c);
            } else if(c=='[') {
                if(source_prefix(pending)) throw Failure("INVALID_CITATION","Malformed nested source reference");
                safe+=pending;pending="[";
            } else {
                pending.push_back(c);
                if(c==']') {
                    check_closed();safe+=pending;pending.clear();
                } else if(c=='\n'||c=='\r') {
                    if(source_prefix(pending)) throw Failure("INVALID_CITATION","Newline inside source reference");
                    safe+=pending;pending.clear();
                } else if(pending.size()>1033) {
                    if(source_prefix(pending)) throw Failure("INVALID_CITATION","Source reference exceeds the bounded buffer");
                    safe+=pending;pending.clear();
                }
            }
        }
        return safe;
    }
    std::string finish() {
        if(!rag) return {};
        if(source_prefix(pending)) throw Failure("INVALID_CITATION","Model ended with an incomplete source reference");
        if(!allowed.empty() && !valid_citations) throw Failure("MISSING_CITATION","Model did not supply any complete current source reference");
        auto tail=std::move(pending);pending.clear();return tail;
    }
};
}
