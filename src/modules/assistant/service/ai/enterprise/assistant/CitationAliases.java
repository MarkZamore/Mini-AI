package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;

/** Per-request short citation IDs; original fragments and UI/history keep canonical IDs. */
public final class CitationAliases {
    private static final String PREFIX = "[source:";
    private final Map<String,String> originals = new LinkedHashMap<>();
    private final Map<String,String> aliases = new LinkedHashMap<>();
    private final ReferenceStream stream;
    public CitationAliases(List<Api.Fragment> sources) {
        this(sources, false);
    }
    CitationAliases(List<Api.Fragment> sources, boolean omitReferences) {
        if (omitReferences && !sources.isEmpty()) throw new IllegalArgumentException("Reference omission requires an empty source context");
        for (var source : sources) {
            String alias = Integer.toString(originals.size() + 1);
            if (aliases.putIfAbsent(source.fragmentId(), alias) != null)
                throw new IllegalArgumentException("Duplicate source ID");
            originals.put(alias, source.fragmentId());
        }
        stream = new ReferenceStream(originals, aliases.keySet(), omitReferences);
    }
    public static CitationAliases forGeneralAnswer() {
        return new CitationAliases(List.of(), true);
    }
    public List<Map<String,Object>> modelSources(List<Api.Fragment> sources) {
        return modelFields(sources.stream().map(Api.Fragment::fields).toList());
    }
    List<Map<String,Object>> modelFields(List<Map<String,Object>> sources) {
        return sources.stream().map(source -> {
            var fields = new LinkedHashMap<String,Object>(source);
            String alias = aliases.get(Objects.toString(source.get("fragment_id"),""));
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
        return stream.push(text);
    }
    public String finish() {
        return stream.finish();
    }
    /** Apply the same syntax/provenance gate to a completed answer containing canonical IDs. */
    public static void validateCanonical(String answer, List<Api.Fragment> sources) {
        Map<String,String> allowed = new LinkedHashMap<>();
        for (var source : sources) allowed.put(source.fragmentId(), source.fragmentId());
        var gate = new ReferenceStream(allowed, allowed.keySet(), false);
        gate.push(answer); gate.finish();
    }
    private static final class ReferenceStream {
        private static final int MAX_PENDING = 1033;
        private final Map<String,String> references;
        private final Set<String> knownHashes;
        private final boolean omitReferences;
        private final StringBuilder pending = new StringBuilder();
        private long discardedDepth;
        private ReferenceStream(Map<String,String> references, Set<String> knownHashes, boolean omitReferences) {
            this.references = Map.copyOf(references); this.knownHashes = Set.copyOf(knownHashes); this.omitReferences = omitReferences;
        }
        private String push(String text) {
            var output = new StringBuilder();
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (discardedDepth > 0) {
                    if (value == '[' && discardedDepth < Long.MAX_VALUE) discardedDepth++;
                    else if (value == ']') discardedDepth--;
                } else if (pending.isEmpty()) {
                    if (value == '[') pending.append(value); else output.append(value);
                } else if (value == '[') {
                    if (sourcePrefix(pending)) throw invalid();
                    output.append(pending); pending.setLength(0); pending.append(value);
                } else {
                    pending.append(value);
                    if (omitReferences && sourcePrefix(pending)) {
                        pending.setLength(0); discardedDepth = 1;
                    } else if (value == ']') {
                        output.append(closed()); pending.setLength(0);
                    } else if (value == '\n' || value == '\r' || pending.length() > MAX_PENDING) {
                        if (sourcePrefix(pending)) throw invalid();
                        output.append(pending); pending.setLength(0);
                    }
                }
            }
            return output.toString();
        }
        private String closed() {
            String value = pending.toString();
            String inner = value.substring(1, value.length() - 1);
            if (sourcePrefix(pending)) {
                if (!value.startsWith(PREFIX)) throw invalid();
                String original = references.get(value.substring(PREFIX.length(), value.length() - 1));
                if (original == null) throw invalid();
                return PREFIX + original + ']';
            }
            if (hex64(inner)) {
                if (omitReferences) return "";
                if (!knownHashes.contains(inner)) throw invalid();
            }
            return value;
        }
        private String finish() {
            if (omitReferences && (discardedDepth > 0 || unfinishedReference())) {
                pending.setLength(0); discardedDepth = 0; return "";
            }
            if (sourcePrefix(pending)) throw invalid();
            String tail = pending.toString(); pending.setLength(0); return tail;
        }
        private boolean unfinishedReference() {
            if (pending.isEmpty()) return false;
            if (pending.length() <= PREFIX.length()) {
                for (int index = 0; index < pending.length(); index++) {
                    char value = pending.charAt(index);
                    if (value >= 'A' && value <= 'Z') value = (char) (value - 'A' + 'a');
                    if (value != PREFIX.charAt(index)) return false;
                }
                return true;
            }
            return pending.charAt(0) == '[' && hex64(pending.substring(1));
        }
        private static boolean sourcePrefix(CharSequence value) {
            if (value.length() < PREFIX.length()) return false;
            for (int index = 0; index < PREFIX.length(); index++) {
                char current = value.charAt(index);
                if (current >= 'A' && current <= 'Z') current = (char) (current - 'A' + 'a');
                if (current != PREFIX.charAt(index)) return false;
            }
            return true;
        }
        private static boolean hex64(String value) {
            if (value.length() != 64) return false;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (!(current >= '0' && current <= '9') && !(current >= 'a' && current <= 'f') && !(current >= 'A' && current <= 'F')) return false;
            }
            return true;
        }
    }
    private static Api.ServiceException invalid() {
        return new Api.ServiceException("INVALID_CITATION", "Ответ отклонён: неизвестный или незавершённый номер источника");
    }
}
