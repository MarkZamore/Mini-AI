package ai.enterprise.search;

import ai.enterprise.contracts.Api;
import java.util.*;

/** Bounded conversational retrieval. Exact search and the on-disk index are unchanged. */
final class AssistantSearch {
    private static final Set<String> CONDITIONS = stems("условия требования критерии рекомендации советы condition conditions requirement requirements recommendation recommendations шарт талап кеңес");
    private static final Set<String> CONDITION_HINTS = stems("условие требование критерий конкурс отбор заявление успеваемость GPA срок финансирование язык оплата стоимость рекомендация condition requirement eligibility application deadline fee funding language");
    private static final Set<String> PROCEDURES = stems("правила порядок процедура обязанности прохождение проходить проходит проходят пройти проходится rule rules procedure procedures ереже тәртіп");
    private static final Set<String> PROCEDURE_HINTS = stems("правило порядок процедура обязанность обязаны должен необходимо инструктаж программа отчет дневник защита безопасность rule procedure obligation must report safety");
    private final Api.Context context;
    private final SearchModule.Search exact;

    AssistantSearch(Api.Context context, SearchModule.Search exact) { this.context=context; this.exact=exact; }

    List<Api.SearchHit> find(String query,int limit,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
        int maximum=Math.min(100,Math.max(1,limit));
        List<Api.SearchHit> original=exact.exact(query,100,documentIds,sections,token);
        if(QuestionQuery.plan(query).map(QuestionQuery.Plan::organizationIdentity).orElse(false))
            return original.subList(0,Math.min(maximum,original.size()));
        LinkedHashMap<String,String> topic=new LinkedHashMap<>();
        for(String word:QuestionQuery.subjectWords(query)) {
            token.check(); String stem=QueryNormalizer.stem(word);
            topic.putIfAbsent(stem,word);
        }
        if(topic.isEmpty()) return List.of();
        Set<String> task=QuestionQuery.taskWords(query); task.removeAll(topic.keySet());
        if(!Collections.disjoint(task,CONDITIONS)) task.addAll(CONDITION_HINTS);
        boolean procedural=!Collections.disjoint(task,PROCEDURES);
        if(procedural) task.addAll(PROCEDURE_HINTS);
        String cleaned=String.join(" ",topic.values());
        List<Api.SearchHit> topical=cleaned.equals(query)?original:exact.exact(cleaned,100,documentIds,sections,token);
        // Retrieve the requested kind of evidence as well as title matches. Every
        // query still requires the complete subject; intent words never form an
        // unanchored OR fallback for an unknown subject, date or identifier.
        List<Api.SearchHit> proceduralHits=new ArrayList<>();
        if(procedural) {
            Set<String> requested=new LinkedHashSet<>();
            for(String word:QueryNormalizer.tokens(query)) {
                if(PROCEDURES.contains(QueryNormalizer.stem(word))) requested.add(word);
                if(requested.size()==2) break;
            }
            for(String word:requested)
                proceduralHits.addAll(exact.exact(cleaned+" "+word,100,documentIds,sections,token));
        }

        var database=context.require(Api.DatabaseServiceV1.class);
        var params=new ArrayList<Object>();
        var sql=new StringBuilder("SELECT d.document_id,d.title FROM documents d WHERE 1=1");
        filters(sql,params,documentIds,sections);
        List<Title> titles=new ArrayList<>();
        for(var row:database.query(sql.toString(),params,token)) {
            token.check(); Set<String> words=stems(Objects.toString(row.get("title"),""));
            if(words.containsAll(topic.keySet())) titles.add(new Title(Objects.toString(row.get("document_id")),
                topic.size()/(double)Math.max(1,words.size())));
        }
        titles.sort(Comparator.comparingDouble(Title::specificity).reversed().thenComparing(Title::id));
        Map<String,Double> titleScores=new HashMap<>();
        Map<String,Api.Fragment> candidates=new LinkedHashMap<>();
        for(var hit:original) candidates.put(hit.fragment().fragmentId(),hit.fragment());
        for(var hit:topical) candidates.put(hit.fragment().fragmentId(),hit.fragment());
        for(var hit:proceduralHits) candidates.put(hit.fragment().fragmentId(),hit.fragment());
        // A matching title anchors the topic even when conditions do not repeat its words.
        for(var title:titles.subList(0,Math.min(3,titles.size()))) {
            token.check(); titleScores.put(title.id(),title.specificity());
            for(var row:database.query("SELECT * FROM chunks WHERE document_id=? ORDER BY rowid LIMIT 2000",List.of(title.id()),token)) {
                token.check(); Api.Fragment fragment=new Api.Fragment(SearchModule.Search.fragmentFields(row));
                candidates.putIfAbsent(fragment.fragmentId(),fragment);
            }
        }
        List<Ranked> ranked=new ArrayList<>();
        for(var fragment:candidates.values()) {
            token.check(); String text=fragment.text();
            if(text.isBlank()||fragment.sourceRefs().isEmpty()) continue;
            Set<String> body=stems(text);
            long hints=task.stream().filter(body::contains).count();
            long overlaps=topic.keySet().stream().filter(body::contains).count();
            // A procedural question needs actual duties/reporting passages, not
            // repetitive headers from a more specifically named document.
            double value=(procedural?200:1000)*titleScores.getOrDefault(fragment.documentId(),0.0)
                +(text.length()>=80?30:0)+Math.min(20,3*Math.log1p(text.length()))+(procedural?24:12)*hints+3*overlaps;
            ranked.add(new Ranked(fragment,value,String.join(" ",QueryNormalizer.tokens(text))));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r->r.fragment().fragmentId()));
        List<Api.SearchHit> result=new ArrayList<>(); List<String> selected=new ArrayList<>();
        for(var item:ranked) {
            token.check();
            if(selected.stream().anyMatch(text->text.contains(item.normalized()))) continue;
            result.add(new Api.SearchHit(item.fragment(),-item.score())); selected.add(item.normalized());
            if(result.size()==maximum) break;
        }
        return List.copyOf(result);
    }
    private static void filters(StringBuilder sql,List<Object> parameters,List<String> ids,List<String> sections) {
        if(ids!=null&&!ids.isEmpty()) { sql.append(" AND d.document_id IN (").append(placeholders(ids.size())).append(')');parameters.addAll(ids); }
        if(sections!=null&&!sections.isEmpty()) { sql.append(" AND d.document_id IN (SELECT document_id FROM document_sections WHERE section IN (").append(placeholders(sections.size())).append("))");parameters.addAll(sections); }
    }
    private static String placeholders(int count) { return String.join(",",Collections.nCopies(count,"?")); }
    private static Set<String> stems(String text) {
        Set<String> words=new HashSet<>(); for(String word:QueryNormalizer.tokens(text)) words.add(QueryNormalizer.stem(word)); return words;
    }
    private record Title(String id,double specificity) {}
    private record Ranked(Api.Fragment fragment,double score,String normalized) {}
}
