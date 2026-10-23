package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;
import java.util.regex.*;

/** Display-only projection. Canonical answers, fragment IDs and provenance remain in the session. */
public final class SourcePresentation {
    private static final Pattern CITATION = Pattern.compile("\\[source:([^]\\r\\n]+)]");
    private static final String WARNINGS = "\n\nОграничения источников: ";
    private SourcePresentation() {}

    public static String answer(String original, List<Api.Fragment> sources) {
        int suffix = original.lastIndexOf(WARNINGS);
        String content = suffix < 0 ? original : original.substring(0, suffix);
        Map<String,Integer> numbers = new LinkedHashMap<>();
        for (var fragment : sources) numbers.putIfAbsent(fragment.fragmentId(), numbers.size() + 1);
        Matcher matcher = CITATION.matcher(content); StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Integer number = numbers.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(number == null ? matcher.group() : "[" + number + "]"));
        }
        matcher.appendTail(result);
        if (suffix >= 0) {
            String warnings = original.substring(suffix).toLowerCase(Locale.ROOT);
            result.append(warnings.contains("partial") || warnings.contains("статус failed")
                ? "\n\nПримечание: источники извлечены не полностью; полноту ответа нужно сверить с оригиналом."
                : "\n\nПримечание: качество извлечения источников требует сверки.");
            result.append(" Подробности — в карточках источников.");
        }
        return result.toString();
    }

    public static String warningDetails(String original) {
        int suffix = original.lastIndexOf(WARNINGS);
        return suffix < 0 ? "" : original.substring(suffix + 2);
    }

    public static boolean needsReview(Api.Fragment fragment) {
        if (fragment.fields().get("quality") instanceof Map<?,?> quality) {
            if (Boolean.TRUE.equals(quality.get("needs_review")) || !"human_verified".equals(quality.get("verification"))) return true;
            if (quality.get("warnings") instanceof List<?> warnings && !warnings.isEmpty()) return true;
        }
        return fragment.fields().get("warnings") instanceof List<?> warnings && !warnings.isEmpty();
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
        return summary + (needsReview(fragment) ? " · требуется сверка" : "");
    }
}
