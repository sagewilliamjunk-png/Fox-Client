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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Player speed readout in blocks-per-second. Useful for parkour, ice roads,
 * elytra flight, and checking speed-effect magnitudes.
 *
 * <p>Samples the player's position delta per tick and averages over a short
 * window to avoid jitter. Optionally shows horizontal-only speed (ignoring
 * vertical motion) and a peak-speed indicator.
 */
public class SpeedometerHudModule extends Module implements HudWidget {

    private static final int SAMPLES = 10; // ~0.5 s window at 20 tps

    private final ModeSetting    axis       = addSetting(new ModeSetting("Axis", "Horizontal",
            List.of("Horizontal", "All")));
    private final ModeSetting    unit       = addSetting(new ModeSetting("Unit", "bps",
            List.of("bps", "m/s", "km/h")));
    private final BooleanSetting showPeak   = addSetting(new BooleanSetting("Show Peak", true));
    private final BooleanSetting showLabel  = addSetting(new BooleanSetting("Show Label", true));
    private final SliderSetting  bgOpacity  = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent     = addSetting(new ColorSetting("Accent",     0xFF44CCCC));
    private final ColorSetting   textColor  = addSetting(new ColorSetting("Text Color", 0xFFFFFFFF));

    private final double[] samples = new double[SAMPLES];
    private int sampleIdx = 0;
    private int sampleCount = 0;
    private double lastX, lastY, lastZ;
    private boolean hasLast = false;
    private double peak = 0;

    public SpeedometerHudModule() {
        super("Speedometer", "Shows player speed in blocks per second", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "speedometer"; }
    @Override public String displayName() { return "Speedometer"; }

    @Override public int widgetWidth()  { return showPeak.get() ? 80 : 58; }
    @Override public int widgetHeight() { return showPeak.get() ? 24 : 14; }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    protected void onDisable() {
        hasLast = false;
        sampleCount = 0;
        sampleIdx = 0;
        peak = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { hasLast = false; return; }

        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (!hasLast) {
            lastX = x; lastY = y; lastZ = z;
            hasLast = true;
            return;
        }
        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        lastX = x; lastY = y; lastZ = z;

        // Per-tick distance → blocks/sec (20 tps)
        double distPerTick = "All".equals(axis.get())
                ? Math.sqrt(dx*dx + dy*dy + dz*dz)
                : Math.sqrt(dx*dx + dz*dz);
        double bps = distPerTick * 20.0;

        samples[sampleIdx] = bps;
        sampleIdx = (sampleIdx + 1) % SAMPLES;
        if (sampleCount < SAMPLES) sampleCount++;

        if (bps > peak) peak = bps;
    }

    private double avgBps() {
        if (sampleCount == 0) return 0;
        double sum = 0;
        for (int i = 0; i < sampleCount; i++) sum += samples[i];
        return sum / sampleCount;
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

        double bps = avgBps();
        String main = formatSpeed(bps);
        int color = textColor.get();

        if (showLabel.get()) main = "Spd " + main;
        gfx.drawString(font, main, x + 2, y + 3, color, false);

        if (showPeak.get()) {
            String peakStr = "Peak " + formatSpeed(peak);
            gfx.drawString(font, peakStr, x + 2, y + 13, 0xFFAAAAAA, false);
        }
    }

    private String formatSpeed(double bps) {
        return switch (unit.get()) {
            case "m/s"  -> String.format("%.2f m/s", bps);
            case "km/h" -> String.format("%.1f km/h", bps * 3.6);
            default     -> String.format("%.2f bps", bps);
        };
    }
}
