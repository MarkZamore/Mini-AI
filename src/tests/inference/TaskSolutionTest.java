package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.util.*;

/** Tests real task-output safety boundaries; semantic acceptance remains a separate source review. */
public final class TaskSolutionTest {
    private static int checks;
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static String reject(String text, List<Api.Fragment> sources, String message) {
        return rejectFor(text, "Составь заявление", sources, message);
    }
    private static String rejectFor(String text, String question, List<Api.Fragment> sources, String message) {
        try { TaskSolution.validate(text, question, sources, new CitationAliases(sources, sources.isEmpty()));
            throw new AssertionError(message);
        } catch (Api.ServiceException expected) {
            check(Set.of("INVALID_TASK_PROPOSAL", "INVALID_CITATION").contains(expected.code), message);
            return expected.getMessage();
        }
    }
    public static void main(String[] args) {
        var source = new Api.Fragment(Map.of("fragment_id", "a".repeat(64), "document_id", "doc1", "title", "Положение",
            "text", "Работник обязан вернуть средства, если прекращает обучение без уважительной причины.",
            "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original"))));
        var sources = List.of(source);
        String knownFacts = "Составь ответное письмо: я получил уведомление 24 октября; моё решение пока неизвестно.";
        for (String kind : List.of("DRAFT_APPLICATION", "DRAFT_LETTER")) {
            var artifactPrompt = Json.object(TaskSolution.prompt(knownFacts, kind, sources, new CitationAliases(sources, false)));
            check(knownFacts.equals(artifactPrompt.get("question")), "The artifact receives all user facts and explicit unknowns unchanged");
            check(List.of(source.title()).equals(artifactPrompt.get("source_titles")), "The artifact retains source-title context");
            check(!artifactPrompt.containsKey("evidence") && !artifactPrompt.toString().contains(source.text()),
                "Separate normative bodies cannot distract an artifact-only application or letter");
        }
        for (String kind : List.of("PLAN", "CHECKLIST", "COMPARE")) {
            var evidencePrompt = Json.object(TaskSolution.prompt(knownFacts, kind, sources, new CitationAliases(sources, false)));
            var suppliedEvidence = (List<?>) evidencePrompt.get("evidence");
            check(suppliedEvidence.size() == 1 && source.text().equals(((Map<?, ?>) suppliedEvidence.getFirst()).get("text")),
                "Evidence-dependent tasks retain the whole source clause, including its condition");
        }
        var artifactOrder = Json.object(TaskSolution.prompt(knownFacts, "DRAFT_APPLICATION", sources, new CitationAliases(sources, false)));
        var evidenceOrder = Json.object(TaskSolution.prompt(knownFacts, "PLAN", sources, new CitationAliases(sources, false)));
        var itemOrder = Json.map(((List<?>) evidenceOrder.get("evidence")).getFirst());
        check(List.copyOf(artifactOrder.keySet()).equals(List.of("question", "task_kind", "source_titles", "note"))
            && List.copyOf(evidenceOrder.keySet()).equals(List.of("question", "task_kind", "evidence", "note"))
            && List.copyOf(itemOrder.keySet()).equals(List.of("fragment_id", "title", "text")),
            "Both task prompt modes and evidence items serialize in a fixed order for reproducible model inputs");
        String draft = "[Адресату]\nот [ФИО]\n\nЗаявление\n\nПрошу рассмотреть мою кандидатуру на участие в программе [программа].\n\n[Дата] [Подпись]";
        check(TaskSolution.validate(draft, "Составь заявление", sources, new CitationAliases(sources, false)).contains("Прошу"), "Useful first-person draft with unknown fields is retained");
        check(TaskSolution.validate(draft + "\n\nЗаполните поля [адресат], [ФИО] и [дата].", "Составь заявление",
            sources, new CitationAliases(sources, false)).endsWith("[дата]."), "A concrete fill-in suggestion does not add a normative duty");
        String letter = "[адресат]\n\nМен [хабарламаның берілген күні] күні хабарлама алдым.\n\nМенің шешімім: [шешімім].\n\n[аты-жөнім] [қолым]";
        check(TaskSolution.validate(letter, "Қазақша жауап хат дайында; шешімімді әлі білмеймін.", sources,
            new CitationAliases(sources, false)).contains("[шешімім]"), "An explicit unknown substantive decision is allowed in a supplied editable letter");
        String incident = "Напиши руководителю уведомление об утрате служебного удостоверения. Дата и ФИО пока неизвестны.";
        String request = "Напиши письмо с просьбой согласовать выбранный график. Мой график пока не указан.";
        String response = "Я получил уведомление от работодателя. Подготовь ответ с моим решением: я согласен.";
        for (String purpose : List.of(incident, request, response, "Қызметтік құжат жоғалғанын басшыға хабарлайтын хат дайында.")) {
            var input = Json.object(TaskSolution.prompt(purpose, "DRAFT_LETTER", sources, new CitationAliases(sources, false)));
            check(purpose.equals(input.get("question")), "Notification, request and reply retain the exact user's communicative purpose and known roles");
            check(!input.toString().contains("[моё решение]"), "The letter input does not insert an unrelated unknown-decision placeholder");
        }
        check(!TaskSolution.system().contains("[моё решение]") && !TaskSolution.instruction("DRAFT_LETTER").contains("[моё решение]"),
            "A literal Russian unknown decision is not forced into every letter or language");
        String notice = "[адресат]\n\nСообщаю об утрате служебного удостоверения.\n\n[дата] [ФИО]";
        check(TaskSolution.validate(notice, incident, sources, new CitationAliases(sources, false)).equals(notice), "A concrete notice needs no invented position or request for the addressee's decision");
        String kazakhNotice = "[адресат]\n\nҚызметтік құжат жоғалғанын хабарлаймын.\n\n[күні] [аты-жөні]";
        check(TaskSolution.validate(kazakhNotice, "Қызметтік құжат жоғалғанын басшыға хабарлайтын хат дайында.", sources,
            new CitationAliases(sources, false)).equals(kazakhNotice), "A Kazakh factual notification does not require a decision placeholder");
        for (String layout : List.of("1. Текст\n2) Продолжение", "**1.** Первый раздел\n\n**2.** Второй раздел", "### **1.**\n\nТекст", "- **1.** Пункт", "1.\nТекст"))
            check(TaskSolution.validate(layout, "Подготовь текст", sources, new CitationAliases(sources, false)).equals(layout), "Markdown or list counters are retained but not treated as new facts: " + layout);
        for (String fields : List.of("[Ұсыныс1]\n[Ұсыныс2]", "[дата начала периода1] — [дата окончания периода1]", "[ФИО1] [ФИО2]", "**1.** [Ұсыныс1]"))
            check(TaskSolution.validate(fields, "Подготовь текст с полями", sources, new CitationAliases(sources, false)).equals(fields), "Named field ordinals are editable layout, not numeric assertions: " + fields);
        for (String number : List.of("Ответить за **17** дней.", "**1.** Ответить за 17 дней.", "[17]", "[17 дней]", "[Срок17дней]", "[Жауап17күн]", "2027. Новый срок"))
            reject(number, sources, "A factual new number or duration is not hidden by markup or brackets: " + number);
        reject("**1.** В течение пяти дней требуется подать заявление.", sources, "Ignoring layout counters does not ignore a spelled-out deadline or duty");
        reject("Работник не обязан вернуть средства [source:1]", sources, "A valid citation cannot reverse a duty");
        reject("Работник обязан вернуть средства [source:1]", sources, "A partial quote cannot drop the antecedent");
        reject("Пользователь обязан обратиться к заместителю ректора.", sources, "A new uncited duty or actor is not accepted");
        reject("В течение пяти дней требуется подать заявление.", sources, "A spelled-out invented deadline is rejected too");
        check(reject("Необходимо заполнить заявление.", sources, "An uncited duty is still rejected")
            .contains("«Необходимо»"), "The existing retry receives the actual Russian marker without copying an untrusted paragraph");
        check(reject("Өтінішті жіберу тиіс.", sources, "An uncited Kazakh duty is still rejected")
            .contains("«тиіс»"), "The retry also identifies the actual Kazakh marker");
        check(reject("You must send the application.", sources, "An uncited English duty is still rejected")
            .contains("«must»"), "The retry identifies the actual English marker");
        reject("Подайте заявление за 17 дней.", sources, "Unknown numeric deadline is rejected");
        for (String falseAction : List.of("Я создал файл заявления.", "Файл сохранён.", "Я отправила письмо.", "Документ успешно зарегистрирован.", "I have submitted the application."))
            reject(falseAction, sources, "No fictitious external completion: " + falseAction);
        String canonical = source.text() + " [source:1]";
        check(TaskSolution.validate(canonical, "Составь заявление", sources, new CitationAliases(sources, false)).contains("если прекращает"), "A whole exact quoted clause keeps its condition");
        reject(source.text() + " [source:2]", sources, "Unknown evidence is rejected");
        boundedPersonalFactChecks(sources);
        System.out.println("TaskSolutionTest PASS " + checks + " assertions");
    }

    private static void boundedPersonalFactChecks(List<Api.Fragment> sources) {
        String unknownDecisionRu = "Подготовь ответ. Моё решение пока неизвестно, дату ответа оставь полем.";
        String unknownDecisionKz = "Жауап хат дайында. Менің ұстанымым әлі анықталмады.";
        for (String text : List.of("Моя позиция: [решение].", "Моё решение: [согласен / отказ]."))
            check(TaskSolution.validate(text, unknownDecisionRu, sources, new CitationAliases(sources, false)).equals(text), "An unknown own decision is fully editable, including mutually exclusive alternatives inside its field");
        for (String text : List.of("Менің ұстанымым: [ұстанымым].", "Менің шешімім: [келісемін / келіспеймін]."))
            check(TaskSolution.validate(text, unknownDecisionKz, sources, new CitationAliases(sources, false)).equals(text), "Unknown Kazakh decision fields use the response language");
        for (String text : List.of("Я согласна с предложением. Моя позиция: [решение].", "Соглашаюсь. [данные]", "Отказываюсь от предложения. [решение]", "Моя позиция: [данные]."))
            rejectFor(text, unknownDecisionRu, sources, "An unrelated or nearby field cannot authorize selecting an unknown own decision: " + text);
        for (String text : List.of("Ұсынысқа келісемін. Менің шешімім: [шешімім].", "Мен бас тартамын. [ұстанымым]", "Менің шешімім: [моё решение]."))
            rejectFor(text, unknownDecisionKz, sources, "A chosen stance or wrong-language field cannot replace the unknown Kazakh decision: " + text);
        check(TaskSolution.validate("Я согласен с предложением.", "Напиши ответ: я согласен с предложением. Дата пока неизвестна.", sources,
            new CitationAliases(sources, false)).equals("Я согласен с предложением."), "An unrelated unknown date does not force a field in place of a known own decision");
        check(TaskSolution.validate("Ұсынысқа келіспеймін.", "Жауап дайында: мен ұсынысқа келіспеймін. Күні белгісіз.", sources,
            new CitationAliases(sources, false)).equals("Ұсынысқа келіспеймін."), "A known Kazakh refusal does not become an unknown decision");

        for (String fact : List.of("Соглашение уже одобрено.", "Изменения внесены.", "Договор подписан.", "Мен шартқа қол қойдым.", "Өзгерістер енгізілді.", "Шешім бекітілді.")) {
            rejectFor(fact, "Подготовь проект с неизвестными реквизитами.", sources, "A draft cannot assert an unprovided completed personal event: " + fact);
            String prompt = "Подготовь письмо. " + fact;
            check(TaskSolution.validate(fact, prompt, sources, new CitationAliases(sources, false)).equals(fact), "A whole positive declarative user fact is retained without guessing a paraphrase: " + fact);
        }
        for (String nonfact : List.of("Не пиши, что договор подписан.", "Если договор подписан, подготовь письмо.", "Пример: «Договор подписан.»", "Договор не подписан.", "Напиши, что договор подписан.", "Договор подписан?", "Когда договор будет подписан, подготовь письмо."))
            rejectFor("Договор подписан.", nonfact, sources, "Negation, request, quoted example, question or condition does not establish a completed user event: " + nonfact);
        for (String nonfact : List.of("Егер шартқа қол қойылған болса, хат дайында.", "Мысал: «Шартқа қол қойылды.»", "Шартқа қол қойылған жоқ.", "Шартқа қол қойылды?", "Хатта шартқа қол қойылды деп жазыңыз."))
            rejectFor("Шартқа қол қойылды.", nonfact, sources, "Kazakh nonfactual/negated/requested events cannot justify a completed event: " + nonfact);
        var eventSource = new Api.Fragment(Map.of("fragment_id", "b".repeat(64), "document_id", "doc-event", "title", "Образец", "text", "Договор подписан.", "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original"))));
        rejectFor("Договор подписан.", "Подготовь проект с полями.", List.of(eventSource), "A source statement is not evidence of a personal completed event");
        rejectFor("Договор подписан [дата].", "Подготовь проект; дата неизвестна.", sources, "A date field does not establish an otherwise unknown completed event");
        check(TaskSolution.validate("Договор подписан [дата].", "Договор подписан. Дату оставь полем.", sources,
            new CitationAliases(sources, false)).equals("Договор подписан [дата]."), "An established completed event may retain an explicitly unknown date as a field");
        rejectFor("Изменения уже внесены.", "Изменения внесены.", sources, "An unproved positive paraphrase deliberately fails closed; the guard is not semantic entailment");

        String unknownResultsRu = "Подготовь отчёт. Результаты и бюджет пока неизвестны.";
        String unknownResultsKz = "Есеп жобасын дайында. Нақты нәтижелер мен бюджет әзірге жоқ.";
        for (String claim : List.of("Дефицит ресурсов замедляет [процесс].", "Эффективность снизилась на [величина].", "Выявлены [проблемы].", "[данные] повышает эффективность.", "Дефицит замедляет работу, но может измениться."))
            rejectFor(claim, unknownResultsRu, sources, "Generic fields or an unrelated modal do not establish unknown factual results: " + claim);
        for (String claim : List.of("Ресурс тапшылығы [үдеріс] жұмысын баяулатады.", "[дерек] тиімділікті төмендетеді.", "[дерек] мәселелері анықталды."))
            rejectFor(claim, unknownResultsKz, sources, "Unknown Kazakh outcomes stay fields rather than asserted causal assessments: " + claim);
        for (String fillable : List.of("Оценка: [оценка].\nПричины: [подтверждённые причины].", "План: [мероприятие].\nРезультат: [фактический результат].", "Ожидаемый эффект: [мероприятие] повысит [показатель]."))
            check(TaskSolution.validate(fillable, unknownResultsRu, sources, new CitationAliases(sources, false)).equals(fillable), "Neutral factual fields and explicitly labeled proposed effects remain usable");
        for (String fillable : List.of("Тиімділік бағасы: [баға].\nСебептері: [расталған себептер].", "Күтілетін әсер: [шара] [көрсеткіш] мәнін арттырады."))
            check(TaskSolution.validate(fillable, unknownResultsKz, sources, new CitationAliases(sources, false)).equals(fillable), "Kazakh neutral fields and explicitly labeled expected effect are retained");
        String knownEffect = "Дефицит ресурсов замедляет поставки.";
        check(TaskSolution.validate(knownEffect, unknownResultsRu + " " + knownEffect, sources, new CitationAliases(sources, false)).equals(knownEffect), "An exact independent positive user fact is not erased by unrelated unknown results");
        check(TaskSolution.title("GENERIC_TASK", false).equals("Проект решения") && TaskSolution.title("GENERIC_TASK", true).equals("Шешім жобасы"), "Generic drafts have neutral headings rather than falsely promising an action plan");
        check(TaskSolution.title("PLAN", false).equals("Предлагаемый план действий") && TaskSolution.title("CHECKLIST", true).equals("Ұсынылатын іс-қимыл жоспары"), "Explicit plan/checklist headings retain their intended type");
    }
}
