package dev.kitsune.client.module.movement;

import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Anti-AFK — periodically nudges the player so idle-kick servers don't
 * disconnect you. Four modes:
 * <ul>
 *   <li><b>Yaw</b> — rotate the camera a few degrees. Lowest visibility but
 *       newer anti-cheats can detect rotation-only packets without movement.</li>
 *   <li><b>Jump</b> — press the jump key for 2 ticks. Mirrors what a real
 *       player would do; produces a normal movement packet.</li>
 *   <li><b>Sneak</b> — toggle sneak briefly. Even more passive than jump.</li>
 *   <li><b>Random</b> — pick one of the above per cycle.</li>
 * </ul>
 *
 * <p><strong>Risk warning:</strong> some servers explicitly ban movement
 * automation. The module is gated behind {@link #iAcceptRisk} which the user
 * must enable manually. Without it, enabling the module is a no-op and a
 * warning toast is shown.
 */
public class AntiAfkModule extends Module {

    private final BooleanSetting iAcceptRisk = addSetting(new BooleanSetting("I accept the risk", false));
    private final SliderSetting interval = addSetting(new SliderSetting("Interval (s)", 30, 5, 120, 5));
    private final SliderSetting amount = addSetting(new SliderSetting("Yaw nudge (deg)", 1.5, 0.5, 10.0, 0.5));
    private final ModeSetting   mode    = addSetting(new ModeSetting("Mode", "Yaw",
            List.of("Yaw", "Jump", "Sneak", "Random")));

    private long lastNudgeMs = 0;
    /** When > 0, we're mid-press of jump/sneak — count down to release. */
    private int holdTicksRemaining = 0;
    /** Which key is currently held during the multi-tick press. */
    private KeyMapping holdingKey = null;

    public AntiAfkModule() {
        super("Anti-AFK", "Tiny periodic nudge to defeat idle kicks", Category.MOVEMENT);
    }

    @Override
    protected void onEnable() {
        if (!iAcceptRisk.get()) {
            NotificationManager.show(
                    "Anti-AFK requires \"I accept the risk\" to be enabled in settings",
                    NotificationManager.Type.WARNING);
        }
        lastNudgeMs = System.currentTimeMillis();
        holdTicksRemaining = 0;
        holdingKey = null;
    }

    @Override
    protected void onDisable() {
        // Defensive: release whatever key we may have been holding.
        releaseHeldKey();
    }

    @Override
    public void onTick() {
        if (!iAcceptRisk.get()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        // Currently mid-press — count down the hold and release when done.
        if (holdTicksRemaining > 0) {
            holdTicksRemaining--;
            if (holdTicksRemaining == 0) releaseHeldKey();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastNudgeMs < interval.get() * 1000L) return;
        lastNudgeMs = now;

        String chosen = mode.get();
        if ("Random".equals(chosen)) {
            int r = (int) (Math.random() * 3);
            chosen = r == 0 ? "Yaw" : r == 1 ? "Jump" : "Sneak";
        }
        switch (chosen) {
            case "Yaw" -> {
                float dyaw = (float) (amount.get() * (Math.random() < 0.5 ? -1 : 1));
                p.setYRot(p.getYRot() + dyaw);
            }
            case "Jump"  -> beginHold(mc.options.keyJump);
            case "Sneak" -> beginHold(mc.options.keyShift);
            default      -> { /* unknown mode — no-op */ }
        }
    }

    private void beginHold(KeyMapping key) {
        if (key == null) return;
        try {
            key.setDown(true);
            holdingKey = key;
            holdTicksRemaining = 2; // hold for two ticks, then release
        } catch (Throwable t) {
            dev.kitsune.client.KitsuneClient.LOGGER.warn("[AntiAfk] beginHold failed: {}", t.toString());
        }
    }

    private void releaseHeldKey() {
        if (holdingKey == null) return;
        try { holdingKey.setDown(false); } catch (Throwable ignored) {}
        holdingKey = null;
        holdTicksRemaining = 0;
    }
}
