package ai.enterprise.tests;

import ai.enterprise.assistant.CitationAliases;
import ai.enterprise.assistant.AssistantModule;
import ai.enterprise.contracts.Api;
import java.util.*;

public final class CitationAliasesTest {
    private static void require(boolean value, String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) {
        String first="a".repeat(64), second="b".repeat(64);
        var sources=List.of(new Api.Fragment(Map.of("fragment_id",first,"text","First fact","source_refs",List.of(Map.of("page",1)))),
            new Api.Fragment(Map.of("fragment_id",second,"text","Second fact","source_refs",List.of(Map.of("page",2)))));
        String model="Факт [source:1], затем [source:2].", expected="Факт [source:"+first+"], затем [source:"+second+"].";
        for(int split=0;split<=model.length();split++) {
            var aliases=new CitationAliases(sources);
            String text=aliases.push(model.substring(0,split))+aliases.push(model.substring(split))+aliases.finish();
            require(text.equals(expected),"Incorrect split at "+split);
            require(AssistantModule.Session.validateCitations(text,sources).equals(expected),"Canonical validation changed");
        }
        var aliases=new CitationAliases(sources);var out=new StringBuilder();
        for(char c:model.toCharArray())out.append(aliases.push(String.valueOf(c)));
        out.append(aliases.finish());require(out.toString().equals(expected),"Character streaming failed");
        var payload=aliases.modelSources(sources);
        require(payload.getFirst().get("fragment_id").equals("1"),"Short ID absent");
        require(payload.getFirst().get("source_refs").equals(sources.getFirst().fields().get("source_refs")),"Provenance changed");
        require(sources.getFirst().fragmentId().equals(first),"Original ID mutated");
        require(aliases.modelHistory(expected).equals(model),"History aliases not restored for current context");
        for(String invalid:List.of("[source:3]","[source:"+first+"]","[source:1")) {
            boolean rejected=false;
            try{var gate=new CitationAliases(sources);gate.push(invalid);gate.finish();}catch(Api.ServiceException e){rejected=e.code.equals("INVALID_CITATION");}
            require(rejected,"Unknown/incomplete source accepted: "+invalid);
        }
        System.out.println("PASS: all citation stream boundaries, original IDs/provenance, history and unknown references");
    }
}
