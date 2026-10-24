package ai.enterprise.assistant;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small bounded decimal calculator. It never infers a document rule, amount or missing operand. */
final class TaskArithmetic {
    record Result(BigDecimal value, String formula, String text, boolean rounded) {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final int MAX_QUERY = 600, MAX_OPERATORS = 12, MAX_NUMBERS = 16, MAX_DEPTH = 4;
    private static final MathContext DIVISION = new MathContext(34, RoundingMode.HALF_UP);
    private static final BigDecimal MAX_ABSOLUTE = new BigDecimal("1E30");
    private static final String UNSIGNED = "(?:[0-9]{1,3}(?: [0-9]{3})+|[0-9]{1,18})(?:[.,][0-9]{1,12})?";
    private static final String NUMBER = "[+-]?" + UNSIGNED;
    private static final String UNIT = "₽|₸|руб(?:ль|ля|лей|ли)?\\.?|тенге|теңге|тг\\.?|usd|eur|доллар(?:а|ов)?|евро|кг|км|м|час(?:а|ов)?|мин(?:ута|уты|ут)?";
    private static final String PREFIX = "(?:рассчитай(?:те)?|посчитай(?:те)?|вычисли(?:те)?|сколько(?:\\s+(?:будет|составит|составляет|равно))?|есепте(?:ңіз)?|тап(?:ыңыз)?)";
    private static final String PERCENT = "(?:%|процент(?:а|ов)?|пайыз(?:ын|ы)?)";
    private static final String MONEY = "₽|₸|руб(?:ль|ля|лей|ли)?\\.?|тенге|теңге|тг\\.?|usd|eur|доллар(?:а|ов)?|евро";
    private static final String CALCULATE_RU = "(?:рассчитай(?:те)?|посчитай(?:те)?|вычисли(?:те)?)";
    private static final String SHORT_CALC_RU = "(?:\\s*[,;.]\\s*(?:покажи(?:те)?|приведи(?:те)?)\\s+(?:(?:короткий|краткий)\\s+)?расч[её]т)?";
    private static final Pattern DISCOUNT_WORD_PROBLEM_RU = pattern("^(?:(?:товар|услуга|покупка|заказ)\\s+стоит|(?:цена|стоимость)(?:\\s+(?:товара|услуги|покупки|заказа))?\\s*[:=]?)\\s*"
        + "(?<base>" + NUMBER + ")\\s*(?<unit>" + MONEY + ")?\\s*[,;.]\\s*скидка\\s*[:=]?\\s*(?<rate>" + NUMBER + ")\\s*" + PERCENT
        + "\\s*[.!?;]?\\s*" + CALCULATE_RU + "\\s+(?:(?:размер|сумму)\\s+скидки|скидку)\\s+и\\s+"
        + "(?:(?:итоговую|конечную)\\s+(?:цену|стоимость)|цену\\s+после\\s+скидки)" + SHORT_CALC_RU + "\\s*[.!?]*$");
    private static final Pattern COMPLETION_WORD_PROBLEM_KZ = pattern("^(?<total>" + UNSIGNED + ")\\s+(?:тапсырманың|сұрақтың|тармақтың|жұмыстың)\\s+"
        + "(?<done>" + UNSIGNED + ")(?:-(?:і|ы))?\\s+орындалды\\s*[.!?;]\\s*"
        + "орындалу\\s+пайызын\\s+және\\s+қалған\\s+(?:тапсырма|сұрақ|тармақ|жұмыс)\\s+санын\\s+есепте(?:ңіз|п)?"
        + "(?:\\s*[,;.]\\s*(?:қысқа\\s+)?(?:есебін|есепті)\\s+көрсет(?:іңіз)?)?\\s*[.!?]*$");
    private static final Pattern COMPLETION_WORD_PROBLEM_RU = pattern("^(?:из\\s+)?(?<total>" + UNSIGNED + ")\\s+(?:заданий|задач|вопросов|пунктов)\\s+"
        + "(?:выполнено|завершено)\\s+(?<done>" + UNSIGNED + ")\\s*[.!?;]\\s*" + CALCULATE_RU
        + "\\s+процент\\s+выполнения\\s+и\\s+(?:(?:число|количество)\\s+оставшихся\\s+(?:заданий|задач|вопросов|пунктов)|остаток)"
        + SHORT_CALC_RU + "\\s*[.!?]*$");
    private static final Pattern RATE_OF_BASE = pattern("^(?:" + PREFIX + "\\s*:?\\s*)?(?<rate>" + NUMBER + ")\\s*" + PERCENT
        + "\\s+(?:от|из)\\s+(?<base>" + NUMBER + ")\\s*(?<unit>" + UNIT + ")?\\s*[.!?]*$");
    private static final Pattern KZ_RATE_OF_BASE = pattern("^(?:(?:есепте(?:ңіз)?|тап(?:ыңыз)?)\\s*:?\\s*)?(?<base>" + NUMBER
        + ")\\s*(?<connector>-(?:ның|нің|дың|дің|тың|тің)|санының|теңгенің|тенгенің|рубльдің)\\s*(?<rate>" + NUMBER
        + ")\\s*(?:%(?:-(?:ын|ін|ы|і))?|пайыз(?:ын|ы|ін|і)?)\\s*(?:есепте(?:ңіз)?|тап(?:ыңыз)?|қанша(?:\\s+болады)?)?\\s*[.!?]*$");
    private static final Pattern CHANGE_RU = pattern("^(?<change>увеличь(?:те)?|уменьши(?:те)?)\\s+(?<base>" + NUMBER
        + ")\\s*(?<unit>" + UNIT + ")?\\s+на\\s+(?<rate>" + NUMBER + ")\\s*" + PERCENT + "\\s*[.!?]*$");
    private static final Pattern CHANGE_KZ = pattern("^(?<base>" + NUMBER + ")\\s+(?<connector>санын|теңгені|тенгені)\\s+(?<rate>" + NUMBER
        + ")\\s*(?:пайызға|%-ға|%-ке)\\s+(?<change>арттыр(?:ыңыз)?|көбейт(?:іңіз)?|азайт(?:ыңыз)?)\\s*[.!?]*$");
    private static final Pattern EXPRESSION_PREFIX = pattern("^" + PREFIX + "(?:\\s+|\\s*:\\s*)");
    private static final Pattern EXPRESSION_SUFFIX = pattern("\\s+(?:есепте(?:ңіз)?|қанша(?:\\s+болады)?)$");
    private static final Pattern END_UNIT = pattern("(?<unit>" + UNIT + ")$");
    private static final Pattern NUMBER_TOKEN = pattern(UNSIGNED);
    private static final Pattern DATE_LIKE = pattern("^[0-9]{1,4}[-./][0-9]{1,2}[-./][0-9]{1,4}$");
    private static final Pattern ISO_DATE_LIKE = pattern("^[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}$");
    private static final Pattern BARE_RANGE = pattern("^[0-9]+\\s*-\\s*[0-9]+$");

