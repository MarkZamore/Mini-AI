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
    private static final Set<String> DEADLINES = stems("когда срок сроки дата даты дней день месяц год deadline when мерзім қашан күн ай жыл");
    private static final Set<String> EXCEPTIONS = stems("исключение исключения кроме запрет разрешение допускается можно exception unless except ерекшелік қоспағанда рұқсат тыйым");
    private static final Set<String> PREDICATES = stems("обязан обязана обязаны должен должна должны необходимо запрещено недопустимо допускается осуществляется проводится выполняется представляет направляет несут ответственность подать подается представить предоставлять сдать сообщить уведомить направить рассмотреть передать оформить утвердить must shall required prohibited қажет міндетті тиіс жүзеге ұсынады тапсырады хабарлау тыйым салынады");
    private static final Set<String> DEPENDENT = stems("если при кроме исключение исключения однако также указанный данный вышеназванный unless except otherwise егер жағдайда қоспағанда сондай көрсетілген аталған");
    private static final Map<String,Set<String>> LEVELS = Map.of(
        "doctoral",stems("докторантура докторант PhD doctoral докторанттар"),
        "masters",stems("магистратура магистрант master masters магистранттар"),
        "undergraduate",stems("бакалавриат бакалавр bachelor undergraduate бакалаврлар"));
    private static final List<String> KAZAKH_SUFFIXES = List.of("ларыңыздың","леріңіздің","тарына","теріне","ларының","лерінің","лардан","лерден","лардың","лердің","тардың","тердің","тарға","терге","ларда","лерде","дарына","деріне","тары","тері","лары","лері","дардың","дердің","дың","дің","тың","тің","ның","нің","дан","ден","тан","тен","мен","бен","пен","лар","лер","ға","ге","қа","ке","да","де","та","те","ны","ні","ды","ді","ты","ті","сы","сі");
    private final Api.Context context;
    private final SearchModule.Search exact;

    AssistantSearch(Api.Context context, SearchModule.Search exact) { this.context=context; this.exact=exact; }

    List<Api.SearchHit> find(String query,int limit,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
        int maximum=Math.min(100,Math.max(1,limit));
        var current=context.require(ai.enterprise.contracts.CorpusIdentityServiceV1.class).current(token);
        if(!current.success())throw new Api.ServiceException(current.error().code(),current.error().message());
        var identity=current.value();
        var corpus=new CurrentCorpusConnector(identity,context.require(Api.DatabaseServiceV1.class));
        if(QuestionQuery.plan(query).map(QuestionQuery.Plan::organizationIdentity).orElse(false))
            return identityEvidence(query,maximum,documentIds,sections,token).stream()
                .map(hit->new Api.SearchHit(corpus.namespaced(hit.fragment()),hit.score())).toList();
        List<Api.SearchHit> ranked=hybrid(query,lexical(query,100,documentIds,sections,token),corpus,documentIds,sections,token);
        Intent intent=intent(query);
        List<Api.SearchHit> selected=intent.normative()?evidence(query,ranked,maximum,intent,token)
            :ranked.subList(0,Math.min(maximum,ranked.size()));
        return selected.stream().map(hit->new Api.SearchHit(corpus.namespaced(hit.fragment()),hit.score())).toList();
    }

    private record Intent(boolean normative,boolean kazakh,Set<String> topic,Set<String> levels,Set<String> hints) {}
    private record Bundle(List<Api.Fragment> fragments,String heading,boolean complete) {}
    private static String identitySpan(String text) {
        for(String expression:List.of(
                "(?isu)(?:некоммерческое\\s+)?(?:акционерное\\s+общество|общество\\s+с\\s+ограниченной\\s+ответственностью|государственное\\s+учреждение|унитарное\\s+предприятие)\\s*[«\"][^»\"]{5,350}[»\"]",
                "(?isu)[«\"][^»\"]{5,350}[»\"]\\s*(?:коммерциялық\\s+емес\\s+акционерлік\\s+қоғам|жауапкершілігі\\s+шектеулі\\s+серіктестік)")) {
            var matcher=java.util.regex.Pattern.compile(expression).matcher(text);
            while(matcher.find()) {
                String span=matcher.group();
                if(span.contains("_")||span.contains("|"))continue;
                // A Russian legal-form line interleaved with a Kazakh column is
                // not a contiguous legal name. Do not reconstruct OCR columns.
                if(span.toLowerCase(Locale.ROOT).startsWith("некоммерческое")||span.toLowerCase(Locale.ROOT).startsWith("акционерное"))
                    if(span.codePoints().anyMatch(c->"әғқңөұүһіӘҒҚҢӨҰҮҺІ".indexOf(c)>=0))continue;
                return span;
            }
        }
        return "";
    }
    private List<Api.SearchHit> identityEvidence(String query,int maximum,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
        var plan=QuestionQuery.plan(query).orElseThrow();var parameters=new ArrayList<Object>();parameters.add(plan.expression());
        var sql=new StringBuilder("SELECT c.*,bm25(chunks_fts,6.0,10.0,1.0,0.5) AS score FROM chunks_fts JOIN chunks c ON c.rowid=chunks_fts.rowid JOIN documents d ON d.document_id=c.document_id WHERE chunks_fts MATCH ?");
        filters(sql,parameters,documentIds,sections);sql.append(" ORDER BY score,c.rowid LIMIT 200");
        var candidates=new ArrayList<Api.Fragment>();var spans=new HashMap<String,String>();var support=new HashMap<String,Set<String>>();
        for(var row:context.require(Api.DatabaseServiceV1.class).query(sql.toString(),parameters,token)) {
            token.check();var fields=SearchModule.Search.fragmentFields(row);fields.remove("score");var fragment=new Api.Fragment(fields);
            if(fragment.sourceRefs().isEmpty())continue;
            Object quality=fields.get("quality");
            if(quality instanceof Map<?,?> details&&(Boolean.TRUE.equals(details.get("needs_review"))||Objects.toString(details.get("method"),"").contains("tesseract")))continue;
            String span=identitySpan(fragment.text());if(span.isEmpty())continue;
            String key=String.join(" ",QueryNormalizer.tokens(span));spans.put(fragment.fragmentId(),span);
            support.computeIfAbsent(key,unused->new HashSet<>()).add(fragment.documentId());candidates.add(fragment);
        }
        candidates.sort(Comparator.<Api.Fragment>comparingDouble(fragment->{
            String key=String.join(" ",QueryNormalizer.tokens(spans.get(fragment.fragmentId())));
            boolean nativeOriginal=fragment.sourceRefs().stream().allMatch(ref->Objects.equals(ref.fields().get("kind"),"ooxml_node")&&Objects.equals(ref.fields().get("artifact_id"),"original"));
            return (nativeOriginal?100:0)+Math.min(5,support.get(key).size())*10.0;
        }).reversed().thenComparing(Api.Fragment::fragmentId));
        var result=new ArrayList<Api.SearchHit>();var seen=new HashSet<String>();
        for(var fragment:candidates) {
            String span=spans.get(fragment.fragmentId()),key=String.join(" ",QueryNormalizer.tokens(span));if(!seen.add(key))continue;
            var fields=new LinkedHashMap<>(fragment.fields());fields.put("evidence_identity_body_span",span);
            fields.put("evidence_bundle_ids",List.of(fragment.fragmentId()));fields.put("evidence_section_heading","");
            fields.put("evidence_context_complete",true);fields.put("evidence_scope_title",fragment.title());fields.put("evidence_scope_ambiguous",false);fields.put("evidence_role","context");
            result.add(new Api.SearchHit(new Api.Fragment(fields),-result.size()));if(result.size()==Math.min(3,maximum))break;
        }
        return List.copyOf(result);
    }
    private static Intent intent(String query) {
        boolean kazakh=query.codePoints().anyMatch(c->"әғқңөұүһіӘҒҚҢӨҰҮҺІ".indexOf(c)>=0);
        Set<String> words=terms(query,kazakh);
        var question=QuestionQuery.plan(query);
        boolean normative=question.isPresent()&&!question.get().organizationIdentity()
            ||!Collections.disjoint(words,CONDITIONS)||!Collections.disjoint(words,PROCEDURES)
            ||!Collections.disjoint(words,DEADLINES)||!Collections.disjoint(words,EXCEPTIONS)
            ||words.stream().anyMatch(w->w.startsWith("міндет")||w.startsWith("тәртіп")||w.startsWith("мерзім"));
        var topic=new LinkedHashSet<String>();
        for(String word:QuestionQuery.subjectWords(query)) {
            String value=term(word,kazakh);
            if(!CONDITIONS.contains(value)&&!PROCEDURES.contains(value)&&!DEADLINES.contains(value)&&!EXCEPTIONS.contains(value))topic.add(value);
        }
        var hints=new HashSet<String>();
        if(!Collections.disjoint(words,CONDITIONS))hints.addAll(CONDITION_HINTS);
        if(!Collections.disjoint(words,PROCEDURES))hints.addAll(PROCEDURE_HINTS);
        if(!Collections.disjoint(words,DEADLINES))hints.addAll(DEADLINES);
        if(!Collections.disjoint(words,EXCEPTIONS))hints.addAll(EXCEPTIONS);
        return new Intent(normative,kazakh,Set.copyOf(topic),levels(query,kazakh),Set.copyOf(hints));
    }
    private static String term(String word,boolean kazakh) {
        if(word.codePoints().anyMatch(Character::isDigit))return word;
        if(kazakh)for(String suffix:KAZAKH_SUFFIXES)if(word.endsWith(suffix)&&word.length()-suffix.length()>=4) {
            word=word.substring(0,word.length()-suffix.length());break;
        }
        return QueryNormalizer.stem(word);
    }
    private static Set<String> terms(String text,boolean kazakh) {
        var words=new HashSet<String>();for(String word:QueryNormalizer.tokens(text))words.add(term(word,kazakh));return words;
    }
    private static Set<String> levels(String text,boolean kazakh) {
        Set<String> words=terms(text,kazakh);var found=new HashSet<String>();
        LEVELS.forEach((level,hints)->{if(!Collections.disjoint(words,hints))found.add(level);});return found;
    }
    private static boolean predicate(String text) {
        return !Collections.disjoint(stems(text),PREDICATES)||text.matches("(?isu).*(?:[0-9]+\\s*(?:рабочих|календарных|дней|числа|күн|жыл)|[\\p{L}]{4,}(?:ется|ются|ится|атся|ятся)|име(?:ет|ют)\\s+право|мож(?:ет|но|ут)|вправе|міндет|тиіс|қажет|тыйым|мүмкін|\\b(?:may|can|shall|must|should)\\b).*" )
            ||text.codePoints().anyMatch(c->"әғқңөұүһіӘҒҚҢӨҰҮҺІ".indexOf(c)>=0)
                &&QueryNormalizer.tokens(text).stream().anyMatch(word->word.matches("[\\p{L}]{3,}(?:ылады|іледі|леді|лады|неді|нады|йды|йді|ады|еді)"));
    }
    private static boolean heading(String text,String title) {
        if(predicate(text)||text.length()>180||text.contains("_")||text.contains("|")
                ||text.codePoints().filter(Character::isLetter).count()<3)return false;
        String normalized=String.join(" ",QueryNormalizer.tokens(text));
        if(normalized.isBlank())return true;
        if(normalized.equals(String.join(" ",QueryNormalizer.tokens(title))))return true;
        int words=QueryNormalizer.tokens(text).size();
        return words<=18&&(text.strip().matches("[0-9]+(?:\\.[0-9]+){0,4}[.)]?\\s+[^\\n;:!?]{3,140}")
            ||text.strip().equals(text.strip().toUpperCase(Locale.ROOT))
            ||words<=6&&!text.matches("(?s).*[.;:!?].*"));
    }
    private static boolean sourceHeading(Api.Fragment fragment) {
        // A table/form field is not a section merely because it is short or
        // numbered. The canonical locator, rather than a guessed topic, decides.
        if(nativeFurniture(fragment)||listItem(fragment.text())||fragment.sourceRefs().stream()
                .anyMatch(ref->Objects.toString(ref.fields().get("xpath"),"").contains("/w:tbl[")))return false;
        return pdf(fragment)?pdfHeading(fragment.text(),fragment.title()):heading(fragment.text(),fragment.title());
    }
    private static boolean continuation(String text) {
        String value=text.strip();Set<String> words=stems(String.join(" ",QueryNormalizer.tokens(value).stream().limit(3).toList()));
        return value.matches("(?s)^(?:[-–—•]|[а-яa-z][.)]|[0-9]+[)]).*" )
            ||!Collections.disjoint(words,DEPENDENT)||value.startsWith("в том числе")||value.startsWith("за исключением");
    }
    private static boolean listItem(String text) {
        return text.strip().matches("(?s)^(?:[-–—•]|[а-яa-z][.)]|[0-9]+[)]).*" );
    }
    private static boolean linkedCondition(String text) {
        String content=text.strip().replaceFirst("^(?:[-–—•]|[а-яa-z][.)]|[0-9]+[)])\\s*","");
        var first=stems(String.join(" ",QueryNormalizer.tokens(content).stream().limit(3).toList()));
        return !Collections.disjoint(first,DEPENDENT)||content.startsWith("в том числе")||content.startsWith("за исключением")||dependsOnPrevious(content);
    }
    private static boolean dependsOnPrevious(String text) {
        String content=text.strip().replaceFirst("^(?:[-–—•]|[а-яa-z][.)]|[0-9]+[)])\\s*","");
        String value=String.join(" ",QueryNormalizer.tokens(content));
        boolean listed=!content.equals(text.strip());
        var words=QueryNormalizer.tokens(content);int modal=-1;
        for(int i=0;i<Math.min(10,words.size());i++)if(Set.of("должен","должна","должны","обязан","обязана","обязаны","тиіс","must","shall").contains(words.get(i))){modal=i;break;}
        boolean independentRule=modal>0;
        String initial=content.replaceFirst("^[0-9]+(?:\\.[0-9]+)*[.)]?[\\s\\p{Zs}]+","");
        var boundary=java.util.regex.Pattern.compile("[.!?]").matcher(initial);
        if(boundary.find())initial=initial.substring(0,boundary.start());
        String firstSentence=String.join(" ",QueryNormalizer.tokens(initial));
        boolean anaphoric=firstSentence.matches("(?s).*(?:в связи с этим|при этом|в этом случае|в таком случае|осыған байланысты|бұл ретте|осындай|мұндай|in this case|at the same time|in connection with this|(?:^| )та(?:кой|кая|кие|ких|кого)(?: |$)).*");
        return listed&&!independentRule
            ||anaphoric
            ||value.matches("(?s)^(?:также|однако|кроме|указанн[а-я]*|данн[а-я]*|за исключением|в таком случае|сондай|аталған|көрсетілген).*" );
    }
    private static boolean relatedHeading(String text) {
        var words=stems(text);return !Collections.disjoint(words,CONDITIONS)||!Collections.disjoint(words,EXCEPTIONS);
    }
    private double evidenceValue(Api.Fragment fragment,Intent intent,int rank) {
        Set<String> body=terms(fragment.text(),intent.kazakh()),title=terms(fragment.title(),intent.kazakh());
        long overlap=intent.topic().stream().filter(w->body.contains(w)||title.contains(w)).count();
        Set<String> section=terms(Objects.toString(fragment.fields().get("evidence_section_heading"),""),intent.kazakh());
        long sectionOverlap=intent.topic().stream().filter(section::contains).count();
        long requested=intent.hints().stream().filter(body::contains).count();
        Set<String> scope=levels(fragment.title(),intent.kazakh());
        boolean conflict=!intent.levels().isEmpty()&&!scope.isEmpty()&&Collections.disjoint(intent.levels(),scope);
        return 12.0/(1+rank)+Math.min(4,overlap)*2+Math.min(2,sectionOverlap)*8+Math.min(6,requested)*1.5+(predicate(fragment.text())?4:0)
            -(sourceHeading(fragment)?8:0)-(conflict?12:0);
    }
    private List<Api.SearchHit> evidence(String query,List<Api.SearchHit> ranked,int maximum,Intent intent,Api.CancellationToken token) {
        ranked=scopedCandidates(query,ranked,intent,token);
        var positions=new HashMap<String,Integer>();for(int i=0;i<ranked.size();i++)positions.put(ranked.get(i).fragment().fragmentId(),i);
        List<Api.SearchHit> ordered=ranked.stream().sorted(Comparator.<Api.SearchHit>comparingDouble(
            hit->evidenceValue(hit.fragment(),intent,positions.get(hit.fragment().fragmentId()))).reversed()
            .thenComparing(hit->hit.fragment().fragmentId())).toList();
        var selected=new LinkedHashMap<String,Api.SearchHit>();var scopes=new HashSet<String>();
        for(var hit:ordered) {
            token.check();
            if(selected.containsKey(hit.fragment().fragmentId()))continue;
            Bundle bundle=bundle(hit.fragment(),Math.min(8,maximum),token);
            if(bundle.fragments().isEmpty())continue;
            var members=new LinkedHashMap<String,Api.Fragment>();
            for(var fragment:bundle.fragments())members.put(fragment.fragmentId(),fragment);
            // Overlapping units must retain a closed dependency set; a caller selecting
            // any one ID can render every whole canonical member, never a chopped list.
            for(var fragment:bundle.fragments())if(selected.containsKey(fragment.fragmentId())) {
                if(governingContext(fragment)||governingContext(selected.get(fragment.fragmentId()).fragment()))continue;
                Object existing=selected.get(fragment.fragmentId()).fragment().fields().get("evidence_bundle_ids");
                if(existing instanceof List<?> ids)for(Object id:ids) {
                    var previous=selected.get(Objects.toString(id));if(previous!=null)members.putIfAbsent(previous.fragment().fragmentId(),previous.fragment());
                }
            }
            long newCount=members.keySet().stream().filter(id->!selected.containsKey(id)).count();
            if(members.size()>8||selected.size()+newCount>maximum||members.values().stream().mapToInt(f->f.text().length()).sum()>24_000)continue;
            List<String> ids=List.copyOf(members.keySet());
            boolean complete=bundle.complete()&&members.values().stream().noneMatch(fragment->Boolean.FALSE.equals(fragment.fields().get("evidence_context_complete")));
            for(var fragment:members.values()) {
                var fields=new LinkedHashMap<>(fragment.fields());fields.put("evidence_bundle_ids",governingContext(fragment)?List.of(fragment.fragmentId()):ids);
                fields.putIfAbsent("evidence_section_heading",bundle.heading());fields.put("evidence_context_complete",governingContext(fragment)||complete);
                fields.put("evidence_scope_title",fragment.title());
                fields.put("evidence_role",governingContext(fragment)?"governing_context":sourceHeading(fragment)?"heading":"context");
                selected.put(fragment.fragmentId(),new Api.SearchHit(new Api.Fragment(fields),hit.score()));
                scopes.addAll(levels(fragment.title(),intent.kazakh()));
            }
            if(selected.size()==maximum)break;
        }
        boolean ambiguous=intent.levels().isEmpty()?!scopes.isEmpty():!scopes.isEmpty()&&Collections.disjoint(intent.levels(),scopes);
        return selected.values().stream().map(hit->{var fields=new LinkedHashMap<>(hit.fragment().fields());
            fields.put("evidence_scope_ambiguous",ambiguous);return new Api.SearchHit(new Api.Fragment(fields),hit.score());}).toList();
    }
    private List<Api.SearchHit> scopedCandidates(String query,List<Api.SearchHit> ranked,Intent intent,Api.CancellationToken token) {
        var anchors=new LinkedHashMap<String,Api.SearchHit>();
        var first=ranked.subList(0,Math.min(12,ranked.size()));var counts=new HashMap<String,Integer>();
        first.forEach(hit->counts.merge(hit.fragment().documentId(),1,Integer::sum));
        for(var hit:first) {
            var title=terms(hit.fragment().title(),intent.kazakh());
            long shared=intent.topic().stream().filter(title::contains).count();
            var body=terms(hit.fragment().text(),intent.kazakh());long bodyShared=intent.topic().stream().filter(body::contains).count();
            boolean anchored=shared>=Math.min(2,intent.topic().size())||shared>=1&&bodyShared>=1
                ||counts.get(hit.fragment().documentId())>=2&&bodyShared>=1;
            if(!intent.topic().isEmpty()&&anchored&&!nativeFurniture(hit.fragment()))
                anchors.putIfAbsent(hit.fragment().documentId(),hit);
            if(anchors.size()==3)break;
        }
        var result=new LinkedHashMap<String,Api.SearchHit>();ranked.forEach(hit->result.put(hit.fragment().fragmentId(),hit));
        var database=context.require(Api.DatabaseServiceV1.class);
        for(var anchor:anchors.values()) {
            token.check();var scoped=new ArrayList<Api.SearchHit>();String sectionHeading="";
            for(var row:database.query("SELECT * FROM chunks WHERE document_id=? ORDER BY rowid LIMIT 2000",List.of(anchor.fragment().documentId()),token)) {
                token.check();var fields=SearchModule.Search.fragmentFields(row);var fragment=new Api.Fragment(fields);
                if(fragment.sourceRefs().isEmpty()||nativeFurniture(fragment))continue;
                if(sourceHeading(fragment)){sectionHeading=fragment.text();continue;}
                if(!pdf(fragment)&&bodyRule(fragment.text())&&!sectionHeading.isEmpty()) {
                    String parent=pointNumber(sectionHeading),child=pointNumber(fragment.text());
                    if(parent.isEmpty()||!child.startsWith(parent+"."))sectionHeading="";
                }
                if(!sectionHeading.isEmpty())fields.put("evidence_section_heading",sectionHeading);
                var existing=result.get(fragment.fragmentId());
                if(existing!=null){var originalFields=new LinkedHashMap<>(existing.fragment().fields());
                    if(!sectionHeading.isEmpty())originalFields.put("evidence_section_heading",sectionHeading);
                    result.put(fragment.fragmentId(),new Api.SearchHit(new Api.Fragment(originalFields),existing.score()));}
                if(!predicate(fragment.text()))continue;
                Set<String> explicit=new HashSet<>(QueryNormalizer.tokens(fragment.title()+" "+fragment.text()));
                if(QueryNormalizer.tokens(query).stream().filter(w->w.matches("(?:19|20|21)[0-9]{2}")||w.matches("(?=.*[0-9])(?=.*[\\p{L}])[\\p{L}0-9]+"))
                    .anyMatch(w->!explicit.contains(w)))continue;
                if(anchor.fragment().fields().containsKey("evidence_answer_mode"))fields.put("evidence_answer_mode",anchor.fragment().fields().get("evidence_answer_mode"));
                scoped.add(new Api.SearchHit(new Api.Fragment(fields),anchor.score()));
            }
            scoped.stream().sorted(Comparator.<Api.SearchHit>comparingDouble(hit->evidenceValue(hit.fragment(),intent,64)).reversed()
                .thenComparing(hit->hit.fragment().fragmentId())).limit(24).forEach(hit->result.putIfAbsent(hit.fragment().fragmentId(),hit));
        }
        return List.copyOf(result.values());
    }
    private static boolean nativeFurniture(Api.Fragment fragment) {
        return !fragment.sourceRefs().isEmpty()&&fragment.sourceRefs().stream().allMatch(ref->{
            String part=Objects.toString(ref.fields().get("part"),""),xpath=Objects.toString(ref.fields().get("xpath"),"");
            return part.matches("word/(?:header|footer)[^/]*\\.xml")||xpath.startsWith("/w:hdr/")||xpath.startsWith("/w:ftr/");
        });
    }
    private static boolean bodyRule(String text) {
        return text.strip().matches("(?s)^(?:[0-9]+(?:\\.[0-9]+)*[.)][\\s\\p{Zs}]*|[0-9]+[\\s\\p{Zs}]+)\\p{L}.*");
    }
    private static String nativeFlow(Api.Fragment fragment) {
        var keys=new HashSet<String>();
        for(var ref:fragment.sourceRefs()) {
            var fields=ref.fields();if(!Objects.equals(fields.get("kind"),"ooxml_node"))continue;
            String xpath=Objects.toString(fields.get("xpath"),""),part=Objects.toString(fields.get("part"),"");
            if(xpath.isBlank()||part.isBlank())continue;
            int cell=xpath.lastIndexOf("/w:tc[");int end=cell<0?-1:xpath.indexOf(']',cell);
            String region=end<0?"body":xpath.substring(0,end+1);
            keys.add(Objects.toString(fields.get("artifact_id"),"")+"|"+part+"|"+region);
        }
        // A composite row spanning cells can stand alone, but must not silently
        // join a neighbouring cell or another parallel-language column.
        return keys.size()>1?"composite:"+fragment.fragmentId():keys.stream().findFirst().orElse("");
    }
    private static boolean sameNativeFlow(Api.Fragment one,Api.Fragment two) {
        String a=nativeFlow(one),b=nativeFlow(two);return a.isEmpty()||b.isEmpty()||a.equals(b);
    }
    private static boolean governingContext(Api.Fragment fragment) {
        return "governing_context".equals(fragment.fields().get("evidence_role"));
    }
    private static Api.Fragment asGoverningContext(Api.Fragment fragment) {
        var fields=new LinkedHashMap<>(fragment.fields());fields.put("evidence_role","governing_context");return new Api.Fragment(fields);
    }
    private static String listContent(String text) {
        return text.strip().replaceFirst("^(?:[-–—•]|[а-яa-z][.)]|[0-9]+[)])\\s*","");
    }
    private static boolean obligation(String text) {
        return QueryNormalizer.tokens(text).stream().anyMatch(word->Set.of(
            "обязан","обязана","обязаны","должен","должна","должны","необходимо","must","shall","тиіс","міндетті","қажет").contains(word));
    }
    private static boolean mandatoryListIntro(String text) {
        if(!obligation(text))return false;
        String content=text.strip().replaceFirst("^[0-9]+(?:\\.[0-9]+)*[.)]?[\\s\\p{Zs}]+","");
        // One governing obligation, or an explicit forward reference, binds
        // every dependent item. A paragraph containing several independent
        // sentences does not turn every subsequent policy into its condition.
        return content.chars().filter(c->c=='.'||c=='!'||c=='?').count()==0
            ||QueryNormalizer.tokens(content).stream().anyMatch(word->word.startsWith("следующ")||word.startsWith("нижеследующ")
                ||word.equals("following")||word.startsWith("мынадай")||word.startsWith("төмендег")||word.equals("келесідей"));
    }
    private static boolean standaloneListRule(String text) {
        String content=listContent(text);
        if(!listItem(text)||!terminal(content)||dependsOnPrevious(content)||!predicate(content))return false;
        return obligation(content)||QueryNormalizer.tokens(content).stream().anyMatch(word->Set.of(
                "запрещено","недопустимо","допускается","вправе","may","can","prohibited","permitted","allowed","тыйым","мүмкін").contains(word))
            ||content.chars().filter(c->c=='.'||c=='!'||c=='?').count()>=2;
    }
    private static boolean referencesIntro(String text,String intro) {
        if(!dependsOnPrevious(text))return false;
        boolean kazakh=intro.codePoints().anyMatch(c->"әғқңөұүһіӘҒҚҢӨҰҮҺІ".indexOf(c)>=0);
        Set<String> subject=terms(intro,kazakh),reference=terms(text,kazakh);
        return subject.stream().anyMatch(word->word.length()>=5&&(reference.contains(word)
            ||kazakh&&List.of("дар","дер","тар","тер").stream().anyMatch(suffix->reference.contains(word+suffix))));
    }
    private Bundle alternativeBundle(List<Api.Fragment> window,int index,int maximum,String section,boolean endKnown) {
        int parent=index;
        if(!window.get(parent).text().strip().endsWith(":")) {
            if(!listItem(window.get(index).text())&&!linkedCondition(window.get(index).text()))return null;
            parent--;
            while(parent>=0&&!window.get(parent).text().strip().endsWith(":")
                    &&(listItem(window.get(parent).text())||linkedCondition(window.get(parent).text())))parent--;
        }
        if(parent<0||parent+1>=window.size()||!window.get(parent).text().strip().endsWith(":")
                ||mandatoryListIntro(window.get(parent).text())||!listItem(window.get(parent+1).text()))return null;
        String intro=window.get(parent).text();
        int cursor=parent+1;boolean independent=false;List<Api.Fragment> requested=null;
        while(cursor<window.size()&&listItem(window.get(cursor).text())) {
            int first=cursor++;independent|=standaloneListRule(window.get(first).text());
            while(cursor<window.size()&&!listItem(window.get(cursor).text())&&!sourceHeading(window.get(cursor))
                    &&!bodyRule(window.get(cursor).text())&&linkedCondition(window.get(cursor).text())
                    &&!referencesIntro(window.get(cursor).text(),intro))cursor++;
            var branch=window.subList(first,cursor);
            if(index>=first&&index<cursor)requested=branch;
        }
        if(!independent)return null;
        if(index==parent)return new Bundle(List.of(asGoverningContext(window.get(parent))),section,true);
        if(requested==null)return null;
        var members=new ArrayList<Api.Fragment>();members.add(asGoverningContext(window.get(parent)));members.addAll(requested);
        while(cursor<window.size()&&!sourceHeading(window.get(cursor))&&!bodyRule(window.get(cursor).text())
                &&referencesIntro(window.get(cursor).text(),intro))members.add(asGoverningContext(window.get(cursor++)));
        boolean complete=standaloneListRule(requested.getFirst().text())&&terminal(members.getLast().text())&&(cursor<window.size()||endKnown);
        if(members.size()>maximum||members.stream().mapToInt(f->f.text().length()).sum()>24_000)return new Bundle(List.of(),section,false);
        // Dependencies are directed: the branch needs the shared intro/condition;
        // shared context does not acquire dependencies on every other branch.
        return new Bundle(List.copyOf(members),section,complete);
    }
    private static String pointNumber(String text) {
        var match=java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)(?:[.)]|[\\s\\p{Zs}])").matcher(text.strip());
        return match.find()?match.group(1):"";
    }
    private Bundle bundle(Api.Fragment anchor,int maximum,Api.CancellationToken token) {
        if(pdf(anchor))return pdfBundle(anchor,maximum,token);
        if(nativeFurniture(anchor))return new Bundle(List.of(),"",false);
        var database=context.require(Api.DatabaseServiceV1.class);
        var at=database.query("SELECT rowid FROM chunks WHERE fragment_id=? AND document_id=?",List.of(anchor.fragmentId(),anchor.documentId()),token);
        if(at.isEmpty())throw new Api.ServiceException("SOURCE_INVALID","Не найдено положение исходного фрагмента");
        Object row=at.getFirst().get("rowid");var before=new ArrayList<Api.Fragment>();var after=new ArrayList<Api.Fragment>();
        for(var value:database.query("SELECT * FROM chunks WHERE document_id=? AND rowid<? ORDER BY rowid DESC LIMIT 32",List.of(anchor.documentId(),row),token))before.add(new Api.Fragment(SearchModule.Search.fragmentFields(value)));
        Collections.reverse(before);
        for(var value:database.query("SELECT * FROM chunks WHERE document_id=? AND rowid>? ORDER BY rowid LIMIT 32",List.of(anchor.documentId(),row),token))after.add(new Api.Fragment(SearchModule.Search.fragmentFields(value)));
        var raw=new ArrayList<Api.Fragment>(before);raw.add(anchor);raw.addAll(after);
        var visible=raw.stream().filter(fragment->!nativeFurniture(fragment)).toList();
        int atIndex=-1;for(int i=0;i<visible.size();i++)if(visible.get(i).fragmentId().equals(anchor.fragmentId())){atIndex=i;break;}
        if(atIndex<0)return new Bundle(List.of(),"",false);
        int left=atIndex,right=atIndex+1;
        while(left>0&&sameNativeFlow(visible.get(left-1),visible.get(left)))left--;
        while(right<visible.size()&&sameNativeFlow(visible.get(right-1),visible.get(right)))right++;
        var window=visible.subList(left,right);
        int index=-1;for(int i=0;i<window.size();i++)if(window.get(i).fragmentId().equals(anchor.fragmentId())){index=i;break;}
        if(index<0)return new Bundle(List.of(),"",false);
        String section="";
        for(int i=index-1;i>=0;i--)if(sourceHeading(window.get(i))){section=window.get(i).text();break;}
        if(sourceHeading(anchor))return new Bundle(List.of(),anchor.text(),false);
        Bundle alternative=alternativeBundle(window,index,maximum,section,after.size()<32);
        if(alternative!=null)return alternative;
        // Native OOXML paragraphs are already source units. A small section is
        // not automatically a dependency: independent numbered rules and the
        // next heading must not be pulled into a matched clause.
        int start=index,end=index+1;boolean governing=!dependsOnPrevious(anchor.text());
        int listParent=index;
        if(listItem(anchor.text())) {
            while(listParent>0&&listItem(window.get(listParent-1).text()))listParent--;
            if(listParent>0&&window.get(listParent-1).text().strip().endsWith(":")) {
                start=listParent-1;governing=!dependsOnPrevious(window.get(start).text());
            }
        }
        if(start==index&&index>0&&(dependsOnPrevious(anchor.text())||window.get(index-1).text().strip().endsWith(":"))) {
            while(start>0) {
                var previous=window.get(start-1);
                if(sourceHeading(previous))break;
                start--;
                if(predicate(previous.text())&&!dependsOnPrevious(previous.text())){governing=true;break;}
                if(!continuation(previous.text()))break;
            }
        }
        boolean governingList=window.get(start).text().strip().endsWith(":");
        boolean listedUnit=listItem(window.get(start).text());
        while(end<window.size()) {
            String next=window.get(end).text(),previous=window.get(end-1).text();
            if(sourceHeading(window.get(end))||bodyRule(next)&&!((governingList||listedUnit)&&listItem(next)))break;
            if(!(((governingList||listedUnit)?continuation(next):linkedCondition(next))||previous.strip().endsWith(":")
                    ||!terminal(previous)&&lowerStart(next)))break;
            end++;
        }
        var original=window.subList(start,end);List<Api.Fragment> unit=usable(original);
        boolean headingGoverned=start>0&&sourceHeading(window.get(start-1))&&original.stream().allMatch(fragment->listItem(fragment.text()));
        if(headingGoverned)governing=true;
        boolean complete=governing&&!unit.isEmpty()&&unit.size()==original.size()
            &&(headingGoverned||predicate(String.join("\n",unit.stream().map(Api.Fragment::text).toList())))
            &&terminal(unit.getLast().text())&&(end<window.size()||after.size()<32);
        if(unit.size()>maximum||unit.stream().mapToInt(f->f.text().length()).sum()>24_000) {
            context.diagnostic("Evidence unit exceeds whole-fragment context budget; source "+anchor.fragmentId());return new Bundle(List.of(),section,false);
        }
        return new Bundle(unit,section,complete);
    }
    private static boolean pdf(Api.Fragment fragment) {
        return !fragment.sourceRefs().isEmpty()&&fragment.sourceRefs().stream().allMatch(ref->Objects.equals(ref.fields().get("kind"),"pdf_region"));
    }
    private static boolean pdfMargin(Api.Fragment fragment) {
        if(!pdf(fragment)||fragment.text().length()>300)return false;
        String text=fragment.text().strip().toLowerCase(Locale.ROOT);
        boolean label=text.matches("(?s).*(?:редакци|страниц|утвержден|решени[ея] правления|edition|page |бекітіл|бет ).*")
            ||text.matches("[\\p{L}]{1,4}\\s+[\\p{L}0-9 .-]{1,45}");
        if(!label)return false;
        for(var reference:fragment.sourceRefs()) {
            Object rawBox=reference.fields().get("bbox"),rawSize=reference.fields().get("page_size");
            if(!(rawBox instanceof List<?> box)||box.size()!=4||!(rawSize instanceof List<?> size)||size.size()!=2)return false;
            double top=((Number)box.get(1)).doubleValue(),bottom=((Number)box.get(3)).doubleValue(),height=((Number)size.get(1)).doubleValue();
            if(!(bottom<=height*.08||top>=height*.92))return false;
        }
        return true;
    }
    private static boolean covers(Api.Fragment outer,Api.Fragment inner) {
        if(!pdf(outer)||!pdf(inner)||outer.fragmentId().equals(inner.fragmentId()))return false;
        String a=outer.text().replaceAll("\\s+"," ").strip(),b=inner.text().replaceAll("\\s+"," ").strip();
        if(a.length()<b.length()||!a.contains(b))return false;
        if(a.equals(b)&&outer.fragmentId().compareTo(inner.fragmentId())>=0)return false;
        return inner.sourceRefs().stream().allMatch(ref->outer.sourceRefs().stream().anyMatch(candidate->{
            var one=ref.fields();var two=candidate.fields();
            if(!Objects.equals(one.get("page"),two.get("page"))||!Objects.equals(one.get("artifact_id"),two.get("artifact_id")))return false;
            if(!(one.get("bbox") instanceof List<?> x)||!(two.get("bbox") instanceof List<?> y)||x.size()!=4||y.size()!=4)return false;
            // Inferred table rows often use the full column width while a
            // text-layer line has a tight box. Exact text + same source/page
            // and containing vertical interval identify the duplicate safely.
            return ((Number)y.get(1)).doubleValue()<=((Number)x.get(1)).doubleValue()+2
                &&((Number)y.get(3)).doubleValue()>=((Number)x.get(3)).doubleValue()-2;
        }));
    }
    private static boolean numberedRule(String text) {return text.strip().matches("(?s)^[0-9]+(?:\\.[0-9]+)+[.)]?\\s+.*");}
    private static boolean pdfHeading(String text,String title) {
        return !numberedRule(text)&&heading(text,title)&&identitySpan(text).isEmpty()
            &&text.strip().equals(text.strip().toUpperCase(Locale.ROOT));
    }
    private static boolean terminal(String text) {return text.strip().matches("(?s).*[.!?;][»\"')\\]]*$");}
    private static boolean lowerStart(String text) {
        String value=text.strip();return !value.isEmpty()&&Character.isLowerCase(value.codePointAt(0));
    }
    private static double pdfOrder(Api.Fragment fragment) {
        double first=Double.MAX_VALUE;
        for(var ref:fragment.sourceRefs())if(ref.fields().get("page") instanceof Number page&&ref.fields().get("bbox") instanceof List<?> box&&box.size()==4)
            first=Math.min(first,page.doubleValue()*10_000_000+((Number)box.get(1)).doubleValue()*1_000+((Number)box.get(0)).doubleValue());
        return first;
    }
    private Bundle pdfBundle(Api.Fragment anchor,int maximum,Api.CancellationToken token) {
        if(pdfMargin(anchor))return new Bundle(List.of(),"",false);
        var database=context.require(Api.DatabaseServiceV1.class);
        var at=database.query("SELECT rowid FROM chunks WHERE fragment_id=? AND document_id=?",List.of(anchor.fragmentId(),anchor.documentId()),token);
        if(at.isEmpty())throw new Api.ServiceException("SOURCE_INVALID","Не найдено положение исходного фрагмента");
        Object row=at.getFirst().get("rowid");var before=new ArrayList<Api.Fragment>();var after=new ArrayList<Api.Fragment>();
        for(var value:database.query("SELECT * FROM chunks WHERE document_id=? AND rowid<? ORDER BY rowid DESC LIMIT 32",List.of(anchor.documentId(),row),token))before.add(new Api.Fragment(SearchModule.Search.fragmentFields(value)));
        Collections.reverse(before);
        for(var value:database.query("SELECT * FROM chunks WHERE document_id=? AND rowid>? ORDER BY rowid LIMIT 32",List.of(anchor.documentId(),row),token))after.add(new Api.Fragment(SearchModule.Search.fragmentFields(value)));
        var raw=new ArrayList<Api.Fragment>(before);raw.add(anchor);raw.addAll(after);
        // PDF extraction can preserve both a joined paragraph and individual
        // geometric rows. Canonical text is never concatenated or rewritten.
        // Mixed table/paragraph extraction may append a second pass over one
        // page. Canonical PDF locators restore the actual source reading order.
        var window=raw.stream().filter(f->!pdfMargin(f)&&raw.stream().noneMatch(outer->covers(outer,f)))
            .sorted(Comparator.comparingDouble(AssistantSearch::pdfOrder).thenComparing(Api.Fragment::fragmentId)).toList();
        int index=-1;for(int i=0;i<window.size();i++)if(window.get(i).fragmentId().equals(anchor.fragmentId())||covers(window.get(i),anchor)){index=i;break;}
        if(index<0)return new Bundle(List.of(),"",false);
        int start=index,end=index+1;String section="";boolean rule=false,knownStart=false,knownEnd=false;
        for(int i=index;i>=0;i--) {
            String text=window.get(i).text();
            if(numberedRule(text)){start=i;rule=true;knownStart=true;break;}
            if(i<index&&pdfHeading(text,anchor.title())){section=text;break;}
        }
        if(!rule) {
            while(start>0&&(lowerStart(window.get(start).text())||!terminal(window.get(start-1).text()))
                    &&!pdfHeading(window.get(start-1).text(),anchor.title()))start--;
            knownStart=!lowerStart(window.get(start).text())&&(start>0||before.size()<32);
        }
        if(rule) {
            end=start+1;
            while(end<window.size()&&!numberedRule(window.get(end).text())&&!pdfHeading(window.get(end).text(),anchor.title()))end++;
            knownEnd=end<window.size()||after.size()<32;
        } else {
            end=Math.max(index+1,start+1);
            while(end<window.size()&&!numberedRule(window.get(end).text())
                    &&(!terminal(window.get(end-1).text())||lowerStart(window.get(end).text())||dependsOnPrevious(window.get(end).text()))
                    &&!pdfHeading(window.get(end).text(),anchor.title()))end++;
            knownEnd=end<window.size()||after.size()<32;
        }
        // Find a real source heading before the governing rule, never fabricate
        // one from the body line that happens to contain its number and actor.
        for(int i=start-1;i>=0;i--)if(pdfHeading(window.get(i).text(),anchor.title())){section=window.get(i).text();break;}
        var unit=usable(window.subList(start,end));
        boolean complete=knownStart&&knownEnd&&!unit.isEmpty()&&terminal(unit.getLast().text())
            &&predicate(String.join("\n",unit.stream().map(Api.Fragment::text).toList()));
        if(unit.size()>maximum||unit.stream().mapToInt(f->f.text().length()).sum()>24_000)return new Bundle(List.of(),section,false);
        return new Bundle(unit,section,complete);
    }
    private static List<Api.Fragment> usable(List<Api.Fragment> fragments) {
        return fragments.stream().filter(fragment->!fragment.text().isBlank()&&!fragment.sourceRefs().isEmpty()).toList();
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
        Intent intent=intent(query);
        List<Api.SearchHit> original=exact.exact(query,100,documentIds,sections,token);
        if(QuestionQuery.plan(query).map(QuestionQuery.Plan::organizationIdentity).orElse(false))
            return original.subList(0,Math.min(maximum,original.size()));
        LinkedHashMap<String,String> topic=new LinkedHashMap<>();
        for(String word:QuestionQuery.subjectWords(query)) {
            token.check(); String stem=term(word,intent.kazakh());
            topic.putIfAbsent(stem,word);
        }
        if(topic.isEmpty()) return List.of();
        Set<String> task=QuestionQuery.taskWords(query); task.removeAll(topic.keySet());
        if(!Collections.disjoint(task,CONDITIONS)) task.addAll(CONDITION_HINTS);
        boolean procedural=!Collections.disjoint(task,PROCEDURES);
        if(procedural) task.addAll(PROCEDURE_HINTS);
        String cleaned=String.join(" ",topic.values());
        List<Api.SearchHit> topical=cleaned.equals(query)?original:exact.exact(cleaned,100,documentIds,sections,token);
        if(intent.kazakh()) {
            var expanded=new ArrayList<Api.SearchHit>(topical);
            expanded.addAll(kazakhTopics(topic.keySet(),documentIds,sections,token));topical=expanded;
        }
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
            token.check(); Set<String> words=terms(Objects.toString(row.get("title"),""),intent.kazakh());
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
            Set<String> body=terms(text,intent.kazakh());
            long hints=task.stream().filter(body::contains).count();
            long overlaps=topic.keySet().stream().filter(body::contains).count();
            // A procedural question needs actual duties/reporting passages, not
            // repetitive headers from a more specifically named document.
            double value=(procedural?200:1000)*titleScores.getOrDefault(fragment.documentId(),0.0)
                +(text.length()>=80?30:0)+Math.min(20,3*Math.log1p(text.length()))+(procedural?24:12)*hints+3*overlaps
                +(intent.normative()&&predicate(text)?40:0)-(intent.normative()&&sourceHeading(fragment)?100:0);
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
    private List<Api.SearchHit> kazakhTopics(Set<String> topic,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
        // Kazakh suffixes are not present in the Russian stem column. Prefixes
        // remain a bounded AND of every subject; years and identifiers stay exact.
        String expression=topic.stream().map(word->{boolean prefix=word.length()>=4&&word.codePoints().allMatch(Character::isLetter);
            String value="\""+word+"\""+(prefix?"*":"");return "(text:"+value+" OR title:"+value+" OR stemmed:"+value+")";})
            .reduce((a,b)->a+" AND "+b).orElse("");
        if(expression.isEmpty())return List.of();
        var parameters=new ArrayList<Object>();parameters.add(expression);
        var sql=new StringBuilder("SELECT c.*,bm25(chunks_fts,6.0,10.0,1.0,0.5) AS score FROM chunks_fts JOIN chunks c ON c.rowid=chunks_fts.rowid JOIN documents d ON d.document_id=c.document_id WHERE chunks_fts MATCH ?");
        filters(sql,parameters,documentIds,sections);sql.append(" ORDER BY score,c.rowid LIMIT 100");
        return context.require(Api.DatabaseServiceV1.class).query(sql.toString(),parameters,token).stream().map(row->{
            var fields=SearchModule.Search.fragmentFields(row);double score=Double.parseDouble(Objects.toString(fields.remove("score")));
            return new Api.SearchHit(new Api.Fragment(fields),score);}).toList();
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
