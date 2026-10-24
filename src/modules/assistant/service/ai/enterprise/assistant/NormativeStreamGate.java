package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A formatting/provenance gate, not a semantic entailment checker.
 * Only a completed paragraph authorizes output, independent of transport chunks.
 * A citation never authorizes a preceding separate paragraph.
 */
final class NormativeStreamGate {
    private static final Pattern CITATION = Pattern.compile("\\[source:([^\\]\\s]+)\\]");
    private static final Pattern SOURCE_PREFIX = Pattern.compile("\\[source:", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[\\t ]*\\n");
    private static final Pattern LIST_PREFIX = Pattern.compile("^\\s*(?:[-*+•]\\s*|\\d+[.)]\\s*)");
    private static final Pattern REFERENCE_FIRST_ITEM = Pattern.compile("^\\s*(?:[-*+•]\\s*|\\d+[.)]\\s*)\\[source:");
    private static final Pattern CLOSING_PUNCTUATION = Pattern.compile("[\\s.,;:!?…)'\\\"»”*_~]*");
    private static final Pattern TRAILER_PREFIX = Pattern.compile("^[\\t ]*[.,;:!?…)]*(?:['\\\"»”*_~]+)?[\\t ]*");
    private static final Pattern ADVICE_RULE = Pattern.compile(
        "\\d|обязан|должен|должна|должны|должно|необходимо|запрещ|не позднее|не ранее|"
        + "согласно|по договору|кодекс|норматив|регламент|стать[яеи]|"
        + "\\bmust\\b|\\bshall\\b|\\brequired\\b|міндет|тиіс", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> HEADINGS = Set.of(
        "краткий ответ", "краткий вывод", "условия или порядок действий", "условия и порядок действий",
        "общие условия и ограничения", "условия и ограничения", "условия", "порядок действий",
        "что делать дальше", "основание", "основания", "источники", "ограничения и уточнения",
        "ограничения и предупреждения", "қысқаша жауап", "шарттар", "әрекеттер тәртібі",
        "не істеу керек", "негіз", "дереккөздер", "short answer", "conditions", "procedure",
        "next steps", "evidence", "sources");
    private static final Set<String> ADVICE_LABELS = Set.of(
        "общие рекомендации (необязательные)", "общие советы (необязательные)",
        "необязательные рекомендации", "general recommendations (optional)",
        "optional recommendations", "жалпы кеңестер (міндетті емес)");
    private static final int MAX_PENDING = 32768;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder headings = new StringBuilder();
    private final Set<String> allowedCanonicalIds;
    private boolean previousCarriageReturn;
    private boolean omitted;
    private boolean emitted;

    /** Compatibility path: CitationAliases must validate/resolve references first. */
    NormativeStreamGate() {
        allowedCanonicalIds = null;
    }

    /** Additional provenance check against the request's actual canonical source IDs. */
    NormativeStreamGate(Set<String> allowedCanonicalIds) {
        this.allowedCanonicalIds = Set.copyOf(Objects.requireNonNull(allowedCanonicalIds));
    }

    String push(String text) {
        Objects.requireNonNull(text);
        StringBuilder visible = new StringBuilder();
        // Drain incrementally: a giant transport chunk cannot bypass the cap.
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n' && previousCarriageReturn) {
                previousCarriageReturn = false;
                continue;
            }
            previousCarriageReturn = value == '\r';
            pending.append(value == '\r' ? '\n' : value);
            if (value == '\n' || value == '\r') {
                var boundary = PARAGRAPH_BREAK.matcher(pending);
                if (boundary.find()) {
                    String block = pending.substring(0, boundary.start());
                    String separator = pending.substring(boundary.start(), boundary.end());
                    pending.delete(0, boundary.end());
                    completeBlock(block, separator, visible);
                }
            }
            if (pending.length() + headings.length() > MAX_PENDING) throw evidenceLimit();
        }
        return visible.toString();
    }