    private TaskArithmetic() {}
    private static Pattern pattern(String value) { return Pattern.compile(value, FLAGS); }

    /** Empty means unsupported or ambiguous: callers must not substitute an invented result. */
    static Optional<Result> solve(String question, String language) {
        if (question == null || question.length() > MAX_QUERY || !("ru".equals(language) || "kz".equals(language)))
            return Optional.empty();
        String query = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .replace('\u2212', '-').replace('\u2010', '-').replace('\u2011', '-').replace('×', '*').replace('÷', '/').strip();
        if (query.isEmpty()) return Optional.empty();
        try {
            Matcher match = DISCOUNT_WORD_PROBLEM_RU.matcher(query);
            if (match.matches()) return discount(match.group("base"), match.group("rate"), match.group("unit"), language);
            for (Pattern completion : new Pattern[] { COMPLETION_WORD_PROBLEM_KZ, COMPLETION_WORD_PROBLEM_RU }) {
                match = completion.matcher(query);
                if (match.matches()) return completion(match.group("total"), match.group("done"), language);
            }
            match = RATE_OF_BASE.matcher(query);
            if (match.matches()) return percentage(match.group("base"), match.group("rate"), match.group("unit"), 0, language);
            match = KZ_RATE_OF_BASE.matcher(query);
            if (match.matches()) return percentage(match.group("base"), match.group("rate"), connectorUnit(match.group("connector")), 0, language);
            match = CHANGE_RU.matcher(query);
            if (match.matches()) return percentage(match.group("base"), match.group("rate"), match.group("unit"), match.group("change").startsWith("увелич") ? 1 : -1, language);
            match = CHANGE_KZ.matcher(query);
            if (match.matches()) return percentage(match.group("base"), match.group("rate"), connectorUnit(match.group("connector")), match.group("change").startsWith("азайт") ? -1 : 1, language);

            String expression = query.replaceFirst("[.!?]+$", "").strip();
            Matcher prefix = EXPRESSION_PREFIX.matcher(expression);
            boolean explicit = prefix.find();
            if (explicit) expression = expression.substring(prefix.end()).strip();
            Matcher suffix = EXPRESSION_SUFFIX.matcher(expression);
            if (suffix.find()) { expression = expression.substring(0, suffix.start()).strip(); explicit = true; }
            if (expression.endsWith("=")) { expression = expression.substring(0, expression.length() - 1).strip(); explicit = true; }
            String unit = "";
            Matcher endUnit = END_UNIT.matcher(expression);
            if (endUnit.find() && endUnit.start() > 0) {
                unit = endUnit.group("unit"); expression = expression.substring(0, endUnit.start()).strip();
            }
            if (ISO_DATE_LIKE.matcher(expression).matches()
                || (!explicit && (DATE_LIKE.matcher(expression).matches() || BARE_RANGE.matcher(expression).matches()))) return Optional.empty();
            if (!expression.matches("[0-9\\s.,+*/()\\-]+") || (!explicit && !expression.matches(".*[+*/()].*"))) return Optional.empty();
            var parser = new Parser(expression);
            Value answer = parser.parse();
            return Optional.of(result(answer.number(), answer.expression(), unit, parser.rounded, language));
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return Optional.empty();
        }
    }

