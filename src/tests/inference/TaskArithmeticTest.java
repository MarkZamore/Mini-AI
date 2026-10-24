package ai.enterprise.assistant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

/** Deterministic arithmetic only, on the complete application classpath; no model or variant app. */
public final class TaskArithmeticTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static TaskArithmetic.Result equals(String query, String expected, String language) {
        var actual = TaskArithmetic.solve(query, language);
        check(actual.isPresent(), "Supported expression rejected: " + query);
        var result = actual.orElseThrow();
        check(result.value().compareTo(new BigDecimal(expected)) == 0, query + " => " + result.value() + ", expected " + expected);
        check(!result.rounded(), "Exact decimal marked approximate: " + query);
        check(!result.formula().isBlank(), "Formula absent: " + query);
        check(result.text().startsWith(language.equals("kz") ? "Нәтиже: " : "Результат: "), "Answer first in requested language: " + query);
        check(result.text().contains(result.formula()), "Visible formula differs from arithmetic artifact: " + query);
        return result;
    }
    private static void reject(String query) {
        check(TaskArithmetic.solve(query, "ru").isEmpty(), "Ambiguous/unsupported query accepted: " + query);
    }
    public static void main(String[] args) {
        equals("2 + 3 * 4", "14", "ru");
        equals("(2 + 3) * 4", "20", "ru");
        equals("Вычисли: 2 + 3 × 4", "14", "ru");
        equals("Сколько будет 1,5 + 2,25?", "3.75", "ru");
        equals("Рассчитай -2 * (3 + 4)", "-14", "ru");
        equals("Посчитай 8 − 11", "-3", "ru");
        equals("Посчитай 2 - -3", "5", "ru");
        equals("Рассчитай 0.1 + 0.2", "0.3", "ru");
        equals("1 / 8", "0.125", "ru");
        equals("Посчитай 1 / 2 / 4", "0.125", "ru");
        equals("6 ÷ 2 = ?", "3", "ru");
        equals("Посчитай 24 000 + 1 000", "25000", "ru");
        equals("Посчитай 24\u00a0000 + 1\u202f000", "25000", "ru");
        equals("Посчитай 0,000000000001 / 100000000000000000", "0.00000000000000000000000000001", "ru");
        equals("Рассчитай 1000000000000000 * 1000000000000000", "1E30", "ru");
        equals("Посчитай: 10", "10", "ru");
        equals("Есептеңіз: 1,25 + 2,75", "4", "kz");
        equals("2 + 3 есепте", "5", "kz");
        equals("2 + 3 қанша болады?", "5", "kz");

        equals("Рассчитай 15% от 24000", "3600", "ru");
        equals("Сколько составляет 15 процентов от 24 000 тенге?", "3600", "ru");
        equals("2,5 процента от 1200", "30", "ru");
        equals("150% от 200", "300", "ru");
        equals("0% от 200", "0", "ru");
        equals("15% от -200", "-30", "ru");
        equals("24000-ның 15 пайызы қанша?", "3600", "kz");
        equals("24000-ның 15%-ын есепте", "3600", "kz");
        equals("24 000 теңгенің 15 пайызын есептеңіз", "3600", "kz");
        equals("Есепте: 200 санының 10 пайызы", "20", "kz");
        equals("Увеличь 200 на 10%", "220", "ru");
        equals("Уменьшите 200 тенге на 10 процентов", "180", "ru");
        equals("200 санын 10 пайызға арттыр", "220", "kz");
        equals("200 теңгені 10 пайызға азайтыңыз", "180", "kz");
        check(equals("15% от 200 тенге", "30", "ru").text().contains("30 тенге"), "Explicit amount unit retained");
        check(equals("200 теңгенің 10 пайызын есепте", "20", "kz").text().contains("20 ₸"), "KZ amount unit retained");
        check(equals("Посчитай 2 + 3 кг", "5", "ru").text().contains("5 кг"), "Explicit result unit retained without conversion");

        var discount = equals("Товар стоит 48 000 тенге, скидка 15%. Посчитай размер скидки и итоговую цену, покажи короткий расчёт.", "40800", "ru");
        check(discount.text().contains("скидки — 7200 тенге") && discount.text().contains("цена — 40800 тенге"),
            "A stated discount problem returns both requested amounts and the explicit currency");
        check(discount.formula().contains("48000 × 15 / 100 = 7200") && discount.formula().contains("48000 − 7200 = 40800"),
            "Discount and final price each have a checked calculation");
        equals("Цена покупки: 1\u00a0250,50 руб., скидка 12,5%. Рассчитайте сумму скидки и конечную стоимость, приведите краткий расчет.", "1094.1875", "ru");
        equals("Заказ стоит 250 евро, скидка 100%. Вычисли скидку и цену после скидки.", "0", "ru");
        equals("Стоимость услуги = 350 тенге; скидка 0%. Посчитай размер скидки и итоговую цену.", "350", "ru");

        var completion = equals("24 тапсырманың 18-і орындалды. Орындалу пайызын және қалған тапсырма санын есептеп, қысқа есебін көрсет.", "75", "kz");
        check(completion.text().contains("орындалуы — 75%") && completion.text().contains("қалғаны — 6"),
            "KZ genitive/ordinal suffixes preserve the total and completed roles");
        check(completion.formula().contains("18 / 24 × 100 = 75%") && completion.formula().contains("24 − 18 = 6"),
            "Completion percentage and remaining count each have a checked calculation");
        equals("100 сұрақтың 25-і орындалды. Орындалу пайызын және қалған сұрақ санын есептеңіз.", "25", "kz");
        equals("20 тармақтың 20\u2011ы орындалды. Орындалу пайызын және қалған тармақ санын есепте.", "100", "kz");
        check(equals("Из 40 заданий выполнено 10. Посчитай процент выполнения и количество оставшихся заданий, покажи краткий расчёт.", "25", "ru")
            .text().contains("осталось — 30"), "The analogous Russian count wording retains the remainder");
        check(equals("Из 8 задач завершено 0. Рассчитай процент выполнения и остаток.", "0", "ru")
            .text().contains("осталось — 8"), "Zero completion retains all remaining tasks");

        var completionRounded = TaskArithmetic.solve("3 тапсырманың 1-і орындалды. Орындалу пайызын және қалған тапсырма санын есепте.", "kz").orElseThrow();
        check(completionRounded.rounded() && completionRounded.text().contains("≈") && completionRounded.text().contains("қалғаны — 2"),
            "Repeating completion percentages disclose approximation while keeping the exact remaining count");
        for (String invalid : List.of(
                "Товар стоит 48000 тенге, скидка 150%. Посчитай размер скидки и итоговую цену.",
                "Товар стоит -48000 тенге, скидка 15%. Посчитай размер скидки и итоговую цену.",
                "Товар стоит 48000 тенге, скидка -15%. Посчитай размер скидки и итоговую цену.",
                "Товар стоит 48000 тенге, скидка 15%. Посчитай размер скидки и итоговую цену с налогом 10%.",
                "Товар стоит 48000 или 50000 тенге, скидка 15%. Посчитай размер скидки и итоговую цену.",
                "Товар стоит 48000 тенге, скидка 15%. Посчитай размер скидки и итоговую цену с учётом доставки.",
                "24 тапсырманың 25-і орындалды. Орындалу пайызын және қалған тапсырма санын есепте.",
                "0 тапсырманың 0-і орындалды. Орындалу пайызын және қалған тапсырма санын есепте.",
                "24 тапсырманың 18-і орындалмады. Орындалу пайызын және қалған тапсырма санын есепте.",
                "24 тапсырманың 18,5-і орындалды. Орындалу пайызын және қалған тапсырма санын есепте.",
                "24 тапсырманың 18-і орындалды. Орындалу пайызын және қалған тапсырма санын есепте, тағы 2 тапсырма қос.",
                "Из 40 заданий выполнено 10. Посчитай процент выполнения и остаток по документам.")) reject(invalid);

        var repeating = TaskArithmetic.solve("1 / 3", "ru").orElseThrow();
        check(repeating.rounded(), "Repeating fraction must disclose rounding");
        check(repeating.value().precision() == 34, "Bounded decimal precision");
        check(repeating.text().contains("≈") && repeating.text().contains("34 значащих"), "Approximation visible to user");
        check(!repeating.formula().contains("= ≈"), "Approximate relation shown correctly");
        var kzRepeating = TaskArithmetic.solve("Есепте 1 / 3", "kz").orElseThrow();
        check(kzRepeating.rounded() && kzRepeating.text().contains("дөңгелектелді"), "KZ rounding disclosure");

        for (String invalid : List.of("", "10-20", "2026-10-24", "Посчитай 2026-10-24", "24.10.2026", "1/2/2026", "10%", "15% от",
                "15% от 200 или 300", "200 и 15 процентов", "Увеличь на 10%", "-10% от 200", "200 15 пайыз",
                "Сколько стоит поездка?", "Рассчитай стоимость поездки", "Вычисли среднюю оценку 4 и 5",
                "Какие сроки установлены 10.10.2026?", "Срок 2 + 3 дня обязателен?", "По документам 15% от 200",
                "Посчитай 1/0", "Посчитай 0/0", "Посчитай 1/(2-2)", "2 +", "(2+3", "2+3)", "2(3+4)",
                "Посчитай 2^100", "Посчитай 2 ** 10", "Посчитай 1e30 + 2", "Посчитай 1_000 + 2", "Посчитай NaN",
                "Посчитай 1,2,3 + 4", "Посчитай 1234567890123456789 + 1", "Посчитай 0.1234567890123 + 1",
                "Посчитай 999999999999999999 * 999999999999999999", "Посчитай (((((2+3)))))",
                "Посчитай " + "+".repeat(13) + "1", "1+".repeat(13) + "1", "2+3; напиши правило",
                "2+3<script>alert(1)</script>", "Runtime.getRuntime().exec(2+3)", "2 + 3 RUB и 4 USD", "`2+3`")) reject(invalid);
        reject(null);
        reject("Рассчитай " + "1".repeat(601));
        check(TaskArithmetic.solve("2+3", "en").isEmpty(), "Unsupported language explicit");

        var seed = new Random(20261024L);
        for (int trial = 0; trial < 80; trial++) {
            int a = seed.nextInt(2000) - 1000, b = seed.nextInt(2000) - 1000, c = seed.nextInt(21) - 10;
            equals("Рассчитай (" + a + "+" + b + ")*" + c,
                BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)).multiply(BigDecimal.valueOf(c)).toPlainString(), "ru");
        }
        for (int trial = 0; trial < 30; trial++) {
            int base = seed.nextInt(10000), rate = seed.nextInt(300);
            equals(rate + "% от " + base,
                BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100)).toPlainString(), "ru");
        }
        System.out.println("TaskArithmeticTest PASS " + assertions + " assertions");
    }
}
