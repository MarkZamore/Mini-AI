package ai.enterprise.contracts;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Local evidence models. Raw corpus IDs are preserved; scores are not probabilities. */
public interface EvidenceModelServiceV1 extends AutoCloseable {
    record Info(String modelSha256, int dimensions, String corpusSha256, boolean rerankerAvailable) {}
    record Candidate(String fragmentId, String documentId, String text) {}
    record ScoredFragment(String fragmentId, String documentId, double score) {}
    /** A fixed relevance rule, never a probability of correctness. */
    record Policy(String kind, int maxCandidates, double adequateMinLogit, double insufficientMaxLogit) {}

    Api.Result<Info> availability();
    default Api.Result<Policy> answerPolicy() { return Api.Result.ok(new Policy("none",0,0,0)); }
    CompletionStage<Api.Result<List<ScoredFragment>>> retrieve(String baseId, String query, int limit,
        List<String> documentIds, List<String> sections, Api.CancellationToken cancellation);
    CompletionStage<Api.Result<List<ScoredFragment>>> rerank(String query, List<Candidate> candidates,
        Api.CancellationToken cancellation);
    CompletionStage<Api.Result<List<float[]>>> embed(List<String> texts, boolean queries,
        Api.CancellationToken cancellation);
    default void close() {}
}
