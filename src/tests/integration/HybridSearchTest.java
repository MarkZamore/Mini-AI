package ai.enterprise.tests;

import ai.enterprise.contracts.*;
import ai.enterprise.database.DatabaseModule;
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
    public static void main(String[] args) {
        Path root=Path.of(args[0]).toAbsolutePath();
        Api.Platform platform=new Api.Platform() {
            public Path executablePath(){return root.resolve("Mini-AI.exe");}public long availableMemoryBytes(){return Long.MAX_VALUE;}
            public void openWith(Path path){throw new AssertionError();}public Process launchWorker(Path exe,Path dir,long bytes){throw new AssertionError();}
        };
        try(var context=new Api.Context(new Api.Environment(root,Executors.newFixedThreadPool(4),platform))) {
            new DatabaseModule().register(context);new SearchModule().register(context);
            var search=context.require(Api.SearchServiceV1.class);var token=new Api.CancellationToken();
            var original=Api.await(search.search("учебный план",10,List.of(),List.of(),token));
            check(!original.isEmpty(),"Existing canonical corpus is present");
            Evidence evidence=new Evidence();evidence.target=original.getFirst().fragment();context.register(EvidenceModelServiceV1.class,evidence);
            check(original.equals(Api.await(search.search("учебный план",10,List.of(),List.of(),token))),"Exact keyword results are unchanged by neural retrieval");
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
            var connector=new CurrentCorpusConnector("local",context.require(Api.DatabaseServiceV1.class));
            for(var neighbor:connector.neighbors(evidence.target,1,1,token)) {
                check(neighbor.documentId().equals(evidence.target.documentId()),"Neighbor cannot cross documents or editions");
                check(connector.fragment(neighbor.fragmentId(),token).orElseThrow().equals(neighbor),"Neighbor is complete canonical evidence");
            }
            token.cancel();check(!search.searchForAssistant("qzxunseen",3,List.of(),List.of(),token).toCompletableFuture().join().success(),"Cancellation preserved");
        }
        System.out.println("Hybrid corpus checks passed");
    }
}
