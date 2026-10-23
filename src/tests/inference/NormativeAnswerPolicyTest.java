package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Session policy checks, independent of generated model wording and semantic quality. */
public final class NormativeAnswerPolicyTest {
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        check(AnswerPolicy.normative("Какие требования установлены для участников?"),"Local requirements need evidence");
        check(AnswerPolicy.normative("Расскажи про общие правила, установленные у нас"),"Local policy wins over general wording");
        check(!AnswerPolicy.normative("Объясни общие правила подготовки отчёта"),"General advice remains available");
        check(!AnswerPolicy.normative("Как работает поиск?"),"Ordinary questions remain available");
        check(!AnswerPolicy.normative("Что такое нормативный документ?"),"Definitions do not invent local norms and can use general knowledge");
        check(AnswerPolicy.normative("Что означает требование согласно документам в базе?"),"An explicitly local definition still needs its source");
        check(AnswerPolicy.normative("Что говорится в документе «Акт готовности» о процентном соотношении?"),"An ordinary question anchored to a document needs evidence even without a normative keyword");
        check(AnswerPolicy.normative("Что означает этот показатель в данном документе?"),"An explicitly document-bound definition stays grounded");
        check(AnswerPolicy.normative("«Акт» құжатында бұл көрсеткіш туралы не айтылған?"),"A Kazakh document-bound question stays grounded");
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath();
        Api.Platform platform=new Api.Platform() {
            public Path executablePath(){return root.resolve("Mini-AI.exe");}
            public long availableMemoryBytes(){return Long.MAX_VALUE;}
            public void openWith(Path path){throw new AssertionError();}
            public Process launchWorker(Path exe,Path dir,long bytes){throw new AssertionError();}
        };
        try(var context=new Api.Context(new Api.Environment(root,Executors.newFixedThreadPool(2),platform))) {
            AtomicInteger generated=new AtomicInteger();
            context.register(Api.InferenceServiceV1.class,new Api.InferenceServiceV1() {
                public Api.Result<Api.ProviderInfo> availability(){return Api.Result.ok(new Api.ProviderInfo("mock","test",8192,1,0));}
                public CompletionStage<Api.Result<Void>> generate(Api.GenerationRequest request,java.util.function.Consumer<Api.GenerationEvent> sink,Api.CancellationToken token) {
                    generated.incrementAndGet();sink.accept(new Api.GenerationEvent("token",request.requestId(),"Общий совет.",null));
                    return CompletableFuture.completedFuture(Api.Result.ok(null));
                }
            });
            context.register(Api.SearchServiceV1.class,new Api.SearchServiceV1() {
                public CompletionStage<Api.Result<List<Api.SearchHit>>> search(String query,int limit,List<String> ids,List<String> sections,Api.CancellationToken token) {return CompletableFuture.completedFuture(Api.Result.ok(List.of()));}
                public CompletionStage<Api.Result<Api.Fragment>> getFragment(String id,Api.CancellationToken token){throw new AssertionError();}
            });
            new AssistantModule().register(context);
            var session=context.require(Api.AssistantSessionServiceV1.class);
            List<Api.AssistantEvent> events=new ArrayList<>();
            check(session.send("Какие обязательные сроки установлены?",events::add,new Api.CancellationToken()).toCompletableFuture().get(3,TimeUnit.SECONDS).success(),"Insufficiency is a completed useful response");
            check(generated.get()==0,"No invented normative fallback is generated without evidence");
            check(events.getLast().text().equals(AnswerPolicy.INSUFFICIENT),"User gets missing evidence and concrete next steps");
            check(events.getLast().sources().isEmpty()&&!events.getLast().text().contains("[source:"),"No fabricated provenance");
            check(session.send("Какие документы обычно готовят?",e->{},new Api.CancellationToken()).toCompletableFuture().get(3,TimeUnit.SECONDS).success(),"General follow-up still works");
            check(generated.get()==1,"Explicit general advice uses local generation");
        }
        System.out.println("Normative policy checks passed");
    }
}
