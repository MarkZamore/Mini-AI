package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.*;

/** Source-claim invariants; no alternate application composition or model wording mocks. */
public final class EvidenceAnswerTest {
    private static int checks;
    private static void check(boolean condition, String why) { checks++; if (!condition) throw new AssertionError(why); }
    private static Api.Fragment fragment(String id, String doc, String text, List<String> bundle) {
        return new Api.Fragment(Map.of("fragment_id", id.repeat(64), "document_id", doc, "title", "Правила организации «Альфа»",
            "text", text, "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original", "source_sha256", "d".repeat(64))),
            "evidence_bundle_ids", bundle.stream().map(value -> value.repeat(64)).toList(), "evidence_context_complete", true));
    }
    private static void rejects(Runnable operation, String why) {
        try { operation.run(); throw new AssertionError(why); }
        catch (Api.ServiceException expected) { check(expected.code.equals("INVALID_EVIDENCE_PLAN"), why); }
    }
    private static Api.Fragment with(Api.Fragment source, String key, Object value) {
        var fields = new LinkedHashMap<>(source.fields()); fields.put(key, value);
        return new Api.Fragment(fields);
    }

    private static void canonicalIdentity() {
        String exact = "Некоммерческое акционерное общество «Северный технический институт»";
        var body = fragment("f", "identity-one", "Документ утверждён. " + exact + ".", List.of("f"));
        body = with(body, "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original",
            "source_sha256", "d".repeat(64), "part", "word/header2.xml", "xpath", "/w:hdr/w:p[1]")));
        var bound = with(body, "evidence_identity_body_span", exact);
        bound = with(bound, "quality", Map.of("method", "native", "needs_review", false,
            "verification", "machine_extracted"));
        var catalog = new EvidenceAnswer.Catalog(List.of(bound));
        var direct = catalog.identityFromCanonical(false).orElseThrow();
        check(direct.text().startsWith("В базе представлены документы организации: " + exact), "A verified native body name is returned directly, not a meta-answer");
        check(direct.text().contains("[source:" + "f".repeat(64) + "]"), "Deterministic identity keeps the canonical citation");
        check(direct.sources().equals(List.of(bound)) && direct.deliveredUnits().size() == 1, "The exact native source is available for opening");
        check(direct.selectedIds().equals(List.of("E1")) && direct.completeEvidence(), "Identity uses only the first ranked bound source");
        check(catalog.identityFromCanonical(true).orElseThrow().text().startsWith("Базада ұсынылған ұйым: " + exact), "KZ framing preserves the source name without translating it");
        String plan = "{\"mode\":\"answer\",\"answer\":\"" + exact + "\",\"brief\":[\"E1\"],\"details\":[],\"missing\":\"none\"}";
        check(catalog.accept(plan, true, false).equals(direct), "The shortcut reuses the existing identity acceptance contract");
        check(new EvidenceAnswer.Catalog(List.of()).identityFromCanonical(false).isEmpty(), "An empty catalog has no deterministic identity");
        check(new EvidenceAnswer.Catalog(List.of(body)).identityFromCanonical(false).isEmpty(), "A name present in text without a search-bound hint is not guessed");
        var injected = with(bound, "evidence_identity_body_span", "Некоммерческое общество «Подставная компания»");
        check(new EvidenceAnswer.Catalog(List.of(injected)).identityFromCanonical(false).isEmpty(), "An injected metadata span absent from canonical body is rejected");
        var titleOnly = with(with(bound, "text", "Утверждён порядок рассмотрения документов."), "title", exact);
        check(new EvidenceAnswer.Catalog(List.of(titleOnly)).identityFromCanonical(false).isEmpty(), "A matching title cannot substitute for body evidence");
        var insideWord = with(with(bound, "text", "Х" + exact + "Я"), "title", exact);
        check(new EvidenceAnswer.Catalog(List.of(insideWord)).identityFromCanonical(false).isEmpty(), "A title boundary cannot rescue an embedded body substring");
        check(new EvidenceAnswer.Catalog(List.of(with(bound, "evidence_identity_body_span", ""))).identityFromCanonical(false).isEmpty(), "An empty bound name falls back");
        check(new EvidenceAnswer.Catalog(List.of(with(bound, "evidence_identity_body_span", List.of(exact)))).identityFromCanonical(false).isEmpty(), "A non-string bound hint falls back");
        var sentence = with(with(bound, "text", "Университет не обязан предоставлять данные."), "evidence_identity_body_span", "Университет не обязан предоставлять данные");
        check(new EvidenceAnswer.Catalog(List.of(sentence)).identityFromCanonical(false).isEmpty(), "A normative sentence cannot be promoted to an organization identity");
        var ordinaryWords = with(with(bound, "text", "Подтверждающий исходный фрагмент"), "evidence_identity_body_span", "Подтверждающий исходный фрагмент");
        check(new EvidenceAnswer.Catalog(List.of(ordinaryWords)).identityFromCanonical(false).isEmpty(), "A non-entity body span is not an organization name");
        var later = with(fragment("g", "identity-two", "АО «Другой институт»", List.of("g")), "evidence_identity_body_span", "АО «Другой институт»");
        check(new EvidenceAnswer.Catalog(List.of(bound, later)).identityFromCanonical(false).orElseThrow().sources().equals(List.of(bound)), "A lower-ranked organization cannot replace the first trusted name");
        check(new EvidenceAnswer.Catalog(List.of(injected, later)).identityFromCanonical(false).isEmpty(), "An invalid top hint does not silently select a different lower-ranked identity");
        check(new EvidenceAnswer.Catalog(List.of(body, later)).identityFromCanonical(false).isEmpty(), "An absent top hint leaves the existing fallback in charge");
        var review = with(bound, "quality", Map.of("method", "native", "needs_review", true));
        check(new EvidenceAnswer.Catalog(List.of(review)).identityFromCanonical(false).isEmpty(), "A source requiring review cannot take the native shortcut");
        var ocr = with(bound, "quality", Map.of("method", "Tesseract OCR", "needs_review", false));
        check(new EvidenceAnswer.Catalog(List.of(ocr)).identityFromCanonical(false).isEmpty(), "OCR cannot use the native original shortcut");
        var converted = with(bound, "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "sha256:converted", "source_sha256", "d".repeat(64))));
        check(new EvidenceAnswer.Catalog(List.of(converted)).identityFromCanonical(false).isEmpty(), "Derived XML evidence stays on the existing fallback path");
        var mixed = with(bound, "source_refs", List.of(Map.of("kind", "ooxml_node", "artifact_id", "original"), Map.of("kind", "pdf_page", "artifact_id", "original")));
        check(new EvidenceAnswer.Catalog(List.of(mixed)).identityFromCanonical(false).isEmpty(), "Every provenance reference must be original OOXML");
    }

    public static void main(String[] args) {
        canonicalIdentity();
        var name = fragment("a", "d1", "Некоммерческое общество «Альфа»", List.of("a"));
        var identity = new EvidenceAnswer.Catalog(List.of(name));
        String identityPlan = "{\"mode\":\"answer\",\"answer\":\"Некоммерческое общество «Альфа»\",\"brief\":[\"E1\"],\"details\":[],\"missing\":\"none\"}";
        var direct = identity.accept(identityPlan, true, false);
        check(direct.text().startsWith("В базе представлены документы организации: Некоммерческое общество «Альфа»"), "Identity answer begins with the actual value");
        check(!direct.text().contains("фрагмент подтверждает"), "No meta-answer in place of a name");
        check(direct.text().contains("[source:" + "a".repeat(64) + "]"), "Only canonical app-generated provenance");

        var rule = fragment("b", "d2", "Работник не обязан возвращать средства, если обучение отменил работодатель.", List.of("b", "c"));
        var exception = fragment("c", "d2", "Исключение: при подложных документах средства возвращаются в течение пяти рабочих дней.", List.of("b", "c"));
        var catalog = new EvidenceAnswer.Catalog(List.of(rule, exception));
        String rulePlan = "{\"mode\":\"answer\",\"answer\":\"обязан\",\"brief\":[\"E1\"],\"details\":[],\"missing\":\"none\"}";
        var result = catalog.accept(rulePlan, false, false);
        check(result.text().startsWith("Работник не обязан"), "A substring cannot flip a negative claim to a positive claim");
        check(result.text().contains("если обучение отменил работодатель"), "The antecedent is retained");
        check(result.text().contains(exception.text()), "Linked exception cannot be omitted by the selector");
        check(result.sources().size() == 2, "Closure sources remain available to the user");
        check(catalog.accept(rulePlan.replace(",\"missing\":\"none\"", ""), false, false).text().equals(result.text()), "An answer-only optional none field does not alter facts or closure");
        rejects(() -> catalog.accept(rulePlan.replace(",\"missing\":\"none\"", "").replace("\"mode\":\"answer\"", "\"mode\":\"clarify\""), false, false), "The reason for a clarification cannot be inferred from an omitted field");
        check(!catalog.accept(rulePlan.replace("обязан", "средства возвращаются через 99 дней"), false, false).text().contains("99"), "Untrusted selection hint never becomes an asserted deadline");
        rejects(() -> catalog.accept(rulePlan.replace("E1", "E999"), false, false), "Grammar-valid invented ID is rejected");
        check(catalog.accept(rulePlan.replace("[\"E1\"]", "[\"E1\",\"E1\"]"), false, false).selectedIds().size() == 1, "Repeated IDs are canonicalized and cannot inflate coverage");
        check(catalog.accept(rulePlan.replace("\"details\":[]", "\"details\":[\"E1\"]"), false, false).selectedIds().size() == 1, "A brief repeated in details does not lose an otherwise usable answer");
        rejects(() -> catalog.accept(rulePlan.replace("\"details\":[]", "\"details\":[\"E1\",\"E1\",\"E1\",\"E1\",\"E1\"]"), false, false), "Autoregressive ID enumeration loops are rejected rather than rendered as useful evidence");
        rejects(() -> catalog.accept(rulePlan.substring(0, rulePlan.length() - 1), false, false), "Truncation is not accepted as a complete answer");
        rejects(() -> catalog.accept(rulePlan.replace("\"none\"", "\"scope\""), false, false), "Answer and missing-scope modes cannot conflict");
        rejects(() -> catalog.accept(rulePlan.replace("\"mode\":\"answer\"", "\"mode\":\"clarify\""), false, false), "Clarification cannot smuggle an asserted rule");

        var foreign = fragment("c", "different-document", exception.text(), List.of("c"));
        rejects(() -> new EvidenceAnswer.Catalog(List.of(rule, foreign)), "Bundle cannot borrow an exception from another document");
        var missingClosure = new EvidenceAnswer.Catalog(List.of(rule)).accept(rulePlan, false, false);
        check(!missingClosure.completeEvidence(), "Missing linked evidence is not called complete");
        check(missingClosure.text().contains("полный порядок по ней установить нельзя"), "An incomplete dependency closure is disclosed after the useful content");
        String insufficient = "{\"mode\":\"insufficient\",\"answer\":\"\",\"brief\":[],\"details\":[],\"missing\":\"evidence\"}";
        var noAnswer = catalog.accept(insufficient, false, false);
        check(noAnswer.sources().isEmpty() && noAnswer.text().contains("В найденных фрагментах"), "No unsupported corpus-wide absence claim");
        check(!EvidenceAnswer.plain("[source:fake]").contains("[source:"), "Copied document text cannot create an app citation");
        var actorFields = new LinkedHashMap<>(fragment("d", "d3", "154. Руководители подразделений", List.of("d", "e")).fields());
        actorFields.put("evidence_role", "heading");
        var actor = new Api.Fragment(actorFields);
        var action = fragment("e", "d3", "обеспечивают сохранность документов до передачи в архив.", List.of("d", "e"));
        var actorResult = new EvidenceAnswer.Catalog(List.of(actor, action)).accept(rulePlan.replace("E1", "E2"), false, false);
        check(actorResult.text().contains("154. Руководители подразделений обеспечивают"), "A closed PDF actor line survives heading misclassification");
        check(actorResult.deliveredUnits().size() == actorResult.sources().size(), "All closed source members are delivered, including context headings");
        var intro = with(fragment("h", "d4", "Основания прекращения договора:", List.of("h")), "evidence_role", "governing_context");
        var branch = fragment("i", "d4", "1) Сторона направляет уведомление, если выбрано соглашение сторон.", List.of("h", "i"));
        var branches = new EvidenceAnswer.Catalog(List.of(intro, branch));
        rejects(() -> branches.accept(rulePlan, false, false), "A governing introduction alone cannot answer a normative question");
        var branchAnswer = branches.accept(rulePlan.replace("E1", "E2"), false, false);
        check(branchAnswer.text().contains(intro.text()) && branchAnswer.text().contains(branch.text()), "A selected independent branch retains its governing context and condition");
        System.out.println("Evidence answer invariants: " + checks + " passed");
    }
}
