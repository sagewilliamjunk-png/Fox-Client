package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.util.ClickTracker;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Clicks-per-second readout for left and right mouse buttons.
 *
 * <p>Sampled at client-tick rate (~20 Hz) by {@link ClickTracker} edge
 * detection on {@code keyAttack} / {@code keyUse}. This is accurate up to
 * normal human click rates; butterfly/drag clicking beyond ~20 CPS will
 * undercount. Clicks within a rolling 1-second window are displayed.
 */
public class CpsHudModule extends BaseHudModule {

    private final BooleanSetting showLeft   = addSetting(new BooleanSetting("Show Left",  true));
    private final BooleanSetting showRight  = addSetting(new BooleanSetting("Show Right", true));
    private final BooleanSetting showLabels = addSetting(new BooleanSetting("Show Labels", true));
    private final ModeSetting    style      = addSetting(new ModeSetting("Style", "Horizontal",
            List.of("Horizontal", "Vertical")));
    private final BooleanSetting splitColors = addSetting(new BooleanSetting("Split L/R Colors", false));
    private final ColorSetting   lmbColor   = addSetting(new ColorSetting("Left Color",  Palette.LMB_BLUE));
    private final ColorSetting   rmbColor   = addSetting(new ColorSetting("Right Color", Palette.RMB_RED));

    private final ClickTracker lmb = new ClickTracker();
    private final ClickTracker rmb = new ClickTracker();
    private int lmbCps = 0, rmbCps = 0;

    public CpsHudModule() {
        super("CPS", "Clicks per second for left and right mouse", Category.HUD, "cps", "CPS");
        useStandardPanel(0.50, Palette.ACCENT_CYAN);
        useTextColor();
    }

    @Override
    public int widgetWidth() {
        boolean v = "Vertical".equals(style.get());
        boolean both = showLeft.get() && showRight.get();
        if (v) return showLabels.get() ? 54 : 34;
        return both ? (showLabels.get() ? 80 : 52) : (showLabels.get() ? 44 : 28);
    }

    @Override
    public int widgetHeight() {
        boolean v = "Vertical".equals(style.get());
        boolean both = showLeft.get() && showRight.get();
        if (!v) return 14;
        return both ? 24 : 12;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        long now = System.currentTimeMillis();

        lmb.tick(mc.options.keyAttack.isDown(), now);
        rmb.tick(mc.options.keyUse.isDown(), now);
        lmbCps = lmb.cps(now);
        rmbCps = rmb.cps(now);
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        int color = textArgb();
        boolean labels = showLabels.get();
        boolean split  = splitColors.get();
        int lColor = split ? lmbColor.get() : color;
        int rColor = split ? rmbColor.get() : color;

        if ("Vertical".equals(style.get())) {
            int cy = y + 2;
            if (showLeft.get()) {
                String s = labels ? ("L: " + lmbCps) : String.valueOf(lmbCps);
                gfx.text(font, s, x + 2, cy, lColor);
                cy += 12;
            }
            if (showRight.get()) {
                String s = labels ? ("R: " + rmbCps) : String.valueOf(rmbCps);
                gfx.text(font, s, x + 2, cy, rColor);
            }
        } else {
            // Horizontal: render L and R independently so each gets its own color.
            int cx = x + 2;
            if (showLeft.get()) {
                String s = (labels ? "L " : "") + lmbCps;
                gfx.text(font, s, cx, y + 3, lColor);
                cx += font.width(s) + 6;
            }
            if (showRight.get()) {
                String s = (labels ? "R " : "") + rmbCps;
                gfx.text(font, s, cx, y + 3, rColor);
            }
        }
    }
}
