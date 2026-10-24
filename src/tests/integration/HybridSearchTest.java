package ai.enterprise.tests;

import ai.enterprise.contracts.*;
import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only canonical corpus integration. Synthetic rankings verify wiring, not model quality. */
public final class HybridSearchTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Evidence implements EvidenceModelServiceV1 {
        Api.Fragment target;boolean available=true,badDocument=false;
        public Api.Result<Info> availability(){return available?Api.Result.ok(new Info("test",384,"test",true)):Api.Result.fail("EVIDENCE_UNAVAILABLE","test");}
        public CompletionStage<Api.Result<List<ScoredFragment>>> retrieve(String base,String query,int limit,List<String> ids,List<String> sections,Api.CancellationToken token) {
            check(base.equals("local"),"Explicit base namespace crosses the contract");
            return CompletableFuture.completedFuture(Api.Result.ok(List.of(new ScoredFragment(target.fragmentId(),badDocument?"foreign-document":target.documentId(),0.9))));
        }
        public CompletionStage<Api.Result<List<ScoredFragment>>> rerank(String query,List<Candidate> candidates,Api.CancellationToken token) {
            return CompletableFuture.completedFuture(Api.Result.ok(candidates.stream().map(c->new ScoredFragment(c.fragmentId(),c.documentId(),c.fragmentId().equals(target.fragmentId())?10:0)).toList()));
        }
        public CompletionStage<Api.Result<List<float[]>>> embed(List<String> texts,boolean queries,Api.CancellationToken token){throw new AssertionError();}
    }
    @SuppressWarnings("unchecked")
    private static Map<Class<?>,Object> testServices(Api.Context context) throws ReflectiveOperationException {
        // Test-only dependency transport substitution after ALL module factories
        // register. Production contracts and module composition remain intact.
        var field=Api.Context.class.getDeclaredField("services");field.setAccessible(true);
        return (Map<Class<?>,Object>)field.get(context);
    }
    private static void evidenceFeatureChecks() throws ReflectiveOperationException {
        Class<?> type=Class.forName("ai.enterprise.search.AssistantSearch");
        var heading=type.getDeclaredMethod("heading",String.class,String.class);heading.setAccessible(true);
        check((boolean)heading.invoke(null,"Порядок выполнения работ","Правила организации работ"),"A title alone is not a procedural clause");
        check(!(boolean)heading.invoke(null,"Работник обязан уведомить руководителя в течение 5 рабочих дней.","Правила организации работ"),"A short actor/action/deadline remains substantive evidence");
        check(!(boolean)heading.invoke(null,"3.2. Дата начала работы ____________________","Договор"),"An unfilled form field is not a source section");
        check(!(boolean)heading.invoke(null,"Н\n1\n2\n3\n4","Положение"),"A single-letter numeric legend is not a PDF section");
        check(!(boolean)heading.invoke(null,"4.5.5 | Научная стажировка | 1,0 кр","План"),"A composite table row is not a document heading");
        var sourceHeading=type.getDeclaredMethod("sourceHeading",Api.Fragment.class);sourceHeading.setAccessible(true);
        check(!(boolean)sourceHeading.invoke(null,new Api.Fragment(Map.of("fragment_id","actor","document_id","fixture","title","Порядок хранения","text","154. Руководители структурных подразделений",
            "source_refs",List.of(Map.of("kind","pdf_region"))))),"A numbered PDF actor line remains deliverable context even without its continuation's predicate");
        check(!(boolean)sourceHeading.invoke(null,new Api.Fragment(Map.of("fragment_id","cell","document_id","fixture","title","Порядок","text","Условия работы",
            "source_refs",List.of(Map.of("kind","ooxml_node","part","word/document.xml","xpath","/w:document/w:body/w:tbl[1]/w:tr[2]/w:tc[1]/w:p[1]"))))),"A native table cell cannot supply a guessed section title");
        var predicate=type.getDeclaredMethod("predicate",String.class);predicate.setAccessible(true);
        check((boolean)predicate.invoke(null,"Қызметкерге төлем айына бір рет карт-шотына аудару жолымен жүргізіледі."),"A Kazakh finite payment clause remains eligible for scoped evidence retrieval");
        check((boolean)predicate.invoke(null,"The employee may be recalled only with written consent."),"An English modal provision is substantive rather than a heading");
        var dependent=type.getDeclaredMethod("dependsOnPrevious",String.class);dependent.setAccessible(true);
        check((boolean)dependent.invoke(null,"За исключением случаев, указанных в предыдущем пункте."),"An exception cannot silently become an independent rule");
        check(!(boolean)dependent.invoke(null,"При получении требования работник обязан уведомить работодателя заранее."),"A complete conditional clause is not denied merely because it starts with a condition");
        check((boolean)dependent.invoke(null,"Неиспользованная в связи с этим часть периода предоставляется по соглашению сторон."),"Internal first-sentence anaphora retains its antecedent");
        check((boolean)dependent.invoke(null,"При этом такие сведения передаются руководителю."),"Anaphoric condition is not a standalone source rule");
        check(!(boolean)dependent.invoke(null,"8.5. По соглашению сторон период может быть разделен. При этом одна часть должна быть не менее двух недель."),"A later sentence's antecedent already contained in the same canonical paragraph does not pull another rule");
        check((boolean)dependent.invoke(null,"Осыған байланысты пайдаланылмаған бөлігі тараптардың келісімі бойынша беріледі."),"Kazakh anaphora retains the preceding condition instead of claiming standalone completeness");
        var term=type.getDeclaredMethod("term",String.class,boolean.class);term.setAccessible(true);
        check(term.invoke(null,"құпияны",true).equals("құпия"),"Kazakh case suffix keeps the subject root");
        check(term.invoke(null,"QXZ2099",true).equals("QXZ2099"),"Identifiers must never undergo suffix or prefix approximation");
        var identity=type.getDeclaredMethod("identitySpan",String.class);identity.setAccessible(true);
        check(identity.invoke(null,"140. Электронные документы формируются в дела.").equals(""),"An unrelated body cannot borrow an organization name from its title");
        check(identity.invoke(null,"Учредитель: Некоммерческое акционерное общество «Национальный исследовательский университет имени Учёного».")
            .equals("Некоммерческое акционерное общество «Национальный исследовательский университет имени Учёного»"),"Identity is an exact contiguous canonical body span");
        check(identity.invoke(null,"Некоммерческое акционерное общество «ҚАЗАҚ ҰЛТТЫҚ ИССЛЕДОВАТЕЛЬСКИЙ УНИВЕРСИТЕТ»").equals(""),"Interleaved bilingual OCR is not reconstructed as an exact organization name");
        check(identity.invoke(null,"Акционерное общество «Название_____организации»").equals(""),"Unfilled form blanks cannot become an organization name");
    }
    private static void wholeBundleChecks(Api.Context context) throws ReflectiveOperationException {
        var rows=new ArrayList<Map<String,Object>>();
        List<String> text=List.of("1. Порядок уведомления","Работник обязан уведомить руководителя о получении требования:",
            "- в письменной форме;","- не позднее 5 рабочих дней;","За исключением случаев временной нетрудоспособности работника.",
            "2. Порядок регистрации","Уполномоченное лицо регистрирует заявление в журнале.");
        for(int i=0;i<text.size();i++)rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document",
            "title","Регламент оформления уведомлений","text",text.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","ooxml_node"))));
        Api.DatabaseServiceV1 fixture=new Api.DatabaseServiceV1() {
            public String sqliteVersion(){return "test-fixture";}
            public List<Map<String,Object>> query(String sql,List<?> parameters,Api.CancellationToken token) {
                token.check();
                if(sql.startsWith("SELECT rowid"))return List.of(Map.of("rowid",Long.parseLong(parameters.getFirst().toString().substring("fixture-".length()))));
                long at=Long.parseLong(parameters.get(1).toString());
                var selected=new ArrayList<Map<String,Object>>();
                for(var row:rows)if(sql.contains("rowid<?")?(long)row.get("rowid")<at:(long)row.get("rowid")>at)selected.add(row);
                if(sql.contains("DESC"))Collections.reverse(selected);
                return selected.subList(0,Math.min(sql.endsWith("LIMIT 32")?32:8,selected.size()));
            }
        };
        var services=testServices(context);Object actual=services.put(Api.DatabaseServiceV1.class,fixture);
        try {
            Class<?> type=Class.forName("ai.enterprise.search.AssistantSearch");
            var constructor=type.getDeclaredConstructor(Api.Context.class,SearchModule.Search.class);constructor.setAccessible(true);
            var assistant=constructor.newInstance(context,new SearchModule.Search(context));
            var method=type.getDeclaredMethod("bundle",Api.Fragment.class,int.class,Api.CancellationToken.class);method.setAccessible(true);
            Object complete=method.invoke(assistant,new Api.Fragment(rows.get(3)),8,new Api.CancellationToken());
            var fragments=complete.getClass().getDeclaredMethod("fragments");fragments.setAccessible(true);
            var completeness=complete.getClass().getDeclaredMethod("complete");completeness.setAccessible(true);
            @SuppressWarnings("unchecked") var members=(List<Api.Fragment>)fragments.invoke(complete);
            check(members.stream().map(Api.Fragment::fragmentId).toList().equals(List.of("fixture-1","fixture-2","fixture-3","fixture-4")),"A matched deadline retains its governing actor/action, sibling requirement and exception, stopping at the next section");
            check((boolean)completeness.invoke(complete),"A closed source section has explicit completeness");
            Object bounded=method.invoke(assistant,new Api.Fragment(rows.get(3)),3,new Api.CancellationToken());
            check(((List<?>)fragments.invoke(bounded)).isEmpty(),"An oversized governing list is skipped whole, never shortened to a seemingly complete deadline");
            Object boundary=method.invoke(assistant,new Api.Fragment(rows.get(6)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var last=(List<Api.Fragment>)fragments.invoke(boundary);
            check(last.size()==1&&last.getFirst().fragmentId().equals("fixture-6"),"The previous section's exception cannot leak into a different section's rule");
            rows.clear();
            List<String> extended=List.of("1. Содержание отчета","Отчет содержит анализ проведенной работы.",
                "a) описание первого направления;","b) описание второго направления;","c) описание третьего направления;",
                "d) описание четвертого направления;","e) описание пятого направления;","f) описание шестого направления;",
                "g) описание седьмого направления;","h) описание восьмого направления;",
                "i) достигнутые результаты должны быть сопоставлены с предыдущим периодом.","2. Выводы","Руководитель утверждает выводы.");
            for(int i=0;i<extended.size();i++)rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document",
                "title","Порядок подготовки отчета","text",extended.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","ooxml_node"))));
            Object independent=method.invoke(assistant,new Api.Fragment(rows.get(10)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var clause=(List<Api.Fragment>)fragments.invoke(independent);
            check(clause.size()==1&&clause.getFirst().fragmentId().equals("fixture-10")&&(boolean)completeness.invoke(independent),"An independent actor/action rule in a long list remains a whole usable clause");
            Object introduction=method.invoke(assistant,new Api.Fragment(rows.get(1)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var introductionOnly=(List<Api.Fragment>)fragments.invoke(introduction);
            check(introductionOnly.size()==1&&introductionOnly.getFirst().fragmentId().equals("fixture-1"),"A complete paragraph ending in a full stop does not inherit the following nominal topic list without a governing colon");
            rows.set(1,new LinkedHashMap<>(rows.get(1)));rows.get(1).put("text","При предоставлении уведомления работник обязан выполнить следующие действия:");
            for(int i=2;i<=10;i++) {rows.set(i,new LinkedHashMap<>(rows.get(i)));rows.get(i).put("text",(char)('a'+i-2)+") работник должен представить документ по действию "+i+".");}
            Object inherited=method.invoke(assistant,new Api.Fragment(rows.get(3)),8,new Api.CancellationToken());
            check(((List<?>)fragments.invoke(inherited)).isEmpty(),"Finite sibling bullets still inherit a colon-governing condition; an oversized mandatory list is not cut");
            rows.clear();
            List<String> pages=List.of("5 ПОДАЧА ЗАЯВЛЕНИЙ","5.1 Работники, желающие участвовать в программе",
                "обязаны подать заявление за два месяца. К заявлению прилагается рекомендация",
                "Утверждено Решением Правления от 01.01.2024","Регламент | Редакция 2 | Страница 2 из 4",
                "Редакция 2 Страница 2 из 4","подразделения и индивидуальный план.","5.2 Координатор обязан зарегистрировать заявление.");
            for(int i=0;i<pages.size();i++) {
                int page=i<4?1:2;double y=i==3?790:i==4||i==5?40:100+i*20;
                rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document","title","Регламент подачи заявлений",
                    "text",pages.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","pdf_region","page",page,"bbox",List.of(80,y,550,y+12),"page_size",List.of(595,842)))));
            }
            Object spanning=method.invoke(assistant,new Api.Fragment(rows.get(2)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var across=(List<Api.Fragment>)fragments.invoke(spanning);
            check(across.stream().map(Api.Fragment::fragmentId).toList().equals(List.of("fixture-1","fixture-2","fixture-6")),"A PDF rule keeps its actor and recommendation across page furniture, ending before the next rule");
            check((boolean)completeness.invoke(spanning),"A page-spanning rule is complete only after the terminal continuation");
            rows.set(6,new LinkedHashMap<>(rows.get(6)));rows.get(6).put("text","подразделения и индивидуальный");
            Object unfinished=method.invoke(assistant,new Api.Fragment(rows.get(2)),8,new Api.CancellationToken());
            check(!(boolean)completeness.invoke(unfinished),"A next rule or footer cannot certify a truncated PDF sentence as complete");
            rows.clear();
            List<String> points=List.of("1. Предмет договора","1.1. Работник выполняет работу, работодатель обязуется выплатить вознаграждение.",
                "2. Общие условия","2.1. Работник выполняет работу по указанному адресу.","2.2. Работодатель обеспечивает условия труда.");
            for(int i=0;i<points.size();i++)rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document",
                "title","Трудовой договор","text",points.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","ooxml_node","part","word/document.xml","xpath","/w:document/w:body/w:p["+(i+1)+"]"))));
            Object point=method.invoke(assistant,new Api.Fragment(rows.get(1)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var onePoint=(List<Api.Fragment>)fragments.invoke(point);
            check(onePoint.size()==1&&onePoint.getFirst().fragmentId().equals("fixture-1"),"A short DOCX section cannot absorb the next general-conditions heading and independent workplace rules");
            rows.set(1,new LinkedHashMap<>(rows.get(1)));rows.get(1).put("text","5.\u00a0Итоги выполнения плана рассматриваются два раза в год на заседании подразделения.");
            rows.set(2,new LinkedHashMap<>(rows.get(2)));rows.get(2).put("text","6.\u00a0План должен подвергаться систематической проверке руководителем.");
            rows.set(3,new LinkedHashMap<>(rows.get(3)));rows.get(3).put("text","7.\u00a0Ответственность за оформление и выполнение плана несут работник и руководитель.");
            Object numbered=method.invoke(assistant,new Api.Fragment(rows.get(2)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var oneNumber=(List<Api.Fragment>)fragments.invoke(numbered);
            check(oneNumber.size()==1&&oneNumber.getFirst().fragmentId().equals("fixture-2")&&(boolean)completeness.invoke(numbered),"NBSP-numbered DOCX provisions remain separate complete source clauses");
            rows.get(1).put("text","8.6. Период отдыха может быть прерван работодателем только с письменного согласия работника.");
            rows.get(2).put("text","Неиспользованная в связи с этим часть периода предоставляется по соглашению сторон или компенсируется.");
            rows.get(3).put("text","8.7. Работник может подать отдельное заявление о другом периоде.");
            Object anaphor=method.invoke(assistant,new Api.Fragment(rows.get(2)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var withAntecedent=(List<Api.Fragment>)fragments.invoke(anaphor);
            check(withAntecedent.stream().map(Api.Fragment::fragmentId).toList().equals(List.of("fixture-1","fixture-2"))&&(boolean)completeness.invoke(anaphor),"Unused-period compensation keeps the prior written-consent/interruption condition and stops before another numbered rule");
            Object antecedent=method.invoke(assistant,new Api.Fragment(rows.get(1)),8,new Api.CancellationToken());
            check(((List<?>)fragments.invoke(antecedent)).size()==2,"Choosing the governing rule also retains its anaphoric continuation");
            rows.clear();
            List<String> kazakhList=List.of("1. Кепілдіктер","1.1. Жұмыс беруші қызметкерге мынадай кепілдіктер береді:",
                "1) бірінші жағдайда жұмыс орнын сақтау кепілдігі;","2) екінші жағдайда өтемақы;",
                "3) үшінші жағдайда өтемақы;","4) төртінші жағдайда өтемақы;","5) бесінші жағдайда жәрдемақы;",
                "6) заңда белгіленген өзге де кепілдіктер.","Көрсетілген төлемдер құжаттарға сәйкес жүргізіледі.",
                "2. Жаңа бөлім","2.1. Қызметкер басқа талапты орындауға тиіс.");
            for(int i=0;i<kazakhList.size();i++)rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document",
                "title","Шарт","text",kazakhList.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","ooxml_node","part","word/document.xml","xpath","/w:document/w:body/w:p["+(i+1)+"]"))));
            Object fullList=method.invoke(assistant,new Api.Fragment(rows.get(3)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var listed=(List<Api.Fragment>)fragments.invoke(fullList);
            check(listed.size()==8&&listed.getFirst().fragmentId().equals("fixture-1")&&listed.getLast().fragmentId().equals("fixture-8")&&(boolean)completeness.invoke(fullList),"A native 1)-6) list retains its governing actor and trailing condition; 3) is not mistaken for a new section");
            check(((List<?>)fragments.invoke(method.invoke(assistant,new Api.Fragment(rows.get(3)),7,new Api.CancellationToken()))).isEmpty(),"A whole mandatory list exceeding the caller budget is skipped, not marked complete after two bullets");
            rows.clear();
            List<String> columns=List.of("Работник обязан представить следующие сведения:","- документ первого вида;","- второй документ требуется по иностранной параллельной колонке.");
            for(int i=0;i<columns.size();i++)rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document",
                "title","Двуязычная форма","text",columns.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","ooxml_node","part","word/document.xml","xpath","/w:document/w:body/w:tbl[1]/w:tr[1]/w:tc["+(i<2?1:2)+"]/w:p["+(i+1)+"]"))));
            Object cellUnit=method.invoke(assistant,new Api.Fragment(rows.get(1)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var oneCell=(List<Api.Fragment>)fragments.invoke(cellUnit);
            check(oneCell.size()==2&&oneCell.stream().noneMatch(f->f.fragmentId().equals("fixture-2")),"OOXML table-cell locators prevent closure from merging a parallel column");
            rows.clear();
            var alternatives=new ArrayList<String>();alternatives.add("1. Основания выбора");alternatives.add("1.1. Основаниями выбора являются:");
            for(int i=1;i<=9;i++)alternatives.add(i+") При выборе варианта "+i+" отправитель обязан уведомить адресата в течение "+i+" рабочих дней. Адресат должен письменно сообщить решение.");
            alternatives.add("Указанные основания применяются только при наличии подтверждающих документов.");alternatives.add("2. Другой раздел");alternatives.add("2.1. Руководитель должен зарегистрировать итог.");
            for(int i=0;i<alternatives.size();i++)rows.add(Map.of("rowid",(long)i,"fragment_id","fixture-"+i,"document_id","fixture-document","title","Порядок выбора",
                "text",alternatives.get(i),"source_refs",List.of(Map.of("artifact_id","original","source_sha256","a".repeat(64),"kind","ooxml_node","part","word/document.xml","xpath","/w:document/w:body/w:p["+(i+1)+"]"))));
            Object alternative=method.invoke(assistant,new Api.Fragment(rows.get(2)),8,new Api.CancellationToken());
            @SuppressWarnings("unchecked") var branch=(List<Api.Fragment>)fragments.invoke(alternative);
            check(branch.stream().map(Api.Fragment::fragmentId).toList().equals(List.of("fixture-1","fixture-2","fixture-11"))&&(boolean)completeness.invoke(alternative),"One complete numbered alternative retains its intro and shared condition without swallowing nine mutually distinct branches");
            check(branch.getFirst().fields().get("evidence_role").equals("governing_context")&&branch.getLast().fields().get("evidence_role").equals("governing_context"),"Shared intro and condition are context dependencies, not independent answers");
            var parse=type.getDeclaredMethod("intent",String.class);parse.setAccessible(true);Class<?> intent=Class.forName("ai.enterprise.search.AssistantSearch$Intent");
            var select=type.getDeclaredMethod("evidence",String.class,List.class,int.class,intent,Api.CancellationToken.class);select.setAccessible(true);
            @SuppressWarnings("unchecked") var directed=(List<Api.SearchHit>)select.invoke(assistant,"Что?",List.of(new Api.SearchHit(new Api.Fragment(rows.get(2)),0),new Api.SearchHit(new Api.Fragment(rows.get(3)),1)),24,parse.invoke(null,"Что?"),new Api.CancellationToken());
            var byId=new HashMap<String,Api.Fragment>();directed.forEach(hit->byId.put(hit.fragment().fragmentId(),hit.fragment()));
            check(byId.get("fixture-1").fields().get("evidence_bundle_ids").equals(List.of("fixture-1")),"The shared intro never acquires reverse dependencies on all alternatives");
            check(byId.get("fixture-2").fields().get("evidence_bundle_ids").equals(List.of("fixture-1","fixture-2","fixture-11"))
                &&byId.get("fixture-3").fields().get("evidence_bundle_ids").equals(List.of("fixture-1","fixture-3","fixture-11")),"Selecting another branch does not union it into the first branch's closed dependency set");
            rows.set(1,new LinkedHashMap<>(rows.get(1)));rows.get(1).put("text","При получении уведомления работник обязан выполнить следующие действия:");
            Object mandatory=method.invoke(assistant,new Api.Fragment(rows.get(2)),8,new Api.CancellationToken());
            check(((List<?>)fragments.invoke(mandatory)).isEmpty(),"The same complete finite numbered items remain cumulative under a governing mandatory list; the oversized list is never shortened");
        } finally {services.put(Api.DatabaseServiceV1.class,actual);}
    }
    public static void main(String[] args) throws ReflectiveOperationException {
        if(args.length<2)throw new IllegalArgumentException("root approved-development-document-id [keyword]");
        Path root=Path.of(args[0]).toAbsolutePath();
        String developmentDocument=args[1],keyword=args.length>2?args[2]:"план";
        Path reserve=root.resolve("src/build/research-20261024/quality-v2/evaluation/public/reserved-families-v1.json");
        if(Files.exists(reserve))try {
            check(!Json.object(Files.readString(reserve)).get("reserved_document_ids").toString().contains(developmentDocument),"New held-out sources are excluded from development tests");
        } catch(java.io.IOException error){throw new IllegalStateException(error);}
        List<String> development=List.of(developmentDocument);
        Api.Platform platform=new Api.Platform() {
            public Path executablePath(){return root.resolve("Mini-AI.exe");}public long availableMemoryBytes(){return Long.MAX_VALUE;}
            public void openWith(Path path){throw new AssertionError();}public Process launchWorker(Path exe,Path dir,long bytes){throw new AssertionError();}
        };
        check(new HashSet<>(GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).toList()).equals(
            Set.of("assistant","database","inference","documents","search","graph","sources")),"Only complete seven-module composition is tested");
        try(var component=EnterpriseAIComponent.create(new Api.Environment(root,Executors.newFixedThreadPool(4),platform),GeneratedCatalog.modules(),GeneratedCatalog.panels())) {
            var context=component.context();
            for(Class<?> service:List.of(Api.DatabaseServiceV1.class,Api.SearchServiceV1.class,Api.InferenceServiceV1.class,
                    EvidenceModelServiceV1.class,Api.DocumentAccessServiceV1.class,Api.SourceViewerServiceV1.class,Api.GraphServiceV1.class,Api.AssistantSessionServiceV1.class))
                check(context.find(service).isPresent(),"Full module service registered: "+service.getSimpleName());
            evidenceFeatureChecks();
            wholeBundleChecks(context);
            var search=context.require(Api.SearchServiceV1.class);var token=new Api.CancellationToken();
            var original=Api.await(search.search(keyword,10,development,List.of(),token));
            check(!original.isEmpty(),"Existing canonical corpus is present");
            Evidence evidence=new Evidence();evidence.target=original.getFirst().fragment();
            var services=testServices(context);Object productionEvidence=services.put(EvidenceModelServiceV1.class,evidence);
            check(productionEvidence!=null,"Real inference evidence provider was registered before the test transport");
            check(original.equals(Api.await(search.search(keyword,10,development,List.of(),token))),"Exact keyword results are unchanged by neural retrieval");
            var dense=Api.await(search.searchForAssistant("qzxunseen",3,List.of(),List.of(),token));
            check(dense.size()==1&&dense.getFirst().fragment().fragmentId().equals(evidence.target.fragmentId()),"Dense candidates are canonical reread and ranked");
            var namespaced=dense.getFirst().fragment();
            check(namespaced.fields().get("base_id").equals("local")&&namespaced.fields().get("fragment_key").toString().startsWith("local/"+namespaced.fields().get("content_version")+"/fragment/"),"Namespace includes the checked corpus version");
            check(namespaced.sourceRefs().equals(evidence.target.sourceRefs())&&namespaced.text().equals(evidence.target.text()),"Original text and provenance preserved");
            var second=new CurrentCorpusConnector("second",context.require(Api.DatabaseServiceV1.class)).namespaced(evidence.target);
            check(!second.fields().get("fragment_key").equals(namespaced.fields().get("fragment_key"))&&!second.fields().get("source_keys").equals(namespaced.fields().get("source_keys")),"Two corpora with identical raw IDs remain distinct");
            check(Api.await(search.searchForAssistant("qzxunseen2099",3,List.of(),List.of(),token)).isEmpty(),"Dense similarity cannot replace an explicit identifier");
            check(Api.await(search.searchForAssistant("qzxunseen",3,List.of("another-document"),List.of(),token)).isEmpty(),"Document filter is checked after neural retrieval");
            evidence.badDocument=true;
            check(search.searchForAssistant("qzxunseen",3,List.of(),List.of(),token).toCompletableFuture().join().error().code().equals("SOURCE_INVALID"),"Wrong document namespace is rejected");
            evidence.badDocument=false;evidence.available=false;
            check(Api.await(search.searchForAssistant("qzxunseen",3,List.of(),List.of(),token)).isEmpty(),"Unavailable evidence model leaves lexical behavior usable");
            var units=Api.await(search.searchForAssistant("Какие требования и порядок установлены для "+keyword+"?",10,development,List.of(),token));
            check(!units.isEmpty(),"Requested development topic retains whole evidence");
            Map<String,Api.Fragment> returned=new HashMap<>();for(var hit:units)returned.put(hit.fragment().fragmentId(),hit.fragment());
            for(var fragment:returned.values()) {
                var canonical=Api.await(search.getFragment(fragment.fragmentId(),token));
                check(fragment.text().equals(canonical.text())&&fragment.sourceRefs().equals(canonical.sourceRefs()),"Unit text and source locators are never cut or synthesized");
                check(fragment.fields().get("evidence_scope_title").equals(canonical.title()),"Scope is the exact canonical document title");
                Object ids=fragment.fields().get("evidence_bundle_ids");check(ids instanceof List<?>,"Every evidence unit declares its canonical dependency IDs");
                var bundle=(List<?>)ids;check(!bundle.isEmpty()&&bundle.size()<=8,"Whole-unit fragment budget");
                int chars=0;for(Object id:bundle) {
                    var member=returned.get(id);check(member!=null,"No bundle is silently trimmed by the global result limit");
                    check(member.documentId().equals(fragment.documentId()),"A bundle never crosses document/edition scope");chars+=member.text().length();
                }
                check(chars<=24_000,"Whole-unit character budget without text slicing");
                check(fragment.fields().get("evidence_context_complete") instanceof Boolean,"Completeness is explicit, never inferred from an existing citation");
            }
            var connector=new CurrentCorpusConnector("local",context.require(Api.DatabaseServiceV1.class));
            for(var neighbor:connector.neighbors(evidence.target,1,1,token)) {
                check(neighbor.documentId().equals(evidence.target.documentId()),"Neighbor cannot cross documents or editions");
                check(connector.fragment(neighbor.fragmentId(),token).orElseThrow().equals(neighbor),"Neighbor is complete canonical evidence");
            }
            token.cancel();check(!search.searchForAssistant("qzxunseen",3,List.of(),List.of(),token).toCompletableFuture().join().success(),"Cancellation preserved");
            services.put(EvidenceModelServiceV1.class,productionEvidence);
        }
        System.out.println("Hybrid corpus checks passed");
    }
}
