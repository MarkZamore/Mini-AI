package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;

public final class AnswerPresentationTest {
    public static void main(String[] args) {
        String id = "a".repeat(64);
        var source = new Api.Fragment(Map.of("fragment_id", id, "document_id", "doc1", "title", "Положение организации",
            "text", "Работник не обязан возвращать средства, если обучение отменил работодатель.",
            "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original")),
            "evidence_bundle_ids", List.of(id), "evidence_context_complete", true));
        var catalog = new EvidenceAnswer.Catalog(List.of(source));
        var answer = catalog.accept("{\"mode\":\"answer\",\"answer\":\"\",\"brief\":[\"E1\"],\"details\":[],\"missing\":\"none\"}",false,false);
        String shown = AnswerPresentation.format(answer, AssistantIntent.analyze("Обязан ли работник возвращать средства?"));
        if (!shown.startsWith("### Ответ\n\nРаботник не обязан") || !shown.contains("если обучение отменил работодатель")) throw new AssertionError("Direct complete answer first");
        if (!shown.contains("### Источники") || !shown.contains("Положение организации") || !shown.contains("[source:"+id+"]")) throw new AssertionError("Real sources preserved");
        if (shown.contains("Ограничения источников") || shown.contains("machine_extracted")) throw new AssertionError("No generated service text");
        String action = AnswerPresentation.format(answer, AssistantIntent.analyze("Подготовь план действий работника."));
        if (!action.startsWith("### Условия из документов")) throw new AssertionError("Requirements remain separate from the proposed artifact");
        String checklist = AnswerPresentation.checklist(answer, AssistantIntent.analyze("Составь чек-лист работника."));
        if (!checklist.startsWith("### Чек-лист\n\n- [ ] Работник не обязан") || !checklist.contains("если обучение отменил работодатель")
            || !checklist.contains("### Источники")) throw new AssertionError("Checklist retains full conditions and citations without another model call");
        if (checklist.contains("### Условия из документов")) throw new AssertionError("Checklist does not duplicate its source requirements");
        var a = new EvidenceAnswer.Unit("E1", source, List.of("E1"), true, false, false, "Условия возврата");
        var secondFields = new LinkedHashMap<>(source.fields()); secondFields.put("document_id", "doc2"); secondFields.put("title", "Правила другой категории");
        var b = new EvidenceAnswer.Unit("E2", new Api.Fragment(secondFields), List.of("E2"), true, false, false, "Исключения");
        var grouped = new EvidenceAnswer.Result("", List.of(source, b.source()), "answer", "none", List.of("E1", "E2"), true, List.of(a,b),
            List.of(new EvidenceAnswer.Paragraph("P1", source.text(), List.of(a)), new EvidenceAnswer.Paragraph("P2", "Иная категория.", List.of(b)),
                new EvidenceAnswer.Paragraph("P3", "Условия первого документа сохраняются.", List.of(a))));
        String scoped = AnswerPresentation.format(grouped, AssistantIntent.analyze("Какие условия возврата?"));
        if (!scoped.contains("### Правила другой категории\n\n#### Исключения\n\nИная категория.")
            || !scoped.contains("### Положение организации\n\n#### Условия возврата\n\nУсловия первого документа")
            || !scoped.contains("### Ответ\n\n#### Условия возврата\n\n")) throw new AssertionError("Each first/transition section and category scope remain explicit");
        if (scoped.contains("*Раздел:") || scoped.contains("*Область применения:")) throw new AssertionError("Provenance is not repeated as service-labelled paragraphs");
        System.out.println("AnswerPresentationTest PASS 8 presentation contracts; no extra inference");
    }
}
