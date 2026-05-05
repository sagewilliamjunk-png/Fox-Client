package dev.kitsune.client.module.render;

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
import net.minecraft.client.player.LocalPlayer;

/**
 * Chunk Info HUD widget — shows the current chunk coordinates, the player's
 * position within the chunk, and a 16×16 mini-map of where in the chunk the
 * player is standing.
 *
 * <p>Implemented as a proper {@link HudWidget} so it can be dragged in the
 * HUD editor and pinned to any corner. Was previously a hard-coded overlay
 * tied to {@code Category.WORLD}; now in {@code Category.HUD} where it
 * belongs.
 */
public class ChunkBordersModule extends Module implements HudWidget {

    private final BooleanSetting showCoords     = addSetting(new BooleanSetting("Show Chunk Coords",  true));
    private final BooleanSetting showInChunkPos = addSetting(new BooleanSetting("Show In-Chunk Pos",  true));
    private final BooleanSetting showMiniMap    = addSetting(new BooleanSetting("Show Mini-Map",      true));
    private final BooleanSetting showBackground = addSetting(new BooleanSetting("Show Background",    true));
    private final ColorSetting   textColor      = addSetting(new ColorSetting("Text Color",          0xFFAABBCC));
    private final ColorSetting   accentColor    = addSetting(new ColorSetting("Player Dot Color",    0xFFFF5555));
    private final SliderSetting  bgOpacity      = addSetting(new SliderSetting("Background Opacity", 0.5, 0.0, 1.0, 0.05));

    public ChunkBordersModule() {
        super("Chunk Info", "Current chunk coords, in-chunk position, and a mini-map", Category.HUD);
        HudManager.register(this);
    }

    // ---- HudWidget impl ----

    @Override public String widgetId()    { return "chunk_info"; }
    @Override public String displayName() { return "Chunk Info"; }

    @Override
    public int widgetWidth() {
        // Wide enough for "In-chunk: 15, 15" at vanilla font width.
        return 104;
    }

    @Override
    public int widgetHeight() {
        int h = 4; // top padding
        if (showCoords.get())     h += 10;
        if (showInChunkPos.get()) h += 10;
        if (showMiniMap.get())    h += 18; // 16 px map + 2 px gap
        return Math.max(12, h);
    }

    @Override
    public boolean isWidgetVisible() {
        return isEnabled()
            && (showCoords.get() || showInChunkPos.get() || showMiniMap.get())
            && Minecraft.getInstance().player != null;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        int chunkX   = player.getBlockX() >> 4;
        int chunkZ   = player.getBlockZ() >> 4;
        int inChunkX = player.getBlockX() & 15;
        int inChunkZ = player.getBlockZ() & 15;

        int w = widgetWidth();
        int h = widgetHeight();

        // Background
        if (showBackground.get()) {
            int alpha = (int) Math.round(bgOpacity.get() * 255.0);
            int bg = (alpha << 24);
            gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg);
        }

        int curY = y + 2;
        int color = textColor.get();

        if (showCoords.get()) {
            gfx.drawString(font, String.format("Chunk: %d, %d", chunkX, chunkZ), x, curY, color, true);
            curY += 10;
        }
        if (showInChunkPos.get()) {
            gfx.drawString(font, String.format("In-chunk: %d, %d", inChunkX, inChunkZ), x, curY, color, true);
            curY += 10;
        }

        if (showMiniMap.get()) {
            curY += 2;
            int mapSize = 16;
            int mapX = x;
            int mapY = curY;

            // Grid background
            gfx.fill(mapX, mapY, mapX + mapSize, mapY + mapSize, 0xFF1A1A2A);

            // Grid lines every 4 blocks
            for (int i = 0; i <= 16; i += 4) {
                gfx.fill(mapX + i, mapY, mapX + i + 1, mapY + mapSize, 0x40FFFFFF);
                gfx.fill(mapX, mapY + i, mapX + mapSize, mapY + i + 1, 0x40FFFFFF);
            }

            // Player dot
            int dotX = mapX + inChunkX;
            int dotY = mapY + inChunkZ;
            gfx.fill(dotX, dotY, dotX + 1, dotY + 1, accentColor.get());

            // Border
            gfx.fill(mapX - 1, mapY - 1, mapX + mapSize + 1, mapY,             0x80FFFFFF);
            gfx.fill(mapX - 1, mapY + mapSize, mapX + mapSize + 1, mapY + mapSize + 1, 0x80FFFFFF);
            gfx.fill(mapX - 1, mapY,           mapX,                 mapY + mapSize, 0x80FFFFFF);
            gfx.fill(mapX + mapSize, mapY,     mapX + mapSize + 1,   mapY + mapSize, 0x80FFFFFF);
        }
    }
}
