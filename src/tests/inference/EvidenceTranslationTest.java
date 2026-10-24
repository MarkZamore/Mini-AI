package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import ai.enterprise.contracts.Json;
import java.util.List;
import java.util.Map;

/** Structural translation-candidate contract only; no model output or semantic quality claim. */
public final class EvidenceTranslationTest {
    private static final String A = "[source:" + "a".repeat(64) + "]", B = "[source:" + "b".repeat(64) + "]";
    private static final List<EvidenceTranslation.Piece> ORIGINAL = List.of(
        new EvidenceTranslation.Piece("P1", "Работник не обязан платить в течение 5 рабочих дней, если договор отменён работодателем. " + A),
        new EvidenceTranslation.Piece("P2", "Оценка «F», поле _____, значения 2.5 и -3; пункт 601-04. " + B));
    private static final String GOOD1 = "Жұмыс беруші шартты жойса, жұмыскер 5 жұмыс күні ішінде төлеуге міндетті емес. " + A;
    private static final String GOOD2 = "«F» бағасы, _____ өрісі, 2.5 және -3 мәндері; 601-04 тармағы. " + B;
    private static int assertions;
    private static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private static String json(String first, String second) {
        return Json.stringify(Map.of("translations", List.of(Map.of("id", "P1", "text", first), Map.of("id", "P2", "text", second))));
    }
    private static void rejected(String raw) {
        try { EvidenceTranslation.validate(raw, ORIGINAL); throw new AssertionError("Invalid translation accepted: " + raw); }
        catch (Api.ServiceException expected) { check(expected.code.equals("INVALID_EVIDENCE_TRANSLATION"), "Stable failure API"); }
    }
    private static void badOriginal(List<EvidenceTranslation.Piece> source) {
        try { EvidenceTranslation.prompt(source); throw new AssertionError("Invalid source pieces accepted"); }
        catch (Api.ServiceException expected) { check(expected.code.equals("INVALID_EVIDENCE_TRANSLATION"), "Invalid source failure API"); }
    }
    private static void term(String source, String target, boolean accepted) {
        var pieces = List.of(new EvidenceTranslation.Piece("P1", source + " " + A));
        String raw = Json.stringify(Map.of("translations", List.of(Map.of("id", "P1", "text", target + " " + A))));
        if (accepted) check(EvidenceTranslation.validate(raw, pieces).size() == 1, "Correct critical terms rejected");
        else {
            try { EvidenceTranslation.validate(raw, pieces); throw new AssertionError("Critical term change accepted: " + target); }
            catch (Api.ServiceException expected) { check(expected.code.equals("INVALID_EVIDENCE_TRANSLATION"), "Critical term failure API"); }
        }
    }
    public static void main(String[] args) {
        var translated = EvidenceTranslation.validate(json(GOOD1, GOOD2), ORIGINAL);
        check(translated.equals(List.of(new EvidenceTranslation.Piece("P1", GOOD1), new EvidenceTranslation.Piece("P2", GOOD2))), "Whole text and exact ordered IDs retained");
        check(EvidenceTranslation.validate("\n " + json(GOOD1, GOOD2) + "\t", ORIGINAL).equals(translated), "JSON whitespace accepted");
        check(Json.object(EvidenceTranslation.prompt(ORIGINAL)).get("target_language").equals("kz"), "Explicit target language");
        check(EvidenceTranslation.prompt(ORIGINAL).contains("не обязан") && EvidenceTranslation.prompt(ORIGINAL).contains(A), "Original negation and provenance in JSON data");
        String stablePrompt = EvidenceTranslation.prompt(ORIGINAL);
        check(stablePrompt.startsWith("{\"target_language\":\"kz\",\"pieces\":[{\"id\":\"P1\",\"text\":"), "Stable target/pieces and id/text prompt order");
        check(EvidenceTranslation.SYSTEM.contains("не пересказ") && EvidenceTranslation.SYSTEM.contains("одна из частей"), "Whole-condition translation prompt");
        boolean immutable = false;
        try { translated.add(new EvidenceTranslation.Piece("P3", "text")); } catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Result list immutable");

        rejected(null);
        rejected("");
        rejected("```json\n" + json(GOOD1, GOOD2) + "\n```");
        rejected(json(GOOD1, GOOD2).substring(0, 40));
        rejected(json(GOOD1, GOOD2) + " trailing text");
        rejected(json(GOOD1, GOOD2).replace("\"translations\":", "\"extra\":true,\"translations\":"));
        rejected("{\"translations\":[],\"translations\":[]}");
        rejected("{\"translations\":[]}");
        rejected(json(GOOD1, GOOD2).replace("\"P1\"", "\"P9\""));
        rejected(json(GOOD1, GOOD2).replace("\"P2\"", "\"P1\""));
        rejected(Json.stringify(Map.of("translations", List.of(Map.of("id", "P2", "text", GOOD2), Map.of("id", "P1", "text", GOOD1)))));
        rejected(Json.stringify(Map.of("translations", List.of(Map.of("id", "P1", "text", GOOD1, "summary", true), Map.of("id", "P2", "text", GOOD2)))));
        rejected(json("", GOOD2));
        rejected(json(" ", GOOD2));
        rejected(json(GOOD1.replace("5", "6"), GOOD2));
        rejected(json(GOOD1 + " 5", GOOD2));
        rejected(json(GOOD1 + " ٥", GOOD2));
        rejected(json(GOOD1, GOOD2.replace("2.5", "2,5")));
        rejected(json(GOOD1, GOOD2.replace("-3", "3")));
        rejected(json(GOOD1, GOOD2.replace("601-04", "601-05")));
        rejected(json(GOOD1, GOOD2.replace("«F»", "«A»")));
        rejected(json(GOOD1, GOOD2.replace("_____", "[аты-жөні]")));
        rejected(json(GOOD1.replace(A, ""), GOOD2));
        rejected(json(GOOD1 + A, GOOD2));
        rejected(json(GOOD1.replace(A, B), GOOD2));
        rejected(json(GOOD1.replace(A, "[source:1]"), GOOD2));
        rejected(json(GOOD1 + " [source:", GOOD2));
        rejected(json(GOOD1 + " [source：1]", GOOD2));
        rejected(json(GOOD1.replace(A, "[" + "a".repeat(64) + "]"), GOOD2));
        rejected(json(GOOD1 + " [5]", GOOD2));
        rejected(json(ORIGINAL.getFirst().text(), GOOD2));
        rejected(json("Кім? 5 " + A, GOOD2));
        rejected(json(GOOD1.repeat(400), GOOD2));
        rejected(" ".repeat(262145));
        for (String unsafe : List.of("\u0000", "\u0008", "\u061c", "\u200b", "\u200c", "\u200d", "\u200e", "\u202e", "\u2066", "\u2060", "\ufeff", "\ud800", "\udc00"))
            rejected(json(GOOD1 + unsafe, GOOD2));

        badOriginal(null);
        badOriginal(List.of());
        badOriginal(List.of(new EvidenceTranslation.Piece("P1", " ")));
        badOriginal(List.of(new EvidenceTranslation.Piece("bad", "Қазақша мәтін")));
        badOriginal(List.of(ORIGINAL.getFirst(), ORIGINAL.getFirst()));
        badOriginal(List.of(new EvidenceTranslation.Piece("P1", "қ".repeat(24001))));
        badOriginal(List.of(new EvidenceTranslation.Piece("P1", "Мәтін [source:1]")));

        // Clear observed polarity/time-unit and invented archive/letter errors now fail closed.
        String semanticWrong = GOOD1.replace("міндетті емес", "міндетті").replace("жұмыс күні", "күнтізбелік күн");
        rejected(json(semanticWrong, GOOD2));
        term("Работник подает заявление в течение 2 рабочих дней.", "Жұмыскер 2 жұмыс күні ішінде өтініш береді.", true);
        term("Работник подает заявление в течение 2 рабочих дней.", "Қызметкер 2 жұмыс күні ішінде мұрағатқа хат жібереді.", false);
        term("Employee submits an application within 2 working days.", "Жұмыскер 2 жұмыс күні ішінде өтініш береді.", true);
        term("Employee submits an application within 2 working days.", "Жұмыс беруші 2 жұмыс күні ішінде өтініш береді.", false);
        term("Работодатель должен направить письмо в архив в течение 2 календарных дней.", "Жұмыс беруші 2 күнтізбелік күн ішінде мұрағатқа хат жіберуге тиіс.", true);
        term("Работодатель должен направить письмо в архив в течение 2 календарных дней.", "Жұмыс беруші 2 күнтізбелік күн ішінде өтініш жіберуге тиіс.", false);
        term("Работник может подать заявление в течение 2 рабочих дней.", "Жұмыскер 2 жұмыс күні ішінде өтініш бере алады, бұл міндетті.", false);
        term("Работник может подать заявление в течение 2 рабочих дней.", "Жұмыскер 2 жұмыс күні ішінде өтініш беруі мүмкін.", true);
        term("Работник не обязан платить в течение 5 рабочих дней.", "Жұмыскер 5 жұмыс күні ішінде төлеуге міндетті емес.", true);
        term("Работник не обязан платить в течение 5 рабочих дней.", "Жұмыскер 5 жұмыс күні ішінде төлеуге міндетті.", false);
        term("Employee must not submit an application within 2 working days.", "Жұмыскер 2 жұмыс күні ішінде өтініш бермеуі тиіс.", true);
        term("Работник подает заявление в течение 2 рабочих дней.", "Жұмыскер 2 күнтізбелік күн ішінде өтініш береді.", false);
        term("Работник подает заявление в течение 2 дней.", "Жұмыскер 2 апта ішінде өтініш береді.", false);
        term("Срок составляет 2 месяца и 1 год.", "Мерзім 2 ай және 1 жыл болады.", true); // Copular болады alone is not a clear permission marker.
        term("Срок составляет 2 месяца и 1 год.", "Мерзім: 2 ай және 1 жыл.", true);
        term("Срок составляет 2 месяца и 1 год.", "Мерзім: 2 апта және 1 жыл.", false);
        term("One part must be at least 2 calendar weeks.", "Бір бөлігі кемінде 2 күнтізбелік апта болуы тиіс.", true);
        term("One part must be at least 2 calendar weeks.", "Әрбір бөлік кемінде 2 күнтізбелік апта болуы тиіс.", false);
        term("Each part must be at least 2 calendar weeks.", "Бір бөлігі кемінде 2 күнтізбелік апта болуы тиіс.", false);
        term("Не позднее 10 числа.", "10 күннен кешіктірмей.", false); // added day unit is not a calendar-date translation.
        var wordNumberSource = List.of(new EvidenceTranslation.Piece("P1", "Одна из частей должна быть не менее двух календарных недель. " + A));
        String wrongNumberWord = "Бір бөлігі кемінде үш күнтізбелік апта болуы тиіс. " + A;
        String wordNumberJson = Json.stringify(Map.of("translations", List.of(Map.of("id", "P1", "text", wrongNumberWord))));
        check(EvidenceTranslation.validate(wordNumberJson, wordNumberSource).getFirst().text().equals(wrongNumberWord),
            "Word numerals still require semantic review; no false digit-parity guarantee");
        term("Работодатель обязан выплатить работнику сумму.", "Жұмыскер Жұмыс берушіге соманы төлеуге міндетті.", true);
        // Both role markers remain; the reversed payment direction above is NOT semantically certified.
        System.out.println("EvidenceTranslationTest PASS " + assertions + " assertions (structural only, not accepted semantic translation)");
    }
}
