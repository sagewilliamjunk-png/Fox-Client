package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/**
 * One-stop performance dashboard: FPS, server-TPS estimate, JVM heap, and
 * round-trip ping. Designed to replace having FpsGraph + ServerTps + Memory
 * widgets all on at once if you only need a glance.
 *
 * <p>All inputs are cheap, derived data:
 * <ul>
 *   <li>FPS — {@code Minecraft.getFps()} (a smoothed value vanilla already maintains).</li>
 *   <li>TPS — measured the same way as {@link ServerTpsHudModule}: wall-clock
 *       deltas between game-time increments, smoothed across a 40-sample window.</li>
 *   <li>Heap — {@code Runtime.getRuntime()} totals; refreshed once per tick to
 *       avoid the small allocation cost of repeated calls per frame.</li>
 *   <li>Ping — {@code mc.getConnection().getPlayerInfo(uuid).getLatency()},
 *       same value the tab list uses.</li>
 * </ul>
 *
 * <p>Sodium-compat note: pure GuiGraphics calls, no shader binding, no GL
 * state changes. Safe under any rendering backend.
 */
public class PerfDashboardModule extends Module implements HudWidget {

    private static final int TPS_WINDOW = 40;

    private final BooleanSetting showFps  = addSetting(new BooleanSetting("Show FPS",  true));
    private final BooleanSetting showTps  = addSetting(new BooleanSetting("Show TPS",  true));
    private final BooleanSetting showRam  = addSetting(new BooleanSetting("Show RAM",  true));
    private final BooleanSetting showPing = addSetting(new BooleanSetting("Show Ping", true));
    private final BooleanSetting colorize = addSetting(new BooleanSetting("Colorize",  true));
    private final SliderSetting  bgOpacity = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent   = addSetting(new ColorSetting("Accent", 0xFF44CCCC));

    // TPS estimator state
    private final long[] gaps = new long[TPS_WINDOW];
    private int gapHead = 0, gapCount = 0;
    private long lastGameTime = -1, lastWallMs = 0;

    // Heap snapshot — refreshed per tick
    private long heapUsedMb = 0, heapMaxMb = 0;

    public PerfDashboardModule() {
        super("Perf Dashboard", "Compact FPS / TPS / RAM / ping read-out", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "perf_dashboard"; }
    @Override public String displayName() { return "Perf"; }
    @Override public int widgetWidth()    { return 130; }
    @Override public int widgetHeight() {
        int rows = 0;
        if (showFps.get())  rows++;
        if (showTps.get())  rows++;
        if (showRam.get())  rows++;
        if (showPing.get()) rows++;
        return 4 + Math.max(1, rows) * 10;
    }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    protected void onDisable() {
        gapHead = 0; gapCount = 0; lastGameTime = -1; lastWallMs = 0;
        heapUsedMb = heapMaxMb = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();

        // ---- TPS ----
        if (mc.level == null) { lastGameTime = -1; }
        else {
            long gt = mc.level.getGameTime();
            long now = System.currentTimeMillis();
            if (lastGameTime < 0) { lastGameTime = gt; lastWallMs = now; }
            else if (gt > lastGameTime) {
                long delta = gt - lastGameTime;
                long wall  = now - lastWallMs;
                long mspt  = (delta > 0) ? (wall / delta) : wall;
                gaps[gapHead] = mspt;
                gapHead = (gapHead + 1) % TPS_WINDOW;
                if (gapCount < TPS_WINDOW) gapCount++;
                lastGameTime = gt;
                lastWallMs = now;
            }
        }

        // ---- Heap ----
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        heapUsedMb = used / (1024 * 1024);
        heapMaxMb  = rt.maxMemory() / (1024 * 1024);
    }

    private double avgMspt() {
        if (gapCount == 0) return 50.0;
        long s = 0;
        for (int i = 0; i < gapCount; i++) s += gaps[i];
        return s / (double) gapCount;
    }

    private static int fpsColor(int fps) {
        if (fps >= 60) return 0xFF55FF55;
        if (fps >= 30) return 0xFFFFAA00;
        return 0xFFFF3333;
    }

    private static int tpsColor(double tps) {
        if (tps >= 19.0) return 0xFF55FF55;
        if (tps >= 15.0) return 0xFFFFAA00;
        return 0xFFFF3333;
    }

    private static int ramColor(long usedMb, long maxMb) {
        if (maxMb <= 0) return 0xFFCCCCCC;
        double pct = usedMb / (double) maxMb;
        if (pct < 0.6) return 0xFF55FF55;
        if (pct < 0.85) return 0xFFFFAA00;
        return 0xFFFF3333;
    }

    private static int pingColor(int ms) {
        if (ms < 60)  return 0xFF55FF55;
        if (ms < 200) return 0xFFFFAA00;
        return 0xFFFF3333;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        int bg = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        boolean color = colorize.get();
        int rowY = y + 2;

        if (showFps.get()) {
            int fps = mc.getFps();
            int c = color ? fpsColor(fps) : 0xFFFFFFFF;
            gfx.drawString(font, "FPS: " + fps, x + 2, rowY, c, false);
            rowY += 10;
        }

        if (showTps.get()) {
            double tps = Math.min(20.0, 1000.0 / Math.max(1.0, avgMspt()));
            int c = color ? tpsColor(tps) : 0xFFFFFFFF;
            gfx.drawString(font, String.format("TPS: %.1f", tps), x + 2, rowY, c, false);
            rowY += 10;
        }

        if (showRam.get()) {
            int c = color ? ramColor(heapUsedMb, heapMaxMb) : 0xFFFFFFFF;
            gfx.drawString(font,
                    String.format("RAM: %d / %d MB", heapUsedMb, heapMaxMb),
                    x + 2, rowY, c, false);
            rowY += 10;
        }

        if (showPing.get()) {
            int ms = -1;
            if (mc.player != null && mc.getConnection() != null) {
                UUID self = mc.player.getUUID();
                PlayerInfo info = mc.getConnection().getPlayerInfo(self);
                if (info != null) ms = Math.max(0, info.getLatency());
            }
            String txt = ms < 0 ? "Ping: —" : ("Ping: " + ms + "ms");
            int c = (color && ms >= 0) ? pingColor(ms) : 0xFFFFFFFF;
            gfx.drawString(font, txt, x + 2, rowY, c, false);
        }
    }
}
