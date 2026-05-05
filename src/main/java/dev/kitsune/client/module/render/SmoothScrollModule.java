package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;

/**
 * Scales scroll-wheel deltas in screens by a configurable multiplier.
 * The actual scaling is applied by {@code ScreenScrollMixin} which reads
 * the static accessor methods below.
 *
 * <p>Acceleration mode makes the first click of a scroll sequence slow,
 * then ramps up for rapid scrolling — mimics a touchpad momentum feel.
 */
public class SmoothScrollModule extends Module {

    private static SmoothScrollModule INSTANCE;

    private final SliderSetting  speed          = addSetting(new SliderSetting("Speed", 1.5, 0.5, 4.0, 0.1));
    private final SliderSetting  smoothing      = addSetting(new SliderSetting("Smoothing", 0.3, 0.0, 0.9, 0.05));
    private final BooleanSetting containerOnly  = addSetting(new BooleanSetting("Containers Only", false));
    private final BooleanSetting reverseScroll  = addSetting(new BooleanSetting("Reverse Scroll",  false));
    private final BooleanSetting acceleration   = addSetting(new BooleanSetting("Acceleration",    false));
    private final SliderSetting  accelMax       = addSetting(new SliderSetting("Max Acceleration", 3.0, 1.5, 6.0, 0.1));

    // Acceleration state
    private long   lastScrollMs    = 0;
    private double accelMultiplier = 1.0;

    public SmoothScrollModule() {
        super("Smooth Scroll", "Configurable screen scroll with smoothing and acceleration", Category.RENDER);
        INSTANCE = this;
    }

    // ---- Static API for mixins ----

    /** Effective scroll multiplier at the current moment (0 when module off). */
    public static double multiplier() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return 1.0;
        return INSTANCE.computeMultiplier();
    }

    /** Whether the scroll boost should only apply inside container screens. */
    public static boolean containerOnly() {
        return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.containerOnly.get();
    }

    /** Whether to negate the scroll delta. */
    public static boolean reversed() {
        return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.reverseScroll.get();
    }

    /** Smoothing factor 0–1 (higher = slower to reach target). */
    public static double smoothing() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return 0;
        return INSTANCE.smoothing.get();
    }

    // ---- Helpers ----

    private double computeMultiplier() {
        double base = speed.get();

        // Acceleration ramps the multiplier up while the user keeps scrolling
        // rapidly, then resets after 300ms of idle. Capped at the user's
        // configured maximum to prevent runaway speed.
        if (acceleration.get()) {
            long now = System.currentTimeMillis();
            long delta = now - lastScrollMs;
            if (delta < 300) {
                accelMultiplier = Math.min(accelMultiplier * 1.15, accelMax.get());
            } else {
                accelMultiplier = 1.0;
            }
            lastScrollMs = now;
            base *= accelMultiplier;
        }

        return base;
    }
}
