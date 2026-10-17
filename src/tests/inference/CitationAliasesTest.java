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
        String general="Общие сведения о подготовке к практике.";
        require(AssistantModule.Session.validateCitations(general,List.of()).equals(general),"A source-free general answer was refused");
        require(AssistantModule.Session.validateCitations(general,sources).equals(general),"An uncited general answer with retrieved candidates was refused");
        var noSources=new CitationAliases(List.of());
        require((noSources.push("Общие сведения ")+noSources.push("о подготовке к практике.")+noSources.finish()).equals(general),"Source-free streaming changed ordinary answer text");
        for(String invalid:List.of("[source:1]","[source:"+first+"]","[source:1")) {
            boolean rejected=false;
            try{var gate=new CitationAliases(List.of());gate.push(invalid);gate.finish();}catch(Api.ServiceException e){rejected=e.code.equals("INVALID_CITATION");}
            require(rejected,"A source-free answer invented a reference: "+invalid);
        }
        for(String invalid:List.of("[source:]","[source:unknown]","[source:1","["+second+"]")) {
            boolean rejected=false;
            try{AssistantModule.Session.validateCitations(invalid,List.of());}catch(Api.ServiceException e){rejected=e.code.equals("INVALID_CITATION");}
            require(rejected,"General-answer validation accepted an invalid source: "+invalid);
        }
        for(String blank:List.of(""," \n\t")) {
            boolean rejected=false;
            try{AssistantModule.Session.validateCitations(blank,List.of());}catch(Api.ServiceException e){rejected=e.code.equals("EMPTY_RESPONSE");}
            require(rejected,"A blank response was accepted as a general answer");
        }
        String unknown="c".repeat(64);
        for(String invalid:List.of(
            "Ввод [SOURCE:1]", "Ввод [Source:1]", "Ввод [sOuRcE:1]", "Ввод [SOURCE:",
            "Ввод [source:1[source:2]]", "Ввод [source:1[вложение]]",
            "Ввод [source:1\n]", "Ввод [source:\r1]", "Ввод [SOURCE:1\n]",
            "Ввод [source:", "Ввод [source:"+"1".repeat(1026), "Ввод [source:"+"1".repeat(1026)+"]",
            "Ввод ["+unknown+"]", "Ввод ["+unknown.toUpperCase(Locale.ROOT)+"]")) {
            rejectAcrossBoundaries(sources,invalid);
            rejectAcrossBoundaries(List.of(),invalid);
        }
        String knownBare="Хеш ["+first+"] сохраняется, без выдуманной ссылки.";
        for(int split=0;split<=knownBare.length();split++) {
            var gate=new CitationAliases(sources);
            require((gate.push(knownBare.substring(0,split))+gate.push(knownBare.substring(split))+gate.finish()).equals(knownBare),"A known bare canonical hash changed at split "+split);
        }
        for(String ordinary:List.of("Текст [в скобках] и [обычное [вложение]]", "Текст [несколько\nстрок]", "Текст ["+"я".repeat(1100)+"]", "Незакрытая обычная скобка [пример", "Короткая последовательность [sou")) {
            var gate=new CitationAliases(List.of());var visible=new StringBuilder();
            for(char character:ordinary.toCharArray())visible.append(gate.push(String.valueOf(character)));
            visible.append(gate.finish());require(visible.toString().equals(ordinary),"Ordinary bracketed text was altered or refused");
        }
        CitationAliases.validateCanonical(expected,sources);
        CitationAliases.validateCanonical(knownBare,sources);
        CitationAliases.validateCanonical(general,List.of());
        for(String invalid:List.of("[SOURCE:"+first+"]", "[source:"+first+"[source:"+second+"]]", "[source:"+first+"\n]", "[source:"+first, "[source:"+unknown+"]", "["+unknown+"]")) {
            boolean rejected=false;
            try{CitationAliases.validateCanonical(invalid,sources);}catch(Api.ServiceException e){rejected=e.code.equals("INVALID_CITATION");}
            require(rejected,"Completed canonical validation accepted a malformed or unknown reference: "+invalid);
        }
        for(String[] example:List.of(
            new String[]{"До [source:1] после.","До  после."},
            new String[]{"До [SOURCE:999] после.","До  после."},
            new String[]{"До [sOuRcE:unknown[вложение]\nпродолжение] после.","До  после."},
            new String[]{"До [source:1", "До "},
            new String[]{"До [SOUR", "До "},
            new String[]{"[source:"+"я".repeat(2100)+"]Полезный текст.","Полезный текст."},
            new String[]{"До ["+unknown+"] после.","До  после."},
            new String[]{"До ["+first+"] после.","До  после."},
            new String[]{"До ["+unknown,"До "},
            new String[]{"[source:1]", ""},
            new String[]{"[SOURCE:", ""},
            new String[]{"Обычный [текст] и [обычное [вложение]].","Обычный [текст] и [обычное [вложение]]."})) {
            for(int split=0;split<=example[0].length();split++) {
                var gate=CitationAliases.forGeneralAnswer();
                String visible=gate.push(example[0].substring(0,split));
                require(!visible.toLowerCase(Locale.ROOT).contains("[source:")&&!visible.contains(unknown),"A reference leaked before general-answer filtering completed");
                visible+=gate.push(example[0].substring(split))+gate.finish();
                require(visible.equals(example[1]),"General-answer filtering changed outside text at split "+split);
            }
            var gate=CitationAliases.forGeneralAnswer();var visible=new StringBuilder();
            for(char character:example[0].toCharArray())visible.append(gate.push(String.valueOf(character)));
            visible.append(gate.finish());require(visible.toString().equals(example[1]),"General-answer filtering failed during single-character streaming");
        }
        System.out.println("PASS: strict citation boundaries/provenance, general-answer reference omission, canonical case, nesting, newlines, bounded buffers and unknown bare hashes");
    }
    private static void rejectAcrossBoundaries(List<Api.Fragment> sources,String invalid) {
        for(int split=0;split<=invalid.length();split++) {
            var gate=new CitationAliases(sources);var visible=new StringBuilder();boolean rejected=false;
            try {
                visible.append(gate.push(invalid.substring(0,split)));
                visible.append(gate.push(invalid.substring(split)));visible.append(gate.finish());
            } catch(Api.ServiceException e){rejected=e.code.equals("INVALID_CITATION");}
            require(rejected,"Malformed source survived a chunk boundary at "+split+": "+invalid);
            require("Ввод ".startsWith(visible.toString()),"Malformed reference was displayed before rejection at split "+split);
        }
        var gate=new CitationAliases(sources);boolean rejected=false;
        try{for(char character:invalid.toCharArray())gate.push(String.valueOf(character));gate.finish();}catch(Api.ServiceException e){rejected=e.code.equals("INVALID_CITATION");}
        require(rejected,"Malformed source survived single-character streaming: "+invalid);
    }
}
