package dev.kitsune.client.util;

/**
 * Rolling-window click counter for one mouse button (or any key). Feed it the
 * raw down-state once per tick via {@link #tick}; it edge-detects presses,
 * stamps them into a fixed ring buffer, and reports how many landed inside
 * the last second via {@link #cps}.
 *
 * <p>Pure logic — no Minecraft types — so it is unit-testable. Sampling at
 * client-tick rate (~20 Hz) is accurate up to normal human click rates;
 * butterfly/drag clicking beyond ~20 CPS will undercount.
 */
public final class ClickTracker {

    private static final int WINDOW_MS = 1000;
    private static final int BUFFER = 64;

    private final long[] clicks = new long[BUFFER];
    private int head = 0;
    private boolean prevDown = false;

    /** Record the button state for this tick; rising edges count as clicks. */
    public void tick(boolean isDown, long nowMs) {
        if (isDown && !prevDown) {
            clicks[head % BUFFER] = nowMs;
            head++;
        }
        prevDown = isDown;
    }

    /** Number of clicks within the last second. */
    public int cps(long nowMs) {
        long cutoff = nowMs - WINDOW_MS;
        int c = 0;
        for (long t : clicks) {
            if (t != 0 && t >= cutoff) c++;
        }
        return c;
    }

    /** Forget all recorded clicks and edge state. */
    public void reset() {
        java.util.Arrays.fill(clicks, 0L);
        head = 0;
        prevDown = false;
    }
}
