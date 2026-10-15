package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;

/** Per-request short citation IDs; original fragments and UI/history keep canonical IDs. */
public final class CitationAliases {
    private static final String PREFIX = "[source:";
    private final Map<String,String> originals = new LinkedHashMap<>();
    private final Map<String,String> aliases = new LinkedHashMap<>();
    private final StringBuilder pending = new StringBuilder();
    public CitationAliases(List<Api.Fragment> sources) {
        for (var source : sources) {
            String alias = Integer.toString(originals.size() + 1);
            if (aliases.putIfAbsent(source.fragmentId(), alias) != null)
                throw new IllegalArgumentException("Duplicate source ID");
            originals.put(alias, source.fragmentId());
        }
    }
    public List<Map<String,Object>> modelSources(List<Api.Fragment> sources) {
        return sources.stream().map(source -> {
            var fields = new LinkedHashMap<String,Object>(source.fields());
            String alias = aliases.get(source.fragmentId());
            if (alias == null) throw new IllegalArgumentException("Unknown source");
            fields.put("fragment_id", alias);
            return (Map<String,Object>) fields;
        }).toList();
    }
    public String modelHistory(String text) {
        for (var entry : aliases.entrySet())
            text = text.replace(PREFIX + entry.getKey() + "]", PREFIX + entry.getValue() + "]");
        return text;
    }
    public String push(String text) {
        pending.append(text);
        var output = new StringBuilder();
        while (!pending.isEmpty()) {
            int start = pending.indexOf(PREFIX);
            if (start < 0) {
                int keep = Math.min(PREFIX.length()-1, pending.length());
                while (keep > 0 && !PREFIX.startsWith(pending.substring(pending.length()-keep))) keep--;
                output.append(pending, 0, pending.length()-keep);
                pending.delete(0, pending.length()-keep);
                break;
            }
            output.append(pending, 0, start);
            pending.delete(0, start);
            int end = pending.indexOf("]");
            if (end < 0) {
                if (pending.length() > 1033) throw invalid();
                break;
            }
            String original = originals.get(pending.substring(PREFIX.length(), end));
            if (original == null) throw invalid();
            output.append(PREFIX).append(original).append(']');
            pending.delete(0, end+1);
        }
        return output.toString();
    }
    public String finish() {
        if (pending.indexOf(PREFIX) >= 0) throw invalid();
        String tail = pending.toString(); pending.setLength(0); return tail;
    }
    private static Api.ServiceException invalid() {
        return new Api.ServiceException("INVALID_CITATION", "Ответ отклонён: неизвестный или незавершённый номер источника");
    }
}
