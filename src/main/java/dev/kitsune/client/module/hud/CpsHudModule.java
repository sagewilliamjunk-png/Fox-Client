package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * Clicks-per-second readout for left and right mouse buttons.
 *
 * <p>Sampled at client-tick rate (~20 Hz) by detecting rising edges of
 * {@code keyAttack} / {@code keyUse}. This is accurate up to normal
 * human click rates; butterfly/drag clicking beyond ~20 CPS will undercount.
 * Clicks within a rolling 1-second window are displayed.
 */
public class CpsHudModule extends Module implements HudWidget {

    private static final int WINDOW_MS = 1000;
    private static final int BUFFER = 64;

    private final BooleanSetting showLeft   = addSetting(new BooleanSetting("Show Left",  true));
    private final BooleanSetting showRight  = addSetting(new BooleanSetting("Show Right", true));
    private final BooleanSetting showLabels = addSetting(new BooleanSetting("Show Labels", true));
    private final ModeSetting    style      = addSetting(new ModeSetting("Style", "Horizontal",
            List.of("Horizontal", "Vertical")));
    private final SliderSetting  bgOpacity  = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent     = addSetting(new ColorSetting("Accent",      0xFF44CCCC));
    private final ColorSetting   textColor  = addSetting(new ColorSetting("Text Color",  0xFFFFFFFF));
    private final BooleanSetting splitColors = addSetting(new BooleanSetting("Split L/R Colors", false));
    private final ColorSetting   lmbColor   = addSetting(new ColorSetting("Left Color",  0xFF4090FF));
    private final ColorSetting   rmbColor   = addSetting(new ColorSetting("Right Color", 0xFFFF5050));

    private final long[] lmbClicks = new long[BUFFER];
    private final long[] rmbClicks = new long[BUFFER];
    private int lmbHead = 0, rmbHead = 0;
    private int lmbCps = 0, rmbCps = 0;
    private boolean prevAttack = false, prevUse = false;

    public CpsHudModule() {
        super("CPS", "Clicks per second for left and right mouse", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "cps"; }
    @Override public String displayName() { return "CPS"; }

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

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        long now = System.currentTimeMillis();

        boolean attack = mc.options.keyAttack.isDown();
        boolean use    = mc.options.keyUse.isDown();
        if (attack && !prevAttack) { lmbClicks[lmbHead % BUFFER] = now; lmbHead++; }
        if (use    && !prevUse)    { rmbClicks[rmbHead % BUFFER] = now; rmbHead++; }
        prevAttack = attack;
        prevUse = use;

        lmbCps = countWithin(lmbClicks, now);
        rmbCps = countWithin(rmbClicks, now);
    }

    private static int countWithin(long[] buf, long now) {
        long cutoff = now - WINDOW_MS;
        int c = 0;
        for (long t : buf) if (t >= cutoff) c++;
        return c;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();
        int bgAlpha = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bgAlpha | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        int color = textColor.get();
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
