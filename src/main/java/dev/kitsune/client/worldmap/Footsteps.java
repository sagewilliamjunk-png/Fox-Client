package dev.kitsune.client.worldmap;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bounded ring buffer of recent player positions, drawn as a fading trail
 * on the world-map screen. Resets per sub-world switch (handled by
 * {@link WorldMapManager}). Pure in-memory — never persisted, by design.
 */
public final class Footsteps {

    /** Cap. ~25 minutes of breadcrumbs at one sample per second. */
    private static final int MAX_POINTS = 1500;
    /** Don't sample more often than this — keeps the trail visually smooth
     *  without wasting memory on every-tick samples while standing still. */
    private static final long MIN_SAMPLE_INTERVAL_MS = 1000;
    /** Skip new samples whose distance from the last point is below this. */
    private static final double MIN_DISTANCE_BLOCKS = 4.0;

    public record Step(double x, double z) {}

    private static final Deque<Step> POINTS = new ArrayDeque<>();
    private static long lastSampleMs = 0;
    private static String boundSubWorld = null;

    private Footsteps() {}

    /** Record a sample. Idempotent — drops the call when too soon, too close,
     *  or the player hasn't moved. */
    public static void sample(String subWorldId, double x, double z) {
        if (subWorldId == null) return;
        // Sub-world change → clear the trail. Even the same server but a
        // different dimension resets so the overworld trail doesn't mix
        // with nether tracks.
        if (!subWorldId.equals(boundSubWorld)) {
            POINTS.clear();
            boundSubWorld = subWorldId;
        }
        long now = System.currentTimeMillis();
        if (now - lastSampleMs < MIN_SAMPLE_INTERVAL_MS) return;
        Step last = POINTS.peekLast();
        if (last != null) {
            double dx = x - last.x;
            double dz = z - last.z;
            if (dx * dx + dz * dz < MIN_DISTANCE_BLOCKS * MIN_DISTANCE_BLOCKS) return;
        }
        POINTS.addLast(new Step(x, z));
        lastSampleMs = now;
        while (POINTS.size() > MAX_POINTS) POINTS.removeFirst();
    }

    /** Snapshot for the renderer. Oldest first; freshest at the end. */
    public static Step[] snapshot() { return POINTS.toArray(new Step[0]); }

    public static void clear() {
        POINTS.clear();
        boundSubWorld = null;
        lastSampleMs = 0;
    }
}
