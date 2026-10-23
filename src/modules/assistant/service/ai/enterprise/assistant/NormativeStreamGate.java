package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;
import java.util.regex.Pattern;

/** Emits citation-terminated evidence blocks. A valid ID is not proof of semantic entailment. */
final class NormativeStreamGate {
    private static final Pattern CITATION=Pattern.compile("\\[source:[^\\]\\s]+\\]");
    private static final int MAX_PENDING=32768;
    private final StringBuilder pending=new StringBuilder();
    private boolean omitted;
    private boolean emitted;

    String push(String text) {
        pending.append(text);
        StringBuilder visible=new StringBuilder();
        int split;
        while((split=pending.indexOf("\n\n"))>=0) {
            String block=pending.substring(0,split);pending.delete(0,split+2);
            if(CITATION.matcher(block).find()||recommendation(block)) {visible.append(block).append("\n\n");emitted=true;}
            else if(punctuationOnly(block)&&emitted) visible.append(block).append("\n\n");
            else if(!block.isBlank()&&!heading(block)) omitted=true;
        }
        var matcher=CITATION.matcher(pending);int end=-1;
        while(matcher.find()) end=matcher.end();
        if(end>=0) {visible.append(pending,0,end);pending.delete(0,end);emitted=true;}
        if(pending.length()>MAX_PENDING) throw new Api.ServiceException("RESPONSE_EVIDENCE_LIMIT","Нормативный блок слишком велик и не содержит проверяемой ссылки");
        return visible.toString();
    }

    String finish() {
        String tail=pending.toString();pending.setLength(0);
        if(punctuationOnly(tail)) return tail;
        if(heading(tail)) return "";
        if(recommendation(tail)) return tail;
        omitted=true;return "";
    }
    boolean omittedUncitedText(){return omitted;}
    private static boolean punctuationOnly(String text) { return text.matches("[\\s\\p{P}]*"); }
    private static boolean heading(String text) {
        String heading=text.strip().replace("*","").replace("#","").replace(":","").toLowerCase(Locale.ROOT).strip();
        return Set.of("краткий ответ","условия или порядок действий","условия","порядок действий","что делать дальше","основание").contains(heading);
    }
    private static boolean recommendation(String text) {
        String normalized=text.strip().replace("*","").replace("#","").toLowerCase(Locale.ROOT).strip();
        return normalized.startsWith("общие рекомендации")||normalized.startsWith("общие советы")
            ||normalized.startsWith("необязательные рекомендации")||normalized.startsWith("general recommendations")
            ||normalized.startsWith("жалпы кеңестер");
    }
}
