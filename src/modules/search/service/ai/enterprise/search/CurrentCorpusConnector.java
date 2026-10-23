package ai.enterprise.search;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.CorpusIdentityServiceV1;
import java.util.*;

/** Adapter over the current read-only canonical index, independent of its organization. */
public final class CurrentCorpusConnector implements CorpusConnector {
    private final String baseId;
    private final Api.DatabaseServiceV1 database;
    private final String version;

    public CurrentCorpusConnector(String baseId, Api.DatabaseServiceV1 database) {
        this(new CorpusIdentityServiceV1.Info(baseId,""),database);
    }
    public CurrentCorpusConnector(CorpusIdentityServiceV1.Info identity, Api.DatabaseServiceV1 database) {
        String baseId=identity.baseId();
        this.baseId = Objects.requireNonNull(baseId);
        this.version=Objects.requireNonNull(identity.contentVersion());
        if(!version.isEmpty()&&!version.matches("[a-f0-9]{64}"))throw new Api.ServiceException("CORPUS_ID_INVALID","Неверная версия базы");
        this.database = Objects.requireNonNull(database);
        key("validation", "id");
    }
    public String baseId() { return baseId; }
    public String contentVersion() { return version; }

    public Optional<Api.Fragment> fragment(String id, Api.CancellationToken token) {
        token.check();
        var rows = database.query("SELECT * FROM chunks WHERE fragment_id=?", List.of(id), token);
        return rows.isEmpty() ? Optional.empty() : Optional.of(new Api.Fragment(SearchModule.Search.fragmentFields(rows.getFirst())));
    }

    public List<Api.Fragment> neighbors(Api.Fragment fragment, int before, int after, Api.CancellationToken token) {
        token.check();
        var anchor = database.query("SELECT rowid FROM chunks WHERE fragment_id=? AND document_id=?",
            List.of(fragment.fragmentId(), fragment.documentId()), token);
        if (anchor.isEmpty()) return List.of();
        Object rowId = anchor.getFirst().get("rowid");
        List<Api.Fragment> result = new ArrayList<>();
        for (boolean previous : List.of(true, false)) {
            int limit = Math.min(2, Math.max(0, previous ? before : after));
            if (limit == 0) continue;
            var rows = database.query("SELECT * FROM chunks WHERE document_id=? AND rowid" + (previous ? "<? ORDER BY rowid DESC" : ">? ORDER BY rowid") + " LIMIT ?",
                List.of(fragment.documentId(), rowId, limit), token);
            List<Api.Fragment> adjacent = new ArrayList<>();
            for (var row : rows) {
                token.check();
                Api.Fragment candidate = new Api.Fragment(SearchModule.Search.fragmentFields(row));
                if (!candidate.text().isBlank() && !candidate.sourceRefs().isEmpty()) adjacent.add(candidate);
            }
            if (previous) Collections.reverse(adjacent);
            result.addAll(adjacent);
        }
        return List.copyOf(result);
    }
}
