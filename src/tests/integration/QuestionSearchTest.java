package ai.enterprise.tests;

import ai.enterprise.contracts.Api;
import ai.enterprise.database.DatabaseModule;
import ai.enterprise.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Real, read-only corpus regressions for conversational retrieval. */
public final class QuestionSearchTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private record Platform(Path root) implements Api.Platform {
        public Path executablePath() { return root.resolve("Mini-AI.exe"); }
        public long availableMemoryBytes() { return Long.MAX_VALUE; }
        public void openWith(Path path) { throw new UnsupportedOperationException(); }
        public Process launchWorker(Path executable, Path dir, long bytes) { throw new UnsupportedOperationException(); }
    }
    public static void main(String[] args) {
        Path root = Path.of(args[0]).toAbsolutePath();
        var environment = new Api.Environment(root, Executors.newFixedThreadPool(4), new Platform(root));
        try (var context = new Api.Context(environment)) {
            new DatabaseModule().register(context);
            new SearchModule().register(context);
            var search = context.require(Api.SearchServiceV1.class);
            for (String question : List.of(
                "Нормативные документы на базе какой организации?",
                "Как называется организация?",
                "Расскажи об учебном плане",
                "Что говорится об учебном плане?",
                "Где найти академическую политику?",
                "Білім беру туралы қандай?")) {
                var hits = Api.await(search.search(question, 10, List.of(), List.of(), new Api.CancellationToken()));
                require(!hits.isEmpty(), "No sources: " + question);
                for (var hit : hits) {
                    var fragment = Api.await(search.getFragment(hit.fragment().fragmentId(), new Api.CancellationToken()));
                    require(!fragment.sourceRefs().isEmpty(), "Missing original provenance");
                    require(fragment.fields().equals(hit.fragment().fields()), "Altered fragment");
                }
                if (question.contains("организации?")) require(hits.stream().anyMatch(h ->
                    h.fragment().fields().get("text").toString().toLowerCase(Locale.ROOT).contains("сатпаев")),
                    "Organization name evidence absent");
                System.out.println("PASS: " + question + " -> " + hits.size() + " original fragments");
            }
            for (String query : List.of("марсианский регламент 2099", "Что говорится про марсианский регламент 2099?",
                "Документы какой организации описывают марсианский регламент 2099?", "???", "Что это?")) {
                require(Api.await(search.search(query, 10, List.of(), List.of(), new Api.CancellationToken())).isEmpty(),
                    "Unrelated sources returned: " + query);
            }
            require(QuestionQuery.plan("учебный план").isEmpty(), "Keyword mode changed");
            var ordinary = Api.await(search.search("учебный план", 10, List.of(), List.of(), new Api.CancellationToken()));
            var natural = Api.await(search.search("Что говорится про учебный план?", 10, List.of(), List.of(), new Api.CancellationToken()));
            require(natural.stream().anyMatch(n -> ordinary.stream().anyMatch(o ->
                o.fragment().documentId().equals(n.fragment().documentId()))), "Equivalent question lost relevant documents");
            var doc = ordinary.getFirst().fragment().documentId();
            var filtered = Api.await(search.search("Что говорится об учебном плане?", 3, List.of(doc), List.of(), new Api.CancellationToken()));
            require(!filtered.isEmpty() && filtered.size() <= 3 && filtered.stream().allMatch(h -> h.fragment().documentId().equals(doc)),
                "Document filter/limit lost");
            var cancelled = new Api.CancellationToken(); cancelled.cancel();
            require(!search.search("Как называется организация?", 10, List.of(), List.of(), cancelled).toCompletableFuture().join().success(),
                "Cancellation lost");
            System.out.println("PASS: unknown subjects, filters, keyword parity, cancellation and provenance");
        }
    }
}
