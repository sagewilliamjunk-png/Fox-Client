package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Target HUD — shows the entity under the player's crosshair.
 *
 * Displays: entity name, health bar (green → yellow → red), current/max HP,
 * and optionally the distance in blocks. All data comes from client-side
 * entity state — no packet spoofing or extra server queries.
 *
 * The widget fades out when no entity is targeted and disappears when disabled.
 * Only {@link LivingEntity} targets are shown (has health); non-living entities
 * like dropped items or boats are ignored.
 */
public class TargetHudModule extends Module implements HudWidget {

    // Fade constants (seconds)
    private static final float HOLD_SECS  = 2.0f;
    private static final float FADE_SECS  = 0.5f;

    private final BooleanSetting showDistance = addSetting(new BooleanSetting("Show Distance", true));
    private final BooleanSetting showArmor    = addSetting(new BooleanSetting("Show Armor",    true));
    private final SliderSetting  widthSetting = addSetting(new SliderSetting("Width", 130, 80, 220, 10));

    // Cached data — updated each tick
    private String  targetName     = null;
    private float   targetHp       = 0;
    private float   targetMaxHp    = 20;
    private float   targetDist     = 0;
    private int     targetArmor    = 0;
    private boolean targetIsPlayer = false;

    // Visibility fade: stays at 1.0 while targeted, counts down when lost
    private float fadeTimer = 0f; // seconds remaining

    public TargetHudModule() {
        super("Target HUD",
              "Shows the crosshair target's name, health bar, and distance. Like Lunar Client's Target HUD.",
              Category.HUD);
        HudManager.register(this);
    }

    // ---- HudWidget --------------------------------------------------------

    @Override public String widgetId()    { return "target_hud"; }
    @Override public String displayName() { return "Target HUD"; }
    @Override public int widgetWidth()    { return widthSetting.get().intValue(); }
    @Override public int widgetHeight()   { return showArmor.get() ? 38 : 30; }
    @Override public boolean isWidgetVisible() { return isEnabled() && (targetName != null || fadeTimer > 0); }

