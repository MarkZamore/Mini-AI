package ai.enterprise.research;

import ai.enterprise.bootstrap.EnterpriseAIComponent;
import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.CorpusIdentityServiceV1;
import ai.enterprise.contracts.EvidenceModelServiceV1;
import ai.enterprise.contracts.Json;
import ai.enterprise.generated.GeneratedCatalog;
import ai.enterprise.platform.WindowsPlatform;
import ai.enterprise.search.QueryNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Public-API evidence-context control in the complete seven-module app.
 * Never calls generation or changes Context registration/private services.
 */
public final class NeighborContextEvaluation {
    private static Set<String> stems(String text) {
        Set<String> result=new TreeSet<>();
        for(String word:QueryNormalizer.tokens(text))result.add(QueryNormalizer.stem(word));
        return result;
    }
    private static List<String> intersection(Set<String> left,Set<String> right) {
        return left.stream().filter(right::contains).sorted().toList();
    }
    private static Map<String,Object> expansion(String question,Map<String,Object> rules) {
        // A source-verified pure reproduction of QuestionQuery.taskWords.
        // Rule text is captured by the analysis helper from pinned source SHA.
        Set<String> task=stems(question);
        task.removeAll(stems(Json.string(rules.get("FILLER"))));
        task.removeAll(stems(Json.string(rules.get("OUTPUT"))));
        var conditions=intersection(task,stems(Json.string(rules.get("CONDITIONS"))));
        var procedures=intersection(task,stems(Json.string(rules.get("PROCEDURES"))));
        return Map.of("expand",!conditions.isEmpty()||!procedures.isEmpty(),
            "task_words",task.stream().sorted().toList(),"condition_matches",conditions,"procedure_matches",procedures,
            "method","Source-verified query-rule reproduction using public QueryNormalizer; no private service access");
    }
    private static List<String> ids(List<Api.SearchHit> hits,int maximum) {
        return hits.stream().limit(maximum).map(h->h.fragment().fragmentId()).toList();
    }
    private static Map<String,Object> denseLowerBound(Api.Context context,String question) throws Exception {
        var token=new Api.CancellationToken();
        var identityResult=context.require(CorpusIdentityServiceV1.class).current(token);
        if(!identityResult.success())throw new IllegalStateException("Corpus identity unavailable: "+identityResult.error());
        var identity=identityResult.value();
        var evidence=context.require(EvidenceModelServiceV1.class);
        var availability=evidence.availability();
        if(!availability.success())throw new IllegalStateException("Evidence model unavailable: "+availability.error());
        var available=availability.value();
        if(available.rerankerAvailable())throw new IllegalStateException("Control requires RRF without BGE ordering/policy");
        var dense=Api.await(evidence.retrieve(identity.baseId(),question,64,List.of(),List.of(),token));
        var search=context.require(Api.SearchServiceV1.class);
        var required=QueryNormalizer.tokens(question).stream()
            .filter(w->w.matches("(?:19|20|21)[0-9]{2}")||w.matches("(?=.*[0-9])(?=.*[\\p{L}])[\\p{L}0-9]+"))
            .distinct().toList();
        Set<String> seen=new HashSet<>();List<Map<String,Object>> checked=new ArrayList<>();int eligible=0;
        for(var hit:dense) {
            if(!seen.add(hit.fragmentId())||!Double.isFinite(hit.score()))throw new IllegalStateException("Invalid dense candidate");
            var fragment=Api.await(search.getFragment(hit.fragmentId(),token));
            if(!fragment.documentId().equals(hit.documentId()))throw new IllegalStateException("Dense canonical identity mismatch");
            Set<String> words=new HashSet<>(QueryNormalizer.tokens(fragment.title()+" "+fragment.text()));
            boolean accepted=required.stream().allMatch(words::contains)&&!fragment.sourceRefs().isEmpty()&&!fragment.text().isBlank();
            if(accepted)eligible++;
            checked.add(Map.of("fragment_id",hit.fragmentId(),"document_id",hit.documentId(),"dense_score",hit.score(),
                "eligible_under_production_filters",accepted));
        }
        return Map.of("eligible_dense_candidates_lower_bound",eligible,"ranked_at_least_ten_proven",eligible>=10,
            "required_literal_tokens",required,"dense_candidates",checked,
            "method","Public EvidenceModelServiceV1.retrieve64 plus public getFragment; same literal-token/source filters as hybrid; unique accepted dense candidates are included before sorted limit64");
    }
    public static void main(String[] args) throws Exception {
        if(args.length<4)throw new IllegalArgumentException("root input.jsonl new-output.jsonl rules.json [limit]");
        Path root=Path.of(args[0]).toAbsolutePath(),input=Path.of(args[1]).toAbsolutePath(),output=Path.of(args[2]).toAbsolutePath();
        if(input.toString().toLowerCase(Locale.ROOT).contains("sealed-final"))throw new IllegalArgumentException("Do not read sealed final");
        int maximum=args.length>4?Integer.parseInt(args[4]):Integer.MAX_VALUE;
        Map<String,Object> rules=Json.object(Files.readString(Path.of(args[3]),StandardCharsets.UTF_8));
        if(Files.exists(output))throw new IllegalArgumentException("Use a new output file");
        Files.createDirectories(output.getParent());var executor=Executors.newFixedThreadPool(4);
        try(var platform=new WindowsPlatform(root);
            var component=EnterpriseAIComponent.create(new Api.Environment(root,executor,platform),GeneratedCatalog.modules(),GeneratedCatalog.panels());
            var writer=Files.newBufferedWriter(output,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            var context=component.context();var search=context.require(Api.SearchServiceV1.class);int number=0;
            for(String line:Files.readAllLines(input,StandardCharsets.UTF_8)) {
                if(line.isBlank())continue;if(number++>=maximum)break;
                var source=Json.object(line);String question=Json.string(source.get("question"));
                var result=new LinkedHashMap<String,Object>();result.put("id",source.get("id"));result.put("question",question);
                result.put("modules",GeneratedCatalog.modules().stream().map(Api.ModuleFactory::id).toList());
                result.put("human_verified",false);result.put("generator_called",false);result.put("expansion",expansion(question,rules));
                long begin=System.nanoTime();
                var ten=search.searchForAssistant(question,10,List.of(),List.of(),new Api.CancellationToken()).toCompletableFuture().get(240,TimeUnit.SECONDS);
                result.put("search_limit10_original",ten);result.put("search_limit10_seconds",(System.nanoTime()-begin)/1e9);
                begin=System.nanoTime();
                var twenty=search.searchForAssistant(question,20,List.of(),List.of(),new Api.CancellationToken()).toCompletableFuture().get(240,TimeUnit.SECONDS);
                result.put("search_limit20_original",twenty);result.put("search_limit20_seconds",(System.nanoTime()-begin)/1e9);
                if(ten.success()&&twenty.success()) {
                    result.put("limit20_first10",twenty.value().stream().limit(10).toList());
                    result.put("same_first5_seed_ids",ids(ten.value(),5).equals(ids(twenty.value(),5)));
                    result.put("same_all10_ids",ids(ten.value(),10).equals(ids(twenty.value(),10)));
                    result.put("anchor_proof",denseLowerBound(context,question));
                }
                result.put("diagnostics",context.diagnostics());
                writer.write(Json.stringify(result));writer.newLine();writer.flush();
                System.out.println(Json.stringify(Map.of("case",number,"id",Json.string(source.get("id")),
                    "expand",Json.map(result.get("expansion")).get("expand"),"two_search_success",ten.success()&&twenty.success())));
            }
        } finally {executor.shutdownNow();}
    }
}
