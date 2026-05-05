package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;

import java.util.List;

/**
 * Heavier backdrop behind the ClickGUI, pause screen, and inventory screens.
 * Uses a darkening overlay (no GLSL post-process — fragile across MC versions).
 *
 * <p>Optionally applies an animated vignette and tint colour for extra flair.
 */
public class MenuBlurModule extends Module {

    private static MenuBlurModule INSTANCE;

    private final SliderSetting  darkness    = addSetting(new SliderSetting("Darkness",    0.55, 0.0, 0.9, 0.05));
    private final SliderSetting  blurRadius  = addSetting(new SliderSetting("Blur Radius",  6,   0,   16,  1));
    private final BooleanSetting vignette    = addSetting(new BooleanSetting("Vignette",   true));
    private final SliderSetting  vigStrength = addSetting(new SliderSetting("Vignette Strength", 0.4, 0.0, 1.0, 0.05));
    private final ColorSetting   tintColor   = addSetting(new ColorSetting("Tint Color",   0x00000000));
    private final BooleanSetting animateFade = addSetting(new BooleanSetting("Animate Fade", true));
    private final SliderSetting  fadeSpeed   = addSetting(new SliderSetting("Fade Speed", 0.15, 0.02, 0.5, 0.01));
    private final ModeSetting    applyTo     = addSetting(new ModeSetting("Apply To", "All Screens",
            List.of("All Screens", "Pause Only", "Inventory Only", "ClickGUI Only")));

    // Animated fade state (screen-open alpha, 0..1)
    private double currentAlpha = 0.0;

    public MenuBlurModule() {
        super("Menu Blur", "Dark backdrop with optional vignette for menus", Category.RENDER);
        INSTANCE = this;
    }

    // ---- Static API for screen/mixin code ----

    /** 0..1 darkness level (0 = no dim). Returns 0 when module is off. */
    public static double darkness() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return 0;
        return INSTANCE.animatedDarkness();
    }

    /** Desired pixel blur radius, 0 when disabled. */
    public static double blurRadius() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return 0;
        return INSTANCE.blurRadius.get();
    }

    /** Whether to draw the extra vignette inside the screen border. */
    public static boolean vignetteEnabled() {
        return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.vignette.get();
    }

    /** 0..1 vignette alpha. */
    public static double vignetteStrength() {
        return INSTANCE == null ? 0 : INSTANCE.vigStrength.get();
    }

    /** Tint ARGB (may be 0x00000000 for no tint). */
    public static int tintColor() {
        return INSTANCE == null ? 0 : INSTANCE.tintColor.get();
    }

    /** Which screen category to apply the effect to. */
    public static String applyTo() {
        return INSTANCE == null ? "All Screens" : INSTANCE.applyTo.get();
    }

    // ---- Tick ----

    @Override
    public void onTick() {
        // Animate the alpha fade-in when a screen is open
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean screenOpen = mc.screen != null;
        double target = screenOpen ? darkness.get() : 0.0;
        double spd    = fadeSpeed.get();
        if (animateFade.get()) {
            currentAlpha += (target - currentAlpha) * spd;
            if (Math.abs(currentAlpha - target) < 0.001) currentAlpha = target;
        } else {
            currentAlpha = target;
        }
    }

    @Override
    protected void onEnable() { currentAlpha = 0; }

    // ---- Helpers ----

    private double animatedDarkness() {
        return animateFade.get() ? currentAlpha : darkness.get();
    }
}
