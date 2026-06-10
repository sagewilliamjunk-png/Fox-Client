package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.util.Palette;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Draggable session K/D widget.
 * Tracks kills, deaths, kill streak, and a colour-coded K/D ratio.
 */
public class KillDeathTrackerModule extends BaseHudModule {

    private final BooleanSetting showStreak   = addSetting(new BooleanSetting("Show Kill Streak", true));
    private final BooleanSetting showRatio    = addSetting(new BooleanSetting("Show K/D Ratio",   true));
    private final BooleanSetting compactMode  = addSetting(new BooleanSetting("Compact Mode",      false));
    private final BooleanSetting showBar      = addSetting(new BooleanSetting("Show Ratio Bar",    true));
    private final SliderSetting  goodKd       = addSetting(new SliderSetting("Good K/D",  2.0, 0.5, 5.0, 0.5));
    private final SliderSetting  okKd         = addSetting(new SliderSetting("OK K/D",    1.0, 0.1, 3.0, 0.1));
    private final ModeSetting    accentColor  = addSetting(new ModeSetting("Accent", "Gold",
            List.of("Gold", "Red", "Blue", "White")));

    private int kills      = 0;
    private int deaths     = 0;
    private int streak     = 0;
    private int bestStreak = 0;
    private boolean wasDead    = false;
    private int lastKillStat   = -1;

    public KillDeathTrackerModule() {
        super("K/D Tracker", "Session kill/death counter with streak", Category.HUD,
                "kill_death", "K/D");
    }

    /** Bespoke appearance: pre-refactor hardcoded background + ModeSetting accent. */
    @Override protected int bgArgb()     { return Palette.PANEL_BG_LEGACY; }
    @Override protected int accentArgb() { return accentModeArgb(); }

    @Override
    public int widgetWidth() { return compactMode.get() ? 90 : 110; }

    @Override
    public int widgetHeight() {
        if (compactMode.get()) return 14;
        int rows = 3; // kills, deaths, ratio (always)
        if (showStreak.get()) rows++;
        if (showBar.get() && showRatio.get()) rows++;
        return rows * 10 + 8;
    }

    @Override
    protected void onEnable() {
        kills = 0; deaths = 0; streak = 0; bestStreak = 0;
        wasDead = false; lastKillStat = -1;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Death detection
        boolean isDead = mc.player.isDeadOrDying();
        if (isDead && !wasDead) {
            deaths++;
            streak = 0; // reset streak on death
        }
        wasDead = isDead;

        // Kill detection via stat tracker
        try {
            int current = mc.player.getStats() != null
                    ? mc.player.getStats().getValue(
                            net.minecraft.stats.Stats.CUSTOM,
                            net.minecraft.stats.Stats.MOB_KILLS)
                    : 0;
            if (lastKillStat >= 0 && current > lastKillStat) {
                int gained = current - lastKillStat;
                kills  += gained;
                streak += gained;
                if (streak > bestStreak) bestStreak = streak;
            }
            lastKillStat = current;
        } catch (Throwable ignored) {}
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();
        double kd = deaths > 0 ? (double) kills / deaths : kills;
        drawPanel(gfx, x, y, w, h);

        // Compact: single line
        if (compactMode.get()) {
            String line = "K " + kills + "  D " + deaths + "  " + String.format("%.2f", kd);
            gfx.text(font, line, x + 2, y + 3, 0xFFFFFFFF);
            return;
        }

        int curY = y + 2;
        int rowH = 10;

        // Kills
        gfx.text(font, "\u2694 Kills   " + kills, x + 2, curY, 0xFF55FF55);
        curY += rowH;

        // Deaths
        gfx.text(font, "\u2620 Deaths  " + deaths, x + 2, curY, 0xFFFF5555);
        curY += rowH;

        // K/D ratio
        if (showRatio.get()) {
            double g = goodKd.get();
            double o = okKd.get();
            int kdColor = kd >= g ? 0xFF55FF55 : kd >= o ? 0xFFFFFF55 : 0xFFFF5555;
            gfx.text(font, "K/D  " + String.format("%.2f", kd), x + 2, curY, kdColor);
            curY += rowH;

            if (showBar.get()) {
                int barW = w - 6;
                float pct = (float) Math.min(1.0, kd / g);
                gfx.fill(x + 2, curY - 3, x + 2 + barW, curY, 0xFF222222);
                gfx.fill(x + 2, curY - 3, x + 2 + Math.max(2, (int)(barW * pct)), curY, kdColor);
                curY += 4;
            }
        }

        // Kill streak
        if (showStreak.get()) {
            String streakStr = "\u26a1 Streak  " + streak;
            if (bestStreak > 0 && bestStreak > streak) {
                streakStr += " (best " + bestStreak + ")";
            }
            int streakColor = streak >= 5 ? 0xFFFFCC00
                    : streak >= 3 ? 0xFFFFAA33
                    : 0xFFCCCCCC;
            gfx.text(font, streakStr, x + 2, curY, streakColor);
        }
    }

    // ---- helpers ----

    private int accentModeArgb() {
        return switch (accentColor.get()) {
            case "Red"   -> 0xFFFF4444;
            case "Blue"  -> 0xFF4488FF;
            case "White" -> 0xFFDDDDDD;
            default      -> 0xFFFFCC33; // Gold
        };
    }
}
