package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.util.*;

/** Checks lossless source text, qualifiers and exact locators in the model-facing projection. */
public final class PromptSourcesTest {
    private static int assertions;

    public static void main(String[] args) {
        String fid = "a".repeat(64), hash = "b".repeat(64);
        String text = "Полный исходный текст: GPA не ниже 2,67; срок подачи за два месяца.\n".repeat(3);
        Map<String,Object> warning = Map.of("code", "OCR_UNVERIFIED", "message", "Нужна сверка",
            "details", Map.of("method", "ocr", "language", "rus"));
        Map<String,Object> differentMessage = Map.of("code", "OCR_UNVERIFIED", "message", "Сверить номер пункта",
            "details", Map.of("method", "ocr", "language", "rus"));
        Map<String,Object> differentDetails = Map.of("code", "OCR_UNVERIFIED", "message", "Нужна сверка",
            "details", Map.of("method", "ocr", "language", "kaz"));
        Map<String,Object> firstRef = Map.of("artifact_id", "original", "kind", "pdf_region", "page", 5,
            "source_sha256", hash, "bbox", List.of(10, 20, 100, 35), "page_size", List.of(600, 800));
        Map<String,Object> secondRef = Map.of("artifact_id", "original", "kind", "pdf_region", "page", 5,
            "source_sha256", hash, "bbox", List.of(10, 36, 100, 51), "page_size", List.of(600, 800));
        Map<String,Object> nextPage = Map.of("artifact_id", "original", "kind", "pdf_region", "page", 6,
            "source_sha256", hash, "bbox", List.of(10, 20, 100, 35), "page_size", List.of(600, 800));
        Map<String,Object> quality = Map.of("method", "ocr", "verification", "machine_extracted",
            "needs_review", true, "confidence", 0.78, "partial", true,
            "warnings", List.of(warning, warning, differentMessage));
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("fragment_id", fid); fields.put("document_id", "document-1");
        fields.put("title", "Положение"); fields.put("text", text); fields.put("source_sha256", hash);
        fields.put("quality", quality); fields.put("warnings", List.of(warning, differentDetails, differentMessage));
        fields.put("source_refs", List.of(firstRef, secondRef, nextPage));
        fields.put("block_id", "block-1");
        fields.put("span", Map.of("presentation", "joined_source_lines", "block_spans", List.of(
            Map.of("block_id", "line-1", "char_start", 0, "char_end", 40),
            Map.of("block_id", "line-2", "char_start", 41, "char_end", text.length()))));
        Api.Fragment fragment = new Api.Fragment(fields);
        String canonicalBefore = Json.stringify(fragment.fields());
        List<Map<String,Object>> projected = PromptSources.project(List.of(fragment));
        check(projected.size() == 1, "Source fragment count is preserved");
        Map<String,Object> item = projected.getFirst();
        for (String key : List.of("fragment_id", "document_id", "title", "text", "source_sha256"))
            check(Objects.equals(item.get(key), fragment.fields().get(key)), "Exact evidence field preserved: " + key);
        check(!item.containsKey("block_id") && !item.containsKey("span"), "Prompt omits internal chunk structure");
        Map<?,?> qualifiers = (Map<?,?>) item.get("quality");
        Map<String,Object> expectedQuality = new LinkedHashMap<>(quality); expectedQuality.remove("warnings");
        check(qualifiers.equals(expectedQuality), "Every quality qualifier except repeated warning list is preserved");
        check(item.get("warnings").equals(List.of(warning, differentMessage, differentDetails)),
            "Warnings deduplicate by complete entry and preserve different message/details with identical codes");
        check(item.get("source_refs").equals(List.of(
            Map.of("artifact_id", "original", "kind", "pdf_region", "page", 5),
            Map.of("artifact_id", "original", "kind", "pdf_region", "page", 6))),
            "Joined PDF lines collapse only to the same exact page locator");
        check(Json.stringify(item).length() < canonicalBefore.length(), "Projection reduces model payload");
        check(Json.stringify(fragment.fields()).equals(canonicalBefore), "Canonical fragment remains byte-for-byte unchanged");
        check(fragment.sourceRefs().size() == 3 && fragment.fields().containsKey("span"), "Application retains geometry and spans");

        Map<String,Object> sheet = Map.of("artifact_id", "original", "kind", "spreadsheet_range",
            "sheet", "Студенты", "range", "B4:D8", "row", 4, "source_sha256", hash,
            "qualifier", "cached_formula_result");
        Map<String,Object> word = Map.of("artifact_id", "original", "kind", "ooxml_node",
            "part", "word/document.xml", "xpath", "/w:document/w:body/w:p[12]", "source_sha256", hash);
        Api.Fragment office = new Api.Fragment(Map.of("fragment_id", "c".repeat(64), "text", "=A1+B1",
            "source_refs", List.of(sheet, word)));
        Map<String,Object> officeProjected = PromptSources.project(List.of(office)).getFirst();
        List<?> officeRefs = (List<?>) officeProjected.get("source_refs");
        Map<String,Object> expectedSheet = new LinkedHashMap<>(sheet); expectedSheet.remove("source_sha256");
        Map<String,Object> expectedWord = new LinkedHashMap<>(word); expectedWord.remove("source_sha256");
        check(officeRefs.equals(List.of(expectedSheet, expectedWord)), "Spreadsheet ranges, sheet and Word part/xpath stay exact");
        check(officeProjected.get("text").equals("=A1+B1"), "Office source text is not rewritten");
        check(PromptSources.project(List.of(fragment, office)).size() == 2, "No fragments are removed");
        check(PromptSources.project(List.of()).isEmpty(), "Empty input remains empty");
        Map<String,Object> minimal = PromptSources.project(List.of(new Api.Fragment(Map.of("fragment_id", fid)))).getFirst();
        check(minimal.equals(Map.of("fragment_id", fid)), "Minimal evidence stays minimal; source refs are never fabricated");

        Map<String,Object> document = new LinkedHashMap<>();
        document.put("document_id", "document-1"); document.put("status", "partial");
        document.put("extra", Map.of("requires_review", true));
        document.put("warnings", new ArrayList<>(List.of(warning, warning, differentMessage, differentDetails)));
        String documentBefore = Json.stringify(document);
        Map<String,Object> compactDocument = PromptSources.documentWarnings(List.of(document)).getFirst();
        check(compactDocument.get("warnings").equals(List.of(warning, differentMessage, differentDetails)),
            "Document warnings deduplicate complete entries only");
        check(compactDocument.get("status").equals("partial") && compactDocument.get("extra").equals(document.get("extra")),
            "Document status and other restrictions remain unchanged");
        check(Json.stringify(document).equals(documentBefore), "Document warning input is not mutated");
        check(PromptSources.documentWarnings(List.of(Map.of("status", "complete"))).getFirst().equals(Map.of("status", "complete")),
            "Absent document warning list is not invented");
        System.out.println("Prompt source projection checks passed: " + assertions);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
