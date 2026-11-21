package ai.enterprise.shell;

/**
 * Continuous wheel target and edge spring, in SWT logical units (96-DPI DIP).
 * The integration is the same as Autonomy&Performance's SmoothScrolling.cs.
 * The host owns rendering and frame scheduling, so no timer survives settling.
 */
public final class SmoothScrollController implements AutoCloseable {
    public static final double WHEEL_STEP = 36;
    public static final double TIME_CONSTANT_MILLISECONDS = 75;
    public static final double SPRING_LIMIT = 18;
    interface Host {
        double maximumOffset();
        double offset();
        boolean animationsEnabled();
        void render(double offset, double spring);
        void requestFrame();
        void cancelFrame();
    }
    private final Host host;
    private double position, target, bounce, velocity, pendingBounce;
    private boolean animating, closed;
    private Boolean animationPreferenceForTesting;

    SmoothScrollController(Host host) {
        this.host = host;
        position = target = host.offset();
    }
    public double offset() { return position; }
    public double targetOffset() { return target; }
    public double bounceOffset() { return bounce; }
    public boolean isAnimating() { return animating; }

    /** Raw Windows wheel delta: 120 is one notch; partial deltas stay partial. */
    public boolean scrollByWheelDelta(double delta) {
        if (closed || !Double.isFinite(delta) || delta == 0 || host.maximumOffset() <= 0) return false;
        double current = animating ? position : clamp(host.offset());
        double raw = (animating ? target : current) - delta * WHEEL_STEP / 120.0;
        double next = clamp(raw);
        boolean animations = animationPreferenceForTesting == null
                ? host.animationsEnabled() : animationPreferenceForTesting;
        if (!animations) {
            jumpTo(next);
            return true;
        }
        if (raw != next) {
            double impulse = -(raw - next) * 8;
            boolean atEdge = next == 0 ? current < .5 : current > host.maximumOffset() - .5;
            if (atEdge) {
                bounce = limit(bounce + Math.signum(impulse) * 1.5, SPRING_LIMIT);
                velocity = limit(velocity + impulse, 480);
            } else pendingBounce = limit(pendingBounce + impulse, 480);
        } else if (next > 0 && next < host.maximumOffset()) pendingBounce = 0;
        target = next;
        if (!animating && (Math.abs(target - current) >= .25 || Math.abs(velocity) > .5)) {
            position = current;
            animating = true;
        }
        // Show the immediate 1.5 DIP edge response in the input message itself.
        host.render(position, bounce);
        if (animating) host.requestFrame();
        return true;
    }

    /** Deterministic frame entry point; runtime frames use the monotonic clock. */
    public void advanceFrame(double elapsedMilliseconds) {
        if (closed || !animating || !Double.isFinite(elapsedMilliseconds) || elapsedMilliseconds <= 0) return;
        double elapsed = Math.min(elapsedMilliseconds, 50);
        target = clamp(target);
        position += (target - position) * (1 - Math.exp(-elapsed / TIME_CONSTANT_MILLISECONDS));
        boolean scrollSettled = Math.abs(target - position) < .25;
        if (scrollSettled) position = target;
        if (Math.abs(target - position) <= 4 && pendingBounce != 0) {
            velocity = limit(velocity + pendingBounce, 480);
            pendingBounce = 0;
        }
        // Exact solution of y'' + 24*y' + 300*y = 0, stable at delayed frames.
        double dt = elapsed / 1000.0, frequency = Math.sqrt(156), damping = Math.exp(-12 * dt);
        double cosine = Math.cos(frequency * dt), sine = Math.sin(frequency * dt), previous = bounce;
        bounce = limit(damping * (previous * cosine + (velocity + 12 * previous) / frequency * sine), SPRING_LIMIT);
        velocity = damping * (velocity * cosine - (12 * velocity + 300 * previous) / frequency * sine);
        boolean springSettled = Math.abs(bounce) < .05 && Math.abs(velocity) < .5;
        if (springSettled) { bounce = 0; velocity = 0; }
        if (scrollSettled && springSettled) {
            animating = false;
            host.cancelFrame();
        }
        host.render(position, bounce);
        if (animating) host.requestFrame();
    }

    /** Manual or programmatic scrolling is immediate and clears wheel motion. */
    public void jumpTo(double offset) {
        if (closed) return;
        animating = false;
        host.cancelFrame();
        bounce = velocity = pendingBounce = 0;
        position = target = clamp(Double.isFinite(offset) ? offset : 0);
        host.render(position, 0);
    }
    public void cancel() {
        if (!closed) jumpTo(host.offset());
    }
    private double clamp(double value) { return Math.max(0, Math.min(Math.max(0, host.maximumOffset()), value)); }
    private static double limit(double value, double maximum) { return Math.max(-maximum, Math.min(maximum, value)); }

    // Package-private developer fixture policy; no Windows setting is changed.
    void setAnimationPreferenceForTesting(Boolean enabled) {
        animationPreferenceForTesting = enabled;
        cancel();
    }
    @Override public void close() {
        if (closed) return;
        cancel();
        closed = true;
    }
}
