package ai.enterprise.graph;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/** Small executable contract test; no UI, SQLite, or external test framework required. */
public final class GraphDomainTest {
    public static void main(String[] args) {
        var ids = List.of("A", "B", "C", "孤立", "Изолирован");
        var links = List.of(new GraphGeometry.Link("A", "B"),
                new GraphGeometry.Link("A", "B"), new GraphGeometry.Link("B", "A"),
                new GraphGeometry.Link("B", "C"), new GraphGeometry.Link("A", "A"),
                new GraphGeometry.Link("A", null), new GraphGeometry.Link("A", "absent"));
        var degree = GraphGeometry.uniqueDegrees(ids, links);
        require(degree.get("A") == 1 && degree.get("B") == 2 && degree.get("C") == 1,
                "Unique undirected neighbor count");
        require(degree.get("Изолирован") == 0 && degree.get("孤立") == 0, "Isolates retained");
        require(GraphGeometry.radius(0) == 16.0, "Minimum radius");
        require(GraphGeometry.radius(1) == 22.0, "Radius formula");
        require(GraphGeometry.radius(1_000_000) == 56.0, "Maximum radius");
        require(!GraphGeometry.isDrag(0, 0, 3, 4), "5 DIP remains a click");
        require(GraphGeometry.isDrag(0, 0, 3, 4.01), "More than 5 DIP is drag");
        Map<String, GraphLayout.Point> layout = GraphLayout.calculate(ids, links, () -> false);
        require(layout.size() == ids.size(), "All documents included");
        require(layout.equals(GraphLayout.calculate(ids, links, () -> false)), "Deterministic layout");
        require(layout.values().stream().allMatch(p -> Double.isFinite(p.x()) && Double.isFinite(p.y())),
                "Finite positions");
        require(GraphLayout.calculate(List.of(), List.of(), () -> false).isEmpty(), "Empty graph");
        var radialIds=new ArrayList<String>();var radialLinks=new ArrayList<GraphGeometry.Link>();radialIds.add("hub");
        for(int i=0;i<8;i++){String leaf="leaf"+i;radialIds.add(leaf);radialLinks.add(new GraphGeometry.Link("hub",leaf));}
        for(int i=0;i<12;i++)radialIds.add("isolated"+i);
        var radial=GraphLayout.calculate(radialIds,radialLinks,()->false);
        double hubDistance=distance(radial.get("hub"));
        var neighborDistances=radialIds.stream().filter(id->id.startsWith("leaf")).map(id->distance(radial.get(id))).sorted().toList();
        var isolatedDistances=radialIds.stream().filter(id->id.startsWith("isolated")).map(id->distance(radial.get(id))).sorted().toList();
        require(hubDistance<neighborDistances.get(4),"A strongly connected hub belongs nearer the center than its neighbors");
        require(isolatedDistances.getLast()-isolatedDistances.getFirst()>20.0,"Independent documents keep organic radial variation instead of an enforced single ring");
        var radialDegrees=GraphGeometry.uniqueDegrees(radialIds,radialLinks);
        for(int a=0;a<radialIds.size();a++)for(int b=a+1;b<radialIds.size();b++){
            var first=radial.get(radialIds.get(a));var second=radial.get(radialIds.get(b));
            double gap=Math.hypot(first.x()-second.x(),first.y()-second.y())-GraphGeometry.radius(radialDegrees.get(radialIds.get(a)))-GraphGeometry.radius(radialDegrees.get(radialIds.get(b)));
            require(gap>=5.0-1e-6,"A radial family preserves every disc's 5 DIP gap");
        }
        require(radial.equals(GraphLayout.calculate(radialIds,radialLinks,()->false)),"Organic disk seeding and degree-weighted gravity are reproducible");
        var evidence = List.of(edge(1, "A", "B"), edge(2, "A", "B"), edge(3, "B", "A"),
                edge(4, "A", "A"), edge(5, "B", null));
        var aggregates = GraphTopology.aggregate(evidence);
        require(aggregates.size() == 2, "Unresolved references are not confirmed lines");
        var cross = aggregates.stream().filter(e -> e.a().equals("A") && e.b().equals("B")).findFirst().orElseThrow();
        require(cross.aToB() == 2 && cross.bToA() == 1, "Direction retained when lines aggregate");
        require(cross.evidenceIds().equals(List.of(1L, 2L, 3L)), "Every evidence ID retained");
        try {
            GraphLayout.calculate(ids, links, () -> true);
            throw new AssertionError("Cancellation ignored");
        } catch (CancellationException expected) { }
        System.out.println("GraphDomainTest: PASS");
    }
    private static ai.enterprise.contracts.Api.GraphEdge edge(long id, String a, String b) {
        return new ai.enterprise.contracts.Api.GraphEdge(id, a, b, "references", Map.of());
    }
    private static void require(boolean truth, String message) {
        if (!truth) throw new AssertionError(message);
    }
    private static double distance(GraphLayout.Point point){return Math.hypot(point.x(),point.y());}
}
