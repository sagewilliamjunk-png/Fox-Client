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
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Estimated server tick rate based on the rate at which the world's game-time
 * advances. On a healthy Vanilla / Paper server you'll see ~20 TPS; lag spikes
 * pull it down.
 *
 * <p>Measurement approach: poll {@code mc.level.getGameTime()} each client
 * tick. Server ticks increment this value at (usually) 20 Hz. We measure the
 * wall-clock elapsed time between game-time increments to infer the server
 * TPS. A rolling window smooths out single-tick jitter.
 *
 * <p>This is an <i>estimate</i> — real TPS can only be known by the server.
 * For Paper/Spigot, consider running {@code /tps} for authoritative numbers.
 */
public class ServerTpsHudModule extends Module implements HudWidget {

    private static final int WINDOW = 40; // samples of tick gaps

    private final BooleanSetting showLabel  = addSetting(new BooleanSetting("Show Label", true));
    private final BooleanSetting colorize   = addSetting(new BooleanSetting("Colorize",   true));
    private final BooleanSetting showMspt   = addSetting(new BooleanSetting("Show MSPT",  false));
    private final SliderSetting  bgOpacity  = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent     = addSetting(new ColorSetting("Accent",     0xFF44CCCC));

    private final long[] gaps = new long[WINDOW];
    private int head = 0;
    private int count = 0;
    private long lastGameTime = -1;
    private long lastWallMs = 0;

    public ServerTpsHudModule() {
        super("Server TPS", "Estimates server tick rate from game-time progression", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "server_tps"; }
    @Override public String displayName() { return "TPS"; }
    @Override public int widgetWidth()    { return showMspt.get() ? 80 : 56; }
    @Override public int widgetHeight()   { return 14; }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    protected void onDisable() {
        head = 0; count = 0;
        lastGameTime = -1;
        lastWallMs = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { lastGameTime = -1; return; }
        long gt = mc.level.getGameTime();
        long now = System.currentTimeMillis();
        if (lastGameTime < 0) {
            lastGameTime = gt;
            lastWallMs = now;
            return;
        }
        if (gt > lastGameTime) {
            long delta = gt - lastGameTime;
            long wall = now - lastWallMs;
            // Average ms-per-tick across the observed interval.
            long mspt = (delta > 0) ? (wall / delta) : wall;
            gaps[head] = mspt;
            head = (head + 1) % WINDOW;
            if (count < WINDOW) count++;
            lastGameTime = gt;
            lastWallMs = now;
        }
    }

    private double averageMspt() {
        if (count == 0) return 50.0; // assume ideal
        long sum = 0;
        for (int i = 0; i < count; i++) sum += gaps[i];
        return sum / (double) count;
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

        double mspt = averageMspt();
        double tps = Math.min(20.0, 1000.0 / Math.max(1.0, mspt));
        int color = colorize.get() ? tpsColor(tps) : 0xFFFFFFFF;
        String s;
        if (showMspt.get()) {
            s = String.format("%s%.1f TPS  %.0fms",
                    showLabel.get() ? "" : "", tps, mspt);
        } else {
            s = showLabel.get() ? String.format("TPS %.1f", tps) : String.format("%.1f", tps);
        }
        gfx.text(font, s, x + 2, y + 3, color);
    }

    private static int tpsColor(double tps) {
        if (tps >= 19.0) return 0xFF33CC55;
        if (tps >= 15.0) return 0xFFFFAA00;
        return 0xFFFF3333;
    }
}
