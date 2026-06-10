package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Real-world clock HUD. Useful for tracking play sessions.
 *
 * <p>Supports 12/24 hour time, optional seconds, optional date, and an
 * optional in-game tick time alongside real-world time.
 */
public class ClockHudModule extends BaseHudModule {

    private final ModeSetting    format      = addSetting(new ModeSetting("Format", "24 hour",
            List.of("24 hour", "12 hour")));
    private final BooleanSetting showSeconds = addSetting(new BooleanSetting("Show Seconds", false));
    private final BooleanSetting showDate    = addSetting(new BooleanSetting("Show Date",    false));
    private final BooleanSetting showGameTime = addSetting(new BooleanSetting("Show Game Time", false));

    public ClockHudModule() {
        super("Clock", "Real-world clock + optional in-game time", Category.HUD, "clock", "Clock");
        useStandardPanel(0.50, Palette.ACCENT_CYAN);
        useTextColor();
    }

    @Override
    public int widgetWidth() {
        int w = 56;
        if ("12 hour".equals(format.get())) w += 10;
        if (showSeconds.get()) w += 18;
        if (showDate.get())    w = Math.max(w, 86);
        return w;
    }

    @Override
    public int widgetHeight() {
        int rows = 1;
        if (showDate.get())     rows++;
        if (showGameTime.get()) rows++;
        return rowsHeight(rows);
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        LocalDateTime now = LocalDateTime.now();
        String timePattern;
        if ("12 hour".equals(format.get())) {
            timePattern = showSeconds.get() ? "h:mm:ss a" : "h:mm a";
        } else {
            timePattern = showSeconds.get() ? "HH:mm:ss" : "HH:mm";
        }
        String timeStr = now.format(DateTimeFormatter.ofPattern(timePattern));

        int curY = y + 3;
        gfx.text(font, timeStr, x + 2, curY, textArgb());
        curY += 10;

        if (showDate.get()) {
            String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            gfx.text(font, dateStr, x + 2, curY, Palette.TEXT_MUTED);
            curY += 10;
        }

        if (showGameTime.get() && mc.level != null) {
            long t = mc.level.getOverworldClockTime() % 24000L;
            int hours = (int)((t / 1000L + 6L) % 24L);
            int minutes = (int)(((t % 1000L) * 60L) / 1000L);
            String g = String.format("MC %02d:%02d", hours, minutes);
            gfx.text(font, g, x + 2, curY, Palette.TEXT_GOLD);
        }
    }
}
