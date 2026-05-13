package dev.kitsune.client.module.misc;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Heap-usage display with optional automatic cleanup.
 *
 * <p>When enabled, shows a bar + percentage of the JVM's used heap vs. max.
 * When "Auto Clean" is on, triggers {@code System.gc()} and pushes a soft
 * reference flush when usage exceeds the threshold — with a cooldown so
 * repeated GCs can't thrash.
 *
 * <p>{@code System.gc()} is a hint, not a guarantee; it's safe and widely used
 * in Minecraft clients for manual "free memory" actions. This module does NOT
 * call it every tick — only when explicitly requested or the threshold
 * condition + cooldown both pass.
 */
public class MemoryCleanerModule extends Module implements HudWidget {

    private final BooleanSetting showHud       = addSetting(new BooleanSetting("Show HUD",     true));
    private final BooleanSetting showBar       = addSetting(new BooleanSetting("Show Bar",     true));
    private final BooleanSetting autoClean     = addSetting(new BooleanSetting("Auto Clean",   false));
    private final BooleanSetting notifyOnClean = addSetting(new BooleanSetting("Notify on Clean", true));
    private final SliderSetting  threshold     = addSetting(new SliderSetting("Auto Threshold %", 85, 50, 95, 1));
    private final SliderSetting  cooldownSec   = addSetting(new SliderSetting("Auto Cooldown (s)", 60, 10, 600, 5));
    private final SliderSetting  bgOpacity     = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent        = addSetting(new ColorSetting("Accent",    0xFF44CCCC));
    private final ColorSetting   warnColor     = addSetting(new ColorSetting("Warn",      0xFFFF3333));

    private long lastAutoCleanMs = 0L;
    private long lastManualCleanMs = 0L;

    public MemoryCleanerModule() {
        super("Memory Cleaner", "Free unused heap and show memory usage", Category.MISC);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "memory_cleaner"; }
    @Override public String displayName() { return "Memory"; }
    @Override public int widgetWidth()    { return showBar.get() ? 120 : 78; }
    @Override public int widgetHeight()   { return 14; }
    @Override public boolean isWidgetVisible() { return isEnabled() && showHud.get(); }

    /** Trigger a manual cleanup. Safe to call from a keybind or command. */
    public void clean() {
        long before = usedBytes();
        System.gc();
        long after = usedBytes();
        lastManualCleanMs = System.currentTimeMillis();
        if (notifyOnClean.get()) {
            long freed = Math.max(0, before - after);
            NotificationManager.show(
                    "Memory cleaned (" + formatMb(freed) + " freed)",
                    NotificationManager.Type.SUCCESS);
        }
    }

    @Override
    public void onTick() {
        if (!autoClean.get()) return;
        double usedPct = percentUsed();
        if (usedPct < threshold.get()) return;

        long now = System.currentTimeMillis();
        long cooldownMs = (long)(cooldownSec.get() * 1000);
        if (now - lastAutoCleanMs < cooldownMs) return;

        long before = usedBytes();
        System.gc();
        lastAutoCleanMs = now;

        if (notifyOnClean.get()) {
            long freed = Math.max(0, before - usedBytes());
            NotificationManager.show(
                    "Auto-cleaned memory (" + formatMb(freed) + " freed)",
                    NotificationManager.Type.INFO);
        }
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        int bg = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        double pct = percentUsed();
        int color = pct > threshold.get() ? warnColor.get() : 0xFFFFFFFF;

        long usedMb = usedBytes() / (1024 * 1024);
        long maxMb  = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String label = String.format("%dM/%dM %.0f%%", usedMb, maxMb, pct);

        if (showBar.get()) {
            int barX = x + 2;
            int barY = y + 3;
            int barW = 60;
            int barH = 8;
            gfx.fill(barX, barY, barX + barW, barY + barH, 0x60000000);
            int fill = (int)(barW * (pct / 100.0));
            int fillColor = pct > threshold.get() ? warnColor.get() : 0xFF33CC55;
            gfx.fill(barX, barY, barX + fill, barY + barH, fillColor);
            gfx.text(font, label, barX + barW + 4, y + 3, color);
        } else {
            gfx.text(font, label, x + 2, y + 3, color);
        }
    }

    private static long usedBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static double percentUsed() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory();
    }

    private static String formatMb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }
}
