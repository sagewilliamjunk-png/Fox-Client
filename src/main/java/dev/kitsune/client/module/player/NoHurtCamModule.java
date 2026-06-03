package dev.kitsune.client.module.player;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.setting.SliderSetting;

/**
 * No Hurt Cam — disables (or reduces) the screen-shake / camera-tilt that MC
 * applies when you take damage. Hands the multiplier off to
 * {@link dev.kitsune.client.mixin.GameRendererNoHurtCamMixin}, which inserts
 * the scale into vanilla's bobHurt path.
 */
public class NoHurtCamModule extends Module {

    private final SliderSetting strength = addSetting(new SliderSetting("Effect strength", 0.0, 0.0, 1.0, 0.05));

    public NoHurtCamModule() {
        super("No Hurt Cam", "Reduce or remove the camera shake on damage.", Category.PLAYER);
    }

    /** 1.0 = vanilla, 0.0 = no shake at all. Read by the GameRenderer mixin. */
    public static float multiplier() {
        NoHurtCamModule m = ModuleManager.getModule(NoHurtCamModule.class);
        if (m == null || !m.isEffectivelyEnabled()) return 1.0f;
        return m.strength.get().floatValue();
    }
}
