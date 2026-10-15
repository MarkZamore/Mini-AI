package ai.enterprise.search;

import java.util.*;

/** Local query planning only: never treats a question as SQL or document instructions. */
public final class QuestionQuery {
    private QuestionQuery() {}
    private static Set<String> stems(String words) {
        var result = new HashSet<String>();
        for (String word : QueryNormalizer.tokens(words)) result.add(QueryNormalizer.stem(word));
        return Set.copyOf(result);
    }
    private static final Set<String> QUESTION_WORDS = Set.of(
        "кто", "что", "какой", "какая", "какие", "какого", "какую", "каким", "каких",
        "как", "где", "когда", "почему", "зачем", "сколько", "чьи", "чьей", "чьего",
        "расскажи", "покажи", "найди", "объясни", "подскажи", "перечисли",
        "расскажите", "покажите", "найдите", "объясните", "подскажите", "каковы", "каков",
        "what", "which", "who", "how", "where", "when", "find", "explain",
        "қандай", "қай", "қалай", "кім", "қашан", "неше", "қайда");
    private static final Set<String> FILLER = stems(
        "кто что какой какая какие какого какую каким каких как где когда почему зачем сколько " +
        "чьи чей чья чьей чьего ли и или а но в во на по о об обо от до для из у к ко с со " +
        "при про за это этот тот есть быть был была были будет можно нужно необходимо мне нам " +
        "пожалуйста расскажи покажи найди объясни подскажи перечисли говорится сказано указано " +
        "расскажите покажите найдите объясните подскажите рассказать показать найти объяснить подсказать " +
        "перечислить узнать каковы каков содержится содержатся предусмотрены " +
        "what which who how where when is are the a an of for to in on about please tell me find explain " +
        "қандай қай қалай кім қашан неше қайда туралы бойынша және ма ме бар айтып бер");
    private static final Set<String> ORGANIZATION = stems(
        "организация организациям учреждение предприятие компания университет колледж институт ұйым ұйымның");
    private static final Set<String> IDENTITY_CONTEXT = stems(
        "нормативный документ документация база данные набор текущий название наименование называться " +
        "основание основа принадлежать относиться наш ваш этот организация учреждение предприятие компания " +
        "университет колледж институт ұйым ұйымның атауы құжат құжаттар базасы");
    private static final Set<String> IDENTITY_WORDS = stems(
        "какой какая какого какую чей чьи чьей чьего название наименование называться қай атауы");
    // Generic legal/name cues. No organization or corpus-specific names are embedded.
    private static final String IDENTITY_EXPRESSION = "text:(\"акционерное общество\" OR " +
        "\"общество с ограниченной ответственностью\" OR \"государственное учреждение\" OR " +
        "\"унитарное предприятие\" OR \"университет имени\" OR \"университет им\" OR " +
        "\"полное наименование\" OR \"коммерциялық емес акционерлік қоғам\" OR " +
        "\"жауапкершілігі шектеулі серіктестік\" OR \"атындағы\" OR \"registered name\")";

    public record Plan(String expression, boolean organizationIdentity) {}

    public static Optional<Plan> plan(String query) {
        var words = QueryNormalizer.tokens(query);
        if (words.isEmpty()) return Optional.empty();
        boolean question = query.indexOf('?') >= 0 || words.stream().anyMatch(QUESTION_WORDS::contains);
        if (!question) return Optional.empty(); // Preserve exact keyword search and its ranking.
        var allStems = words.stream().map(QueryNormalizer::stem).toList();
        var content = words.stream().filter(w -> !FILLER.contains(QueryNormalizer.stem(w))).distinct().limit(30).toList();
        boolean identity = allStems.stream().anyMatch(ORGANIZATION::contains)
            && allStems.stream().anyMatch(IDENTITY_WORDS::contains)
            && content.stream().allMatch(w -> IDENTITY_CONTEXT.contains(QueryNormalizer.stem(w)));
        if (identity) return Optional.of(new Plan(IDENTITY_EXPRESSION, true));
        // Keep every subject term, date and identifier. An OR fallback would let an unknown
        // document borrow matches from common words and produce unsupported answers.
        String expression = content.stream().map(QuestionQuery::term).reduce((a,b) -> a + " AND " + b).orElse("");
        return Optional.of(new Plan(expression, false));
    }

    private static String term(String word) {
        String stem = QueryNormalizer.stem(word);
        // The existing index stems body text only. Match inflected Russian titles without
        // rebuilding the recipient's database; never prefix-match short stems or identifiers.
        String title = !stem.equals(word) && stem.length() >= 4 && word.codePoints().allMatch(c -> c >= 'а' && c <= 'я' || c == 'ё')
            ? " OR title:\"" + stem + "\"*" : "";
        return "(\"" + word + "\" OR stemmed:\"" + stem + "\"" + title + ")";
    }
}
