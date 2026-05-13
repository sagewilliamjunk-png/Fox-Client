package dev.kitsune.client.module.render;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;

/**
 * Client-side weather and time override. Changes the visual appearance of
 * weather and time of day without affecting the server. Purely cosmetic.
 *
 * <p>Also optionally renders a clock widget on the HUD.
 */
public class WeatherTimeModule extends Module implements HudWidget {

    private final BooleanSetting clearWeather  = addSetting(new BooleanSetting("Clear Weather",   true));
    private final ModeSetting    timeOverride   = addSetting(new ModeSetting("Time Override", "None",
            List.of("None", "Day (noon)", "Sunrise", "Sunset", "Night", "Midnight")));
    private final ModeSetting    clockFormat    = addSetting(new ModeSetting("Clock Format", "12h",
            List.of("12h", "24h", "Ticks", "Off")));
    private final BooleanSetting showWeatherHud = addSetting(new BooleanSetting("Show Weather Label", true));
    private final BooleanSetting showClockHud   = addSetting(new BooleanSetting("Show Clock HUD",    true));

    public WeatherTimeModule() {
        super("Weather/Time", "Client-side weather and time override + clock HUD", Category.HUD);
        HudManager.register(this);
    }

    // ---- HudWidget ----

    @Override public String widgetId()    { return "weather_time"; }
    @Override public String displayName() { return "Weather/Time"; }
    @Override public int widgetWidth()    { return 100; }
    @Override public int widgetHeight() {
        int rows = 0;
        if (showClockHud.get() && !clockFormat.get().equals("Off")) rows++;
        if (showWeatherHud.get()) rows++;
        return Math.max(1, rows) * 10 + 8;
    }
    @Override public boolean isWidgetVisible() { return isEnabled() && (showClockHud.get() || showWeatherHud.get()); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x88000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFF4499CC);

        int curY = y + 2;

        // Clock
        if (showClockHud.get() && !clockFormat.get().equals("Off")) {
            long dayTime  = level.getOverworldClockTime() % 24000L;
            String timeStr = switch (clockFormat.get()) {
                case "24h"  -> format24h(dayTime);
                case "Ticks" -> dayTime + "t";
                default     -> format12h(dayTime); // 12h
            };
            // Pick icon based on time
            String icon = (dayTime >= 0 && dayTime < 12000) ? "\u2600" : "\u263d"; // sun/moon
            gfx.text(font, icon + " " + timeStr, x + 2, curY, 0xFFFFDD88);
            curY += 10;
        }

        // Weather label
        if (showWeatherHud.get()) {
            boolean raining  = level.isRaining();
            boolean thunder  = level.isThundering();
            String weather;
            int weatherCol;
            if (thunder) {
                weather = "\u26a1 Thunder";
                weatherCol = 0xFFCCCCFF;
            } else if (raining) {
                weather = "\ud83c\udf27 Rain";
                weatherCol = 0xFF88AADD;
            } else {
                weather = "\u2600 Clear";
                weatherCol = 0xFFFFDD88;
            }
            gfx.text(font, weather, x + 2, curY, weatherCol);
        }
    }

    // ---- Module tick ----

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ClientLevel level = mc.level;

        if (clearWeather.get()) {
            level.setRainLevel(0);
            level.setThunderLevel(0);
        }

        switch (timeOverride.get()) {
            case "Day (noon)" -> level.setTimeFromServer(6000);
            case "Sunrise"    -> level.setTimeFromServer(0);
            case "Sunset"     -> level.setTimeFromServer(12000);
            case "Night"      -> level.setTimeFromServer(14000);
            case "Midnight"   -> level.setTimeFromServer(18000);
        }
    }

    @Override
    protected void onDisable() {
        // Time/weather re-syncs automatically via server packets
    }

    // ---- helpers ----

    /** Convert Minecraft day ticks (0–24000) to a 24-hour clock string. */
    private static String format24h(long dayTime) {
        // 0t = 6:00 AM; 6000t = noon; 18000t = midnight
        long totalMins = ((dayTime + 6000) % 24000) * 60 / 1000;
        long hours = (totalMins / 60) % 24;
        long mins  = totalMins % 60;
        return String.format("%02d:%02d", hours, mins);
    }

    private static String format12h(long dayTime) {
        long totalMins = ((dayTime + 6000) % 24000) * 60 / 1000;
        long hours24 = (totalMins / 60) % 24;
        long mins    = totalMins % 60;
        boolean pm   = hours24 >= 12;
        long hours12 = hours24 % 12;
        if (hours12 == 0) hours12 = 12;
        return String.format("%d:%02d %s", hours12, mins, pm ? "PM" : "AM");
    }
}
