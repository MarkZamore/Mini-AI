package ai.enterprise.assistant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** A transparent RU/KZ rule baseline, not a trained or calibrated classifier. */
record AssistantIntent(AssistantIntent.Kind kind, boolean isAction, boolean isIdentity, String language) {
    enum Kind {
        INFORMATION, IDENTITY, CONDITIONS, PROCEDURE,
        DRAFT_APPLICATION, DRAFT_LETTER, PLAN, CHECKLIST, MEMO, COMPARE, CALCULATE, GENERIC_TASK
    }

    private static final int FLAGS = Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static Pattern words(String body) { return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + body + ")(?![\\p{L}\\p{N}_])", FLAGS); }
    private static final String RU_COMMANDS =
        "составь(?:те)?|составляй(?:те)?|напиши(?:те)?|пиши(?:те)?|подготовь(?:те)?|подготавливай(?:те)?|"
        + "оформи(?:те)?|оформляй(?:те)?|заполни(?:те)?|заполняй(?:те)?|сделай(?:те)?|создай(?:те)?|создавай(?:те)?|"
        + "сформируй(?:те)?|разработай(?:те)?|набросай(?:те)?|дай(?:те)?|покажи(?:те)?|перечисли(?:те)?|"
        + "сравни(?:те)?|сравнивай(?:те)?|сопоставь(?:те)?|рассчитай(?:те)?|рассчитывай(?:те)?|"
        + "посчитай(?:те)?|подсчитай(?:те)?|вычисли(?:те)?|спланируй(?:те)?|планируй(?:те)?|помоги(?:те)?|"
        + "переведи(?:те)?|сократи(?:те)?|переформулируй(?:те)?|отредактируй(?:те)?|исправь(?:те)?|проверь(?:те)?";
    private static final String KZ_COMMANDS =
        "құрастыр(?:ыңыз|шы)?|құр(?:ыңыз|ып\\s+бер(?:іңіз|ші)?)?|жаз(?:ыңыз|шы|ып\\s+бер(?:іңіз|ші)?)?|"
        + "дайында(?:ңыз|шы|п\\s+бер(?:іңіз|ші)?)?|жаса(?:ңыз|шы|п\\s+бер(?:іңіз|ші)?)?|"
        + "әзірле(?:ңіз|ші|п\\s+бер(?:іңіз|ші)?)?|рәсімде(?:ңіз|ші|п\\s+бер(?:іңіз|ші)?)?|толтыр(?:ыңыз|шы|ып\\s+бер(?:іңіз|ші)?)?|"
        + "салыстыр(?:ыңыз|шы)?|есепте(?:ңіз|ші|п\\s+бер(?:іңіз|ші)?)?|жоспарла(?:ңыз|шы)?|"
        + "санап\\s+бер(?:іңіз|ші)?|тізіп\\s+бер(?:іңіз|ші)?|көмектес(?:іңіз|ші)?|ұсын(?:ыңыз|шы)?|"
        + "көрсет(?:іңіз|ші)?|бер(?:іңіз|ші)?|аудар(?:ыңыз|шы)?|қысқарт(?:ыңыз|шы)?|түзет(?:іңіз|ші)?|тексер(?:іңіз|ші)?";
    private static final String RU_INFINITIVES =
        "составить|написать|оформить|подготовить|заполнить|сделать|создать|разработать|сформировать|"
        + "спланировать|сравнить|сопоставить|рассчитать|посчитать|подсчитать|вычислить|перечислить|"
        + "перевести|сократить|отредактировать|исправить|проверить|разобраться|понять|"
        + "подать|получить|пройти|участвовать|вступить|выбрать|поступить|обратиться";
    private static final String KZ_INFINITIVES =
        "жазуға|құрастыруға|рәсімдеуге|дайындауға|әзірлеуге|толтыруға|жоспарлауға|салыстыруға|есептеуге|"
        + "жасауға|алуға|тапсыруға|қатысуға|өтуге|жазу|құрастыру|рәсімдеу|дайындау|әзірлеу|толтыру|"
        + "жоспарлау|салыстыру|есептеу|жасау|алу|тапсыру|қатысу|өту";
    private static final Pattern COMMAND = words(RU_COMMANDS + "|" + KZ_COMMANDS);
    private static final Pattern FRAME = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?:как(?:\\s+(?:мне|нам|я|мы))?|(?:ты\\s+)?(?:можешь|можете)(?:\\s+ли)?|"
        + "(?:мне\\s+)?(?:нужно|надо|хочу)|не\\s+мог(?:ли)?\\s+бы(?:\\s+(?:ты|вы))?)"
        + "(?:\\s+(?:ты|вы))?\\s+(?<verb>"
        + RU_INFINITIVES + ")(?![\\p{L}\\p{N}_])", FLAGS);
    private static final Pattern KZ_FRAME = Pattern.compile("(?<![\\p{L}\\p{N}_])қалай\\s+(?<verb>" + KZ_INFINITIVES
        + "|жазамын|рәсімдеймін|дайындаймын|жоспарлаймын|салыстырамын|есептеймін|аламын|тапсырамын|қатысамын|өтемін)(?![\\p{L}\\p{N}_])", FLAGS);
    private static final Pattern SELF_GUIDANCE = words("как\\s+(?:мне|нам)|маған\\s+қалай|мен\\s+қалай|бізге\\s+қалай");
    private static final Pattern NEGATED = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])не\\s+(?:(?:нужно|надо|стоит|следует|пожалуйста)\\s+)?(?:"
        + RU_COMMANDS + "|" + RU_INFINITIVES + "|делай(?:те)?|делать|объясняй|помогай|рассказывай|показывай)"
        + "(?![\\p{L}\\p{N}_])[^,;.!?\\n—–]*", FLAGS);
    private static final Pattern KZ_NEGATED = words(
        "жазба(?:ңыз)?|жасама(?:ңыз)?|дайындама(?:ңыз)?|құрастырма(?:ңыз)?|әзірлеме(?:ңіз)?|"
        + "рәсімдеме(?:ңіз)?|жоспарлама(?:ңыз)?|салыстырма(?:ңыз)?|есептеме(?:ңіз)?|толтырма(?:ңыз)?|көмектеспе(?:ңіз)?");
    private static final Pattern NEGATED_NEED = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])не\\s+(?:нужен|нужна|нужно|нужны|хочу|надо)(?![\\p{L}\\p{N}_])[^,;.!?\\n—–]*", FLAGS);
    private static final Pattern HELP_AFTER = Pattern.compile("^\\s*(?:(?:мне|нам|маған|бізге|пожалуйста)\\s+)?(" + RU_INFINITIVES + "|" + KZ_INFINITIVES + ")(?![\\p{L}\\p{N}_])", FLAGS);
    private static final Pattern HELP_BEFORE = Pattern.compile("(" + KZ_INFINITIVES + ")\\s*$", FLAGS);
    private static final String ARTIFACT = "заявлени\\p{L}*|письм\\p{L}*|план\\p{L}*|чек[-\\s]?лист\\p{L}*|список\\p{L}*|памятк\\p{L}*|сравнени\\p{L}*|расчет\\p{L}*";
    private static final Pattern NEED_ARTIFACT = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?:нужен|нужна|нужно|нужны|хочу)\\s+"
        + "(?:(?:готовый|готовое|готовая|черновик|шаблон|образец)\\s+)?(?<artifact>" + ARTIFACT + ")(?![\\p{L}\\p{N}_])", FLAGS);
    private static final Pattern KZ_NEED_ARTIFACT = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?<artifact>өтініш|хат|жоспар|тексеру\\s+тізімі|жадынама|ескертпе|салыстыру|есептеу)\\s+(?:керек|қажет)(?![\\p{L}\\p{N}_]|\\s+емес)", FLAGS);
    private static final Pattern RU_LANGUAGE = words(
        "как|что|какие|какой|мне|помоги|пожалуйста|напиши|составь|подготовь|заявление|письмо|памятк\\p{L}*|"
        + "условия|требования|правила|объясни|сравни|рассчитай|посчитай|нужно|можно|организация|университет");
    private static final Pattern KZ_LANGUAGE = words(
        "қалай|қандай|маған|мен|бізге|жаса|жасап|дайында|жаз|жазыңыз|өтініш\\p{L}*|хат|жоспар\\p{L}*|"
        + "тізім\\p{L}*|жадынама\\p{L}*|ескертпе\\p{L}*|есепте|салыстыр|түсіндір|кім|қай|шарт\\p{L}*|талап\\p{L}*|керек|болады|көмектес\\p{L}*|санап|бер|пайыз\\p{L}*|деген|бұл");
    private static final Pattern IDENTITY = Pattern.compile(
        "(?:как\\s+(?:полностью\\s+)?называется|(?:полное\\s+)?(?:официальное\\s+)?(?:название|наименование)|"
        + "что\\s+(?:это\\s+)?за\\s+организац|(?:какая|какой|какого|какую)\\s+(?:организац|университет|учреждени|компани|предприяти|вуз)|"
        + "назови|укажи|покажи|атауы|қалай\\s+аталады|атауын|қай\\s+(?:ұйым|мекеме|университет))", FLAGS);
    private static final Pattern OTHER_NAME_TARGET = Pattern.compile(
        "(?:как\\s+(?:полностью\\s+)?называется|название|наименование)\\s+(?:форма|бланк|процедура|документ|программа|модель|помощник|проект|должность|подразделение)", FLAGS);
    private static final Pattern ORGANIZATION = words(
        "организаци\\p{L}*|университет\\p{L}*|учреждени\\p{L}*|компани\\p{L}*|предприяти\\p{L}*|вуз\\p{L}*|"
        + "ұйым\\p{L}*|мекеме\\p{L}*");
    private static final Pattern NAME_TARGET = words("название|наименование|атауын|атауы");
    private static final Pattern CONDITIONS = Pattern.compile(
        "услови|требован|критери|ограничени|допуск|какие\\s+(?:правила|документы)|кто\\s+может|можно\\s+ли|разрешен|"
        + "право\\s+на|шарт|талап|кім\\s+қатыса|бола\\s+ма|рұқсат|қандай\\s+құжат", FLAGS);
    private static final Pattern PROCEDURE = Pattern.compile(
        "порядок|процедур|какие\\s+шаги|что\\s+делать|как[^.!?;\\n]*(?:оформля|подается|подают|провод|организован|организуется|организуют|организовыва|"
        + "осуществ|проход|работает|утвержда)|тәртіп|рәсімделеді|ұйымдастыр|қалай[^.!?;\\n]*(?:жұмыс|беріледі|өтеді|рәсімде)", FLAGS);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+", FLAGS);
    private static final Pattern TABLE = words("таблиц\\p{L}*|кесте\\p{L}*");
    private static final Pattern TWO_COLUMNS = words(
        "в\\s+(?:двух|2)\\s+(?:колонках|столбцах)|с\\s+(?:двумя|2)\\s+(?:колонками|столбцами)|"
        + "(?:две|2)\\s+колонки|(?:два|2)\\s+столбца|(?:екі|2)\\s+баған(?:ды|ға|да|нан\\s+тұратын)?");
    private static final Pattern NEGATED_TABLE_FORMAT = Pattern.compile(
        "(?<![\\p{L}\\p{N}_])(?:не\\s+(?:в|с)\\s+(?:двух|двумя|2)\\s+(?:колонк\\p{L}*|столбц\\p{L}*)|"
        + "без\\s+(?:таблиц\\p{L}*|(?:двух|2)\\s+(?:колонок|столбцов))|не\\s+таблиц\\p{L}*|"
        + "таблиц\\p{L}*\\s+не\\s+(?:нужна|нужны|нужно|надо)|"
        + "(?:кесте\\p{L}*|(?:екі|2)\\s+баған\\p{L}*)\\s+(?:керек|қажет)\\s+емес)(?![\\p{L}\\p{N}_])", FLAGS);
    private record Target(Kind kind, Pattern pattern) {}
    private static final List<Target> TARGETS = List.of(
        new Target(Kind.DRAFT_APPLICATION, words("заявлени\\p{L}*|өтініш\\p{L}*")),
        new Target(Kind.DRAFT_LETTER, words("письм\\p{L}*|хат(?:ты|тың|қа|ын|ым|ымды|ымыз|ымызды|ыңызды)?")),
        new Target(Kind.PLAN, words("план\\p{L}*|порядок\\s+действий|жоспар\\p{L}*")),
        new Target(Kind.CHECKLIST, words("чек[-\\s]?лист\\p{L}*|список\\p{L}*|чек[-\\s]?парақ\\p{L}*|тізім\\p{L}*")),
        new Target(Kind.MEMO, words("памятк\\p{L}*|жадынама\\p{L}*|ескертпе\\p{L}*")),
        new Target(Kind.COMPARE, words("сравнени\\p{L}*|сопоставлени\\p{L}*|салыстыру\\p{L}*|салыстырмалы\\s+кесте")),
        new Target(Kind.CALCULATE, words("расчет\\p{L}*|подсчет\\p{L}*|вычислени\\p{L}*|есептеу\\p{L}*|есеп[- ]қисап")));
    private record Action(int start, int verbStart, int verbEnd, String verb, Kind forced) {}

    AssistantIntent {
        Objects.requireNonNull(kind);
        if (!Set.of("ru", "kz").contains(language)) throw new IllegalArgumentException("Unknown intent language");
        if (isAction != actionKind(kind) || isIdentity != (kind == Kind.IDENTITY))
            throw new IllegalArgumentException("Intent flags differ from kind");
    }

    static AssistantIntent analyze(String query) {
        String text = normalize(unquoted(Objects.toString(query, "")));
        String language = language(text);
        String operative = maskNegated(text);
        var actions = new ArrayList<Action>();
        var command = COMMAND.matcher(operative);
        while (command.find()) {
            String verb = command.group();
            Kind forced = forced(verb);
            if (verb.startsWith("помог") || verb.startsWith("көмектес")) {
                var after = HELP_AFTER.matcher(operative.substring(command.end()));
                var before = HELP_BEFORE.matcher(operative.substring(0, command.start()));
                if (after.find()) forced = forced(after.group(1));
                else if (before.find()) forced = forced(before.group(1));
            }
            actions.add(new Action(command.start(), command.start(), command.end(), verb, forced));
        }
        var frame = FRAME.matcher(operative);
        while (frame.find()) {
            // A question asking which documents must be prepared is informational.
            String prefix = operative.substring(clauseStart(operative, frame.start()), frame.start());
            if (prefix.stripLeading().matches("(?s)^(?:какие|какой|какая|какое|каких)(?:\\s.*|$)")) continue;
            actions.add(new Action(frame.start(), frame.start("verb"), frame.end("verb"), frame.group("verb"), forced(frame.group("verb"))));
        }
        var kzFrame = KZ_FRAME.matcher(operative);
        while (kzFrame.find()) actions.add(new Action(kzFrame.start(), kzFrame.start("verb"), kzFrame.end("verb"), kzFrame.group("verb"), forced(kzFrame.group("verb"))));
        actions.sort(Comparator.comparingInt(Action::start).thenComparingInt(a -> a.forced == null ? 1 : 0));
        for (Action action : actions) {
            Kind kind = action.forced != null ? action.forced : target(operative, action, language);
            kind = requestedColumns(operative, action.verbStart, action.verbEnd, kind);
            if (kind == Kind.GENERIC_TASK && identity(operative) && NAME_TARGET.matcher(operative).find()
                    && (action.verb.startsWith("дай") || action.verb.startsWith("покажи") || action.verb.startsWith("көрсет")))
                return of(Kind.IDENTITY, language);
            return of(kind, language);
        }
        // A direct need for an artifact is different from "Какие требования к плану?".
        for (Pattern pattern : List.of(NEED_ARTIFACT, KZ_NEED_ARTIFACT)) {
            var need = pattern.matcher(operative);
            while (need.find()) for (Target target : TARGETS)
                if (target.pattern.matcher(need.group("artifact")).matches())
                    return of(requestedColumns(operative, need.start(), need.end(), target.kind), language);
        }
        if (SELF_GUIDANCE.matcher(operative).find()) return of(Kind.PLAN, language);
        if (identity(operative)) return of(Kind.IDENTITY, language);
        var conditions = CONDITIONS.matcher(operative); var procedure = PROCEDURE.matcher(operative);
        int conditionAt = conditions.find() ? conditions.start() : Integer.MAX_VALUE;
        int procedureAt = procedure.find() ? procedure.start() : Integer.MAX_VALUE;
        if (conditionAt < procedureAt) return of(Kind.CONDITIONS, language);
        if (procedureAt != Integer.MAX_VALUE) return of(Kind.PROCEDURE, language);
        return of(Kind.INFORMATION, language);
    }

    private static AssistantIntent of(Kind kind, String language) {
        return new AssistantIntent(kind, actionKind(kind), kind == Kind.IDENTITY, language);
    }

    private static boolean actionKind(Kind kind) {
        return switch (kind) {
            case DRAFT_APPLICATION, DRAFT_LETTER, PLAN, CHECKLIST, MEMO, COMPARE, CALCULATE, GENERIC_TASK -> true;
            default -> false;
        };
    }

    private static Kind forced(String verb) {
        if (verb.startsWith("сравн") || verb.startsWith("сопостав") || verb.startsWith("салыстыр")) return Kind.COMPARE;
        if (verb.startsWith("рассч") || verb.startsWith("рассчит") || verb.startsWith("посчит") || verb.startsWith("подсчит")
                || verb.startsWith("вычисл") || verb.startsWith("есепте") || verb.startsWith("санап")) return Kind.CALCULATE;
        if (verb.startsWith("сплан") || verb.startsWith("планир") || verb.startsWith("жоспарла")) return Kind.PLAN;
        if (verb.startsWith("перечисл") || verb.startsWith("тізіп")) return Kind.CHECKLIST;
        if (Set.of("подать", "получить", "пройти", "участвовать", "вступить", "выбрать", "поступить", "обратиться",
                "алуға", "тапсыруға", "қатысуға", "өтуге", "алу", "тапсыру", "қатысу", "өту",
                "аламын", "тапсырамын", "қатысамын", "өтемін").contains(verb)) return Kind.PLAN;
        if (Set.of("разобраться", "понять").contains(verb)) return Kind.GENERIC_TASK;
        return null;
    }

    private static Kind target(String text, Action action, String language) {
        int left = action.verbStart, right = action.verbEnd;
        while (left > 0 && !boundary(text.charAt(left - 1))) left--;
        while (right < text.length() && !boundary(text.charAt(right))) right++;
        Kind selected = Kind.GENERIC_TASK; int best = Integer.MAX_VALUE;
        for (Target target : TARGETS) {
            var match = target.pattern.matcher(text).region(left, right);
            while (match.find()) {
                boolean after = match.start() >= action.verbEnd;
                boolean before = match.end() <= action.verbStart;
                if (!after && !before) continue;
                int distance = countWords(after ? text.substring(action.verbEnd, match.start()) : text.substring(match.end(), action.verbStart));
                if (distance > 7) continue;
                int score = distance * 10 + ((language.equals("kz") ? before : after) ? 0 : 1);
                if (score < best) { best = score; selected = target.kind; }
            }
        }
        return selected;
    }

    private static Kind requestedColumns(String text, int start, int end, Kind kind) {
        if (kind != Kind.MEMO && kind != Kind.GENERIC_TASK) return kind;
        while (start > 0 && !boundary(text.charAt(start - 1))) start--;
        while (end < text.length() && !boundary(text.charAt(end))) end++;
        String clause = text.substring(start, end);
        if (NEGATED_TABLE_FORMAT.matcher(clause).find() || !TWO_COLUMNS.matcher(clause).find()) return kind;
        return kind == Kind.MEMO || TABLE.matcher(clause).find() ? Kind.COMPARE : kind;
    }

    private static int countWords(String text) {
        int result = 0; var words = WORD.matcher(text); while (words.find()) result++; return result;
    }

    private static boolean boundary(char value) { return ".;!?\n—–".indexOf(value) >= 0; }
    private static int clauseStart(String text, int position) {
        while (position > 0 && !boundary(text.charAt(position - 1)) && text.charAt(position - 1) != ',') position--;
        return position;
    }
    private static boolean identity(String text) {
        return ORGANIZATION.matcher(text).find() && IDENTITY.matcher(text).find() && !OTHER_NAME_TARGET.matcher(text).find();
    }
    private static String language(String text) {
        int ru = 0, kz = 0;
        var russian = RU_LANGUAGE.matcher(text); while (russian.find()) ru++;
        var kazakh = KZ_LANGUAGE.matcher(text); while (kazakh.find()) kz++;
        if (kz > ru) return "kz";
        if (ru > 0) return "ru";
        return text.matches("(?s).*[әғқңөұүһі].*") ? "kz" : "ru";
    }

    private static String maskNegated(String text) {
        StringBuilder result = new StringBuilder(text);
        var negated = NEGATED.matcher(text);
        while (negated.find()) for (int i = negated.start(); i < negated.end(); i++) result.setCharAt(i, ' ');
        var negativeNeed = NEGATED_NEED.matcher(text);
        while (negativeNeed.find()) for (int i = negativeNeed.start(); i < negativeNeed.end(); i++) result.setCharAt(i, ' ');
        var kz = KZ_NEGATED.matcher(text);
        while (kz.find()) {
            // The object before a negative KZ verb is not an affirmative drafting request.
            int start = clauseStart(text, kz.start());
            for (int i = start; i < kz.end(); i++) result.setCharAt(i, ' ');
        }
        return result.toString();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String unquoted(String text) {
        StringBuilder result = new StringBuilder(text.length()); char close = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (close != 0) {
                result.append(value == '\n' ? '\n' : ' ');
                if (value == close && (i == 0 || text.charAt(i - 1) != '\\')) close = 0;
            } else {
                char end = switch (value) { case '«' -> '»'; case '“' -> '”'; case '„' -> '“'; case '‘' -> '’'; case '"', '\'', '`' -> value; default -> 0; };
                boolean apostrophe = value == '\'' && i > 0 && i + 1 < text.length()
                    && Character.isLetter(text.charAt(i - 1)) && Character.isLetter(text.charAt(i + 1));
                if (end != 0 && !apostrophe) { close = end; result.append(' '); }
                else result.append(value);
            }
        }
        return result.toString();
    }

    /** Prompt policy only: it performs no tool action and creates no file. */
    String responseInstruction() {
        if (!isAction) return language.equals("kz")
            ? "Сұраққа тікелей жауап бер. Дереккөздердегі қолданылу аясын, барлық маңызды шарттарды, мерзімдер мен ерекшеліктерді сақта; жеткіліксіз деректі ашық көрсет."
            : "Ответь прямо на вопрос. Сохрани область применения, все существенные условия, сроки и исключения источников; недостаток оснований обозначь явно.";
        String start = switch (kind) {
            case DRAFT_APPLICATION -> language.equals("kz") ? "Жауапты көшіріп қолдануға болатын дайын өтініш жобасынан баста." : "Начни с готового черновика заявления, который можно скопировать.";
            case DRAFT_LETTER -> language.equals("kz") ? "Жауапты көшіріп қолдануға болатын дайын хат жобасынан баста." : "Начни с готового черновика письма, который можно скопировать.";
            case PLAN -> language.equals("kz") ? "Жауапты қолдануға болатын нақты қадамдық жоспардан баста." : "Начни с готового конкретного пошагового плана.";
            case CHECKLIST -> language.equals("kz") ? "Жауапты қолдануға болатын тексеру тізімінен баста." : "Начни с готового списка проверки с отмечаемыми пунктами.";
            case MEMO -> language.equals("kz") ? "Жауапты сұралған тақырыптар бойынша бөлінген дайын қысқа жадынамадан баста." : "Начни с готовой короткой памятки, разделённой по запрошенным темам.";
            case COMPARE -> language.equals("kz") ? "Жауапты дайын салыстырудан баста: айырмашылықтарды өлшемдер бойынша көрсет." : "Начни с готового сравнения: сопоставь варианты по явным критериям.";
            case CALCULATE -> language.equals("kz") ? "Есеп нәтижесін, формуланы және өлшем бірліктерін көрсет; міндетті бастапқы деректер жоқ болса, оларды сұра және формуланы бер, сан ойлап таппа." : "Начни с результата расчёта, формулы и единиц; если обязательных исходных данных нет, запроси их и дай формулу, число не выдумывай.";
            default -> language.equals("kz") ? "Тапсырманы мәтінмен орындап, дайын нәтижеден баста; жетіспейтін міндетті деректерді нақты сұра." : "Выполни задачу текстом и начни с готового результата; необходимые недостающие данные запроси конкретно.";
        };
        return start + (language.equals("kz")
            ? " Белгісіз аты-жөнді, алушыны, лауазымды және күндерді [толтырылатын өрістер] ретінде қалдыр, ойлап таппа. Нәтижеден кейін дереккөздердегі барлық маңызды шарттарды, мерзімдер мен ерекшеліктерді сілтемелерімен толық бер. Жалпы кеңестерді ұйымның талаптарынан бөлек, міндетті емес деп белгіле. Расталған құрал нәтижесі болмаса, құжат жіберілді, файл жасалды не сақталды, өтініш берілді немесе сыртқы әрекет орындалды деп айтпа: бұл тек мәтіндік жоба не жоспар."
            : " Неизвестные имя, адресата, должность и даты оставь как [поля для заполнения], не выдумывай. После результата полностью приведи относящиеся условия, сроки и исключения источников со ссылками. Общие рекомендации отдели от требований организации и пометь необязательными. Без подтверждённого результата инструмента не утверждай, что документ отправлен, файл создан или сохранён, заявление подано либо выполнено внешнее действие: это только текстовый черновик или план.");
    }
}
