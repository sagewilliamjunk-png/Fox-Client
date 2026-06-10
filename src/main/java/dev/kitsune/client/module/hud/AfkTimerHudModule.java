package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * AFK Timer — appears after the player has been idle (no rotation, no
 * movement, no held keys) for a configurable number of minutes and counts
 * how long they've been away. Useful for streamers and for noticing you
 * left yourself parked near an idle-kick window.
 *
 * <p>Display only — it never generates input (that's the gray-zone Anti-AFK
 * module's job, which is gated separately).
 */
public class AfkTimerHudModule extends BaseHudModule {

    private final SliderSetting thresholdMin = addSetting(new SliderSetting("Show After (min)", 2, 1, 15, 1));

    private long idleSinceMs = 0;
    private float lastYaw, lastPitch;
    private double lastX, lastY, lastZ;
    private boolean hasBaseline = false;

    public AfkTimerHudModule() {
        super("AFK Timer", "Shows how long you've been idle", Category.HUD,
                "afk_timer", "AFK");
        useStandardPanel(0.50, Palette.ACCENT_ORANGE);
        useTextColor();
    }

    @Override public int widgetWidth()  { return 78; }
    @Override public int widgetHeight() { return 14; }

    @Override
    public boolean isWidgetVisible() {
        return isEnabled() && idleMs() >= thresholdMs();
    }

    private long thresholdMs() { return (long) (thresholdMin.get() * 60_000); }

    private long idleMs() {
        if (idleSinceMs == 0) return 0;
        return System.currentTimeMillis() - idleSinceMs;
    }

    @Override
    protected void onDisable() {
        idleSinceMs = 0;
        hasBaseline = false;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { idleSinceMs = 0; hasBaseline = false; return; }

        boolean inputHeld = false;
        try {
            inputHeld = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                    || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
                    || mc.options.keyJump.isDown() || mc.options.keyAttack.isDown()
                    || mc.options.keyUse.isDown();
        } catch (Throwable ignored) {}

        boolean moved = !hasBaseline
                || p.getYRot() != lastYaw || p.getXRot() != lastPitch
                || p.getX() != lastX || p.getY() != lastY || p.getZ() != lastZ;

        lastYaw = p.getYRot(); lastPitch = p.getXRot();
        lastX = p.getX(); lastY = p.getY(); lastZ = p.getZ();
        hasBaseline = true;

        // Any rotation, motion, or held movement/mouse key resets the clock.
        // An open screen does NOT count as activity — parking in the pause
        // menu is still AFK.
        if (moved || inputHeld) {
            idleSinceMs = System.currentTimeMillis();
        } else if (idleSinceMs == 0) {
            idleSinceMs = System.currentTimeMillis();
        }
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        long idle = idleMs();
        if (idle < thresholdMs()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        long totalSec = idle / 1000;
        String s = totalSec >= 3600
                ? String.format("AFK %dh%02dm", totalSec / 3600, (totalSec % 3600) / 60)
                : String.format("AFK %dm%02ds", totalSec / 60, totalSec % 60);
        gfx.text(font, s, x + 2, y + 3, textArgb());
    }
}
