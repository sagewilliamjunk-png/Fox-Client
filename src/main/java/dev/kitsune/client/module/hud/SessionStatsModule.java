package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Draggable session stats widget.
 * Tracks play time, distance walked, current speed, and XP level.
 */
public class SessionStatsModule extends Module implements HudWidget {

    private final BooleanSetting showTime     = addSetting(new BooleanSetting("Show Time",      true));
    private final BooleanSetting showDistance = addSetting(new BooleanSetting("Show Distance",  true));
    private final BooleanSetting showSpeed    = addSetting(new BooleanSetting("Show Speed",     true));
    private final BooleanSetting showXp       = addSetting(new BooleanSetting("Show XP Level",  false));
    private final BooleanSetting compactMode  = addSetting(new BooleanSetting("Compact Mode",   false));
    private final ModeSetting    distUnit     = addSetting(new ModeSetting("Distance Unit", "Blocks",
            List.of("Blocks", "Km", "Miles")));

    // ---- session state ----
    private long   startTime;
    private double lastX, lastY, lastZ;
    private double totalDistance;
    private boolean hasLastPos;

    // Speed smoothing — keep last 4 ticks to smooth it out
    private final double[] speedBuf = new double[4];
    private int speedIdx = 0;

    public SessionStatsModule() {
        super("Session Stats", "Tracks session play time, distance, and speed", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "session_stats"; }
    @Override public String displayName() { return "Session"; }

    @Override
    public int widgetWidth() { return compactMode.get() ? 100 : 120; }

    @Override
    public int widgetHeight() {
        if (compactMode.get()) return 14;
        int rows = 0;
        if (showTime.get())     rows++;
        if (showDistance.get()) rows++;
        if (showSpeed.get())    rows++;
        if (showXp.get())       rows++;
        return Math.max(1, rows) * 10 + 8;
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    protected void onEnable() {
        startTime    = System.currentTimeMillis();
        totalDistance = 0;
        hasLastPos   = false;
        for (int i = 0; i < speedBuf.length; i++) speedBuf[i] = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        if (hasLastPos) {
            double dx = px - lastX;
            double dy = py - lastY;
            double dz = pz - lastZ;
            double d  = Math.sqrt(dx * dx + dy * dy + dz * dz);
            totalDistance  += d;
            // Speed in blocks/s: 20 ticks/s × per-tick distance
            speedBuf[speedIdx % speedBuf.length] = d * 20.0;
            speedIdx++;
        }

        lastX = px; lastY = py; lastZ = pz;
        hasLastPos = true;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();

        // Background
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x90000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFF44AAFF); // blue accent

        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        long hours   = minutes / 60;

        if (compactMode.get()) {
            String time = hours > 0
                    ? String.format("%dh%02dm", hours, minutes % 60)
                    : String.format("%dm%02ds", minutes, seconds % 60);
            String dist = fmtDist(totalDistance);
            gfx.text(font, time + "  " + dist, x + 2, y + 3, 0xFFFFFFFF);
            return;
        }

        int curY = y + 2;
        int rowH = 10;

        if (showTime.get()) {
            String timeStr = hours > 0
                    ? String.format("Time  %dh %02dm", hours, minutes % 60)
                    : String.format("Time  %dm %02ds", minutes, seconds % 60);
            gfx.text(font, timeStr, x + 2, curY, 0xFFCCCCCC);
            curY += rowH;
        }

        if (showDistance.get()) {
            gfx.text(font, "Dist  " + fmtDist(totalDistance), x + 2, curY, 0xFFAADDFF);
            curY += rowH;
        }

        if (showSpeed.get()) {
            double avg = 0;
            for (double v : speedBuf) avg += v;
            avg /= speedBuf.length;
            String spd;
            if (avg < 0.01) {
                spd = "0.0 b/s";
            } else if (distUnit.get().equals("Km")) {
                spd = String.format("%.2f km/h", avg * 0.072); // blocks/s → km/h approx
            } else {
                spd = String.format("%.1f b/s", avg);
            }
            int spdColor = avg > 8 ? 0xFF55FF55 : avg > 4 ? 0xFFFFFF55 : 0xFFCCCCCC;
            gfx.text(font, "Speed " + spd, x + 2, curY, spdColor);
            curY += rowH;
        }

        if (showXp.get()) {
            int lvl = mc.player.experienceLevel;
            float prog = mc.player.experienceProgress;
            gfx.text(font, "XP  Lv " + lvl + " (" + (int)(prog * 100) + "%)", x + 2, curY, 0xFF88FF44);
        }
    }

    // ---- helpers ----

    private String fmtDist(double blocks) {
        return switch (distUnit.get()) {
            case "Km"    -> blocks >= 1000
                    ? String.format("%.2f km", blocks / 1000.0)
                    : String.format("%.0f m", blocks);
            case "Miles" -> String.format("%.2f mi", blocks * 0.000189394);
            default      -> blocks >= 1000
                    ? String.format("%.1f k", blocks / 1000.0)
                    : String.format("%.0f blk", blocks);
        };
    }
}
