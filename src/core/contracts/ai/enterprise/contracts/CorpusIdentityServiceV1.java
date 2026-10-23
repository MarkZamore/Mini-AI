package ai.enterprise.contracts;

/** Identity of the currently opened read-only corpus, independent of organization or query language. */
public interface CorpusIdentityServiceV1 {
    record Info(String baseId, String contentVersion) {}
    Api.Result<Info> current(Api.CancellationToken cancellation);
}
