package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import net.minecraft.client.Minecraft;

/**
 * Forces maximum gamma so every surface is fully lit — equivalent to
 * a gamma/brightness slider cranked to the maximum without requiring a
 * night-vision potion.
 *
 * <p>Saves and restores the player's original gamma value on toggle so
 * their Options preference is not permanently overwritten.
 */
public class FullBrightnessModule extends Module {

    /** Gamma value that makes every surface fully visible. */
    private static final double FULL_BRIGHT_GAMMA = 16.0;

    /** Saved gamma value so we can restore it when the module is disabled. */
    private double savedGamma = -1;

    public FullBrightnessModule() {
        super("Full Brightness", "Maximum gamma — see everything without night-vision.", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        savedGamma = mc.options.gamma.get();
        mc.options.gamma.set(FULL_BRIGHT_GAMMA);
    }

    @Override
    protected void onDisable() {
        if (savedGamma < 0) return;
        Minecraft mc = Minecraft.getInstance();
        mc.options.gamma.set(savedGamma);
        savedGamma = -1;
    }
}
