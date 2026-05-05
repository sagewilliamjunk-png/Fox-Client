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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Real-world clock HUD. Useful for tracking play sessions.
 *
 * <p>Supports 12/24 hour time, optional seconds, optional date, and an
 * optional in-game tick time alongside real-world time.
 */
public class ClockHudModule extends Module implements HudWidget {

    private final ModeSetting    format      = addSetting(new ModeSetting("Format", "24 hour",
            List.of("24 hour", "12 hour")));
    private final BooleanSetting showSeconds = addSetting(new BooleanSetting("Show Seconds", false));
    private final BooleanSetting showDate    = addSetting(new BooleanSetting("Show Date",    false));
    private final BooleanSetting showGameTime = addSetting(new BooleanSetting("Show Game Time", false));
    private final SliderSetting  bgOpacity   = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent      = addSetting(new ColorSetting("Accent",     0xFF44CCCC));
    private final ColorSetting   textColor   = addSetting(new ColorSetting("Text Color", 0xFFFFFFFF));

    public ClockHudModule() {
        super("Clock", "Real-world clock + optional in-game time", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "clock"; }
    @Override public String displayName() { return "Clock"; }

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
        return rows * 10 + 6;
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        int bg = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        LocalDateTime now = LocalDateTime.now();
        String timePattern;
        if ("12 hour".equals(format.get())) {
            timePattern = showSeconds.get() ? "h:mm:ss a" : "h:mm a";
        } else {
            timePattern = showSeconds.get() ? "HH:mm:ss" : "HH:mm";
        }
        String timeStr = now.format(DateTimeFormatter.ofPattern(timePattern));

        int curY = y + 3;
        int color = textColor.get();
        gfx.drawString(font, timeStr, x + 2, curY, color, false);
        curY += 10;

        if (showDate.get()) {
            String dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            gfx.drawString(font, dateStr, x + 2, curY, 0xFFAAAAAA, false);
            curY += 10;
        }

        if (showGameTime.get() && mc.level != null) {
            long t = mc.level.getDayTime() % 24000L;
            int hours = (int)((t / 1000L + 6L) % 24L);
            int minutes = (int)(((t % 1000L) * 60L) / 1000L);
            String g = String.format("MC %02d:%02d", hours, minutes);
            gfx.drawString(font, g, x + 2, curY, 0xFFDDAA55, false);
        }
    }
}
