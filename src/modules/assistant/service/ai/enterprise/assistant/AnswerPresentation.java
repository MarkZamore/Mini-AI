package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;

/** Headings and source lists use verified data only; formatting never invokes a model. */
final class AnswerPresentation {
    private AnswerPresentation() {}

    static String format(EvidenceAnswer.Result answer, AssistantIntent intent) {
        return format(answer, intent, false);
    }

    /** Closed evidence is the checklist itself; no second generation or duplicated rule list. */
    static String checklist(EvidenceAnswer.Result answer, AssistantIntent intent) {
        return format(answer, intent, true);
    }

    private static String format(EvidenceAnswer.Result answer, AssistantIntent intent, boolean checklist) {
        boolean kz = intent.language().equals("kz");
        if (!answer.mode().equals("answer")) return (kz ? "### Нақтылау қажет\n\n" : "### Что уточнить\n\n") + answer.text();
        var text = new StringBuilder(kz ? "### Жауап\n\n" : "### Ответ\n\n");
        if (intent.isIdentity() || answer.paragraphs().isEmpty()) text.append(answer.text());
        else {
            if (checklist) text = new StringBuilder(kz ? "### Чек-парақ\n\n" : "### Чек-лист\n\n");
            else if (intent.isAction()) text = new StringBuilder(kz ? "### Құжаттардағы талаптар\n\n" : "### Условия из документов\n\n");
            boolean multipleDocuments = answer.sources().stream().map(Api.Fragment::documentId).distinct().count() > 1;
            int index = 0;
            String previousSection = "", previousDocument = "";
            for (var paragraph : answer.paragraphs()) {
                var unit = paragraph.units().getFirst();
                boolean newDocument = (multipleDocuments || unit.ambiguous()) && !unit.source().documentId().equals(previousDocument);
                String section = unit.section().strip();
                if (index++ > 0) {
                    text.append("\n\n");
                    if (newDocument) {
                        text.append("### ").append(EvidenceAnswer.plain(unit.source().title())).append("\n\n");
                        if (!section.isEmpty()) text.append("#### ").append(EvidenceAnswer.plain(section)).append("\n\n");
                    }
                    else if (!section.isEmpty() && !section.equals(previousSection))
                        text.append("### ").append(EvidenceAnswer.plain(section)).append("\n\n");
                    else if (index == 2) text.append(detailsTitle(intent, kz)).append("\n\n");
                } else if (!section.isEmpty()) text.append("#### ").append(EvidenceAnswer.plain(section)).append("\n\n");
                // Put the answer before its provenance. Source titles still delimit distinct
                // scopes, but every paragraph no longer repeats a document/section service label.
                text.append(checklist ? "- [ ] " + paragraph.text().replace("\n", "\n  ") : paragraph.text());
                if (index == 1 && (multipleDocuments || unit.ambiguous()))
                    text.append("\n\n*«").append(EvidenceAnswer.plain(unit.source().title())).append("».*");
                previousSection = section;
                previousDocument = unit.source().documentId();
            }
            text.append(evidenceNotes(answer, kz));
        }
        return text.append(sourcesSection(answer.sources(), kz)).toString();
    }

    static String evidenceNotes(EvidenceAnswer.Result answer, boolean kz) {
        var text = new StringBuilder();
        if (!answer.completeEvidence()) text.append(kz
            ? "\n\n### Нақтылау қажет\n\nБайланысты шарттардың бір бөлігі табылмады. Жоғарыдағы мәліметтер толық рәсімді белгілемейді."
            : "\n\n### Что ещё уточнить\n\nЧасть связанных условий не получена. Выше приведена подтверждённая часть; полный порядок по ней установить нельзя.");
        if (kz && answer.paragraphs().stream().anyMatch(paragraph -> paragraph.text().codePoints()
                .noneMatch(c -> "әғқңөұүһіӘҒҚҢӨҰҮҺІ".indexOf(c) >= 0)))
            text.append("\n\n*Үзінділер дереккөз тілінде, аудармасыз келтірілген.*");
        return text.toString();
    }

    static String sourcesSection(List<Api.Fragment> fragments, boolean kz) {
        var text = new StringBuilder();
        var documents = new LinkedHashMap<String, List<Api.Fragment>>();
        for (var source : fragments) documents.computeIfAbsent(source.documentId(), ignored -> new ArrayList<>()).add(source);
        if (!documents.isEmpty()) {
            text.append(kz ? "\n\n### Дереккөздер\n" : "\n\n### Источники\n");
            for (var sources : documents.values()) {
                text.append("\n- «").append(EvidenceAnswer.plain(sources.getFirst().title())).append("» ");
                var seen = new HashSet<String>();
                for (var source : sources) if (seen.add(source.fragmentId())) text.append("[source:").append(source.fragmentId()).append(']');
            }
        }
        return text.toString();
    }

    private static String detailsTitle(AssistantIntent intent, boolean kazakh) {
        return switch (intent.kind()) {
            case CONDITIONS -> kazakh ? "### Шарттар мен шектеулер" : "### Условия и ограничения";
            case PROCEDURE, PLAN, CHECKLIST -> kazakh ? "### Іс-қимыл тәртібі мен шарттары" : "### Порядок действий и условия";
            default -> kazakh ? "### Нені ескеру керек" : "### Что учесть";
        };
    }
}
