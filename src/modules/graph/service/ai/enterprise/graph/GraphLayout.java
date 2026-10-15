package ai.enterprise.graph;

import java.util.Collection;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Reproducible static adapter to the same center-gravity physics used interactively. */
public final class GraphLayout {
    private GraphLayout() {}
    public record Point(double x, double y) {}
    public static Map<String, Point> calculate(Collection<String> documentIds,
                                               Collection<GraphGeometry.Link> links,
                                               BooleanSupplier cancelled) {
        GraphPhysics physics = new GraphPhysics(documentIds, links);
        for (int i = 0; i < GraphPhysics.MAX_SETTLE_STEPS && !physics.sleeping(); i++) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            physics.step(GraphPhysics.STEP_SECONDS, cancelled);
        }
        return physics.snapshot();
    }
}
