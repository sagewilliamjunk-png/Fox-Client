package dev.kitsune.client.module.misc;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import net.minecraft.client.Minecraft;

/**
 * Drops the framerate cap to {@link KitsuneConfig#backgroundFpsLimit} when the
 * Minecraft window loses focus, restoring it when focus comes back. Saves
 * meaningful battery / GPU on laptops when alt-tabbed.
 *
 * <p>Ported from the legacy {@code AdaptiveFpsLimitFeature} into the proper
 * module system in v1.2. Poison-proofing carried over: we persist the user's
 * foreground FPS preference into Kitsune's own JSON, so a crash mid-throttle
 * can't leave them stuck at 30 fps on next launch.
 */
public class AdaptiveFpsLimitModule extends Module {

    private boolean throttled = false;

    public AdaptiveFpsLimitModule() {
        super("Adaptive FPS Limit",
              "Drops FPS cap when the window loses focus; restores when refocused.",
              Category.MISC);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        KitsuneConfig cfg = KitsuneConfig.get();
        if (cfg.foregroundFpsLimit <= 0) {
            int current = mc.options.framerateLimit().get();
            int bg = Math.max(1, cfg.backgroundFpsLimit);
            // Only trust the current vanilla value if it doesn't already look
            // throttled. Otherwise fall back to a sane 260fps default so the
            // user isn't stuck at the background cap after a crash-poisoned
            // options.txt.
            cfg.foregroundFpsLimit = (current <= bg) ? 260 : current;
            KitsuneConfig.save();
        }
        if (mc.isWindowActive()) {
            mc.options.framerateLimit().set(cfg.foregroundFpsLimit);
        }
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        int fg = KitsuneConfig.get().foregroundFpsLimit;
        if (fg > 0) mc.options.framerateLimit().set(fg);
        throttled = false;
    }

    @Override
    public void onTick() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null || client.options == null) return;
        KitsuneConfig cfg = KitsuneConfig.get();
        int fg = cfg.foregroundFpsLimit > 0 ? cfg.foregroundFpsLimit : 260;
        int bg = Math.max(1, cfg.backgroundFpsLimit);

        boolean focused = client.isWindowActive();
        int current = client.options.framerateLimit().get();
        int want    = focused ? fg : bg;

        // Idempotent: only write when the value needs to change.
        if (current != want) {
            client.options.framerateLimit().set(want);
        }
        throttled = !focused;
    }
}
