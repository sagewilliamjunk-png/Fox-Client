package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * Compact XP readout: current level + percent to next + (optional) raw points
 * to next level. Useful for enchanting timing without squinting at the vanilla
 * green bar.
 *
 * <p>All data is read directly off the local {@link LocalPlayer} — no packets,
 * no server calls.
 */
public class XpHudModule extends BaseHudModule {

    private final BooleanSetting showProgressBar = addSetting(new BooleanSetting("Show Bar",      true));
    private final BooleanSetting showPercent     = addSetting(new BooleanSetting("Show Percent",  true));
    private final BooleanSetting showPointsToNext = addSetting(new BooleanSetting("Show XP To Next", false));

    public XpHudModule() {
        super("XP HUD", "Shows current level, percent to next, and optional raw XP", Category.HUD,
                "xp_hud", "XP");
        useStandardPanel(0.50, 0xFF5FE85F);
    }

    @Override
    public int widgetWidth() {
        int w = 80;
        if (showPointsToNext.get()) w = 110;
        return w;
    }

    @Override
    public int widgetHeight() {
        int h = 12;
        if (showProgressBar.get()) h += 4;
        return h + 4;
    }

    /** Vanilla XP-to-next-level formula. Public for testability. */
    static int xpToNext(int level) {
        if (level >= 30) return 112 + (level - 30) * 9;
        if (level >= 15) return 37 + (level - 15) * 5;
        return 7 + level * 2;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        drawPanel(gfx, x, y, w, h);

        if (p == null) {
            gfx.text(font, "§8XP —", x + 2, y + 3, Palette.TEXT_MUTED);
            return;
        }

        int level = p.experienceLevel;
        float prog = Math.max(0f, Math.min(1f, p.experienceProgress));
        int needed = xpToNext(level);
        int remaining = (int) Math.ceil(needed * (1.0 - prog));

        StringBuilder line = new StringBuilder();
        line.append("Lv ").append(level);
        if (showPercent.get()) {
            line.append("  ").append(Math.round(prog * 100f)).append('%');
        }
        if (showPointsToNext.get()) {
            line.append("  -").append(remaining).append("xp");
        }
        gfx.text(font, line.toString(), x + 2, y + 3, Palette.TEXT_WHITE);

        if (showProgressBar.get()) {
            int barY = y + h - 6;
            int barW = w - 4;
            // Track
            gfx.fill(x + 2, barY, x + 2 + barW, barY + 3, 0x80303030);
            // Fill
            int filled = Math.round(barW * prog);
            int color = (accentArgb() & 0x00FFFFFF) | 0xCC000000;
            if (filled > 0) {
                gfx.fill(x + 2, barY, x + 2 + filled, barY + 3, color);
            }
        }
    }
}
