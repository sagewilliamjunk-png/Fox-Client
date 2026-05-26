package dev.kitsune.client.features.qol;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.features.FoxFeature;
import net.minecraft.client.Minecraft;

/**
 * Smooth hold-key zoom — Lunar/Feather style.
 *
 * <p><b>How it works:</b> {@code GameRendererMixin} reads
 * {@link #getEffectiveZoomFactor()} every frame and divides the FOV by it.
 * Nothing in the user's saved options is ever mutated, so a crash mid-zoom
 * cannot poison their FOV slider. {@code currentFactor} smoothly lerps toward
 * either {@code zoomFactor} (held) or {@code 1.0} (released), so there's no
 * sudden snap.
 *
 * <ul>
 *   <li>Hold zoom key → smooth zoom in</li>
 *   <li>Release → smooth zoom out</li>
 *   <li>Scroll wheel while zoomed → adjust zoom level (2× to 10×)</li>
 *   <li>Cinematic smooth camera enabled while zoomed (idempotent toggle)</li>
 * </ul>
 *
 * Server-safety: purely visual, allowed everywhere.
 */
public class ZoomFeature implements FoxFeature {

    public static final String ID = "zoom";

    /** User-configurable zoom factor (set by scroll wheel, 2.0..10.0). */
    private double zoomFactor = 4.0;

    /** Smoothed multiplier the mixin reads. 1.0 = no zoom, lerps toward target. */
    private static volatile double currentFactor = 1.0;

    /** True while the zoom key is held. */
    private boolean active = false;

    /** Saved smooth-camera state so we can restore on release. */
    private boolean savedSmoothCamera = false;

    private static final double MIN_ZOOM   = 2.0;
    private static final double MAX_ZOOM   = 10.0;
    private static final double LERP_SPEED = 0.30;

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Zoom"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public String category() { return "qol"; }

    /**
     * Read by {@link dev.kitsune.client.mixin.GameRendererMixin}. Returns 1.0
     * when the zoom is fully released — the mixin short-circuits in that case.
     */
    public static double getEffectiveZoomFactor() {
        return currentFactor;
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && active) {
            mc.options.smoothCamera = savedSmoothCamera;
        }
        active = false;
        currentFactor = 1.0;
    }

    @Override
    public void tick(Minecraft client) {
        if (client == null || client.options == null) return;
        boolean held = KitsuneClient.zoomKey != null && KitsuneClient.zoomKey.isDown();

        if (held && !active) {
            // Start zooming — capture smooth-camera state once
            savedSmoothCamera = client.options.smoothCamera;
            client.options.smoothCamera = true;
            active = true;
            zoomFactor = 4.0;
        } else if (!held && active) {
            // Stop zooming
            client.options.smoothCamera = savedSmoothCamera;
            active = false;
        }

        // Smooth toward target every tick
        double target = active ? zoomFactor : 1.0;
        currentFactor = lerp(currentFactor, target, LERP_SPEED);

        // Snap when very close so the mixin can short-circuit
        if (Math.abs(currentFactor - target) < 0.005) currentFactor = target;
    }

    /**
     * Called from {@code MouseScrollMixin}-style handler when the user scrolls
     * while zooming. Adjusts the zoom level. Positive delta = zoom in more.
     */
    public void adjustZoom(double delta) {
        if (!active) return;
        zoomFactor += delta * 0.5;
        zoomFactor = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoomFactor));
    }

    public boolean isActive() { return active; }

    private static double lerp(double from, double to, double speed) {
        return from + (to - from) * speed;
    }
}
