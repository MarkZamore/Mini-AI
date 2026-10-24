package ai.enterprise.assistant;

import ai.enterprise.contracts.Api;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Source/provenance/format regressions; no model or application-composition variants. */
public final class NormativeStreamGateTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private static final String CA = "[source:" + A + "]", CB = "[source:" + B + "]";
    private static final List<Api.Fragment> SOURCES = List.of(
        new Api.Fragment(Map.of("fragment_id", A, "text", "First source", "source_refs", List.of(Map.of("page", 1)))),
        new Api.Fragment(Map.of("fragment_id", B, "text", "Second source", "source_refs", List.of(Map.of("page", 2)))));
    private static int assertions;

    private record Outcome(String text, boolean omitted) {}

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static Outcome render(List<String> chunks, boolean aliases) {
        var gate = new NormativeStreamGate(Set.of(A, B));
        var provenance = aliases ? new CitationAliases(SOURCES) : null;
        var output = new StringBuilder();
        for (String chunk : chunks) output.append(gate.push(aliases ? provenance.push(chunk) : chunk));
        if (aliases) output.append(gate.push(provenance.finish()));
        output.append(gate.finish());
        CitationAliases.validateCanonical(output.toString(), SOURCES);
        return new Outcome(output.toString(), gate.omittedUncitedText());
    }

    private static void expect(String input, String expected, boolean omitted, String reason) {
        Outcome result = render(List.of(input), false);
        check(result.equals(new Outcome(expected, omitted)), reason + ": " + result);
    }

    private static List<String> fixedChunks(String value, int length) {
        var chunks = new ArrayList<String>();
        for (int start = 0; start < value.length(); start += length)
            chunks.add(value.substring(start, Math.min(start + length, value.length())));
        return chunks;
    }

    private static void rechunk(String input, String expected, boolean omitted, boolean aliases) {
        Outcome wanted = new Outcome(expected, omitted);
        for (int split = 0; split <= input.length(); split++) {
            check(render(List.of(input.substring(0, split), input.substring(split)), aliases).equals(wanted),
                "Two-way split changed semantic content/omission at " + split);
        }
        for (int length : new int[] {1, 2, 3, 7, 31, 64, 127})
            check(render(fixedChunks(input, length), aliases).equals(wanted), "Fixed-size rechunk changed output: " + length);
        var random = new Random(20261024L);
        for (int trial = 0; trial < 80; trial++) {
            var chunks = new ArrayList<String>();
            for (int start = 0; start < input.length();) {
                int end = Math.min(input.length(), start + 1 + random.nextInt(41));
                chunks.add(input.substring(start, end)); start = end;
            }
            check(render(chunks, aliases).equals(wanted), "Random rechunk changed output, trial " + trial);
        }
    }

    private static void error(String input, String code, boolean aliases) {
        for (int size : new int[] {1, 7, Math.max(1, input.length())}) {
            boolean rejected = false;
            try { render(fixedChunks(input, size), aliases); }
            catch (Api.ServiceException failure) { rejected = failure.code.equals(code); }
            check(rejected, "Expected " + code + " with chunk size " + size);
        }
    }

    public static void main(String[] args) {
        var gate = new NormativeStreamGate();
        check(gate.push("Условие из документа ").isEmpty(), "Uncited provisional norm remains buffered");
        check(gate.push(CA).isEmpty(), "A citation token alone does not close a paragraph");
        check(gate.push(".\n\n").equals("Условие из документа " + CA + ".\n\n"),
            "Completed evidence block is streamed before generation ends");
        check(gate.push("Неподтверждённый обязательный срок.").isEmpty(), "Next norm stays buffered");
        check(gate.finish().isEmpty() && gate.omittedUncitedText(), "Uncited tail omitted, warning retained");

        String organization = "### Краткий ответ\n\nОрганизация — НАО «КазНИТУ имени К.И. Сатпаева» " + CA + ".\n\n";
        rechunk(organization, organization, false, false);
        String conditions = "## Условия и порядок действий\n\nПо соглашению работника и работодателя одна часть отпуска — не менее двух календарных недель " + CA + ".\n\n";
        rechunk(conditions, conditions, false, false);
        String kz = "### Қысқаша жауап\n\nЕгер бұйрық шығарылмаса, жұмыскер сынақтан өткен болып саналады " + CA + ".";
        rechunk(kz, kz, false, false);
        String unicode = "Название «ҚазНИТУ» 🌐; условие сақталады " + CA + ".";
        rechunk(unicode, unicode, false, false);
        String separateCitationLine = "При согласии обеих сторон:\n* одна часть — не менее двух календарных недель;\n* соглашение относится к разделению отпуска.\n" + CA;
        rechunk(separateCitationLine, separateCitationLine, false, false);

        String unsafeSameParagraph = "Минимум — полторы дневные/часовые ставки " + CA + ". Обязательный выдуманный срок — 99 дней.\n\n";
        String safePrefix = "Минимум — полторы дневные/часовые ставки " + CA + ". \n\n";
        rechunk(unsafeSameParagraph, safePrefix, true, false);
        String unsafeAlias = "Минимум — полторы ставки [source:1]. Обязательный выдуманный срок — 99 дней.\n\n";
        rechunk(unsafeAlias, "Минимум — полторы ставки " + CA + ". \n\n", true, true);

        // Lost organization cannot be recovered by borrowing a later paragraph's ID.
        String missingName = "НАО «КазНИТУ имени К.И. Сатпаева».\n\nЭто полное официальное наименование " + CA + ".";
        expect(missingName, "Это полное официальное наименование " + CA + ".", true,
            "No invented citation association across a blank line");
        expect("Неподтверждённое правило.\n \nПодтверждённое " + CA, "Подтверждённое " + CA, true,
            "Whitespace-only blank line still separates independent paragraphs");
        expect("## Условия\n\nНеизвестное требование.\n\nФакт " + CA, "Факт " + CA, true,
            "Heading from a dropped block is not attached to an unrelated later claim");
        expect("## Основание\n\n" + CA, "", true, "Citation-only paragraph does not make a heading into evidence");
        expect("## Условия", "", false, "Standalone structural heading contains no normative claim");

        String two = "Первое правило " + CA + ".\n\nВторое правило " + CB + ".";
        rechunk(two, two, false, false);
        String windows = two.replace("\n", "\r\n");
        rechunk(windows, two, false, false);
        expect("Число — 2,67 " + CA + ".", "Число — 2,67 " + CA + ".", false, "Number and units preserved");
        expect("2,67 " + CA, "2,67 " + CA, false, "A cited numeric answer is substantive");

        String orphan = "Почасовую нагрузку не включают в оба итога " + CA + ".\n\n### Основание\n\n* " + CB;
        String noOrphan = "Почасовую нагрузку не включают в оба итога " + CA + ".\n\n";
        rechunk(orphan, noOrphan, true, false);
        expect("Правило " + CA + ".\n* " + CB, "Правило " + CA + ".", true,
            "A reference-only item in the same paragraph is also omitted");
        expect("* " + CA + " — Фрагмент 1", "", true, "Reference-first source-list entry is not an asserted evidence block");
        for (String prefix : List.of("1.", "1)", "1. ", "1) ", "-", "•")) {
            String numberedOrphan = "Правило " + CA + ".\n" + prefix + CB;
            rechunk(numberedOrphan, "Правило " + CA + ".", true, false);
        }
        for (String prefix : List.of("1.", "1)", "1. ", "1) "))
            expect(prefix + "Основание " + CA, "", true, "Numbered structural label cannot make a naked citation substantive");
        expect("1.Основание\n\nПодтверждённый факт " + CA, "1.Основание\n\nПодтверждённый факт " + CA,
            false, "Compact numbering on a real structural heading is retained with its evidence block");
        expect("Правило " + CA + ". Незавершённое обязательное", "Правило " + CA + ". ", true,
            "Incomplete unreferenced prose after a citation is not released at finish");
        expect("Правило " + CA + ".\n\n[9]", "Правило " + CA + ".\n\n", true,
            "Unresolved bare numeric tail does not survive because it resembles punctuation");

        String advice = "Общие рекомендации (необязательные): уточните дату.\n\n";
        rechunk(advice, advice, false, false);
        expect("Общие рекомендации: работник обязан явиться в пятницу.\n\n", "", true,
            "An advice heading cannot launder an uncited obligation");
        expect("Необязательные рекомендации: работник должен явиться.\n\n", "", true,
            "Even an optional label cannot launder a mandatory action");
        expect("Общие рекомендации (необязательные): заявление подают за 2 месяца.\n\n", "", true,
            "Unverified quantities/deadlines are not exempt advice");

        error("Правило [source:unknown].\n\n", "INVALID_CITATION", false);
        error("Сначала известное " + CA + ", затем неизвестное [source:unknown].\n\n", "INVALID_CITATION", false);
        error("Правило [source:3].\n\n", "INVALID_CITATION", true);
        error("Правило [source:1", "INVALID_CITATION", true);
        error("Правило [source:", "INVALID_CITATION", false);
        error("Правило [SOURCE:1].", "INVALID_CITATION", true);
        error("Правило [" + "c".repeat(64) + "].", "INVALID_CITATION", true);
        error("x".repeat(32769) + CA + "\n\n", "RESPONSE_EVIDENCE_LIMIT", false);
        error("Основание\n\n".repeat(4000), "RESPONSE_EVIDENCE_LIMIT", false);

        var cancelled = new NormativeStreamGate(Set.of(A, B));
        check(cancelled.push("Завершённое " + CA + ".\n\n").contains(CA), "Completed prefix available before cancellation");
        check(cancelled.push("Незавершённое [source:").isEmpty(), "Cap/cancellation has no provisional tail output");
        // On cancellation/OUTPUT_LIMIT callers discard this instance, not finish it.

        // Syntax is not entailment: the new verified-answer route must evaluate meaning.
        String incorrectButCited = "В источнике не указана частота выплат " + CA + ".";
        expect(incorrectButCited, incorrectButCited, false, "Gate does not pretend to establish semantic correctness");
        var empty = new NormativeStreamGate(Set.of());
        check(empty.push("").isEmpty() && empty.finish().isEmpty() && !empty.omittedUncitedText(), "Empty stream has no missing claim");
        check(empty.finish().isEmpty(), "Repeated finish is empty");
        System.out.println("Normative streaming checks passed; assertions=" + assertions);
    }
}
