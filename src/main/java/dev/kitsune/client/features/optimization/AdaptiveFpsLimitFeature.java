package dev.kitsune.client.features.optimization;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.features.FoxFeature;
import net.minecraft.client.Minecraft;

/**
 * Drops the framerate cap to {@link KitsuneConfig#backgroundFpsLimit} when the
 * Minecraft window loses focus, restoring it when focus comes back. Saves
 * meaningful battery / GPU on laptops when alt-tabbed.
 *
 * <p>Poison-proofing: the throttle directly mutates vanilla's {@code
 * framerateLimit} option, which is serialised to {@code options.txt} on
 * shutdown. If the client ever crashes or is killed while throttled, the
 * throttled value ends up as the user's saved preference — they'd launch
 * next time stuck at 30fps with no visible cause.
 *
 * <p>To prevent that: on first enable we capture the current limit into
 * {@link KitsuneConfig#foregroundFpsLimit} (persisted to Kitsune's own JSON,
 * not vanilla's). Every focused tick we force vanilla's limit back to that
 * captured value. Even if a prior session poisoned vanilla's options.txt,
 * the next launch reads Kitsune's untainted copy and restores correctly.
 *
 * <p>Server-safety: not gameplay-relevant, allowed everywhere.
 */
public class AdaptiveFpsLimitFeature implements FoxFeature {
    public static final String ID = "fps_limit";
    /** True when the feature currently has vanilla set to the background cap. */
    private boolean throttled = false;

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Adaptive FPS Limit"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public String category() { return "optimization"; }

    @Override
    public void onEnable() {
        // Capture the user's foreground FPS preference the first time this
        // feature ever runs. Uses a guarded sentinel (-1) so we don't
        // overwrite a good value with a poisoned one on a later launch.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        KitsuneConfig cfg = KitsuneConfig.get();
        if (cfg.foregroundFpsLimit <= 0) {
            int current = mc.options.framerateLimit().get();
            int bg = Math.max(1, cfg.backgroundFpsLimit);
            // Only trust the current vanilla value if it doesn't already
            // look like a throttled value. Otherwise fall back to a sane
            // default so the user isn't stuck at 30fps.
            cfg.foregroundFpsLimit = (current <= bg) ? 260 : current;
            KitsuneConfig.save();
        }
        // Eagerly correct any poisoned vanilla limit.
        if (mc.isWindowActive()) {
            mc.options.framerateLimit().set(cfg.foregroundFpsLimit);
        }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        // Always restore the canonical foreground value on disable.
        int fg = KitsuneConfig.get().foregroundFpsLimit;
        if (fg > 0) mc.options.framerateLimit().set(fg);
        throttled = false;
    }

    @Override
    public void tick(Minecraft client) {
        if (client == null || client.getWindow() == null || client.options == null) return;
        KitsuneConfig cfg = KitsuneConfig.get();
        int fg = cfg.foregroundFpsLimit > 0 ? cfg.foregroundFpsLimit : 260;
        int bg = Math.max(1, cfg.backgroundFpsLimit);

        boolean focused = client.isWindowActive();
        int current = client.options.framerateLimit().get();
        int want    = focused ? fg : bg;

        // Idempotent: only write when the value actually needs to change,
        // so we're not hammering vanilla's Option setter every tick.
        if (current != want) {
            client.options.framerateLimit().set(want);
        }
        throttled = !focused;
    }
}
