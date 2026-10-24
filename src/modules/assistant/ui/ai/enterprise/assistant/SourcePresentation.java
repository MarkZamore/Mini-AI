package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;
import java.util.regex.*;

/** Display-only projection. Canonical answers, fragment IDs and provenance remain in the session. */
public final class SourcePresentation {
    private static final Pattern CITATION = Pattern.compile("\\[source:([^]\\r\\n]+)]");
    private static final String WARNINGS = "\n\nОграничения источников: ";
    private static final Pattern LEGACY_FLAGS = Pattern.compile("(?i)(?:OCR|needs_review|partial|low_confidence|machine_extracted)(?:[ ,]+(?:OCR|needs_review|partial|low_confidence|machine_extracted))*");
    private SourcePresentation() {}

    public static String answer(String original, List<Api.Fragment> sources) {
        int suffix = original.lastIndexOf(WARNINGS);
        String content = suffix < 0 ? original : original.substring(0, suffix);
        Map<String,Integer> numbers = new LinkedHashMap<>();
        for (var fragment : sources) numbers.putIfAbsent(fragment.fragmentId(), numbers.size() + 1);
        Set<String> cited = new HashSet<>();
        Matcher matcher = CITATION.matcher(content); StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Integer number = numbers.get(matcher.group(1));
            if (number != null) cited.add(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(number == null ? matcher.group() : "[" + number + "]"));
        }
        matcher.appendTail(result);
        List<Api.Fragment> used = sources.stream().filter(source -> cited.contains(source.fragmentId())).toList();
        boolean documentPartial = false, documentFailed = false;
        List<String> unknown = new ArrayList<>();
        if (suffix >= 0) for (String item : original.substring(suffix + WARNINGS.length()).split(";\\s*")) {
            String warning = item.strip(), lower = warning.toLowerCase(Locale.ROOT);
            if (lower.equals("документ имеет статус partial")) documentPartial = true;
            else if (lower.equals("документ имеет статус failed")) documentFailed = true;
            else if (lower.equals("есть фрагменты, требующие сверки")
                || lower.equals("машинное извлечение/ocr не подтверждено ручной сверкой")
                || LEGACY_FLAGS.matcher(warning).matches()) {
                // These aggregate extraction labels are described by the cited
                // fragments' actual metadata. They are not answer-quality labels.
            } else if (!warning.isEmpty() && !routineExtractionWarning(lower)) unknown.add(warning);
        }
        List<String> notes = new ArrayList<>();
        if (documentFailed) notes.add("Извлечение одного из документов завершилось ошибкой: проверьте оригинал.");
        if (!notes.isEmpty()) result.append("\n\n### Ограничение ответа\n\n").append(String.join(" ", notes));
        // Unrecognised restrictions may be critical. Keep them verbatim, along
        // with unknown citation tokens, rather than replacing them with a generic note.
        if (!unknown.isEmpty()) result.append("\n\n### Важное ограничение\n\n").append(String.join("; ", unknown));
        return result.toString();
    }

    private static boolean routineExtractionWarning(String value) {
        // Known extraction diagnostics remain available verbatim in the source cards.
        return value.startsWith("pdf line ordering is inferred from geometry")
            || value.startsWith("document has extraction limitations; inspect canonical json")
            || value.startsWith("document has extraction limitations")
            || value.equals("inspect canonical json warnings and the cited source.") || value.equals("inspect source.")
            || value.startsWith("table cells inferred from page geometry")
            || value.startsWith("table geometry on page ")
            || value.startsWith("blocks outside visible page bounds")
            || value.startsWith("xml locators refer to preserved libreoffice docx, not original binary/rtf page positions.")
            || value.equals("recovered by 400 dpi sparse-text segmentation after empty ocr")
            || value.equals("requires local review.")
            || value.matches("page [0-9]+ received a 400 dpi sparse-text retry")
            || value.equals("original empty result is retained.")
            || value.matches("page [0-9]+ contains low-confidence or disagreeing ocr\\..*")
            || value.matches("[0-9]+ ocr outputs recovered quote-safely from retained tsv\\..*");
    }

    public static String warningDetails(String original) {
        int suffix = original.lastIndexOf(WARNINGS);
        return suffix < 0 ? "" : original.substring(suffix + 2);
    }

    public static boolean needsReview(Api.Fragment fragment) {
        return fragmentConcern(fragment) || manualReviewUnconfirmed(fragment);
    }

    private static boolean fragmentConcern(Api.Fragment fragment) {
        if (fragment.fields().get("quality") instanceof Map<?,?> quality) {
            if (Boolean.TRUE.equals(quality.get("needs_review")) || Boolean.TRUE.equals(quality.get("partial"))
                || Boolean.TRUE.equals(quality.get("low_confidence")) || "low".equals(quality.get("confidence"))) return true;
            if (quality.get("warnings") instanceof List<?> warnings && !warnings.isEmpty()) return true;
        }
        return fragment.fields().get("warnings") instanceof List<?> warnings && !warnings.isEmpty();
    }

    private static boolean manualReviewUnconfirmed(Api.Fragment fragment) {
        return fragment.fields().get("quality") instanceof Map<?,?> quality
            && !"human_verified".equals(quality.get("verification"));
    }

    public static String locator(Api.Fragment fragment) {
        Set<String> locations = new LinkedHashSet<>();
        for (Api.SourceRef ref : fragment.sourceRefs()) {
            Map<String,Object> f = ref.fields();
            locations.add(switch (ref.kind()) {
                case "pdf_region" -> f.get("page") instanceof Number page ? "Стр. " + page : "Фрагмент PDF";
                case "sheet_range" -> "Лист «" + Objects.toString(f.get("sheet"), "без названия") + "»"
                    + (f.get("range") instanceof String range && !range.isBlank() ? ", " + range : "");
                case "ooxml_node" -> "Фрагмент документа";
                case "drawing" -> "Рисунок в документе";
                default -> "Фрагмент источника";
            });
        }
        String summary = String.join(" · ", locations.stream().limit(3).toList());
        if (locations.size() > 3) summary += " · другие места в карточке";
        if (summary.isEmpty()) summary = "Место указано в карточке";
        return summary + (fragmentConcern(fragment) ? " · требуется сверка"
            : manualReviewUnconfirmed(fragment) ? " · ручная сверка не отмечена" : "");
    }
}