    // ---- Module -----------------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            targetName = null;
            return;
        }
        LocalPlayer self = mc.player;

        Entity target = mc.crosshairPickEntity;
        if (!(target instanceof LivingEntity living)) {
            // No living entity targeted — start fade countdown if we had one
            if (targetName != null) {
                targetName = null;
                fadeTimer = HOLD_SECS + FADE_SECS;
            }
            return;
        }

        // Populate cached info
        Component nameComp = living.getDisplayName();
        targetName     = nameComp != null ? nameComp.getString() : living.getClass().getSimpleName();
        targetHp       = living.getHealth();
        targetMaxHp    = Math.max(1, living.getMaxHealth());
        targetDist     = (float) self.distanceTo(living);
        targetArmor    = living.getArmorValue(); // 0–20 points (each point = half armor icon)
        targetIsPlayer = living instanceof Player;
        fadeTimer      = 0; // actively targeted — no fade
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Opacity: full while targeted, fading when recently lost.
        float alpha;
        if (targetName != null) {
            alpha = 1.0f;
        } else if (fadeTimer > 0) {
            // Only the FADE_SECS tail is actually a fade; HOLD_SECS is full opacity
            fadeTimer -= 0.05f; // approx per-frame at 20 tps — good enough
            alpha = Math.max(0, Math.min(1, (fadeTimer - HOLD_SECS) / FADE_SECS + 1));
            if (fadeTimer <= 0) { fadeTimer = 0; return; }
        } else {
            return;
        }

        int w = widthSetting.get().intValue();
        int h = widgetHeight();
        int ia = (int)(alpha * 0xFF);

        // ---- Background card ----
        int bgColor  = (ia * 0x88 / 0xFF) << 24 | 0x0D0D14;
        int barBg    = (ia * 0x55 / 0xFF) << 24 | 0x111111;
        gfx.fill(x, y, x + w, y + h, bgColor);
        // Thin accent line at top (orange for players, lime for mobs)
        int accentA = (ia) << 24;
        int accent  = targetIsPlayer ? (accentA | 0xFF8830) : (accentA | 0x44CC66);
        gfx.fill(x, y, x + w, y + 1, accent);

        // ---- Name ----
        String displayName = targetName != null ? targetName : "???";
        if (font.width(displayName) > w - 8) {
            // Truncate with ellipsis
            while (displayName.length() > 1 && font.width(displayName + "…") > w - 8) {
                displayName = displayName.substring(0, displayName.length() - 1);
            }
            displayName += "…";
        }
        int nameColor = (ia << 24) | (targetIsPlayer ? 0xFFFFAA : 0xEEEEEE);
        gfx.drawString(font, displayName, x + 4, y + 4, nameColor, false);

        // ---- Distance (optional) ----
        if (showDistance.get() && targetName != null) {
            String distText = String.format("%.1fm", targetDist);
            int dtW = font.width(distText);
            int mutedColor = (ia << 24) | 0x888888;
            gfx.drawString(font, distText, x + w - dtW - 4, y + 4, mutedColor, false);
        }

        // ---- Health bar ----
        int barY   = y + 15;
        int barH   = 4;
        int barPad = 4;
        int barW   = w - barPad * 2;
        float frac = Math.max(0, Math.min(1, targetHp / targetMaxHp));

        // Background
        gfx.fill(x + barPad, barY, x + barPad + barW, barY + barH, barBg);

        // Filled portion — gradient from green (full) → yellow (mid) → red (low)
        int filledW = Math.round(frac * barW);
        if (filledW > 0) {
            int barColor = healthColor(frac, ia);
            gfx.fill(x + barPad, barY, x + barPad + filledW, barY + barH, barColor);
        }

        // ---- HP text ----
        String hpText = Math.round(targetHp) + " / " + Math.round(targetMaxHp);
        int hpW = font.width(hpText);
        int hpColor = (ia << 24) | 0xCCCCCC;
        gfx.drawString(font, hpText, x + (w - hpW) / 2, barY + barH + 3, hpColor, false);

        // ---- Armor row (optional) ----
        if (showArmor.get()) {
            int armorY = y + h - 10;
            String armorLabel = "Armor: ";
            int labelW = font.width(armorLabel);
            gfx.drawString(font, armorLabel, x + 4, armorY, (ia << 24) | 0x888888, false);
            // Draw armor points as small filled squares (like Minecraft's armor icons but simplified)
            int dotX = x + 4 + labelW;
            int full  = targetArmor / 2;  // full armor icons
            int half  = targetArmor % 2;  // half icon
            int empty = 10 - full - half; // empty icons
            for (int i = 0; i < full;  i++) { gfx.fill(dotX + i*6, armorY + 1, dotX + i*6 + 4, armorY + 5, (ia << 24) | 0xBBBBBB); }
            dotX += full * 6;
            if (half > 0) { gfx.fill(dotX, armorY + 1, dotX + 2, armorY + 5, (ia << 24) | 0x777777); dotX += 6; }
            for (int i = 0; i < empty; i++) { gfx.fill(dotX + i*6, armorY + 1, dotX + i*6 + 4, armorY + 5, (ia << 24) | 0x333333); }
        }
    }

    // ---- helpers ----------------------------------------------------------

    /** Returns an ARGB int for the health bar colour. */
    private static int healthColor(float frac, int ia) {
        // Green at 1.0, yellow at 0.5, red at 0.0
        int r, g;
        if (frac > 0.5f) {
            // Green → Yellow (0.5–1.0)
            float t = (frac - 0.5f) * 2f;  // 0–1
            r = (int)((1f - t) * 0xFF);     // 0 at full, 255 at yellow
            g = 0xFF;
        } else {
            // Yellow → Red (0–0.5)
            float t = frac * 2f;            // 0–1
            r = 0xFF;
            g = (int)(t * 0xAA);
        }
        return (ia << 24) | (r << 16) | (g << 8);
    }
}
