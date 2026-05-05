package dev.kitsune.client.module.movement;

import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Anti-AFK — periodically nudges the player's view by a tiny amount so
 * idle-kick servers don't disconnect you.
 *
 * <p><strong>Risk warning:</strong> some servers explicitly ban movement
 * automation. The module is gated behind {@link #iAcceptRisk} which the user
 * must enable manually. Without it, enabling the module is a no-op and a
 * warning toast is shown.
 */
public class AntiAfkModule extends Module {

    private final BooleanSetting iAcceptRisk = addSetting(new BooleanSetting("I accept the risk", false));
    private final SliderSetting interval = addSetting(new SliderSetting("Interval (s)", 30, 5, 120, 5));
    private final SliderSetting amount = addSetting(new SliderSetting("Yaw nudge", 1.5, 0.5, 10.0, 0.5));

    private long lastNudgeMs = 0;

    public AntiAfkModule() {
        super("Anti-AFK", "Tiny periodic view nudge to defeat idle kicks", Category.MOVEMENT);
    }

    @Override
    protected void onEnable() {
        if (!iAcceptRisk.get()) {
            NotificationManager.show(
                    "Anti-AFK requires \"I accept the risk\" to be enabled in settings",
                    NotificationManager.Type.WARNING);
            // Don't auto-disable — leave the toggle visibly on so the user
            // knows to flip the risk flag and re-enable.
        }
        lastNudgeMs = System.currentTimeMillis();
    }

    @Override
    public void onTick() {
        if (!iAcceptRisk.get()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        long now = System.currentTimeMillis();
        if (now - lastNudgeMs < interval.get() * 1000L) return;
        lastNudgeMs = now;
        float dyaw = (float) (amount.get() * (Math.random() < 0.5 ? -1 : 1));
        p.setYRot(p.getYRot() + dyaw);
    }
}
