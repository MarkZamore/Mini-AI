package ai.enterprise.assistant;

import java.util.ArrayList;
import java.util.List;

/** Focused pure unit checks on the full application classpath; no model or composition variants. */
public final class AssistantIntentTest {
    private static int assertions;
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) failures.add(message);
    }

    private static void intent(String query, AssistantIntent.Kind kind, String language) {
        var actual = AssistantIntent.analyze(query);
        check(actual.kind() == kind, query + " => " + actual.kind() + ", expected " + kind);
        check(actual.language().equals(language), query + " => language " + actual.language() + ", expected " + language);
        check(actual.isIdentity() == (kind == AssistantIntent.Kind.IDENTITY), "Identity flag: " + query);
        boolean action = switch (kind) {
            case DRAFT_APPLICATION, DRAFT_LETTER, PLAN, CHECKLIST, MEMO, COMPARE, CALCULATE, GENERIC_TASK -> true;
            default -> false;
        };
        check(actual.isAction() == action, "Action flag: " + query);
    }

    private static void ru(String query, AssistantIntent.Kind kind) { intent(query, kind, "ru"); }
    private static void kz(String query, AssistantIntent.Kind kind) { intent(query, kind, "kz"); }

    public static void main(String[] args) {
        usingInformation();
        usingActions();
        usingKazakh();
        usingQuotationsNegationAndPriorities();
        usingMemosAndColumnRequests();
        usingPolicyAndContract();
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("AssistantIntentTest PASS " + assertions + " assertions");
    }

    private static void usingInformation() {
        ru(null, AssistantIntent.Kind.INFORMATION);
        ru("", AssistantIntent.Kind.INFORMATION);
        ru("Что такое академическая мобильность?", AssistantIntent.Kind.INFORMATION);
        ru("Где хранится договор?", AssistantIntent.Kind.INFORMATION);
        ru("Какие условия участия в программе?", AssistantIntent.Kind.CONDITIONS);
        ru("Можно ли участвовать без языкового сертификата?", AssistantIntent.Kind.CONDITIONS);
        ru("Какие документы нужны, чтобы написать заявление?", AssistantIntent.Kind.CONDITIONS);
        ru("Какие документы нужно подготовить для участия?", AssistantIntent.Kind.CONDITIONS);
        ru("Кто может получить разрешение?", AssistantIntent.Kind.CONDITIONS);
        ru("Как организовано управление рисками?", AssistantIntent.Kind.PROCEDURE);
        ru("Как оформляется заявление?", AssistantIntent.Kind.PROCEDURE);
        ru("Каков порядок прохождения практики?", AssistantIntent.Kind.PROCEDURE);
        ru("Как называется организация, представленная в базе?", AssistantIntent.Kind.IDENTITY);
        ru("Документы какой организации представлены в базе?", AssistantIntent.Kind.IDENTITY);
        ru("Нормативные документы на базе какой организации?", AssistantIntent.Kind.IDENTITY);
        ru("Что за организация представлена в документах?", AssistantIntent.Kind.IDENTITY);
        ru("Что это за организация?", AssistantIntent.Kind.IDENTITY);
        ru("Какое полное наименование учреждения?", AssistantIntent.Kind.IDENTITY);
        ru("Укажи официальное наименование компании", AssistantIntent.Kind.IDENTITY);
        ru("Покажи название предприятия", AssistantIntent.Kind.IDENTITY);
        ru("Как называется университет «Новая вершина»?", AssistantIntent.Kind.IDENTITY);
        ru("Как тебя зовут?", AssistantIntent.Kind.INFORMATION);
        ru("Как называется форма заявления в организации?", AssistantIntent.Kind.INFORMATION);
        ru("Как называется документ университета?", AssistantIntent.Kind.INFORMATION);
        ru("Кто ректор университета?", AssistantIntent.Kind.INFORMATION);
        ru("Какие требования к плану?", AssistantIntent.Kind.CONDITIONS);
        ru("Мне нужно знать требования к заявлению", AssistantIntent.Kind.CONDITIONS);
        ru("Каковы условия и порядок рассмотрения?", AssistantIntent.Kind.CONDITIONS);
        ru("Каков порядок и условия рассмотрения?", AssistantIntent.Kind.PROCEDURE);
    }

    private static void usingActions() {
        ru("Напиши заявление на отпуск", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Составьте заявление о переводе", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Помоги оформить заявление на участие", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Помоги мне написать заявление", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Можешь написать заявление?", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Можешь ли ты написать заявление?", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Не мог бы ты написать заявление?", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Как написать заявление?", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Как мне написать заявление?", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Мне нужен шаблон заявления", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Нужно заявление о приёме", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Напиши официальное письмо партнёру", AssistantIntent.Kind.DRAFT_LETTER);
        ru("Подготовь письмо по вопросу заявления", AssistantIntent.Kind.DRAFT_LETTER);
        ru("Составь план подготовки заявления", AssistantIntent.Kind.PLAN);
        ru("Спланируй подготовку к поездке", AssistantIntent.Kind.PLAN);
        ru("Как мне подать заявление?", AssistantIntent.Kind.PLAN);
        ru("Как мне участвовать в программе?", AssistantIntent.Kind.PLAN);
        ru("Как мне начать?", AssistantIntent.Kind.PLAN);
        ru("Сделай чек-лист перед поездкой", AssistantIntent.Kind.CHECKLIST);
        ru("Перечисли документы для участия", AssistantIntent.Kind.CHECKLIST);
        ru("Сформируй список проверки", AssistantIntent.Kind.CHECKLIST);
        ru("Сравни два заявления", AssistantIntent.Kind.COMPARE);
        ru("Помоги сравнить заявление и письмо", AssistantIntent.Kind.COMPARE);
        ru("Подготовь сравнение двух способов участия", AssistantIntent.Kind.COMPARE);
        ru("Рассчитай расходы на поездку", AssistantIntent.Kind.CALCULATE);
        ru("Посчитай 15 процентов от 24000", AssistantIntent.Kind.CALCULATE);
        ru("Вычисли сумму для заявления", AssistantIntent.Kind.CALCULATE);
        ru("Сделай расчёт суммы", AssistantIntent.Kind.CALCULATE);
        ru("Переведи текст на русский", AssistantIntent.Kind.GENERIC_TASK);
        ru("Сократи этот абзац", AssistantIntent.Kind.GENERIC_TASK);
        ru("Помоги разобраться с источником", AssistantIntent.Kind.GENERIC_TASK);
        ru("Исправь ошибки в тексте", AssistantIntent.Kind.GENERIC_TASK);
        ru("Напиши пояснение", AssistantIntent.Kind.GENERIC_TASK);
        ru("Напиши заявление в организацию «Жаңа қала»", AssistantIntent.Kind.DRAFT_APPLICATION);
    }

    private static void usingKazakh() {
        kz("Академиялық ұтқырлық деген не?", AssistantIntent.Kind.INFORMATION);
        kz("Қатысу шарттары қандай?", AssistantIntent.Kind.CONDITIONS);
        kz("Қандай құжат қажет?", AssistantIntent.Kind.CONDITIONS);
        kz("Өтініш қалай рәсімделеді?", AssistantIntent.Kind.PROCEDURE);
        kz("Құжаттар қандай тәртіппен беріледі?", AssistantIntent.Kind.PROCEDURE);
        kz("Ұйымның толық атауы қандай?", AssistantIntent.Kind.IDENTITY);
        kz("Базадағы ұйым қалай аталады?", AssistantIntent.Kind.IDENTITY);
        kz("Бұл қай мекеменің құжаттары?", AssistantIntent.Kind.IDENTITY);
        kz("Маған өтініш жазып бер", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Өтінішті дайындаңыз", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Өтінішті рәсімдеңіз", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Өтініш рәсімдеуге көмектес", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Өтінішті қалай жазуға болады?", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Өтінішті қалай жазамын?", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Өтінішті қалай жазу керек?", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Маған дайын өтініш керек", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Ресми хат дайындаңыз", AssistantIntent.Kind.DRAFT_LETTER);
        kz("Хатты жазып беріңіз", AssistantIntent.Kind.DRAFT_LETTER);
        kz("Қадамдық жоспар құр", AssistantIntent.Kind.PLAN);
        kz("Жоспар жаса", AssistantIntent.Kind.PLAN);
        kz("Іссапарды жоспарлаңыз", AssistantIntent.Kind.PLAN);
        kz("Маған қалай қатысуға болады?", AssistantIntent.Kind.PLAN);
        kz("Қалай қатысамын?", AssistantIntent.Kind.PLAN);
        kz("Қалай қатысу керек?", AssistantIntent.Kind.PLAN);
        kz("Тексеру тізімін жаса", AssistantIntent.Kind.CHECKLIST);
        kz("Құжаттарды тізіп бер", AssistantIntent.Kind.CHECKLIST);
        kz("Екі нұсқаны салыстыр", AssistantIntent.Kind.COMPARE);
        kz("Салыстыруға көмектес", AssistantIntent.Kind.COMPARE);
        kz("Соманы есепте", AssistantIntent.Kind.CALCULATE);
        kz("15 пайызын санап бер", AssistantIntent.Kind.CALCULATE);
        kz("Мәтінді аудар", AssistantIntent.Kind.GENERIC_TASK);
        kz("Есеп дайында", AssistantIntent.Kind.GENERIC_TASK);
        kz("Хаттама дайында", AssistantIntent.Kind.GENERIC_TASK);
        kz("Маған өтініштің талаптарын түсіндір", AssistantIntent.Kind.CONDITIONS);
    }

    private static void usingQuotationsNegationAndPriorities() {
        ru("Что означает фраза «Напиши заявление»?", AssistantIntent.Kind.INFORMATION);
        ru("Что означает команда \"сравни заявление и письмо\"?", AssistantIntent.Kind.INFORMATION);
        ru("Что значит `рассчитай сумму`?", AssistantIntent.Kind.INFORMATION);
        ru("Что означает текст:\n```\nНапиши заявление\n```", AssistantIntent.Kind.INFORMATION);
        ru("Напиши письмо по тексту «составь заявление»", AssistantIntent.Kind.DRAFT_LETTER);
        ru("Объясни выражение «Напиши заявление", AssistantIntent.Kind.INFORMATION);
        kz("«Өтініш жазып бер» деген не?", AssistantIntent.Kind.INFORMATION);
        ru("Не пиши заявление, объясни условия", AssistantIntent.Kind.CONDITIONS);
        ru("Не нужно писать письмо; какие требования к участию?", AssistantIntent.Kind.CONDITIONS);
        ru("Мне не нужно заявление, объясни условия", AssistantIntent.Kind.CONDITIONS);
        ru("Не хочу письмо; составь план", AssistantIntent.Kind.PLAN);
        ru("Не сравнивай варианты, составь план", AssistantIntent.Kind.PLAN);
        ru("Не только напиши заявление, но и объясни условия", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Маған өтініш жазба, тек шарттарын түсіндір", AssistantIntent.Kind.CONDITIONS);
        kz("Хат дайындамаңыз, талаптарды түсіндіріңіз", AssistantIntent.Kind.CONDITIONS);
        kz("Өтініш жазба, жоспар жаса", AssistantIntent.Kind.PLAN);
        kz("Маған өтініш қажет емес; шарттары қандай?", AssistantIntent.Kind.CONDITIONS);
        ru("Сначала сравни варианты, затем напиши заявление", AssistantIntent.Kind.COMPARE);
        ru("Напиши заявление, затем сравни варианты", AssistantIntent.Kind.DRAFT_APPLICATION);
        ru("Составь план, а затем письмо", AssistantIntent.Kind.PLAN);
        ru("Какие условия участия? Затем составь план", AssistantIntent.Kind.PLAN);
        ru("Напиши заявление и назови организацию", AssistantIntent.Kind.DRAFT_APPLICATION);
        kz("Нұсқаларды салыстыр, содан кейін өтініш жаз", AssistantIntent.Kind.COMPARE);
    }

    private static void usingMemosAndColumnRequests() {
        ru("Подготовь памятку о подключении к сети", AssistantIntent.Kind.MEMO);
        ru("Сделай две отдельные короткие памятки: подготовка собрания и оформление отчёта", AssistantIntent.Kind.MEMO);
        ru("Мне нужна памятка для посетителя", AssistantIntent.Kind.MEMO);
        ru("Помоги составить краткую памятку для нового сотрудника", AssistantIntent.Kind.MEMO);
        kz("Қауіпсіздік туралы қысқа жадынама дайында", AssistantIntent.Kind.MEMO);
        kz("Маған екі бөлек ескертпе жаса", AssistantIntent.Kind.MEMO);
        kz("Жадынама керек", AssistantIntent.Kind.MEMO);
        kz("Ескертпені жазып беріңіз", AssistantIntent.Kind.MEMO);

        ru("Сделай памятку в двух колонках: до поездки и после возвращения", AssistantIntent.Kind.COMPARE);
        ru("Подготовь краткую памятку с двумя столбцами: очная и дистанционная подача", AssistantIntent.Kind.COMPARE);
        ru("Нужна памятка в 2 столбцах о двух вариантах участия", AssistantIntent.Kind.COMPARE);
        ru("Создай таблицу в двух колонках: первый вариант и второй вариант", AssistantIntent.Kind.COMPARE);
        kz("Екі бағанды жадынама жаса: сабақта және емтиханда", AssistantIntent.Kind.COMPARE);
        kz("Ескертпені екі бағанға бөліп дайында", AssistantIntent.Kind.COMPARE);
        kz("2 бағаннан тұратын кесте жаса: бірінші және екінші нұсқа", AssistantIntent.Kind.COMPARE);

        ru("Что написано в памятке для посетителя?", AssistantIntent.Kind.INFORMATION);
        ru("Что означает таблица в двух колонках?", AssistantIntent.Kind.INFORMATION);
        ru("Есть ли в документе таблица с двумя столбцами?", AssistantIntent.Kind.INFORMATION);
        kz("Екі бағанды кестеде не жазылған?", AssistantIntent.Kind.INFORMATION);
        kz("Жадынама деген не?", AssistantIntent.Kind.INFORMATION);
        ru("Что значит «Сделай памятку в двух колонках»?", AssistantIntent.Kind.INFORMATION);
        kz("«Екі бағанды жадынама жаса» деген не?", AssistantIntent.Kind.INFORMATION);

        ru("Не делай таблицу в двух колонках, напиши памятку", AssistantIntent.Kind.MEMO);
        ru("Сделай памятку, но не в двух колонках", AssistantIntent.Kind.MEMO);
        ru("Сделай памятку без таблицы и без двух колонок", AssistantIntent.Kind.MEMO);
        ru("Не нужна памятка в двух колонках; где лежит документ?", AssistantIntent.Kind.INFORMATION);
        kz("Екі бағанды кесте жасама, жадынама дайында", AssistantIntent.Kind.MEMO);
        kz("Жадынама жаса, екі бағанды кесте керек емес", AssistantIntent.Kind.MEMO);
        kz("Екі бағанды жадынама қажет емес; құжат қайда?", AssistantIntent.Kind.INFORMATION);
        ru("Сначала напиши письмо, затем сделай памятку в двух колонках", AssistantIntent.Kind.DRAFT_LETTER);
        ru("Сравни варианты без таблицы", AssistantIntent.Kind.COMPARE);
    }

    private static void usingPolicyAndContract() {
        for (String query : List.of("Напиши заявление", "Напиши письмо", "Составь план", "Сделай чек-лист",
                "Сделай памятку", "Сравни варианты", "Рассчитай сумму", "Переведи текст")) {
            String policy = AssistantIntent.analyze(query).responseInstruction();
            check(policy.contains("Начни") || policy.contains("начни"), "Action begins with useful result: " + query);
            check(policy.contains("[поля для заполнения]"), "No invented personal metadata: " + query);
            check(policy.contains("условия, сроки и исключения") && policy.contains("со ссылками"), "Full source conditions: " + query);
            check(policy.contains("рекомендации отдели") && policy.contains("необязательными"), "Advice separated: " + query);
            check(policy.contains("Без подтверждённого результата инструмента не утверждай"), "No fabricated external actions: " + query);
        }
        String kzPolicy = AssistantIntent.analyze("Өтініш жазып бер").responseInstruction();
        check(kzPolicy.startsWith("Жауапты") && kzPolicy.contains("дайын өтініш"), "KZ useful draft first");
        check(kzPolicy.contains("[толтырылатын өрістер]") && kzPolicy.contains("ерекшеліктерді"), "KZ complete conditions and placeholders");
        check(kzPolicy.contains("Расталған құрал нәтижесі болмаса"), "KZ no fabricated tool completion");
        check(!AssistantIntent.analyze("Какие условия участия?").responseInstruction().contains("готового"), "Information does not demand invented draft");
        boolean invalid = false;
        try { new AssistantIntent(AssistantIntent.Kind.PLAN, false, false, "ru"); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "Kind and flags cannot contradict each other");
        invalid = false;
        try { new AssistantIntent(AssistantIntent.Kind.INFORMATION, false, false, "xx"); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "Language contract is explicit");
    }
}
