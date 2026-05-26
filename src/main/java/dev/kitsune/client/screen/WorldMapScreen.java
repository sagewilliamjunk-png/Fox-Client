package dev.kitsune.client.screen;

import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import dev.kitsune.client.worldmap.WorldMapData;
import dev.kitsune.client.worldmap.WorldMapManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;

/**
 * Full-screen scrollable world map. Press M to open. Reads the persistent
 * {@link WorldMapData} for the current sub-world and renders every discovered
 * chunk as 16×16 pixels of altitude-shaded terrain, scaled by zoom.
 *
 * <h3>Controls</h3>
 * <ul>
 *   <li>Mouse drag — pan</li>
 *   <li>Scroll wheel — zoom (0.5×–8× pixels per block)</li>
 *   <li>Home — recenter on the player</li>
 *   <li>F — fit-to-content (centre and zoom to the bounding box of stored chunks)</li>
 *   <li>Esc / M — close</li>
 * </ul>
 *
 * <p>Waypoints are overlaid as colored squares with their symbol; the local
 * player's position renders as a white triangle pointing in their facing.
 */
public class WorldMapScreen extends Screen {

    private final Screen parent;

    /** World-block-space center of the viewport. Initialised to the player's
     *  current position. */
    private double centerWorldX;
    private double centerWorldZ;

    /** Pixels per world block. 1.0 = one map pixel per block; 0.5 = two
     *  blocks per pixel (zoomed out); 4.0 = each block is a 4×4 square. */
    private double pixelsPerBlock = 1.0;

    /** Drag-pan state. */
    private boolean dragging = false;
    private double  dragOriginScreenX, dragOriginScreenY;
    private double  dragOriginCenterX, dragOriginCenterZ;

