package ai.enterprise.search;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import ai.enterprise.contracts.CorpusIdentityServiceV1;
import java.util.*;
import java.util.concurrent.CompletionStage;

public final class SearchModule implements Api.ModuleFactory {
    public String id(){return "search";}
    public void register(Api.Context context) {
        context.register(CorpusIdentityServiceV1.class,new CorpusIdentity(context.environment().db()));
        var search=new Search(context);context.register(Api.SearchServiceV1.class,search);
        context.command("search",args->searchCommand(search,args,false));
        context.command("assistant-search",args->searchCommand(search,args,true));
        context.command("get-fragment",args->{if(args.isEmpty())throw new Api.ServiceException("ARGUMENT","Укажите ID фрагмента");return Api.await(search.getFragment(args.getFirst(),new Api.CancellationToken()));});
    }
    private static Object searchCommand(Search search,List<String> args,boolean conversational) {
            if(args.isEmpty())throw new Api.ServiceException("ARGUMENT","Укажите запрос");
            int limit=10;var documents=new ArrayList<String>();var sections=new ArrayList<String>();
            for(int i=1;i<args.size();i++) {
                String option=args.get(i);
                if(i==1&&!option.startsWith("--")) {limit=Integer.parseInt(option);continue;}
                if(i+1>=args.size())throw new Api.ServiceException("ARGUMENT","Не задано значение: "+option);
                String value=args.get(++i);
                switch(option) {
                    case "--limit" -> limit=Integer.parseInt(value);
                    case "--document-id" -> documents.add(value);
                    case "--section" -> sections.add(value);
                    default -> throw new Api.ServiceException("ARGUMENT","Неизвестный параметр поиска: "+option);
                }
            }
            return Api.await(conversational
                ? search.searchForAssistant(args.getFirst(),limit,documents,sections,new Api.CancellationToken())
                : search.search(args.getFirst(),limit,documents,sections,new Api.CancellationToken()));
    }
    public static final class Search implements Api.SearchServiceV1 {
        private final Api.Context context;
        public Search(Api.Context context){this.context=context;}
        public CompletionStage<Api.Result<List<Api.SearchHit>>> search(String query,int limit,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
            return context.async(token,()->exact(query,limit,documentIds,sections,token));
        }
        public CompletionStage<Api.Result<List<Api.SearchHit>>> searchForAssistant(String query,int limit,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
            return context.async(token,()->new AssistantSearch(context,this).find(query,limit,documentIds,sections,token));
        }
        List<Api.SearchHit> exact(String query,int limit,List<String> documentIds,List<String> sections,Api.CancellationToken token) {
                var plan=QuestionQuery.plan(query);
                String expression=plan.map(QuestionQuery.Plan::expression).orElseGet(()->QueryNormalizer.matchExpression(query));
                if(expression.isEmpty())return List.of();
                var params=new ArrayList<Object>();params.add(expression);
                var sql=new StringBuilder("SELECT c.*,bm25(chunks_fts,6.0,10.0,1.0,0.5) AS score FROM chunks_fts JOIN chunks c ON c.rowid=chunks_fts.rowid WHERE chunks_fts MATCH ?");
                // Name evidence must contain enough text to identify the owner rather than
                // an isolated OCR word or a table heading such as 'Полное наименование'.
                if(plan.isPresent()&&plan.get().organizationIdentity())sql.append(" AND length(c.text)>=80");
                if(documentIds!=null&&!documentIds.isEmpty()){sql.append(" AND c.document_id IN (").append(placeholders(documentIds.size())).append(')');params.addAll(documentIds);}
                if(sections!=null&&!sections.isEmpty()){sql.append(" AND c.document_id IN (SELECT document_id FROM document_sections WHERE section IN (").append(placeholders(sections.size())).append("))");params.addAll(sections);}
                // A name question needs a few corroborating passages, not ten repetitive
                // OCR headers that slow the laptop model and crowd out the actual name.
                int maximum=plan.isPresent()&&plan.get().organizationIdentity()?3:100;
                sql.append(" ORDER BY score,c.rowid LIMIT ?");params.add(Math.min(maximum,Math.max(1,limit)));
                return context.require(Api.DatabaseServiceV1.class).query(sql.toString(),params,token).stream().map(row->{var fields=fragmentFields(row);double score=Double.parseDouble(Json.string(fields.remove("score")));return new Api.SearchHit(new Api.Fragment(fields),score);}).toList();
        }
        public CompletionStage<Api.Result<Api.Fragment>> getFragment(String fragmentId,Api.CancellationToken token) {
            return context.async(token,()-> {
                var rows=context.require(Api.DatabaseServiceV1.class).query("SELECT * FROM chunks WHERE fragment_id=?",List.of(fragmentId),token);
                if(rows.isEmpty())throw new Api.ServiceException("FRAGMENT_NOT_FOUND","Не найден фрагмент: "+fragmentId);
                return new Api.Fragment(fragmentFields(rows.getFirst()));
            });
        }
        static LinkedHashMap<String,Object> fragmentFields(Map<String,Object> row){var result=new LinkedHashMap<>(row);result.remove("rowid");result.remove("stemmed");result.remove("codes");return result;}
        private static String placeholders(int n){return String.join(",",Collections.nCopies(n,"?"));}
    }
}
