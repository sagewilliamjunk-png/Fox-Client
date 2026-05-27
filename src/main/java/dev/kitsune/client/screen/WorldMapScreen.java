package dev.kitsune.client.screen;

import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import dev.kitsune.client.worldmap.Footsteps;
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
        // Export PNG — dumps the current cache into a PNG file inside the
        // user's screenshots folder. Same folder vanilla F2 uses so the
        // launcher's Screenshots gallery picks it up automatically.
        this.addRenderableWidget(FoxButton.of(184, 8, 90, 20,
                Component.literal("💾 Export PNG"),
                b -> exportPng()));
    }

    /** Allocate one big int[] covering the bbox at 1 pixel per block and
     *  blit each cached chunk's 16×16 grid into it. Then hand to ImageIO. */
    private void exportPng() {
        WorldMapData data = WorldMapManager.active();
        if (data == null || data.count() == 0) {
            dev.kitsune.client.hud.NotificationManager.show(
                    "Nothing to export yet — walk around to explore.",
                    dev.kitsune.client.hud.NotificationManager.Type.WARNING);
            return;
        }
        try {
            int minCx = data.minCx(), maxCx = data.maxCx();
            int minCz = data.minCz(), maxCz = data.maxCz();
            int widthPx  = ((maxCx - minCx + 1) << 4);
            int heightPx = ((maxCz - minCz + 1) << 4);
            // Reasonable cap — 8192×8192 is 256 MB worst case for the int[]
            // and would already produce a 100+ MB PNG. If the user has been
            // exploring for that long they can split exports manually.
            if (widthPx > 8192 || heightPx > 8192) {
                dev.kitsune.client.hud.NotificationManager.show(
                        "Explored area too large to export — " + widthPx + "×" + heightPx + ".",
                        dev.kitsune.client.hud.NotificationManager.Type.WARNING);
                return;
            }
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(widthPx, heightPx, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            // Fill background black so missing chunks (holes in exploration)
            // are visually distinct from unexplored map margins.
            int[] backing = new int[widthPx * heightPx];
            java.util.Arrays.fill(backing, 0xFF000000);
            for (var entry : data.tiles.entrySet()) {
                var cp = entry.getKey();
                int[] tile = entry.getValue();
                int baseX = (cp.x() - minCx) << 4;
                int baseY = (cp.z() - minCz) << 4;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        backing[(baseY + lz) * widthPx + (baseX + lx)] = tile[lz * 16 + lx];
                    }
                }
            }
            img.setRGB(0, 0, widthPx, heightPx, backing, 0, widthPx);

            // Resolve the target file: <gameDir>/screenshots/foxmap-<sub>-<ts>.png.
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            java.io.File ssDir = new java.io.File(mc.gameDirectory, "screenshots");
            if (!ssDir.exists() && !ssDir.mkdirs()) {
                throw new java.io.IOException("Failed to create screenshots dir: " + ssDir);
            }
            String safeSub = data.subWorldId.replaceAll("[^a-zA-Z0-9._-]", "_");
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                    .format(new java.util.Date());
            java.io.File out = new java.io.File(ssDir, "foxmap-" + safeSub + "-" + stamp + ".png");
            javax.imageio.ImageIO.write(img, "PNG", out);

            dev.kitsune.client.hud.NotificationManager.show(
                    "Exported " + widthPx + "×" + heightPx + " → " + out.getName(),
                    dev.kitsune.client.hud.NotificationManager.Type.SUCCESS);
        } catch (Throwable t) {
            dev.kitsune.client.KitsuneClient.LOGGER.warn("[WorldMap] PNG export failed: {}", t.toString());
            dev.kitsune.client.hud.NotificationManager.show(
                    "Export failed: " + t.getMessage(),
                    dev.kitsune.client.hud.NotificationManager.Type.WARNING);
        }
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
        // Right-click anywhere on the map area = drop a waypoint there.
        // We translate screen → world space and create immediately, with a
        // best-effort Y from the surface tile that covers that block.
        if (e.button() == 1 && !super.mouseClicked(e, focused)) {
            double worldX = centerWorldX + (e.x() - this.width  / 2.0) / pixelsPerBlock;
            double worldZ = centerWorldZ + (e.y() - this.height / 2.0) / pixelsPerBlock;
            createWaypointAtWorld((int) Math.floor(worldX), (int) Math.floor(worldZ));
            return true;
        }
        // Left-click = pan drag.
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

    /** Create a waypoint at the given world block (x, z). The y comes from
     *  whatever surface tile we have stored — if the chunk isn't in the
     *  cache, defaults to sea level. */
    private void createWaypointAtWorld(int worldX, int worldZ) {
        int worldY = 64; // sea-level fallback
        WorldMapData data = WorldMapManager.active();
        if (data != null) {
            ChunkPos cp = new ChunkPos(worldX >> 4, worldZ >> 4);
            // We stored ARGB altitude colors, not raw heights — derive an
            // approximate y by inverting the heightToColor formula. Quick &
            // dirty: average the green channel since it maps monotonically
            // to altitude, then unscale back into the sea-level range.
            int[] tile = data.get(cp);
            if (tile != null) {
                int lx = (worldX & 15);
                int lz = (worldZ & 15);
                int argb = tile[lz * 16 + lx];
                int g = (argb >> 8) & 0xFF;
                // From ChunkColorTile: g = round(50 + t*170)
                float t = Math.max(0, Math.min(1, (g - 50) / 170f));
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    int sea = mc.level.getSeaLevel();
                    int top = Math.min(320, sea + 96);
                    int bot = Math.max(-64, sea - 32);
                    worldY = Math.round(bot + t * (top - bot));
                }
            }
        }
        String name = WaypointManager.nextDefaultName();
        String sym  = name.length() > 0 ? name.substring(0, 1).toUpperCase() : "•";
        Waypoint w = new Waypoint(
                null, name, worldX, worldY, worldZ,
                Waypoint.DEFAULT_COLOR, sym, false, false);
        WaypointManager.addToCurrent(w);
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

        // Fast terrain pass — one gfx.blit per visible chunk. Previously the
        // world map ran 256 gfx.fill calls per chunk which froze the game for
        // several seconds on open with even modest exploration. The pose-stack
        // transform applies scale + center translate so each chunk's blit
        // stays in world coordinates.
        gfx.pose().pushMatrix();
        gfx.pose().translate(halfW, halfH);
        gfx.pose().scale((float) scale, (float) scale);
        gfx.pose().translate(-(float) cxBlock, -(float) czBlock);
        for (var entry : data.tiles.entrySet()) {
            ChunkPos cp = entry.getKey();
            int blockX = cp.getMinBlockX();
            int blockZ = cp.getMinBlockZ();
            // Cull in screen space.
            double sx = halfW + (blockX - cxBlock) * scale;
            double sy = halfH + (blockZ - czBlock) * scale;
            if (sx + chunkPx < 0 || sx > this.width || sy + chunkPx < 0 || sy > this.height) continue;
            net.minecraft.resources.Identifier id = data.textures.idFor(cp);
            if (id == null) {
                // Texture might not be uploaded yet (happens for chunks read
                // off disk that haven't been touched since). Lazy-upload from
                // the raw int[] and try again next frame.
                int[] tile = entry.getValue();
                data.textures.upsert(cp, tile);
                continue;
            }
            gfx.blit(id, blockX, blockZ, blockX + 16, blockZ + 16, 0f, 1f, 0f, 1f);
        }
        gfx.pose().popMatrix();

        // Footsteps trail — render before waypoints so the markers sit on top.
        // Fade alpha from 0 (oldest) up to 255 (most recent) so the trail
        // looks like it dissolves behind the player.
        Footsteps.Step[] steps = Footsteps.snapshot();
        if (steps.length >= 2) {
            for (int i = 1; i < steps.length; i++) {
                Footsteps.Step a = steps[i - 1];
                Footsteps.Step b = steps[i];
                int alpha = (int) Math.round(40 + 200.0 * (i / (double) steps.length));
                int color = (alpha << 24) | 0xFFDD66;
                double axSx = halfW + (a.x() - cxBlock) * scale;
                double axSy = halfH + (a.z() - czBlock) * scale;
                double bxSx = halfW + (b.x() - cxBlock) * scale;
                double bxSy = halfH + (b.z() - czBlock) * scale;
                // Quick line draw via Bresenham — gfx.fill is the only
                // primitive we have, so we plot 1×1 pixels along the segment.
                int x0 = (int) axSx, y0 = (int) axSy, x1 = (int) bxSx, y1 = (int) bxSy;
                int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
                int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
                int err = dx + dy;
                int safety = 2000; // anti-OOL safety net for extreme zoom
                while (safety-- > 0) {
                    if (x0 >= 0 && x0 < this.width && y0 >= 0 && y0 < this.height) {
                        gfx.fill(x0, y0, x0 + 1, y0 + 1, color);
                    }
                    if (x0 == x1 && y0 == y1) break;
                    int e2 = 2 * err;
                    if (e2 >= dy) { err += dy; x0 += sx; }
                    if (e2 <= dx) { err += dx; y0 += sy; }
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
        gfx.text(this.font, "§7Drag to pan · scroll to zoom · §fright-click§7 to drop waypoint · §fM§7 / §fEsc§7 to close",
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
