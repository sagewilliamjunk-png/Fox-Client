package dev.kitsune.client.features.qol;

import dev.kitsune.client.features.FoxFeature;
import net.minecraft.client.Minecraft;

/**
 * Toggle full brightness (gamma=15). When disabled, restores the previous gamma
 * value the user had before the feature took over.
 *
 * Server-safety: visual only, allowed everywhere. (Some hardcore servers consider
 * gamma manipulation an unfair advantage; for those, add a server rule that
 * forces this feature off via {@link dev.kitsune.client.server.ServerRule}.)
 */
public class FullBrightFeature implements FoxFeature {

    public static final String ID = "full_bright";
    public static final double FULL_BRIGHT = 15.0;

    private double savedGamma = -1.0;

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Full Brightness"; }
    @Override public String category() { return "visual"; }

    @Override
    public void onEnable() {
        // During onInitializeClient, Minecraft.getInstance() returns the
        // partially-constructed client *before* `options` is initialised.
        // Any feature that defaults to ON on the active profile hits this
        // path, so be defensive: null-guard and skip. The first client-tick
        // sync will pick the feature up once options exists.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        savedGamma = mc.options.gamma().get();
        mc.options.gamma().set(FULL_BRIGHT);
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        if (savedGamma >= 0.0) {
            mc.options.gamma().set(savedGamma);
        }
    }
}
