package dev.kitsune.client.module.misc;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;

/**
 * Smooth hold-key zoom — Lunar/Feather style. Ported from the legacy
 * {@code ZoomFeature} into the proper module system in v1.2.
 *
 * <p>How it works: {@code GameRendererMixin} reads
 * {@link #getEffectiveZoomFactor()} every frame and divides the FOV by it.
 * Nothing in the user's saved options is mutated, so a crash mid-zoom cannot
 * poison their FOV slider. {@code currentFactor} smoothly lerps toward either
 * {@code zoomFactor} (held) or {@code 1.0} (released), so there's no sudden snap.
 *
 * <p>The mixin's read path is intentionally static — keeps the mixin one
 * import-light line so it doesn't pull the entire module manager into a
 * hot render call.
 */
public class ZoomModule extends Module {

    private static final double LERP_SPEED = 0.30;

    private final SliderSetting zoomLevel = addSetting(new SliderSetting("Zoom Factor", 4.0, 2.0, 10.0, 0.5));

    /** Smoothed multiplier the mixin reads. 1.0 = no zoom, lerps toward target. */
    private static volatile double currentFactor = 1.0;
    private static volatile ZoomModule INSTANCE = null;

    private boolean active = false;
    private boolean savedSmoothCamera = false;

    public ZoomModule() {
        super("Zoom", "Hold the zoom key for a smooth scoped-in view.", Category.MISC);
        INSTANCE = this;
    }

    /** Read by {@link dev.kitsune.client.mixin.GameRendererMixin}. Returns 1.0
     *  when the zoom is fully released — the mixin short-circuits in that case. */
    public static double getEffectiveZoomFactor() {
        return currentFactor;
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && active && mc.options != null) {
            mc.options.smoothCamera = savedSmoothCamera;
        }
        active = false;
        currentFactor = 1.0;
    }

    @Override
    public void onTick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return;
        boolean held = KitsuneClient.zoomKey != null && KitsuneClient.zoomKey.isDown();

        if (held && !active) {
            savedSmoothCamera = client.options.smoothCamera;
            client.options.smoothCamera = true;
            active = true;
        } else if (!held && active) {
            client.options.smoothCamera = savedSmoothCamera;
            active = false;
        }

        double target = active ? zoomLevel.get() : 1.0;
        currentFactor = lerp(currentFactor, target, LERP_SPEED);
        if (Math.abs(currentFactor - target) < 0.005) currentFactor = target;
    }

    public boolean isActive() { return active; }

    private static double lerp(double from, double to, double speed) {
        return from + (to - from) * speed;
    }
}
