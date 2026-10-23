package ai.enterprise.search;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.EvidenceModelServiceV1;
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
        var current=context.require(ai.enterprise.contracts.CorpusIdentityServiceV1.class).current(token);
        if(!current.success())throw new Api.ServiceException(current.error().code(),current.error().message());
        var identity=current.value();
        var corpus=new CurrentCorpusConnector(identity,context.require(Api.DatabaseServiceV1.class));
        List<Api.SearchHit> ranked=hybrid(query,lexical(query,100,documentIds,sections,token),corpus,documentIds,sections,token);
        boolean expand=!Collections.disjoint(QuestionQuery.taskWords(query),CONDITIONS)
            || !Collections.disjoint(QuestionQuery.taskWords(query),PROCEDURES);
        LinkedHashMap<String,Api.SearchHit> selected=new LinkedHashMap<>();
        int anchors=expand?Math.max(1,(maximum+1)/2):maximum;
        for(var hit:ranked) {
            token.check();selected.putIfAbsent(hit.fragment().fragmentId(),hit);
            if(selected.size()==anchors) break;
        }
        if(expand) {
            List<Api.SearchHit> seeds=List.copyOf(selected.values());
            // Adjacent clauses are read from the same canonical document, never synthesized
            // or merged with another edition. Source warnings and raw text remain intact.
            for(int offset=0;offset<2 && selected.size()<maximum;offset++) for(var hit:seeds) {
                var neighbors=corpus.neighbors(hit.fragment(),1,1,token);
                if(offset<neighbors.size()) {
                    var neighbor=neighbors.get(offset);
                    selected.putIfAbsent(neighbor.fragmentId(),new Api.SearchHit(neighbor,hit.score()));
                }
                if(selected.size()==maximum) break;
            }
        }
        for(var hit:ranked) {
            if(selected.size()>=maximum) break;
            selected.putIfAbsent(hit.fragment().fragmentId(),hit);
        }
        return selected.values().stream().map(hit->new Api.SearchHit(corpus.namespaced(hit.fragment()),hit.score())).toList();
    }

    private List<Api.SearchHit> hybrid(String query,List<Api.SearchHit> lexical,CorpusConnector corpus,
        List<String> documentIds,List<String> sections,Api.CancellationToken token) {
        var optional=context.find(EvidenceModelServiceV1.class);
        if(optional.isEmpty()) return lexical;
        var service=optional.get();var available=service.availability();
        if(!available.success()) {requireRecoverable(new Api.ServiceException(available.error().code(),available.error().message()));context.diagnostic("Evidence models unavailable; lexical retrieval: "+available.error().code());return lexical;}
        List<EvidenceModelServiceV1.ScoredFragment> dense;
        try {dense=Api.await(service.retrieve(corpus.baseId(),query,64,documentIds,sections,token));}
        catch(Api.ServiceException error) {token.check();requireRecoverable(error);context.diagnostic("Dense retrieval failed; lexical retrieval: "+error.code);return lexical;}
        Map<String,Api.Fragment> candidates=new LinkedHashMap<>();Map<String,Double> fusion=new HashMap<>();
        int rank=0;
        for(var hit:lexical) {
            candidates.put(hit.fragment().fragmentId(),hit.fragment());
            fusion.put(hit.fragment().fragmentId(),1.0/(60+ ++rank));
        }
        Set<String> denseIds=new HashSet<>();rank=0;
        for(var hit:dense) {
            token.check();
            if(!Double.isFinite(hit.score()) || !denseIds.add(hit.fragmentId()))
                throw new Api.ServiceException("SOURCE_INVALID","Модель поиска вернула недопустимые или повторные источники");
            Api.Fragment fragment=corpus.fragment(hit.fragmentId(),token)
                .orElseThrow(()->new Api.ServiceException("SOURCE_INVALID","Модель поиска ссылается на отсутствующий фрагмент"));
            if(!fragment.documentId().equals(hit.documentId())) throw new Api.ServiceException("SOURCE_INVALID","Фрагмент принадлежит другому документу");
            if(documentIds!=null&&!documentIds.isEmpty()&&!documentIds.contains(fragment.documentId())) continue;
            if(sections!=null&&!sections.isEmpty()) {
                String marks=String.join(",",Collections.nCopies(sections.size(),"?"));
                var parameters=new ArrayList<Object>();parameters.add(fragment.documentId());parameters.addAll(sections);
                if(context.require(Api.DatabaseServiceV1.class).query("SELECT document_id FROM document_sections WHERE document_id=? AND section IN ("+marks+") LIMIT 1",parameters,token).isEmpty()) continue;
            }
            // A semantic neighbour cannot silently change an explicit year or identifier.
            Set<String> words=new HashSet<>(QueryNormalizer.tokens(fragment.title()+" "+fragment.text()));
            if(QueryNormalizer.tokens(query).stream().filter(w->w.matches("(?:19|20|21)[0-9]{2}") || w.matches("(?=.*[0-9])(?=.*[\\p{L}])[\\p{L}0-9]+"))
                .anyMatch(w->!words.contains(w))) continue;
            if(fragment.sourceRefs().isEmpty()||fragment.text().isBlank()) continue;
            candidates.putIfAbsent(fragment.fragmentId(),fragment);
            fusion.merge(fragment.fragmentId(),1.0/(60+ ++rank),Double::sum);
        }
        List<Api.Fragment> ordered=candidates.values().stream().sorted(Comparator
            .<Api.Fragment>comparingDouble(f->fusion.get(f.fragmentId())).reversed().thenComparing(Api.Fragment::fragmentId)).limit(64).toList();
        if(available.value().rerankerAvailable()&&!ordered.isEmpty()) {
            var policyResult=service.answerPolicy();
            if(!policyResult.success())throw new Api.ServiceException("SOURCE_POLICY_INVALID",policyResult.error().message());
            var policy=policyResult.value();
            if(policy.kind().equals("bge-relevance-v1")) {
                // These thresholds were fixed on calibration and checked on a
                // separate gate. They estimate relevance only. Keep RRF order:
                // the reranking experiment did not establish a quality gain.
                var gatePool=dense.stream().map(f->candidates.get(f.fragmentId())).filter(Objects::nonNull)
                    .limit(policy.maxCandidates()).toList();
                if(gatePool.isEmpty())return lexical.stream().map(hit->{var fields=new LinkedHashMap<>(hit.fragment().fields());
                    fields.put("evidence_answer_mode","clarify");return new Api.SearchHit(new Api.Fragment(fields),hit.score());}).toList();
                List<EvidenceModelServiceV1.ScoredFragment> checked;
                try {checked=Api.await(service.rerank(query,gatePool.stream().map(f->new EvidenceModelServiceV1.Candidate(f.fragmentId(),f.documentId(),f.text())).toList(),token));}
                catch(Api.ServiceException error){token.check();requireRecoverable(error);context.diagnostic("Evidence relevance check failed; anchored lexical retrieval: "+error.code);return lexical;}
                var expected=new HashMap<String,String>();gatePool.forEach(f->expected.put(f.fragmentId(),f.documentId()));
                var seen=new HashSet<String>();double highest=-Double.MAX_VALUE;
                for(var item:checked){if(!expected.containsKey(item.fragmentId())||!expected.get(item.fragmentId()).equals(item.documentId())
                    ||!seen.add(item.fragmentId())||!Double.isFinite(item.score()))throw new Api.ServiceException("SOURCE_INVALID","Неверный результат оценки достаточности");
                    highest=Math.max(highest,item.score());}
                if(seen.size()!=gatePool.size())throw new Api.ServiceException("SOURCE_INVALID","Оценка источников потеряла фрагменты");
                String mode=highest>=policy.adequateMinLogit()?"answer":highest<=policy.insufficientMaxLogit()?"insufficient":"clarify";
                context.diagnostic("Evidence relevance mode: "+mode+"; semantic sufficiency is not independently verified");
                return ordered.stream().map(f->{var fields=new LinkedHashMap<>(f.fields());fields.put("evidence_answer_mode",mode);
                    return new Api.SearchHit(new Api.Fragment(fields),-fusion.get(f.fragmentId()));}).toList();
            }
            if(!policy.kind().equals("none"))throw new Api.ServiceException("SOURCE_POLICY_INVALID","Неизвестная политика оценки источников");
            // Bound CPU work so a new question does not block generation behind
            // dozens of cross-encoder passes. Keep the remaining RRF candidates
            // available, rather than turning this budget into a recall cutoff.
            var rankPool=ordered.subList(0,Math.min(8,ordered.size()));
            List<EvidenceModelServiceV1.ScoredFragment> rescored;
            try {rescored=Api.await(service.rerank(query,rankPool.stream().map(f->new EvidenceModelServiceV1.Candidate(f.fragmentId(),f.documentId(),f.text())).toList(),token));}
            catch(Api.ServiceException error) {token.check();requireRecoverable(error);context.diagnostic("Reranking failed; reciprocal-rank fusion: "+error.code+": "+error.getMessage());return ordered.stream().map(f->new Api.SearchHit(f,-fusion.get(f.fragmentId()))).toList();}
            Map<String,Double> scores=new HashMap<>();
            for(var hit:rescored) {
                var fragment=candidates.get(hit.fragmentId());
                if(fragment==null||!fragment.documentId().equals(hit.documentId())||!Double.isFinite(hit.score())||scores.put(hit.fragmentId(),hit.score())!=null)
                    throw new Api.ServiceException("SOURCE_INVALID","Ранжирование вернуло неизвестный или неверный источник");
            }
            if(scores.size()!=rankPool.size()||rankPool.stream().anyMatch(f->!scores.containsKey(f.fragmentId())))
                throw new Api.ServiceException("SOURCE_INVALID","Ранжирование потеряло исходные фрагменты");
            var result=new ArrayList<Api.SearchHit>();
            rankPool.stream().sorted(Comparator.<Api.Fragment>comparingDouble(f->scores.get(f.fragmentId())).reversed().thenComparing(Api.Fragment::fragmentId))
                .forEach(f->result.add(new Api.SearchHit(f,-scores.get(f.fragmentId()))));
            ordered.subList(rankPool.size(),ordered.size()).forEach(f->result.add(new Api.SearchHit(f,-fusion.get(f.fragmentId()))));
            return List.copyOf(result);
        }
        return ordered.stream().map(f->new Api.SearchHit(f,-fusion.get(f.fragmentId()))).toList();
    }

    private static void requireRecoverable(Api.ServiceException error) {
        // A transport outage can fall back to anchored lexical retrieval. A
        // malformed model, wrong base, bad ID or protocol must remain visible.
        if(!Set.of("EVIDENCE_UNAVAILABLE","RERANKER_UNAVAILABLE","WORKER_TIMEOUT","WORKER_EXITED",
            "WORKER_CLOSED","WORKER_START","OUT_OF_MEMORY","BUSY","EVIDENCE_TIMEOUT","EVIDENCE_TRANSPORT").contains(error.code))throw error;
    }

    private List<Api.SearchHit> lexical(String query,int limit,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
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
