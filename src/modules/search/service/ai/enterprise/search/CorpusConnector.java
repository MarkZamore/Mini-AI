package ai.enterprise.search;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Corpus boundary: local IDs and original provenance never acquire another corpus's meaning. */
public interface CorpusConnector {
    String baseId();
    default String contentVersion() { return ""; }
    Optional<Api.Fragment> fragment(String fragmentId, Api.CancellationToken token);
    List<Api.Fragment> neighbors(Api.Fragment fragment, int before, int after, Api.CancellationToken token);

    default String key(String kind, String localId) {
        if (!baseId().matches("[A-Za-z0-9._-]{1,64}") || localId == null || localId.isBlank())
            throw new Api.ServiceException("CORPUS_ID_INVALID", "Не задан однозначный идентификатор базы или источника");
        if (!kind.matches("[a-z][a-z0-9_-]{0,31}"))throw new Api.ServiceException("CORPUS_ID_INVALID","Неверный тип идентификатора источника");
        return baseId() + "/" + (contentVersion().isEmpty()?"":contentVersion()+"/") + kind + "/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(localId.getBytes(StandardCharsets.UTF_8));
    }

    default Api.Fragment namespaced(Api.Fragment fragment) {
        if (fragment.fragmentId().isBlank() || fragment.documentId().isBlank() || fragment.sourceRefs().isEmpty())
            throw new Api.ServiceException("SOURCE_INVALID", "Фрагмент не имеет исходной привязки к документу");
        var fields = new LinkedHashMap<>(fragment.fields());
        fields.put("base_id", baseId());
        if(!contentVersion().isEmpty())fields.put("content_version", contentVersion());
        fields.put("document_key", key("document", fragment.documentId()));
        fields.put("fragment_key", key("fragment", fragment.fragmentId()));
        List<String> sources = new ArrayList<>();
        for (var ref : fragment.sourceRefs()) {
            // Identity includes the owning document; identical artifact names in two documents
            // must not collide. No page, edition or verification status is inferred here.
            String artifact = ref.artifactId().isBlank() ? ref.sourceSha256() : ref.artifactId();
            if (artifact.isBlank()) throw new Api.ServiceException("SOURCE_INVALID", "У источника нет идентификатора");
            if(!ref.sourceSha256().matches("[a-f0-9]{64}"))throw new Api.ServiceException("SOURCE_INVALID","У источника нет проверяемой версии SHA-256");
            sources.add(key("source", Json.stringify(List.of(fragment.documentId(), ref.sourceSha256(), artifact))));
        }
        fields.put("source_keys", sources.stream().distinct().toList());
        return new Api.Fragment(fields);
    }
}