    String finish() {
        StringBuilder visible = new StringBuilder();
        completeBlock(pending.toString(), "", visible);
        pending.setLength(0);
        headings.setLength(0); // A structural label is not a rule on its own.
        previousCarriageReturn = false;
        return visible.toString();
    }

    boolean omittedUncitedText() {
        return omitted;
    }

    private void completeBlock(String raw, String separator, StringBuilder visible) {
        validateReferences(raw);
        if (raw.isBlank()) {
            if (!headings.isEmpty()) headings.append(raw).append(separator);
            else if (emitted) visible.append(raw).append(separator);
            return;
        }
        if (heading(raw)) {
            headings.append(raw).append(separator);
            return;
        }
        // Reference-first list entries are not citation-terminated assertions.
        // A truncated "* [source:9]" must not leak as a naked [9].
        StringBuilder cleaned = new StringBuilder();
        for (String line : raw.split("\\n", -1)) {
            if (REFERENCE_FIRST_ITEM.matcher(line).find()) {
                omitted = true;
                continue;
            }
            if (!cleaned.isEmpty()) cleaned.append('\n');
            cleaned.append(line);
        }
        String block = cleaned.toString();
        var citation = CITATION.matcher(block);
        int end = -1;
        while (citation.find()) end = citation.end();
        if (end >= 0 && meaningful(block.substring(0, end))) {
            String suffix = block.substring(end);
            String accepted;
            if (CLOSING_PUNCTUATION.matcher(suffix).matches()) accepted = block;
            else {
                var punctuation = TRAILER_PREFIX.matcher(suffix);
                accepted = block.substring(0, end) + (punctuation.find() ? punctuation.group() : "");
                omitted = true;
            }
            visible.append(headings).append(accepted).append(separator);
            headings.setLength(0);
            emitted = true;
        } else if (recommendation(block)) {
            visible.append(headings).append(block).append(separator);
            headings.setLength(0);
            emitted = true;
        } else if (CLOSING_PUNCTUATION.matcher(block).matches() && emitted) {
            visible.append(block).append(separator);
        } else {
            // A dropped paragraph's heading cannot label an unrelated later one.
            headings.setLength(0);
            omitted = true;
        }
    }

    private void validateReferences(String block) {
        var citation = CITATION.matcher(block);
        while (citation.find()) {
            if (allowedCanonicalIds != null && !allowedCanonicalIds.contains(citation.group(1))) throw invalidCitation();
        }
        if (SOURCE_PREFIX.matcher(CITATION.matcher(block).replaceAll("")).find()) throw invalidCitation();
    }

    private static boolean meaningful(String prefix) {
        String withoutReferences = CITATION.matcher(prefix).replaceAll("");
        for (String line : withoutReferences.split("\\n", -1)) {
            String value = LIST_PREFIX.matcher(line).replaceFirst("");
            if (!heading(value) && value.codePoints().anyMatch(Character::isLetterOrDigit)) return true;
        }
        return false;
    }

    private static String plain(String text) {
        return text.strip().replace("*", "").replace("#", "").replace(":", "")
            .replaceFirst("^\\d+[.)]\\s*", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static boolean heading(String text) {
        return HEADINGS.contains(plain(text));
    }

    private static boolean recommendation(String text) {
        String value = plain(text);
        for (String label : ADVICE_LABELS) {
            if (value.startsWith(label)) {
                String rest = value.substring(label.length()).strip();
                return !rest.isEmpty() && !ADVICE_RULE.matcher(rest).find();
            }
        }
        return false;
    }

    private static Api.ServiceException invalidCitation() {
        return new Api.ServiceException("INVALID_CITATION", "Ответ отклонён: неизвестный или незавершённый номер источника");
    }

    private static Api.ServiceException evidenceLimit() {
        return new Api.ServiceException("RESPONSE_EVIDENCE_LIMIT", "Нормативный блок слишком велик и не содержит проверяемой ссылки");
    }
}
