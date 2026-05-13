package dev.kitsune.client.module.hud;

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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

import java.util.List;

/**
 * Draggable widget showing server name, ping, and estimated TPS.
 * Ping and TPS are visualised with colour-coded progress bars.
 */
public class ServerInfoHudModule extends Module implements HudWidget {

    private final BooleanSetting showName     = addSetting(new BooleanSetting("Show Server Name", true));
    private final BooleanSetting showPingBar  = addSetting(new BooleanSetting("Show Ping Bar", true));
    private final BooleanSetting showTpsBar   = addSetting(new BooleanSetting("Show TPS Bar",  true));
    private final BooleanSetting showNumeric  = addSetting(new BooleanSetting("Show Numbers",  true));
    private final BooleanSetting compactMode  = addSetting(new BooleanSetting("Compact Mode",  false));
    private final SliderSetting  goodPing     = addSetting(new SliderSetting("Good Ping (ms)",  80, 20, 200, 10));
    private final SliderSetting  badPing      = addSetting(new SliderSetting("Bad Ping (ms)",  200, 100, 500, 25));
    private final ModeSetting    accentColor  = addSetting(new ModeSetting("Accent", "Cyan",
            List.of("Cyan", "Orange", "Green", "White")));

    // ---- TPS estimation ----
    private long   lastWorldTime = -1;
    private long   lastTimeMs   = -1;
    private double estimatedTps = 20.0;

    public ServerInfoHudModule() {
        super("Server Info", "Shows server name, ping, and TPS", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "server_info"; }
    @Override public String displayName() { return "Server"; }

    @Override
    public int widgetWidth() { return compactMode.get() ? 100 : 130; }

    @Override
    public int widgetHeight() {
        if (compactMode.get()) return 14;
        int rows = 0;
        if (showName.get())                     rows++;
        rows++; // ping row always
        if (showPingBar.get())                  rows++;
        rows++; // tps row always
        if (showTpsBar.get())                   rows++;
        return rows * 10 + 8;
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long worldTime = mc.level.getGameTime();
        long now       = System.currentTimeMillis();
        if (lastWorldTime >= 0 && worldTime != lastWorldTime) {
            long tickDelta = worldTime - lastWorldTime;
            long msDelta   = now - lastTimeMs;
            if (msDelta > 0 && tickDelta > 0) {
                double measured = (tickDelta * 1000.0) / msDelta;
                estimatedTps = estimatedTps * 0.85 + Math.min(20.0, measured) * 0.15;
                estimatedTps = Math.min(20.0, Math.max(0.0, estimatedTps));
            }
        }
        lastWorldTime = worldTime;
        lastTimeMs    = now;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();
        int accent = accentArgb();

        // Background
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x90000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent);

        // Ping
        int ping = -1;
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
            if (info != null) ping = info.getLatency();
        }

        int good = goodPing.get().intValue();
        int bad  = badPing.get().intValue();

        if (compactMode.get()) {
            String line = "Ping " + (ping >= 0 ? ping + "ms" : "N/A")
                    + "  TPS " + String.format("%.1f", estimatedTps);
            gfx.text(font, line, x + 2, y + 3, 0xFFFFFFFF);
            return;
        }

        int curY = y + 2;
        int rowH = 10;

        // Server name
        if (showName.get()) {
            ServerData sd = mc.getCurrentServer();
            String name = sd != null ? sd.name : "Singleplayer";
            if (font.width(name) > w - 4) {
                name = font.plainSubstrByWidth(name, w - 12) + "…";
            }
            gfx.text(font, name, x + 2, curY, accent);
            curY += rowH;
        }

        // Ping
        int pingColor = pingColor(ping, good, bad);
        if (showNumeric.get()) {
            String pingStr = "Ping  " + (ping >= 0 ? ping + " ms" : "N/A");
            gfx.text(font, pingStr, x + 2, curY, pingColor);
        }
        curY += rowH;

        if (showPingBar.get()) {
            int barW = w - 6;
            int barY = curY - 4;
            float pct = ping < 0 ? 0f : 1f - Math.min(1f, ping / (float) bad);
            drawBar(gfx, x + 2, barY, barW, 3, pct, pingColor);
            curY += 4;
        }

        // TPS
        double tps = Math.round(estimatedTps * 10.0) / 10.0;
        int tpsColor = tps >= 19.0 ? 0xFF55FF55
                : tps >= 15.0 ? 0xFFFFFF55
                : tps >= 10.0 ? 0xFFFFAA00
                : 0xFFFF5555;
        if (showNumeric.get()) {
            gfx.text(font, "TPS  " + tps, x + 2, curY, tpsColor);
        }
        curY += rowH;

        if (showTpsBar.get()) {
            int barW = w - 6;
            int barY = curY - 4;
            float pct = (float)(estimatedTps / 20.0);
            drawBar(gfx, x + 2, barY, barW, 3, pct, tpsColor);
        }
    }

    // ---- helpers ----

    private static void drawBar(GuiGraphicsExtractor gfx, int x, int y, int w, int h, float pct, int color) {
        gfx.fill(x, y, x + w, y + h, 0xFF222222);
        int fill = Math.max(2, (int)(w * pct));
        gfx.fill(x, y, x + fill, y + h, color);
    }

    private int pingColor(int ping, int good, int bad) {
        if (ping < 0) return 0xFF888888;
        if (ping < good)       return 0xFF55FF55;
        if (ping < (good + bad) / 2) return 0xFFFFFF55;
        if (ping < bad)        return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    private int accentArgb() {
        return switch (accentColor.get()) {
            case "Orange" -> 0xFFE87722;
            case "Green"  -> 0xFF44DD88;
            case "White"  -> 0xFFDDDDDD;
            default       -> 0xFF44CCCC; // Cyan
        };
    }
}