    private static Optional<Result> discount(String rawBase, String rawRate, String rawUnit, String language) {
        BigDecimal base = number(rawBase), rate = number(rawRate);
        if (base.signum() < 0 || rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0) return Optional.empty();
        BigDecimal reduction = checked(base.multiply(rate).divide(new BigDecimal("100")));
        BigDecimal payable = checked(base.subtract(reduction));
        String unit = rawUnit == null || rawUnit.isBlank() ? "" : " " + rawUnit;
        String formula = format(base) + " × " + format(rate) + " / 100 = " + format(reduction) + unit
            + "; " + format(base) + " − " + format(reduction) + " = " + format(payable) + unit;
        String text = language.equals("kz")
            ? "Нәтиже: жеңілдік — " + format(reduction) + unit + "; соңғы баға — " + format(payable) + unit + ".\nЕсептеу: " + formula + "."
            : "Результат: размер скидки — " + format(reduction) + unit + "; итоговая цена — " + format(payable) + unit + ".\nРасчёт: " + formula + ".";
        return Optional.of(new Result(payable, formula, text, false));
    }

    private static Optional<Result> completion(String rawTotal, String rawDone, String language) {
        BigDecimal total = number(rawTotal), done = number(rawDone);
        if (total.signum() <= 0 || total.scale() > 0 || done.signum() < 0 || done.scale() > 0 || done.compareTo(total) > 0)
            return Optional.empty();
        BigDecimal percent; boolean rounded = false;
        BigDecimal numerator = checked(done.multiply(new BigDecimal("100")));
        try { percent = checked(numerator.divide(total)); }
        catch (ArithmeticException repeating) { percent = checked(numerator.divide(total, DIVISION)); rounded = true; }
        BigDecimal remaining = checked(total.subtract(done));
        String percentText = (rounded ? "≈ " : "") + format(percent) + "%";
        String formula = format(done) + " / " + format(total) + " × 100" + (rounded ? " ≈ " : " = ") + format(percent) + "%"
            + "; " + format(total) + " − " + format(done) + " = " + format(remaining);
        String text = language.equals("kz")
            ? "Нәтиже: орындалуы — " + percentText + "; қалғаны — " + format(remaining) + ".\nЕсептеу: " + formula + "."
            : "Результат: выполнено — " + percentText + "; осталось — " + format(remaining) + ".\nРасчёт: " + formula + ".";
        if (rounded) text += language.equals("kz") ? "\nШексіз бөлшек 34 мәнді цифрға дейін дөңгелектелді."
            : "\nБесконечная десятичная дробь округлена до 34 значащих цифр.";
        return Optional.of(new Result(percent, formula, text, rounded));
    }

    private static Optional<Result> percentage(String rawBase, String rawRate, String unit, int change, String language) {
        BigDecimal base = number(rawBase), rate = number(rawRate);
        if (rate.signum() < 0) return Optional.empty();
        BigDecimal part = checked(base.multiply(rate).divide(new BigDecimal("100")));
        BigDecimal value = change > 0 ? checked(base.add(part)) : change < 0 ? checked(base.subtract(part)) : part;
        String formula = format(base) + " × " + format(rate) + " / 100";
        if (change != 0) formula = format(base) + (change > 0 ? " + (" : " − (") + formula + ")";
        return Optional.of(result(value, formula, unit, false, language));
    }

