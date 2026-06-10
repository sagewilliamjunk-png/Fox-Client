package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.util.Palette;
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
public class ServerTpsHudModule extends BaseHudModule {

    private static final int WINDOW = 40; // samples of tick gaps

    private final BooleanSetting showLabel  = addSetting(new BooleanSetting("Show Label", true));
    private final BooleanSetting colorize   = addSetting(new BooleanSetting("Colorize",   true));
    private final BooleanSetting showMspt   = addSetting(new BooleanSetting("Show MSPT",  false));

    private final long[] gaps = new long[WINDOW];
    private int head = 0;
    private int count = 0;
    private long lastGameTime = -1;
    private long lastWallMs = 0;

    public ServerTpsHudModule() {
        super("Server TPS", "Estimates server tick rate from game-time progression", Category.HUD,
                "server_tps", "TPS");
        useStandardPanel(0.50, Palette.ACCENT_CYAN);
    }

    @Override public int widgetWidth()  { return showMspt.get() ? 80 : 56; }
    @Override public int widgetHeight() { return 14; }

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
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        double mspt = averageMspt();
        double tps = Math.min(20.0, 1000.0 / Math.max(1.0, mspt));
        int color = colorize.get() ? tpsColor(tps) : Palette.TEXT_WHITE;
        String s;
        if (showMspt.get()) {
            s = String.format("%.1f TPS  %.0fms", tps, mspt);
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
