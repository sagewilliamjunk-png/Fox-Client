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
 * Draggable rolling FPS graph + counter widget.
 * Shows current, average, min, and max FPS with a colour-coded history graph.
 */
public class FpsGraphModule extends Module implements HudWidget {

    private static final int MAX_SAMPLES = 120;

    private final SliderSetting  graphWidth   = addSetting(new SliderSetting("Width",  100, 50, 200, 5));
    private final SliderSetting  graphHeight  = addSetting(new SliderSetting("Height",  32, 16,  80, 4));
    private final SliderSetting  targetFps    = addSetting(new SliderSetting("Target FPS", 60, 30, 300, 10));
    private final BooleanSetting showNumber   = addSetting(new BooleanSetting("Show FPS number", true));
    private final BooleanSetting showAvg      = addSetting(new BooleanSetting("Show avg/min/max", true));
    private final BooleanSetting showRefLine  = addSetting(new BooleanSetting("Show target line", true));
    private final ColorSetting   goodColor    = addSetting(new ColorSetting("Good color",  0xFF33CC55));
    private final ColorSetting   midColor     = addSetting(new ColorSetting("Mid color",   0xFFFFAA00));
    private final ColorSetting   badColor     = addSetting(new ColorSetting("Bad color",   0xFFFF3333));
    private final ModeSetting    graphStyle   = addSetting(new ModeSetting("Style", "Bars",
            List.of("Bars", "Line", "Filled")));

    private final int[] fpsSamples = new int[MAX_SAMPLES];
    private int sampleIndex = 0;
    private int sampleCount = 0;
    private int tickCounter = 0;

    public FpsGraphModule() {
        super("FPS Graph", "Rolling FPS counter with history graph", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "fps_graph"; }
    @Override public String displayName() { return "FPS Graph"; }
    @Override public int widgetWidth()    { return graphWidth.get().intValue() + 4; }
    @Override public int widgetHeight() {
        int h = graphHeight.get().intValue();
        if (showNumber.get()) h += 12;
        if (showAvg.get())    h += 10;
        return h + 4;
    }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void onTick() {
        tickCounter++;
        if (tickCounter % 2 == 0) {
            fpsSamples[sampleIndex] = Minecraft.getInstance().getFps();
            sampleIndex = (sampleIndex + 1) % MAX_SAMPLES;
            if (sampleCount < MAX_SAMPLES) sampleCount++;
        }
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int gw = graphWidth.get().intValue();
        int gh = graphHeight.get().intValue();
        int target = targetFps.get().intValue();

        int currentFps = mc.getFps();
        int good  = goodColor.get();
        int mid   = midColor.get();
        int bad   = badColor.get();

        // Stats
        int minFps = Integer.MAX_VALUE, maxFps = 0, sumFps = 0, count = 0;
        for (int i = 0; i < sampleCount; i++) {
            int v = fpsSamples[i];
            if (v < minFps) minFps = v;
            if (v > maxFps) maxFps = v;
            sumFps += v;
            count++;
        }
        if (count == 0) { minFps = 0; maxFps = 1; }
        int avgFps = count > 0 ? sumFps / count : 0;
        int graphMax = Math.max(maxFps, target + 10);

        int curY = y + 2;
        int w = widgetWidth();

        // Background
        gfx.fill(x - 2, y - 2, x + w + 2, y + widgetHeight() + 2, 0x90000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, fpsColor(currentFps, target, good, mid, bad));

        // Current FPS number
        if (showNumber.get()) {
            int col = fpsColor(currentFps, target, good, mid, bad);
            gfx.text(font, currentFps + " FPS", x + 2, curY, col);
            curY += 12;
        }

        // Graph area
        int gy = curY;
        gfx.fill(x, gy, x + gw, gy + gh, 0x50000000);
        gfx.fill(x, gy, x + 1, gy + gh, 0x40FFFFFF);
        gfx.fill(x, gy + gh - 1, x + gw, gy + gh, 0x40FFFFFF);

        // Reference line at target FPS
        if (showRefLine.get() && graphMax > 0) {
            int refY = gy + gh - (int)(target / (float) graphMax * gh);
            refY = Math.max(gy, Math.min(gy + gh - 1, refY));
            for (int i = 0; i < gw; i += 3) {
                gfx.fill(x + i, refY, x + i + 2, refY + 1, 0x80FFFFFF);
            }
        }

        String style = graphStyle.get();
        int prevBarH = -1;
        for (int i = 0; i < gw && i < MAX_SAMPLES; i++) {
            int idx = (sampleIndex + i) % MAX_SAMPLES;
            int fps = fpsSamples[idx];
            int barH = graphMax > 0 ? Math.min(gh, (int)((float) fps / graphMax * gh)) : 0;
            int col = fpsColor(fps, target, good, mid, bad);

            if ("Bars".equals(style)) {
                if (barH > 0) gfx.fill(x + i, gy + gh - barH, x + i + 1, gy + gh, col);
            } else if ("Filled".equals(style)) {
                if (barH > 0) gfx.fill(x + i, gy + gh - barH, x + i + 1, gy + gh, (col & 0x00FFFFFF) | 0xA0000000);
                gfx.fill(x + i, gy + gh - barH, x + i + 1, gy + gh - barH + 1, col);
            } else { // Line
                if (prevBarH >= 0) {
                    int y1 = gy + gh - prevBarH;
                    int y2 = gy + gh - barH;
                    int lo = Math.min(y1, y2), hi = Math.max(y1, y2);
                    gfx.fill(x + i - 1, lo, x + i, hi + 1, col);
                }
                gfx.fill(x + i, gy + gh - barH, x + i + 1, gy + gh - barH + 1, col);
            }
            prevBarH = barH;
        }
        curY = gy + gh + 2;

        // Avg/Min/Max row
        if (showAvg.get()) {
            String stats = String.format("avg%d lo%d hi%d", avgFps, minFps, maxFps);
            gfx.text(font, stats, x + 2, curY, 0xFF999999);
        }
    }

    private static int fpsColor(int fps, int target, int good, int mid, int bad) {
        if (fps >= target)          return good;
        if (fps >= target * 2 / 3) return mid;
        return bad;
    }
}