    private static String connectorUnit(String connector) {
        return connector.startsWith("теңг") || connector.startsWith("тенг") ? "₸" : connector.startsWith("руб") ? "₽" : "";
    }

    private static Result result(BigDecimal value, String expression, String rawUnit, boolean rounded, String language) {
        String unit = rawUnit == null || rawUnit.isBlank() ? "" : " " + rawUnit;
        String formula = expression + (rounded ? " ≈ " : " = ") + format(value) + unit;
        String text = (language.equals("kz") ? "Нәтиже: " : "Результат: ") + (rounded ? "≈ " : "") + format(value) + unit
            + ".\n" + (language.equals("kz") ? "Есептеу: " : "Расчёт: ") + formula + ".";
        if (rounded) text += language.equals("kz") ? "\nШексіз бөлшек 34 мәнді цифрға дейін дөңгелектелді."
            : "\nБесконечная десятичная дробь округлена до 34 значащих цифр.";
        return new Result(value, formula, text, rounded);
    }

    private static BigDecimal number(String raw) {
        String normalized = raw.replace(" ", "").replace(',', '.');
        if (!normalized.matches("[+-]?[0-9]{1,18}(?:\\.[0-9]{1,12})?")) throw new IllegalArgumentException("Number outside input bounds");
        return checked(new BigDecimal(normalized));
    }

    private static BigDecimal checked(BigDecimal value) {
        value = value.stripTrailingZeros();
        if (value.precision() > 64 || value.scale() > 48 || value.abs().compareTo(MAX_ABSOLUTE) > 0)
            throw new ArithmeticException("Arithmetic result outside bounds");
        return value;
    }

    private static String format(BigDecimal value) { return value.stripTrailingZeros().toPlainString().replace('.', ','); }
    private record Value(BigDecimal number, String expression) {}

    private static final class Parser {
        private final String expression;
        private int position, operators, numbers, depth;
        private boolean rounded;
        Parser(String expression) { this.expression = expression; }
        Value parse() {
            Value answer = sum(); spaces();
            if (position != expression.length()) throw new IllegalArgumentException("Extra input");
            return answer;
        }
        Value sum() {
            Value value = product();
            while (true) {
                spaces(); char op = current(); if (op != '+' && op != '-') return value;
                operation(); position++; Value right = product();
                value = new Value(checked(op == '+' ? value.number().add(right.number()) : value.number().subtract(right.number())),
                    "(" + value.expression() + (op == '+' ? " + " : " − ") + right.expression() + ")");
            }
        }
        Value product() {
            Value value = factor();
            while (true) {
                spaces(); char op = current(); if (op != '*' && op != '/') return value;
                operation(); position++; Value right = factor();
                BigDecimal answer;
                if (op == '*') answer = value.number().multiply(right.number());
                else {
                    if (right.number().signum() == 0) throw new ArithmeticException("Division by zero");
                    try { answer = value.number().divide(right.number()); }
                    catch (ArithmeticException repeating) { answer = value.number().divide(right.number(), DIVISION); rounded = true; }
                }
                value = new Value(checked(answer), "(" + value.expression() + (op == '*' ? " × " : " / ") + right.expression() + ")");
            }
        }
        Value factor() {
            spaces(); char next = current();
            if (next == '+' || next == '-') {
                operation(); position++; Value value = factor();
                return next == '-' ? new Value(checked(value.number().negate()), "−" + value.expression()) : value;
            }
            if (next == '(') {
                if (++depth > MAX_DEPTH) throw new IllegalArgumentException("Expression nesting outside bounds");
                position++; Value value = sum(); spaces();
                if (current() != ')') throw new IllegalArgumentException("Unclosed group");
                position++; depth--; return value;
            }
            Matcher token = NUMBER_TOKEN.matcher(expression).region(position, expression.length());
            if (!token.lookingAt() || ++numbers > MAX_NUMBERS) throw new IllegalArgumentException("Expected bounded number");
            position = token.end(); BigDecimal number = number(token.group());
            return new Value(number, format(number));
        }
        void operation() { if (++operators > MAX_OPERATORS) throw new IllegalArgumentException("Too many operators"); }
        void spaces() { while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) position++; }
        char current() { return position < expression.length() ? expression.charAt(position) : '\0'; }
    }
}
