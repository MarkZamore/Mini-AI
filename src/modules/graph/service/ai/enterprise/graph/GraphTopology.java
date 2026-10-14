package ai.enterprise.graph;

import ai.enterprise.contracts.Api;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Display aggregation preserves direction and the IDs of every confirmed evidence record. */
public final class GraphTopology {
    private GraphTopology() {}
    public record Aggregate(String a, String b, int aToB, int bToA, List<Long> evidenceIds) {}
    private record Pair(String a, String b) implements Comparable<Pair> {
        public int compareTo(Pair other) {
            int result = a.compareTo(other.a);
            return result == 0 ? b.compareTo(other.b) : result;
        }
    }
    private static final class Accumulator {
        int forward, backward;
        final List<Long> evidence = new ArrayList<>();
    }
    public static List<Aggregate> aggregate(List<Api.GraphEdge> edges) {
        var pairs = new TreeMap<Pair, Accumulator>();
        for (Api.GraphEdge edge : edges) {
            if (edge.targetId() == null || edge.targetId().isEmpty()) continue;
            boolean forward = edge.sourceId().compareTo(edge.targetId()) <= 0;
            Pair pair = forward ? new Pair(edge.sourceId(), edge.targetId()) : new Pair(edge.targetId(), edge.sourceId());
            var aggregate = pairs.computeIfAbsent(pair, ignored -> new Accumulator());
            if (forward) aggregate.forward++; else aggregate.backward++;
            aggregate.evidence.add(edge.id());
        }
        return pairs.entrySet().stream().map(e -> new Aggregate(e.getKey().a, e.getKey().b,
                e.getValue().forward, e.getValue().backward, List.copyOf(e.getValue().evidence))).toList();
    }
}