    public WorldMapScreen(Screen parent) {
        super(Component.literal("World Map"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            centerWorldX = mc.player.getX();
            centerWorldZ = mc.player.getZ();
        }

        // Top-right close button
        this.addRenderableWidget(FoxButton.of(this.width - 76, 8, 64, 20,
                Component.literal("Close"),
                b -> this.onClose()));
        // Home: recenter
        this.addRenderableWidget(FoxButton.of(8, 8, 88, 20,
                Component.literal("📍 Player"),
                b -> {
                    if (mc.player != null) {
                        centerWorldX = mc.player.getX();
                        centerWorldZ = mc.player.getZ();
                    }
                }));
        // Fit-to-content
        this.addRenderableWidget(FoxButton.of(100, 8, 80, 20,
                Component.literal("🗺 Fit"),
                b -> fitToContent()));
    }

    private void fitToContent() {
        WorldMapData d = WorldMapManager.active();
        if (d == null || d.count() == 0) return;
        // Chunk bounds → world block bounds.
        double minBx = d.minCx() << 4;
        double maxBx = (d.maxCx() << 4) + 16;
        double minBz = d.minCz() << 4;
        double maxBz = (d.maxCz() << 4) + 16;
        centerWorldX = (minBx + maxBx) / 2.0;
        centerWorldZ = (minBz + maxBz) / 2.0;
        double widthBlocks  = Math.max(16, maxBx - minBx);
        double heightBlocks = Math.max(16, maxBz - minBz);
        double zoomX = (this.width  - 40) / widthBlocks;
        double zoomY = (this.height - 80) / heightBlocks;
        pixelsPerBlock = Math.max(0.05, Math.min(8.0, Math.min(zoomX, zoomY)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double sx, double sy) {
        // Zoom toward the cursor: convert cursor screen pos to world, change
        // zoom, then shift center so the cursor stays over the same world pos.
        double mapPxX = mouseX - this.width  / 2.0;
        double mapPxY = mouseY - this.height / 2.0;
        double worldXBefore = centerWorldX + mapPxX / pixelsPerBlock;
        double worldZBefore = centerWorldZ + mapPxY / pixelsPerBlock;

        double factor = sy > 0 ? 1.25 : 0.8;
        pixelsPerBlock = Math.max(0.05, Math.min(8.0, pixelsPerBlock * factor));

        double worldXAfter  = centerWorldX + mapPxX / pixelsPerBlock;
        double worldZAfter  = centerWorldZ + mapPxY / pixelsPerBlock;
        centerWorldX += worldXBefore - worldXAfter;
        centerWorldZ += worldZBefore - worldZAfter;
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean focused) {
        // Start drag only on left-click in the map area (not over a button).
        if (e.button() == 0 && !super.mouseClicked(e, focused)) {
            dragging = true;
            dragOriginScreenX = e.x();
            dragOriginScreenY = e.y();
            dragOriginCenterX = centerWorldX;
            dragOriginCenterZ = centerWorldZ;
            return true;
        }
        return super.mouseClicked(e, focused);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
        dragging = false;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
        if (dragging) {
            double dxScreen = e.x() - dragOriginScreenX;
            double dyScreen = e.y() - dragOriginScreenY;
            centerWorldX = dragOriginCenterX - dxScreen / pixelsPerBlock;
            centerWorldZ = dragOriginCenterZ - dyScreen / pixelsPerBlock;
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partial) {
        // Dark backdrop covering the whole window.
        gfx.fill(0, 0, this.width, this.height, 0xFF0D0D14);

        WorldMapData data = WorldMapManager.active();
        if (data == null || data.count() == 0) {
            gfx.text(this.font, "No world data yet — walk around to explore.",
                    this.width / 2 - 120, this.height / 2, 0xFFAAAAAA);
            super.extractRenderState(gfx, mouseX, mouseY, partial);
            return;
        }

        // Render tiles. We iterate ALL stored chunks but skip ones whose
        // bounding rect is fully outside the screen.
        double scale = pixelsPerBlock;
        double cxBlock = centerWorldX;
        double czBlock = centerWorldZ;
        int halfW = this.width / 2;
        int halfH = this.height / 2;
        // Each chunk is 16 blocks wide.
        double chunkPx = 16 * scale;

        for (var entry : data.tiles.entrySet()) {
            ChunkPos cp = entry.getKey();
            int[] tile = entry.getValue();
            double blockX = cp.x() << 4;
            double blockZ = cp.z() << 4;
            // Screen position of the chunk's top-left corner.
            double sx = halfW + (blockX - cxBlock) * scale;
            double sy = halfH + (blockZ - czBlock) * scale;
            // Cull when fully off-screen.
            if (sx + chunkPx < 0 || sx > this.width || sy + chunkPx < 0 || sy > this.height) continue;
            // Per-block draw — coarse at low zoom, sharp at high zoom.
            if (scale >= 1.0) {
                int pxPerBlock = (int) Math.ceil(scale);
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int x0 = (int) Math.floor(sx + lx * scale);
                        int y0 = (int) Math.floor(sy + lz * scale);
                        gfx.fill(x0, y0, x0 + pxPerBlock, y0 + pxPerBlock, tile[lz * 16 + lx]);
                    }
                }
            } else {
                // Zoomed out — sample every Nth block to avoid pixel-thrash.
                int step = Math.max(1, (int) Math.floor(1.0 / scale));
                for (int lx = 0; lx < 16; lx += step) {
                    for (int lz = 0; lz < 16; lz += step) {
                        int x0 = (int) Math.floor(sx + lx * scale);
                        int y0 = (int) Math.floor(sy + lz * scale);
                        gfx.fill(x0, y0, x0 + 1, y0 + 1, tile[lz * 16 + lx]);
                    }
                }
            }
        }

        // Waypoint markers.
        for (Waypoint w : WaypointManager.current()) {
            double wsx = halfW + (w.x() - cxBlock) * scale;
            double wsy = halfH + (w.z() - czBlock) * scale;
            if (wsx < -10 || wsx > this.width + 10 || wsy < -10 || wsy > this.height + 10) continue;
            int x = (int) wsx, y = (int) wsy;
            gfx.fill(x - 3, y - 3, x + 4, y + 4, 0xFF000000);
            gfx.fill(x - 2, y - 2, x + 3, y + 3, w.color());
            if (scale >= 0.5) {
                String sym = w.deathpoint() ? "☠" : (w.symbol() == null || w.symbol().isEmpty() ? "•" : w.symbol().substring(0, 1));
                gfx.text(this.font, sym, x - this.font.width(sym) / 2, y - 4, 0xFF000000);
                String name = (w.deathpoint() ? "☠ " : "") + w.name();
                int tw = this.font.width(name);
                gfx.fill(x + 6, y - 5, x + 8 + tw + 2, y + 6, 0x99000000);
                gfx.text(this.font, name, x + 8, y - 4, 0xFFFFFFFF);
            }
        }

        // Player marker.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double psx = halfW + (mc.player.getX() - cxBlock) * scale;
            double psy = halfH + (mc.player.getZ() - czBlock) * scale;
            int px = (int) psx, py = (int) psy;
            // 3×3 white dot + 5px facing tick.
            gfx.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
            float yaw = (float) Math.toRadians(mc.player.getYRot());
            int tx = px + (int) Math.round(Math.sin(-yaw) * 6);
            int ty = py + (int) Math.round(-Math.cos(-yaw) * 6);
            gfx.fill(Math.min(px, tx), Math.min(py, ty), Math.max(px, tx) + 1, Math.max(py, ty) + 1, 0xCCFFFFFF);
        }

        // Status pills (top-right corner under the close button).
        Minecraft mc2 = Minecraft.getInstance();
        if (mc2.player != null) {
            String coords = (int) mc2.player.getX() + ", " + (int) mc2.player.getY() + ", " + (int) mc2.player.getZ();
            gfx.text(this.font, "§7Player: §f" + coords, this.width - 220, 36, 0xFFFFFFFF);
        }
        gfx.text(this.font, String.format("§7Zoom: §f%.2fx  §7Chunks: §f%d",
                pixelsPerBlock, data.count()), this.width - 220, 50, 0xFFFFFFFF);
        gfx.text(this.font, "§7Drag to pan · scroll to zoom · §fM§7 / §fEsc§7 to close",
                12, this.height - 16, 0xFFAAAAAA);

        // Buttons last so they layer on top of the map.
        super.extractRenderState(gfx, mouseX, mouseY, partial);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        // Match the open key (M) as a close shortcut too.
        if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_M) {
            this.onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
