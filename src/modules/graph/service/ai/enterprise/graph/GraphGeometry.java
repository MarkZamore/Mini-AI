package ai.enterprise.graph;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** UI-independent graph sizing and degree calculation, in display-independent pixels. */
public final class GraphGeometry {
    private GraphGeometry() {}

    public record Link(String sourceId, String targetId) {}

    public static double radius(int uniqueNeighbors) {
        if (uniqueNeighbors < 0) throw new IllegalArgumentException("Negative degree");
        return Math.min(56.0, 16.0 + 6.0 * Math.sqrt(uniqueNeighbors));
    }

    /** Duplicate edges and self references never increase a node's degree. */
    public static Map<String, Integer> uniqueDegrees(Collection<String> documentIds,
                                                     Collection<Link> links) {
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (String id : documentIds) neighbors.put(id, new HashSet<>());
        for (Link link : links) {
            if (link.targetId() == null || link.sourceId().equals(link.targetId())) continue;
            if (!neighbors.containsKey(link.sourceId()) || !neighbors.containsKey(link.targetId())) continue;
            neighbors.get(link.sourceId()).add(link.targetId());
            neighbors.get(link.targetId()).add(link.sourceId());
        }
        Map<String, Integer> degrees = new HashMap<>();
        neighbors.forEach((id, adjacent) -> degrees.put(id, adjacent.size()));
        return Map.copyOf(degrees);
    }

    /** Crossing this threshold turns a mouse gesture into a drag, even if it returns to its start. */
    public static boolean isDrag(double startX, double startY, double x, double y) {
        return Math.hypot(x - startX, y - startY) > 5.0;
    }
}
