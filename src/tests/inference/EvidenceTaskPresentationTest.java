package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.util.*;

/** Tests bounded layout/provenance, not semantic adequacy of a generated grouping. */
public final class EvidenceTaskPresentationTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void rejects(Runnable operation, String why) {
        try { operation.run(); throw new AssertionError(why); }
        catch (Api.ServiceException failure) { check(failure.code.equals("INVALID_TASK_PROPOSAL"), why); }
    }
    private static Api.Fragment source(String digit, String text) {
        return source(digit, text, "Порядок работы");
    }
    private static Api.Fragment source(String digit, String text, String title) {
        return new Api.Fragment(Map.of("fragment_id", digit.repeat(64), "document_id", "same-document",
            "title", title, "text", text, "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original"))));
    }
    private static EvidenceAnswer.Result result(String aText, String bText, boolean complete) {
        var a = source("a", aText); var b = source("b", bText);
        var one = new EvidenceAnswer.Unit("E1", a, List.of("E1"), complete, false, false, "Общий раздел");
        var two = new EvidenceAnswer.Unit("E2", b, List.of("E2"), complete, false, false, "Общий раздел");
        return new EvidenceAnswer.Result("", List.of(a, b), "answer", "none", List.of("E1", "E2"), complete, List.of(one, two),
            List.of(new EvidenceAnswer.Paragraph("P1", aText + " [source:" + a.fragmentId() + "]", List.of(one)),
                new EvidenceAnswer.Paragraph("P2", bText + " [source:" + b.fragmentId() + "]", List.of(two))));
    }
    private static String layout() {
        return Json.stringify(Map.of("groups", List.of(Map.of("paragraphs", List.of("P1")),
            Map.of("paragraphs", List.of("P2")))));
    }
    private static int count(String value, String part) { return (value.length() - value.replace(part, "").length()) / part.length(); }

    public static void main(String[] args) throws Exception {
        String aText = "Работник сообщает решение в течение пяти рабочих дней, если получил уведомление об изменении условий.";
        String bText = "Получатель уведомления о расторжении отвечает в течение трёх рабочих дней письменно. Исключение: иной порядок требует отдельного основания. Если уведомление не получено, этот срок не применяется.";
        var answer = result(aText, bText, true);
        String question = "Составь план, памятку и сопоставление: Изменение и Расторжение.";
        String raw = layout();
        for (String kind : List.of("PLAN", "MEMO", "COMPARE")) {
            var input = Json.object(EvidenceTaskPresentation.prompt(question, kind, answer));
            check(question.equals(input.get("question")), "The task question is preserved as data");
            var items = (List<?>) input.get("paragraphs");
            check(items.size() == 2 && Json.map(items.getFirst()).get("id").equals("P1"), "Only accepted closed paragraphs enter the layout catalog");
            String text = EvidenceTaskPresentation.render(raw, question, kind, answer, false);
            check(count(text, aText) == 1 && count(text, bText) == 1, "Each full condition/exception occurs exactly once in the artifact");
            check(text.contains("[source:" + "a".repeat(64) + "]") && text.contains("[source:" + "b".repeat(64) + "]"), "Both canonical citations survive");
            check(text.contains("Порядок работы") && text.contains("Общий раздел"), "Each paragraph retains document and section scope");
            check(count(text, "### Источники") == 1 && !text.contains("### Условия из документов"), "Append sources once without a duplicate conditions dump");
        }
        String plan = EvidenceTaskPresentation.render(raw, question, "PLAN", answer, false);
        check(plan.contains("- [ ] ") && plan.contains("не устанавливает обязательную последовательность"), "Plan checks are proposed organization, not new statutory chronology");
        String table = EvidenceTaskPresentation.render(raw, question, "COMPARE", answer, false);
        check(table.contains("| Положение 1 | Положение 2 |"), "Same-document and same-section provisions can still be shown side by side");
        check(!table.contains("| Изменение | Расторжение |"), "Query labels cannot misrepresent semantic column assignment as a verified heading");
        check(table.contains("Если уведомление не получено, этот срок не применяется."), "A negative applicability condition cannot be dropped for a shorter table cell");
        for (String frame : List.of("`" + raw + "`", "``" + raw + "``", "```json\n" + raw + "\n```",
                raw + "`", raw + "``", raw + "```", "`" + raw, "```JSON\n" + raw, " \n" + raw + "\t "))
            check(EvidenceTaskPresentation.render(frame, question, "COMPARE", answer, false).equals(table), "Bounded optional Markdown framing changes no canonical content or grouping");
        for (String invalidFrame : List.of("Пояснение: " + raw, raw + "\nПояснение", "```json\n" + raw + "\nПояснение\n```",
                "```json\n" + raw + "\n```\nПояснение", raw + "{}", "```json\n" + raw + "{}\n```",
                "````" + raw + "````", raw + "````", "```javascript\n" + raw + "\n```", "`json " + raw + "`",
                "```json\n" + raw.substring(0, raw.length() - 1) + "\n```", "~" + raw, raw + ";", "```json\n{not-json}\n```"))
            rejects(() -> EvidenceTaskPresentation.render(invalidFrame, question, "COMPARE", answer, false), "Framing never extracts JSON from prose, a second object, partial JSON or other stray characters");
        rejects(() -> EvidenceTaskPresentation.render("```json\n" + raw.replace("P2", "P999") + "\n```", question, "COMPARE", answer, false), "Framing cannot admit an unknown paragraph");
        rejects(() -> EvidenceTaskPresentation.render(raw.replace("P2", "P1") + "`", question, "COMPARE", answer, false), "A trailing tick cannot hide duplicate paragraphs");
        rejects(() -> EvidenceTaskPresentation.render("`{\"groups\":[{\"paragraphs\":[\"P1\"]}]}`", question, "COMPARE", answer, false), "Framing cannot bypass two sides or full condition coverage");
        String kz = EvidenceTaskPresentation.render(raw, question, "MEMO", result(aText, bText, false), true);
        check(kz.startsWith("### Жаднама") && kz.contains("Байланысты шарттардың бір бөлігі табылмады"), "Incomplete closure remains visible after useful content");
        check(kz.contains("Үзінділер дереккөз тілінде") && kz.contains(aText), "Original-language evidence is retained without inventing a translation");
        String memo = EvidenceTaskPresentation.render(raw, question, "MEMO", answer, false);
        check(memo.contains("#### Группа 1: Порядок работы") && memo.contains("#### Группа 2: Порядок работы"), "Repeated canonical memo titles use neutral application numbering, not invented subjects");
        check(plan.contains("#### Группа 1: Порядок работы — Общий раздел") && plan.contains("#### Группа 2: Порядок работы — Общий раздел"), "A common plan section is qualified by its common canonical title, with neutral numbering if repeated");
        check(!EvidenceTaskPresentation.system().contains("\"label\""), "The model schema has no generated group-heading field");
        String inflected = "Сопоставь условия при изменении и при расторжении договора.";
        check(EvidenceTaskPresentation.render(raw, inflected, "COMPARE", answer, false).contains(aText), "Comparison does not depend on reproducing a query inflection as a label");

        var escaped = result("Условие A | B\nВторая строка <br> & запись.", bText, true);
        String escapedTable = EvidenceTaskPresentation.render(raw, question, "COMPARE", escaped, false);
        check(escapedTable.contains("A \\| B<br>Вторая строка &lt;br&gt; &amp; запись."), "Table escapes literal pipes and HTML before adding visible line breaks");
        check(count(escapedTable, "| --- | --- |") == 1, "A source pipe cannot add a column");
        var groupedRaw = Json.stringify(Map.of("groups", List.of(Map.of("paragraphs", List.of("P2", "P1")))));
        String reordered = EvidenceTaskPresentation.render(groupedRaw, question, "PLAN", answer, false);
        check(reordered.indexOf(bText) < reordered.indexOf(aText) && count(reordered, aText) == 1, "Grouping may organize whole paragraphs while preserving all facts");
        var otherSource = source("b", bText, "Другой порядок");
        var otherUnit = new EvidenceAnswer.Unit("E2", otherSource, List.of("E2"), true, false, false, "Общий раздел");
        var mixed = new EvidenceAnswer.Result("", List.of(answer.sources().getFirst(), otherSource), "answer", "none", answer.selectedIds(), true,
            List.of(answer.deliveredUnits().getFirst(), otherUnit), List.of(answer.paragraphs().getFirst(),
                new EvidenceAnswer.Paragraph("P2", answer.paragraphs().get(1).text(), List.of(otherUnit))));
        for (String kind : List.of("PLAN", "MEMO")) {
            String mixedText = EvidenceTaskPresentation.render(groupedRaw, question, kind, mixed, false);
            check(mixedText.contains("#### Группа 1\n\n") && !mixedText.contains("#### Другой порядок"), "A mixed-source group cannot use its first paragraph title as a false umbrella in " + kind);
            check(count(mixedText, aText) == 1 && count(mixedText, bText) == 1 && mixedText.contains("«Порядок работы»") && mixedText.contains("«Другой порядок»"), "Neutral headings preserve every complete paragraph and its own real scope in " + kind);
        }
        String mixedKazakh = EvidenceTaskPresentation.render(groupedRaw, question, "MEMO", mixed, true);
        check(mixedKazakh.contains("#### Топ 1\n\n"), "Mixed Kazakh navigation uses an application label, not a false common source");
        var combinedParagraph = new EvidenceAnswer.Paragraph("P1", mixed.paragraphs().getFirst().text() + "\n\n" + mixed.paragraphs().get(1).text(), mixed.deliveredUnits());
        var combined = new EvidenceAnswer.Result("", mixed.sources(), "answer", "none", mixed.selectedIds(), true, mixed.deliveredUnits(), List.of(combinedParagraph));
        String oneGroup = "{\"groups\":[{\"paragraphs\":[\"P1\"]}]}";
        check(EvidenceTaskPresentation.render(oneGroup, question, "MEMO", combined, false).contains("#### Группа 1\n\n"), "All units inside one paragraph participate in heading commonality, not only its first source");
        var otherSection = new EvidenceAnswer.Unit("E2", answer.sources().get(1), List.of("E2"), true, false, false, "Иной раздел");
        var sectionMixed = new EvidenceAnswer.Result("", answer.sources(), "answer", "none", answer.selectedIds(), true,
            List.of(answer.deliveredUnits().getFirst(), otherSection), List.of(answer.paragraphs().getFirst(),
                new EvidenceAnswer.Paragraph("P2", answer.paragraphs().get(1).text(), List.of(otherSection))));
        String commonTitlePlan = EvidenceTaskPresentation.render(groupedRaw, question, "PLAN", sectionMixed, false);
        check(commonTitlePlan.contains("#### Порядок работы\n\n") && !commonTitlePlan.contains("#### Порядок работы —"), "A plan with mixed sections may assert only the common title");
        check(commonTitlePlan.contains("Общий раздел") && commonTitlePlan.contains("Иной раздел"), "Dropping an unsupported umbrella section does not hide per-paragraph section scope");

        rejects(() -> EvidenceTaskPresentation.render(raw.replace("P2", "P999"), question, "PLAN", answer, false), "Unknown paragraph ID is rejected");
        rejects(() -> EvidenceTaskPresentation.render(raw.replace("P2", "P1"), question, "MEMO", answer, false), "The same closed paragraph cannot appear twice");
        rejects(() -> EvidenceTaskPresentation.render(groupedRaw.replace("\"P2\",", ""), question, "PLAN", answer, false), "Omitting a paragraph cannot drop conditions or exceptions");
        rejects(() -> EvidenceTaskPresentation.render("{\"groups\":[{\"paragraphs\":[]}]}", question, "PLAN", answer, false), "Empty groups are not useful task artifacts");
        rejects(() -> EvidenceTaskPresentation.render("{\"groups\":[]}", question, "MEMO", answer, false), "An empty layout is rejected");
        rejects(() -> EvidenceTaskPresentation.render(raw.substring(0, raw.length() - 1), question, "MEMO", answer, false), "A truncated JSON layout is not completed speculatively");
        rejects(() -> EvidenceTaskPresentation.render(raw.replace("{\"groups\"", "{\"answer\":\"Новый срок 17 дней\",\"groups\""), question, "PLAN", answer, false), "Free-fact root fields cannot be rendered");
        var freeItem = Json.stringify(Map.of("groups", List.of(Map.of("paragraphs", List.of("P1", "P2"), "text", "Новый срок 17 дней"))));
        rejects(() -> EvidenceTaskPresentation.render(freeItem, question, "MEMO", answer, false), "A model cannot add prose or numbers inside a group");
        for (String kind : List.of("PLAN", "MEMO", "COMPARE")) {
            for (String label : List.of("Изменение", "# Заголовок", "[source:fake]", "SOURCE:fake", "<br>", "A|B", "X\nY", "X\u202eY", "&lt;br&gt;")) {
                String extraLabel = Json.stringify(Map.of("groups", List.of(Map.of("label", label, "paragraphs", List.of("P1")), Map.of("paragraphs", List.of("P2")))));
                rejects(() -> EvidenceTaskPresentation.render(extraLabel, question + " " + label, kind, answer, false), "All group labels, including query-bound prose or markup, are forbidden in " + kind);
            }
        }
        check(EvidenceTaskPresentation.render(raw, question, "COMPARE", answer, false).contains(aText), "Same-source comparison does not require invented distinct titles");
        rejects(() -> EvidenceTaskPresentation.render(groupedRaw, question, "COMPARE", answer, false), "Comparison needs two nonempty sides");
        var many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 9; i++) many.add(Map.of("paragraphs", List.of("P1")));
        rejects(() -> EvidenceTaskPresentation.render(Json.stringify(Map.of("groups", many)), question, "MEMO", answer, false), "Group count is capped before rendering");
        rejects(() -> EvidenceTaskPresentation.render(" ".repeat(16_385), question, "PLAN", answer, false), "Raw layout output is bounded");
        rejects(() -> EvidenceTaskPresentation.render(raw, question, "DRAFT_LETTER", answer, false), "This helper cannot replace freeform draft semantics");
        var one = new EvidenceAnswer.Result("", List.of(answer.sources().getFirst()), "answer", "none", List.of("E1"), true,
            List.of(answer.deliveredUnits().getFirst()), List.of(answer.paragraphs().getFirst()));
        rejects(() -> EvidenceTaskPresentation.prompt(question, "COMPARE", one), "One closed paragraph cannot be split into a fake two-sided comparison");
        var refusal = new EvidenceAnswer.Result("Недостаточно", List.of(), "insufficient", "evidence", List.of(), false, List.of(), List.of());
        rejects(() -> EvidenceTaskPresentation.prompt(question, "MEMO", refusal), "A non-answer has no layout model request");
        var dependent = new EvidenceAnswer.Unit("E1", answer.sources().getFirst(), List.of("E1", "E2"), true, false, false, "Общий раздел");
        var partial = new EvidenceAnswer.Result("", answer.sources(), "answer", "none", answer.selectedIds(), true,
            List.of(dependent, answer.deliveredUnits().get(1)),
            List.of(new EvidenceAnswer.Paragraph("P1", answer.paragraphs().getFirst().text(), List.of(dependent)), answer.paragraphs().get(1)));
        rejects(() -> EvidenceTaskPresentation.prompt(question, "COMPARE", partial), "Overlapping bundles cannot split a condition into another column or reorder it away from its duty");
        var huge = result("А".repeat(96_001), bText, true);
        rejects(() -> EvidenceTaskPresentation.prompt(question, "PLAN", huge), "Oversized whole evidence fails rather than losing a condition");
        var hugeTable = result("А".repeat(40_000), "Б".repeat(30_000), true);
        rejects(() -> EvidenceTaskPresentation.prompt(question, "COMPARE", hugeTable), "A table exceeding the native renderer budget fails before the layout call, without truncation");
        if (args.length == 2) replayObservedComparison(java.nio.file.Path.of(args[0]), java.nio.file.Path.of(args[1]));
        System.out.println("EvidenceTaskPresentationTest PASS " + checks + " assertions");
    }

    /** Offline replay of the preserved open-development framing defect; not a new generated answer. */
    private static void replayObservedComparison(java.nio.file.Path answers, java.nio.file.Path receipt) throws Exception {
        Map<String, Object> row = null;
        for (String line : java.nio.file.Files.readAllLines(answers, java.nio.charset.StandardCharsets.UTF_8)) {
            var value = Json.object(line);
            if ("action-dev-v1-04".equals(value.get("id"))) { row = value; break; }
        }
        if (row == null) throw new AssertionError("The preserved comparison case is missing");
        var delivery = Json.map(row.get("evidence_delivery"));
        var sourceMap = new LinkedHashMap<String, Api.Fragment>();
        for (Object item : (List<?>) row.get("history")) {
            var message = Json.map(item);
            if (!"assistant".equals(message.get("role"))) continue;
            for (Object source : (List<?>) message.get("sources")) {
                var fragment = new Api.Fragment(Json.map(source)); sourceMap.put(fragment.fragmentId(), fragment);
            }
        }
        var unitMaps = new LinkedHashMap<String, Map<String, Object>>();
        for (Object item : (List<?>) delivery.get("delivered_units")) {
            var unit = Json.map(item); unitMaps.put((String) unit.get("unit_id"), unit);
        }
        var units = new ArrayList<EvidenceAnswer.Unit>(); var paragraphs = new ArrayList<EvidenceAnswer.Paragraph>();
        for (Object item : (List<?>) delivery.get("canonical_paragraphs")) {
            var paragraph = Json.map(item);
            var members = ((List<?>) paragraph.get("unit_ids")).stream().map(String.class::cast).toList();
            var local = new ArrayList<EvidenceAnswer.Unit>();
            for (String id : members) {
                var original = unitMaps.get(id); var source = sourceMap.get((String) original.get("fragment_id"));
                check(source != null && source.text().equals(original.get("text")), "Offline replay keeps each actual source text, not a substituted fixture");
                var unit = new EvidenceAnswer.Unit(id, source, members, true, false, false, (String) original.get("section"));
                units.add(unit); local.add(unit);
            }
            paragraphs.add(new EvidenceAnswer.Paragraph((String) paragraph.get("id"), (String) paragraph.get("text"), List.copyOf(local)));
        }
        var answer = new EvidenceAnswer.Result((String) delivery.get("canonical_answer"), List.copyOf(sourceMap.values()), "answer", "none",
            ((List<?>) delivery.get("selected_ids")).stream().map(String.class::cast).toList(), true, List.copyOf(units), List.copyOf(paragraphs));
        String marker = "Reference task layout candidate: ", originalRaw = null;
        for (Object diagnostic : (List<?>) row.get("diagnostics")) {
            String text = diagnostic.toString(); int at = text.indexOf(marker);
            if (at >= 0) { originalRaw = text.substring(at + marker.length()); break; }
        }
        if (originalRaw == null) throw new AssertionError("The original proposal was not captured");
        String question = (String) row.get("question");
        check(originalRaw.endsWith("`"), "The preserved actual candidate reproduces the observed trailing Markdown tick");
        String text = EvidenceTaskPresentation.render(originalRaw, question, "COMPARE", answer, true);
        for (var paragraph : paragraphs) {
            String escaped = paragraph.text().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("|", "\\|").replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
            check(count(text, escaped) == 1, "Actual observed comparison paragraph survives once after framing-only normalization");
        }
        check(text.contains("| Ереже 1 | Ереже 2 |"), "The original framed actual proposal produces source-bound application columns");
        var output = new LinkedHashMap<String, Object>();
        output.put("scope", "Offline framing-only replay of preserved v15 action04; no new inference, not task-quality acceptance");
        output.put("input_answers_sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(answers))));
        output.put("result_reconstruction", "Recorded canonical paragraphs, their unit lists and exact source cards; the recorded paragraph membership represents per-unit bundle membership, not a captured live Unit object");
        output.put("observed_v15", "Both original proposals were rejected; framing normalization is an offline formatting check, not a newly accepted generated answer");
        output.put("normalization_scope", "Only optional boundary Markdown backticks/json fence; groups, paragraph IDs/order and canonical content are unchanged");
        output.put("human_review", false); output.put("original_proposal", originalRaw);
        output.put("paragraph_count", paragraphs.size()); output.put("source_count", sourceMap.size()); output.put("rendered_text", text);
        java.nio.file.Files.writeString(receipt, Json.stringify(output) + "\n", java.nio.charset.StandardCharsets.UTF_8);
    }
}
