#pragma once
#include "support.hpp"
#include "json.h"
#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"

namespace worker {
inline bool evidence_selection_request(const Json & messages) {
    constexpr const char * marker="MINI_AI_EVIDENCE_SELECTION_JSON_V1\n";
    return !messages.empty() && messages.front().at("role")=="system" &&
        messages.front().at("content").get_ref<const std::string &>().rfind(marker,0)==0;
}

inline bool evidence_translation_request(const Json & messages) {
    constexpr const char * marker="MINI_AI_COMPLETE_EVIDENCE_TRANSLATION_JSON_V1\n";
    return !messages.empty() && messages.front().at("role")=="system" &&
        messages.front().at("content").get_ref<const std::string &>().rfind(marker,0)==0;
}

// This constrains syntax only. The host must still verify actual evidence IDs,
// the exact answer span and preservation of all material source conditions.
inline const char * evidence_selection_grammar() {
    return R"GBNF(root ::= "{" "\"mode\"" ":" mode "," "\"answer\"" ":" answer "," "\"brief\"" ":" units "," "\"details\"" ":" units "," "\"missing\"" ":" missing "}"
mode ::= "\"answer\"" | "\"clarify\"" | "\"insufficient\""
answer ::= "\"" char{0,400} "\""
units ::= "[" (unit ("," unit){0,31})? "]"
unit ::= "\"E" [1-9] [0-9]{0,2} "\""
missing ::= "\"none\"" | "\"scope\"" | "\"evidence\"" | "\"detail\"" | "\"individual\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" (non-surrogate | surrogate-pair))
hex ::= [0-9a-fA-F]
non-surrogate ::= [0-9a-cA-Ce-fE-F] hex{3} | [dD] [0-7] hex{2}
surrogate-pair ::= [dD] [89aAbB] hex{2} "\\u" [dD] [c-fC-F] hex{2}
)GBNF";
}

// Syntax only: the host verifies exact input IDs/order, length, numbers,
// references and language; semantic equivalence needs separate source review.
inline const char * evidence_translation_grammar() {
    return R"GBNF(root ::= "{" "\"translations\"" ":" "[" piece ("," piece){0,31} "]" "}"
piece ::= "{" "\"id\"" ":" "\"P" [1-9] [0-9]{0,2} "\"" "," "\"text\"" ":" text "}"
text ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" (non-surrogate | surrogate-pair))
hex ::= [0-9a-fA-F]
non-surrogate ::= [0-9a-cA-Ce-fE-F] hex{3} | [dD] [0-7] hex{2}
surrogate-pair ::= [dD] [89aAbB] hex{2} "\\u" [dD] [c-fC-F] hex{2}
)GBNF";
}

template<class CompleteOutput>
inline Json generation_terminal(const std::string & id,bool saw_eog,bool cancelled,CompleteOutput complete_output) {
    if(cancelled) return {{"type","cancelled"},{"requestId",id}};
    if(!saw_eog) return {{"type","error"},{"requestId",id},{"code","OUTPUT_LIMIT"},
        {"message","Generation reached maxTokens before end of generation; the response is incomplete"}};
    // A limit/cancellation must never flush a partial UTF-8 codepoint or citation.
    complete_output();
    return {{"type","done"},{"requestId",id}};
}

class ChatTemplate {
    std::string source;
    jinja::program program;
public:
    explicit ChatTemplate(std::string text) : source(std::move(text)),program(jinja::parse_from_tokens(jinja::lexer().tokenize(source))) {}
    jinja::string render(const Json & messages) {
        jinja::context environment(source);
        auto input=common_json::parse(Json{{"messages",messages},{"add_generation_prompt",true},{"enable_thinking",false},{"preserve_thinking",false}}.dump());
        jinja::global_from_json(environment,input,true);
        jinja::runtime runtime(environment);
        return jinja::runtime::gather_string_parts(runtime.execute(program))->as_string();
    }
};
}
