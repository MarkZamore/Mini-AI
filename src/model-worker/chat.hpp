#pragma once
#include "support.hpp"
#include "json.h"
#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"

namespace worker {
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
