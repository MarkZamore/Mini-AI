package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;

/** Model-facing evidence projection; canonical fragments remain intact in the application. */
final class PromptSources {
    private static final List<String> EVIDENCE_FIELDS = List.of(
        "fragment_id", "document_id", "title", "text", "source_sha256",
        "base_id", "content_version", "document_key", "fragment_key", "source_keys",
        "section", "section_path", "revision", "edition", "effective_date", "publication_date", "language");
    private static final Set<String> GEOMETRY_AND_REPEATED_HASH = Set.of(
        "bbox", "page_size", "source_sha256");

    private PromptSources() {}

    static List<Map<String,Object>> project(List<Api.Fragment> fragments) {
        List<Map<String,Object>> result = new ArrayList<>(fragments.size());
        for (Api.Fragment fragment : fragments) {
            Map<String,Object> fields = fragment.fields();
            Map<String,Object> projected = new LinkedHashMap<>();
            for (String key : EVIDENCE_FIELDS)
                if (fields.containsKey(key)) projected.put(key, fields.get(key));

            Object qualityRaw = fields.get("quality");
            Object qualityWarnings = null;
            if (qualityRaw instanceof Map<?,?> quality) {
                Map<String,Object> qualifiers = new LinkedHashMap<>();
                quality.forEach((key,value) -> {
                    if (!"warnings".equals(key)) qualifiers.put((String) key, value);
                });
                qualityWarnings = quality.get("warnings");
                projected.put("quality", qualifiers);
            } else if (fields.containsKey("quality")) {
                projected.put("quality", qualityRaw);
            }

            List<Object> warnings = uniqueEntries(qualityWarnings, fields.get("warnings"));
            if (!warnings.isEmpty()) projected.put("warnings", warnings);

            Object refsRaw = fields.get("source_refs");
            if (refsRaw instanceof List<?> refs) {
                Set<Object> locators = new LinkedHashSet<>();
                for (Object raw : refs) {
                    if (raw instanceof Map<?,?> ref) {
                        Map<String,Object> locator = new LinkedHashMap<>();
                        ref.forEach((key,value) -> {
                            if (!GEOMETRY_AND_REPEATED_HASH.contains(key)) locator.put((String) key, value);
                        });
                        locators.add(locator);
                    } else {
                        // Preserve unrecognised evidence rather than inventing a locator.
                        locators.add(raw);
                    }
                }
                projected.put("source_refs", new ArrayList<>(locators));
            } else if (fields.containsKey("source_refs")) {
                projected.put("source_refs", refsRaw);
            }
            result.add(Api.freeze(projected));
        }
        return List.copyOf(result);
    }

    static List<Map<String,Object>> documentWarnings(List<Map<String,Object>> documents) {
        List<Map<String,Object>> result = new ArrayList<>(documents.size());
        for (Map<String,Object> document : documents) {
            Map<String,Object> projected = new LinkedHashMap<>(document);
            Object warnings = document.get("warnings");
            if (warnings instanceof List<?>) projected.put("warnings", uniqueEntries(warnings));
            result.add(Api.freeze(projected));
        }
        return List.copyOf(result);
    }

    private static List<Object> uniqueEntries(Object... lists) {
        Set<Object> unique = new LinkedHashSet<>();
        for (Object raw : lists) if (raw instanceof List<?> entries) unique.addAll(entries);
        return new ArrayList<>(unique);
    }
}
