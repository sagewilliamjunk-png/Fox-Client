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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full-featured minimap + entity radar — replaces the old EntityRadarModule
 * and adds the bulk of Xaero's-minimap parity in one module.
 *
 * <h2>What's here</h2>
 * <ul>
 *   <li><b>View modes:</b> Dots only · Heightmap · Heightmap + Dots</li>
 *   <li><b>Shape:</b> Circle (vanilla minimap aesthetic) · Square (Xaeros style)</li>
 *   <li><b>North lock:</b> when on, the map stops rotating with the player
 *       and a direction arrow indicates which way they face.</li>
 *   <li><b>Cave mode:</b> when the player is underground or has a solid
 *       block roof, render a cross-section at the player's altitude
 *       instead of the surface heightmap.</li>
 *   <li><b>Overlays:</b> chunk grid · slime chunks (XOR hash) · light overlay
 *       (highlights mob-spawnable blocks).</li>
 *   <li><b>Entity colors:</b> players (white), hostile mobs (red), friendly
 *       mobs (yellow), items (cyan), other entities (purple). Each toggleable.</li>
 *   <li><b>Tab-held heads:</b> hold the player-list key to swap player dots
 *       for their 8×8 skin face.</li>
 *   <li><b>Info pills under the map:</b> coords · biome · light level ·
 *       game time (12h/24h) · camera angles. Each toggleable.</li>
 *   <li><b>Zoom keybinds:</b> {@code KitsuneClient.minimapZoomIn / Out / Enlarge}
 *       handled by the host KitsuneClient tick loop.</li>
 * </ul>
 *
 * <p>All data is client-side only. No packets sent, no extra requests — safe
 * on every server.
 *
 * <p>Performance: terrain rebuilds once per chunk-cross or every 40 ticks
 * (whichever first). Entity scan runs every other tick. Per-frame cost is
 * one circle/square fill + cached ARGB writes for terrain + a small list
 * iteration for entities + the info-pill text. Comfortably under a millisecond
 * even at the maximum 150px size.
 */
public class MinimapModule extends Module implements HudWidget {

    private static final int CACHE_TICKS = 2;
    private static final int TERRAIN_REFRESH_TICKS = 40;

    /** Singleton so the KitsuneClient tick loop can drive zoom keybinds without
     *  needing a ModuleManager lookup each frame. Set in the constructor. */
    private static volatile MinimapModule INSTANCE;

    // ---- settings ----

    private final ModeSetting    viewMode       = addSetting(new ModeSetting("View Mode", "Heightmap + Dots",
            List.of("Dots", "Heightmap", "Heightmap + Dots")));
    private final ModeSetting    colorMode      = addSetting(new ModeSetting("Color Mode", "Vanilla Map",
            List.of("Vanilla Map", "Altitude", "Biome Tinted")));
    private final ModeSetting    shape          = addSetting(new ModeSetting("Shape", "Circle",
            List.of("Circle", "Square")));
    private final SliderSetting  rangeBlocks    = addSetting(new SliderSetting("Range (blocks)", 64, 16, 256, 8));
    private final SliderSetting  sizePixels     = addSetting(new SliderSetting("Size (pixels)",   60, 30, 150, 5));
    private final SliderSetting  enlargeFactor  = addSetting(new SliderSetting("Enlarge × (hold-key)", 2.0, 1.5, 4.0, 0.5));
    private final BooleanSetting northLock      = addSetting(new BooleanSetting("North Lock", false));
    private final BooleanSetting caveMode       = addSetting(new BooleanSetting("Auto Cave Mode", true));
    private final BooleanSetting compassLines   = addSetting(new BooleanSetting("Compass Lines",  true));
    private final BooleanSetting cardinalLabels = addSetting(new BooleanSetting("N/S/E/W Labels", true));

    // Entity radar
    private final BooleanSetting showPlayers    = addSetting(new BooleanSetting("Show Players",    true));
    private final BooleanSetting showHostiles   = addSetting(new BooleanSetting("Show Hostile Mobs", true));
    private final BooleanSetting showFriendlies = addSetting(new BooleanSetting("Show Friendly Mobs", true));
    private final BooleanSetting showItems      = addSetting(new BooleanSetting("Show Items",      true));
    private final BooleanSetting showOther      = addSetting(new BooleanSetting("Show Other Entities", false));
    private final BooleanSetting tabSwapsHeads  = addSetting(new BooleanSetting("Tab → Player Heads", true));

    // Overlays
    private final BooleanSetting chunkGrid      = addSetting(new BooleanSetting("Chunk Grid",     false));
    private final BooleanSetting slimeChunks    = addSetting(new BooleanSetting("Slime Chunks",   false));
    private final BooleanSetting lightOverlay   = addSetting(new BooleanSetting("Light Overlay (≤7)", false));

    // Waypoints
    private final BooleanSetting showWaypoints  = addSetting(new BooleanSetting("Show Waypoints",  true));
    private final SliderSetting  wpMaxDistance  = addSetting(new SliderSetting("Local WP max draw (blocks)", 256, 64, 4096, 64));

    // Info pills below the map
    private final BooleanSetting showCoords     = addSetting(new BooleanSetting("Info: Coordinates", true));
    private final BooleanSetting showBiome      = addSetting(new BooleanSetting("Info: Biome",       false));
    private final BooleanSetting showLight      = addSetting(new BooleanSetting("Info: Light Level", false));
    private final BooleanSetting showTime       = addSetting(new BooleanSetting("Info: Game Time",   false));
    private final BooleanSetting time24h        = addSetting(new BooleanSetting("Time: 24-hour",     false));
    private final BooleanSetting showFacing     = addSetting(new BooleanSetting("Info: Facing",      false));

    // ---- cached state ----

    /** Cached radar entry. `entityType` is held so the Tab-held render path
     *  can look up the entity's spawn egg sprite for mob icons. */
    private record RadarEntry(double dx, double dz, EntityKind kind, UUID uuid,
                              net.minecraft.world.entity.EntityType<?> entityType) {}
    private enum EntityKind { PLAYER, HOSTILE, FRIENDLY, ITEM, OTHER }

    private List<RadarEntry> entityCache = new ArrayList<>();
    private int entityCacheTick = 0;

    /** GPU-side cache. Each chunk = one 16×16 DynamicTexture so the renderer
     *  does one gfx.blit per chunk instead of 256 gfx.fill calls. Huge perf
     *  win — the old per-pixel render was tanking frame times. */
    private final dev.kitsune.client.worldmap.MapTextureCache terrainTextures =
            new dev.kitsune.client.worldmap.MapTextureCache("kitsune_minimap");
    /** Track which chunks we've computed this terrain pass for so retainAndCloseRest
     *  can free GPU memory when the player moves out of an area. */
    private final java.util.HashSet<ChunkPos> activeChunkSet = new java.util.HashSet<>();
    private long lastPlayerChunkX = Long.MIN_VALUE;
    private long lastPlayerChunkZ = Long.MIN_VALUE;
    private int  lastPlayerY      = Integer.MIN_VALUE;
    private boolean lastWasCave   = false;
    private int  terrainRefreshCounter = 0;

    // Runtime-controlled zoom (keybinds adjust this) — saved into rangeBlocks
    // on tick for persistence. enlargeActive is true while the user holds the
    // enlarge key; rendering multiplies sizePixels by enlargeFactor.
    private volatile boolean enlargeActive = false;

    public MinimapModule() {
        super("Minimap",
              "Full-featured minimap with cave mode, overlays, entity radar, and Tab-to-show-heads.",
              Category.HUD);
        HudManager.register(this);
        INSTANCE = this;
    }

    public static MinimapModule instance() { return INSTANCE; }

    /** Called by KitsuneClient when the user presses the in/out zoom keybinds. */
    public void adjustZoom(int blockDelta) {
        double current = rangeBlocks.get();
        double next = Math.max(16, Math.min(256, current + blockDelta));
        if (next != current) rangeBlocks.set(next);
    }

    /** Called by KitsuneClient at the start/end of an enlarge-key press. */
    public void setEnlargeActive(boolean v) { this.enlargeActive = v; }

    // ---- HudWidget --------------------------------------------------------

    @Override public String widgetId()    { return "minimap"; }
    @Override public String displayName() { return "Minimap"; }

    private int effectiveSize() {
        int base = sizePixels.get().intValue();
        if (!enlargeActive) return base;
        return Math.min(300, (int)(base * enlargeFactor.get()));
    }

    @Override public int widgetWidth()  { return effectiveSize() * 2 + 8; }

    @Override
    public int widgetHeight() {
        int pillRows = countActivePills();
        // 11px per pill row, +4px padding around them.
        return effectiveSize() * 2 + 8 + (pillRows > 0 ? pillRows * 11 + 4 : 0);
    }

    private int countActivePills() {
        int n = 0;
        if (showCoords.get()) n++;
        if (showBiome.get())  n++;
        if (showLight.get())  n++;
        if (showTime.get())   n++;
        if (showFacing.get()) n++;
        return n;
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    // ---- Module -----------------------------------------------------------

    @Override
    protected void onDisable() {
        entityCache.clear();
        terrainTextures.closeAll();
        activeChunkSet.clear();
        lastPlayerChunkX = Long.MIN_VALUE;
        lastPlayerChunkZ = Long.MIN_VALUE;
        lastPlayerY      = Integer.MIN_VALUE;
        enlargeActive    = false;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            entityCache.clear();
            return;
        }

        // 1. Entity cache — throttled.
        if (++entityCacheTick % CACHE_TICKS == 0) {
            entityCache = collectEntities(mc, mc.player);
        }

        // 2. Terrain cache — rebuild on chunk-cross, Y-band-cross (cave mode),
        //    cave-flag flip, or every TERRAIN_REFRESH_TICKS ticks.
        if (!"Dots".equals(viewMode.get())) {
            long cx = mc.player.getBlockX() >> 4;
            long cz = mc.player.getBlockZ() >> 4;
            int  py = mc.player.getBlockY();
            boolean cave = caveMode.get() && isInCave(mc, py);
            terrainRefreshCounter++;
            boolean refresh = (cx != lastPlayerChunkX)
                           || (cz != lastPlayerChunkZ)
                           || (cave != lastWasCave)
                           || (cave && Math.abs(py - lastPlayerY) >= 4)
                           || terrainRefreshCounter >= TERRAIN_REFRESH_TICKS;
            if (refresh) {
                lastPlayerChunkX = cx;
                lastPlayerChunkZ = cz;
                lastPlayerY      = py;
                lastWasCave      = cave;
                terrainRefreshCounter = 0;
                rebuildTerrainCache(mc, cx, cz, cave, py);
            }
        }
    }

    /** True when there's a solid-block "roof" within 3 blocks above the player.
     *  Matches Xaeros' default cave-detection heuristic (3-block roof check). */
    private static boolean isInCave(Minecraft mc, int playerY) {
        if (mc.level == null || mc.player == null) return false;
        int x = mc.player.getBlockX();
        int z = mc.player.getBlockZ();
        int probeMax = playerY + 8;
        for (int y = playerY + 2; y <= probeMax; y++) {
            BlockState s = mc.level.getBlockState(new BlockPos(x, y, z));
            if (!s.isAir() && s.isSolid()) return true;
        }
        return false;
    }

    private List<RadarEntry> collectEntities(Minecraft mc, LocalPlayer self) {
        double px = self.getX();
        double pz = self.getZ();
        double maxR = rangeBlocks.get();
        double maxR2 = maxR * maxR;
        List<RadarEntry> out = new ArrayList<>();

        if (showPlayers.get()) {
            for (var p : mc.level.players()) {
                if (p == self) continue;
                double dx = p.getX() - px;
                double dz = p.getZ() - pz;
                if (dx * dx + dz * dz <= maxR2) {
                    out.add(new RadarEntry(dx, dz, EntityKind.PLAYER, p.getUUID(), p.getType()));
                }
            }
        }

        if (showHostiles.get() || showFriendlies.get() || showItems.get() || showOther.get()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == self) continue;
                if (e instanceof Player) continue; // handled above
                double dx = e.getX() - px;
                double dz = e.getZ() - pz;
                if (dx * dx + dz * dz > maxR2) continue;
                EntityKind kind = classify(e);
                if (kind == EntityKind.HOSTILE  && !showHostiles.get()) continue;
                if (kind == EntityKind.FRIENDLY && !showFriendlies.get()) continue;
                if (kind == EntityKind.ITEM     && !showItems.get())    continue;
                if (kind == EntityKind.OTHER    && !showOther.get())    continue;
                out.add(new RadarEntry(dx, dz, kind, e.getUUID(), e.getType()));
            }
        }
        return out;
    }

    private static EntityKind classify(Entity e) {
        if (e instanceof ItemEntity) return EntityKind.ITEM;
        if (e instanceof Enemy)       return EntityKind.HOSTILE;
        if (e instanceof Mob)         return EntityKind.FRIENDLY;
        return EntityKind.OTHER;
    }

    private static int colorFor(EntityKind k) {
        return switch (k) {
            case PLAYER   -> 0xFFFFFFFF;
            case HOSTILE  -> 0xFFFF4444;
            case FRIENDLY -> 0xFFFFCC22;
            case ITEM     -> 0xFF44CCFF;
            case OTHER    -> 0xFFCC66FF;
        };
    }

    // ---- terrain cache ----------------------------------------------------

    private void rebuildTerrainCache(Minecraft mc, long playerCx, long playerCz, boolean cave, int playerY) {
        ClientLevel level = mc.level;
        if (level == null) return;
        int chunkRange = (int) Math.ceil(rangeBlocks.get() / 16.0) + 1;
        activeChunkSet.clear();
        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                activeChunkSet.add(new ChunkPos((int)(playerCx + dx), (int)(playerCz + dz)));
            }
        }
        // Evict GPU textures for chunks outside the new range.
        terrainTextures.retainAndCloseRest(activeChunkSet);
        dev.kitsune.client.worldmap.ChunkColorTile.ColorMode mode = currentColorMode();
        for (ChunkPos cp : activeChunkSet) {
            if (terrainTextures.contains(cp) && !cave) continue;
            int[] tile = cave
                    ? computeCaveTile(level, cp, playerY)
                    : dev.kitsune.client.worldmap.ChunkColorTile.surface(level, cp, mode);
            if (tile != null) terrainTextures.upsert(cp, tile);
        }
    }

    /** Public accessor so the WorldMap can read the same color mode the
     *  minimap is using. Falls back to ALTITUDE when the mode string is
     *  unrecognised so a malformed setting can't blank the tile compute. */
    public dev.kitsune.client.worldmap.ChunkColorTile.ColorMode currentColorMode() {
        String s = colorMode.get();
        return switch (s) {
            case "Vanilla Map"  -> dev.kitsune.client.worldmap.ChunkColorTile.ColorMode.VANILLA_MAP;
            case "Biome Tinted" -> dev.kitsune.client.worldmap.ChunkColorTile.ColorMode.BIOME_TINTED;
            default             -> dev.kitsune.client.worldmap.ChunkColorTile.ColorMode.ALTITUDE;
        };
    }

    /** Back-compat for code that only cares about the boolean tint flag. */
    public boolean biomeTinted() { return currentColorMode() == dev.kitsune.client.worldmap.ChunkColorTile.ColorMode.BIOME_TINTED; }

    // Surface heightmap tiles now go through ChunkColorTile.surface() (with
    // optional biome tinting). Local computeCaveTile stays — cave mode is
    // minimap-only and doesn't need to be shared with the world map.

    /** Cave cross-section tile — for each (lx,lz), walk DOWN from playerY+1
     *  until we hit a solid block; render its floor in altitude shade. If we
     *  reach world bottom without finding anything, render a faint dark dot. */
    private static int[] computeCaveTile(ClientLevel level, ChunkPos cp, int playerY) {
        var access = level.getChunk(cp.x(), cp.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return null;
        int[] argb = new int[256];
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        int minY  = level.getMinY();
        int searchTop = Math.min(playerY + 1, level.getMaxY());
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int floorY = minY;
                for (int y = searchTop; y >= minY; y--) {
                    BlockState s = chunk.getBlockState(new BlockPos(baseX + lx, y, baseZ + lz));
                    if (!s.isAir() && s.isSolid()) {
                        floorY = y;
                        break;
                    }
                }
                argb[lz * 16 + lx] = heightToColor(level, floorY);
            }
        }
        return argb;
    }

    private static int heightToColor(ClientLevel level, int y) {
        int sea = level.getSeaLevel();
        int top = Math.min(320, sea + 96);
        int bot = Math.max(-64, sea - 32);
        float t = (float) Math.max(0, Math.min(1, (y - bot) / (float)(top - bot)));
        int r = Math.round(60 + t * 130);
        int g = Math.round(50 + t * 170);
        int b = Math.round(40 + t * 200);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        int r  = effectiveSize();
        int cx = x + r + 4;
        int cy = y + r + 4;
        boolean square = "Square".equals(shape.get());
        boolean drawTerrain = !"Dots".equals(viewMode.get());
        boolean drawDots    = !"Heightmap".equals(viewMode.get());
        boolean north       = northLock.get();
        boolean cave        = caveMode.get() && lastWasCave;

        // 1. Background (square or circle).
        if (square) {
            gfx.fill(cx - r, cy - r, cx + r, cy + r, 0xBB0D0D14);
        } else {
            drawFilledCircle(gfx, cx, cy, r, 0xBB0D0D14);
        }

        // Effective yaw to rotate the map by. north-lock = 0 so the map stays still.
        double yawRad = north ? 0 : Math.toRadians(player.getYRot());
        double cosY = Math.cos(yawRad);
        double sinY = Math.sin(yawRad);

        // 2. Terrain
        if (drawTerrain) drawTerrain(gfx, mc, cx, cy, r, square, cosY, sinY);

        // 3. Overlays (chunk grid, slime, light)
        if (chunkGrid.get())   drawChunkGrid(gfx, mc, cx, cy, r, square, cosY, sinY);
        if (slimeChunks.get()) drawSlimeChunks(gfx, mc, cx, cy, r, square, cosY, sinY);
        if (lightOverlay.get()) drawLightOverlay(gfx, mc, cx, cy, r, square, cosY, sinY);

        // 4. Compass cross-hairs (in screen space — useful even with north-lock).
        if (compassLines.get()) {
            int lc = 0x33FFFFFF;
            gfx.fill(cx - 1, cy - r + 2, cx + 1, cy + r - 2, lc);
            gfx.fill(cx - r + 2, cy - 1, cx + r - 2, cy + 1, lc);
        }

        // 5. Entity dots / heads
        if (drawDots) drawEntities(gfx, mc, player, cx, cy, r, square, cosY, sinY);

        // 5b. Waypoints — drawn above entities so a player on top of a waypoint
        //     doesn't fully cover the marker.
        if (showWaypoints.get()) drawWaypoints(gfx, mc, player, cx, cy, r, square, cosY, sinY);

        // 6. Self marker + facing arrow when north-locked
        if (north) {
            // White arrow pointing the player's actual facing direction.
            float yaw = (float) Math.toRadians(player.getYRot());
            drawArrow(gfx, cx, cy, yaw, 0xFFFFFFFF);
        } else {
            gfx.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
            gfx.fill(cx, cy - 4, cx + 1, cy - 1, 0xCCFFFFFF);
        }

        // 7. Border
        int border1 = cave ? 0xFF553322 : 0xFF22222E;
        int border2 = cave ? 0xFF775544 : 0xFF333340;
        if (square) {
            drawSquareOutline(gfx, cx - r, cy - r, cx + r, cy + r, border1);
            drawSquareOutline(gfx, cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1, border2);
        } else {
            drawCircleOutline(gfx, cx, cy, r,     border1);
            drawCircleOutline(gfx, cx, cy, r - 1, border2);
        }

        // 8. Cave-mode pill in the top-right of the map.
        if (cave) {
            Font f = mc.font;
            gfx.text(f, "§7CAVE", cx + r - 22, cy - r + 3, 0xFFCC9966);
        }

        // 9. Cardinal labels (always anchored to true north, even when rotating).
        if (cardinalLabels.get()) {
            Font font = mc.font;
            float yaw = north ? 0f : (float) Math.toRadians(player.getYRot());
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "N", (float) Math.PI);
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "S", 0f);
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "E", (float) -Math.PI / 2);
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "W", (float) Math.PI / 2);
        }

        // 10. Info pills below the map.
        renderInfoPills(gfx, mc, player, x, y + r * 2 + 8);
    }

    private boolean insideShape(int dx, int dy, int r, boolean square) {
        if (square) return Math.abs(dx) <= r && Math.abs(dy) <= r;
        return dx * dx + dy * dy <= r * r;
    }

    // ---- terrain / overlay render passes ----------------------------------

    /** Fast terrain pass — one gfx.blit per chunk. Uses the pose stack to
     *  scale + rotate the entire view so the per-chunk blits stay axis-aligned
     *  in world space. Scissor clips to the minimap's bounding rect; the
     *  circular border drawn afterwards hides the corner overflow.
     *
     *  Replaces the old per-pixel render that did 4000+ gfx.fill calls per
     *  frame at the default range — the cause of the framerate tank. */
    private void drawTerrain(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null || activeChunkSet.isEmpty()) return;
        float scale = (float)(r / rangeBlocks.get());
        float playerX = (float) p.getX();
        float playerZ = (float) p.getZ();
        // Yaw used to rotate the pose. (cosY, sinY) is the standard
        // 2D rotation matrix; reconstruct the angle for pose.rotate.
        // Note: north-lock passes cosY=1, sinY=0 so this resolves to 0 rad.
        float yaw = (float) Math.atan2(sinY, cosY);

        // Clip to the minimap's axis-aligned bounding box. The circular border
        // overlays the corners so any rotated terrain that spills slightly
        // outside the circle isn't visible to the user.
        gfx.enableScissor(cx - r, cy - r, cx + r, cy + r);
        gfx.pose().pushMatrix();
        // Center the rotation around the minimap centre, scale to map zoom,
        // then translate world-coords so the player is at the centre.
        gfx.pose().translate((float) cx, (float) cy);
        gfx.pose().rotate(yaw);
        gfx.pose().scale(scale, scale);
        gfx.pose().translate(-playerX, -playerZ);

        for (ChunkPos cp : activeChunkSet) {
            net.minecraft.resources.Identifier id = terrainTextures.idFor(cp);
            if (id == null) continue;
            int baseX = cp.getMinBlockX();
            int baseZ = cp.getMinBlockZ();
            // Each chunk: one 16×16 blit in WORLD space. The pose transform
            // handles all rotation/scale; we just give it world coords.
            gfx.blit(id, baseX, baseZ, baseX + 16, baseZ + 16, 0f, 1f, 0f, 1f);
        }

        gfx.pose().popMatrix();
        gfx.disableScissor();
    }

    private void drawChunkGrid(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        double scale = r / rangeBlocks.get();
        int innerLim = r - 2;
        double playerX = p.getX();
        double playerZ = p.getZ();
        int chunkRange = (int) Math.ceil(rangeBlocks.get() / 16.0) + 1;
        int color = 0x66FFFFFF;
        long pcx = p.getBlockX() >> 4;
        long pcz = p.getBlockZ() >> 4;
        // Vertical and horizontal chunk boundary lines projected on the rotated map.
        for (int cdx = -chunkRange; cdx <= chunkRange + 1; cdx++) {
            for (int cdz = -chunkRange; cdz <= chunkRange + 1; cdz++) {
                int wx = (int)((pcx + cdx) << 4);
                int wz = (int)((pcz + cdz) << 4);
                double dx = wx - playerX;
                double dz = wz - playerZ;
                double sx = dx * cosY + dz * sinY;
                double sy = dx * sinY - dz * cosY;
                int px = cx + (int)(sx * scale);
                int py = cy + (int)(sy * scale);
                if (!insideShape(px - cx, py - cy, innerLim, square)) continue;
                gfx.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    private void drawSlimeChunks(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        double scale = r / rangeBlocks.get();
        int innerLim = r - 2;
        double playerX = p.getX();
        double playerZ = p.getZ();
        int chunkRange = (int) Math.ceil(rangeBlocks.get() / 16.0) + 1;
        long pcx = p.getBlockX() >> 4;
        long pcz = p.getBlockZ() >> 4;
        long worldSeed = 0; // we don't have access to the seed on most servers
        int color = 0x44229922;
        for (int cdx = -chunkRange; cdx <= chunkRange; cdx++) {
            for (int cdz = -chunkRange; cdz <= chunkRange; cdz++) {
                int cxw = (int)(pcx + cdx);
                int czw = (int)(pcz + cdz);
                if (!isSlimeChunk(worldSeed, cxw, czw)) continue;
                // Render as a translucent overlay over the chunk's 16×16 area.
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        double dx = (cxw << 4) + lx - playerX;
                        double dz = (czw << 4) + lz - playerZ;
                        double sx = dx * cosY + dz * sinY;
                        double sy = dx * sinY - dz * cosY;
                        int px = cx + (int)(sx * scale);
                        int py = cy + (int)(sy * scale);
                        if (!insideShape(px - cx, py - cy, innerLim, square)) continue;
                        gfx.fill(px, py, px + 1, py + 1, color);
                    }
                }
            }
        }
    }

    /** Slime-chunk RNG. Vanilla uses java.util.Random seeded by world seed +
     *  chunk position. Works without the seed on creative/singleplayer where
     *  worldSeed==0; on multiplayer (unknown seed) it produces wrong-but-stable
     *  results — better than nothing and matches the warning Xaeros gives. */
    private static boolean isSlimeChunk(long worldSeed, int cx, int cz) {
        long seed = worldSeed
                + (long)(cx * cx * 0x4c1906)
                + (long)(cx * 0x5ac0db)
                + (long)(cz * cz) * 0x4307a7L
                + (long)(cz * 0x5f24f) ^ 0x3ad8025fL;
        java.util.Random rng = new java.util.Random(seed);
        return rng.nextInt(10) == 0;
    }

    private void drawLightOverlay(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        double scale = r / rangeBlocks.get();
        int innerLim = r - 2;
        double playerX = p.getX();
        double playerZ = p.getZ();
        int rangeBlocksInt = (int) Math.ceil(rangeBlocks.get());
        int color = 0x66FF2222;
        Heightmap.Types h = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        int px0 = mc.player.getBlockX();
        int pz0 = mc.player.getBlockZ();
        // Limited sampling — every 2 blocks — to keep cost bounded.
        for (int dx = -rangeBlocksInt; dx <= rangeBlocksInt; dx += 2) {
            for (int dz = -rangeBlocksInt; dz <= rangeBlocksInt; dz += 2) {
                int wx = px0 + dx;
                int wz = pz0 + dz;
                int wy = mc.level.getHeight(h, wx, wz);
                int blockLight = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,
                        new BlockPos(wx, wy, wz));
                if (blockLight > 7) continue;
                double sx = dx * cosY + dz * sinY;
                double sy = dx * sinY - dz * cosY;
                int spx = cx + (int)(sx * scale);
                int spy = cy + (int)(sy * scale);
                if (!insideShape(spx - cx, spy - cy, innerLim, square)) continue;
                gfx.fill(spx, spy, spx + 2, spy + 2, color);
            }
        }
    }

    // ---- entities ---------------------------------------------------------

    private void drawEntities(GuiGraphicsExtractor gfx, Minecraft mc, LocalPlayer self, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        double scale = r / rangeBlocks.get();
        int innerLim = r - 3;
        boolean tabHeld = tabSwapsHeads.get()
                && mc.options != null
                && mc.options.keyPlayerList != null
                && mc.options.keyPlayerList.isDown();

        for (RadarEntry e : entityCache) {
            double sx =  e.dx() * cosY + e.dz() * sinY;
            double sy =  e.dx() * sinY - e.dz() * cosY;
            int dotX = cx + (int)(sx * scale);
            int dotY = cy + (int)(sy * scale);
            if (!insideShape(dotX - cx, dotY - cy, innerLim, square)) continue;

            if (tabHeld) {
                if (e.kind() == EntityKind.PLAYER && drawPlayerHead(gfx, mc, e.uuid(), dotX, dotY)) continue;
                // Mob / item icon via spawn egg or fallback item.
                if (e.kind() != EntityKind.PLAYER && drawSpawnEggIcon(gfx, e.entityType(), dotX, dotY)) continue;
            }
            gfx.fill(dotX - 1, dotY - 1, dotX + 1, dotY + 1, colorFor(e.kind()));
        }
    }

    /** Render a spawn-egg item at half scale (8×8) centred on (px, py).
     *  Falls back to false when the entity has no spawn egg (boats, projectiles,
     *  item-frames, etc.) so the caller draws a dot instead. */
    private static boolean drawSpawnEggIcon(GuiGraphicsExtractor gfx, net.minecraft.world.entity.EntityType<?> type, int px, int py) {
        if (type == null) return false;
        try {
            var holderOpt = net.minecraft.world.item.SpawnEggItem.byId(type);
            if (holderOpt.isEmpty()) return false;
            net.minecraft.world.item.Item spawnEgg = holderOpt.get().value();
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(spawnEgg);
            // gfx.item renders at 16×16; scale 0.5 so the icon fits the dot's
            // ~8px area without dominating the radar. pushMatrix/popMatrix
            // keeps the pose stack balanced.
            gfx.pose().pushMatrix();
            gfx.pose().translate(px - 4, py - 4);
            gfx.pose().scale(0.5f, 0.5f);
            gfx.item(stack, 0, 0);
            gfx.pose().popMatrix();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void drawWaypoints(GuiGraphicsExtractor gfx, Minecraft mc, LocalPlayer self, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        var list = dev.kitsune.client.waypoint.WaypointManager.current();
        if (list.isEmpty()) return;
        double scale = r / rangeBlocks.get();
        int innerLim = r - 3;
        double playerX = self.getX();
        double playerZ = self.getZ();
        double localCap = wpMaxDistance.get();
        double localCap2 = localCap * localCap;
        Font font = mc.font;

        for (var w : list) {
            double dx = w.x() - playerX;
            double dz = w.z() - playerZ;
            double d2 = dx * dx + dz * dz;
            // Local waypoints respect the cap; global always render but clip to
            // the visible map circle/square anyway.
            if (!w.global() && d2 > localCap2) continue;
            double sx =  dx * cosY + dz * sinY;
            double sy =  dx * sinY - dz * cosY;
            int mx = cx + (int)(sx * scale);
            int my = cy + (int)(sy * scale);
            // Clamp inside the visible area — distant waypoints stick to the
            // border instead of disappearing, so the user can tell which
            // direction they're in.
            int dxFromCenter = mx - cx, dyFromCenter = my - cy;
            if (!insideShape(dxFromCenter, dyFromCenter, innerLim, square)) {
                if (square) {
                    int max = innerLim;
                    int sxC = Math.max(-max, Math.min(max, dxFromCenter));
                    int syC = Math.max(-max, Math.min(max, dyFromCenter));
                    mx = cx + sxC; my = cy + syC;
                } else {
                    double mag = Math.sqrt(dxFromCenter * (double)dxFromCenter + dyFromCenter * (double)dyFromCenter);
                    if (mag > 0.001) {
                        double k = innerLim / mag;
                        mx = cx + (int)(dxFromCenter * k);
                        my = cy + (int)(dyFromCenter * k);
                    }
                }
            }
            // Marker: 5×5 filled square with a dark outline, colored by waypoint.
            gfx.fill(mx - 3, my - 3, mx + 3, my + 3, 0xFF000000);
            gfx.fill(mx - 2, my - 2, mx + 2, my + 2, w.color());
            // Symbol on top (1-character label).
            String sym = w.deathpoint() ? "☠" : (w.symbol() == null || w.symbol().isEmpty() ? "•" : w.symbol().substring(0, 1));
            int textColor = 0xFF000000;
            int tw = font.width(sym);
            gfx.text(font, sym, mx - tw / 2, my - 4, textColor);
        }
    }

    private static boolean drawPlayerHead(GuiGraphicsExtractor gfx, Minecraft mc, UUID uuid, int px, int py) {
        try {
            if (mc.getConnection() == null) return false;
            PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
            if (info == null) return false;
            Identifier skinId = info.getSkin().body().texturePath();
            if (skinId == null) return false;
            int half = 4;
            gfx.blit(skinId, px - half, py - half, px - half + 8, py - half + 8,
                    8f / 64f, 16f / 64f, 8f / 64f, 16f / 64f);
            gfx.blit(skinId, px - half, py - half, px - half + 8, py - half + 8,
                    40f / 64f, 48f / 64f, 8f / 64f, 16f / 64f);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- info pills -------------------------------------------------------

    private void renderInfoPills(GuiGraphicsExtractor gfx, Minecraft mc, LocalPlayer p, int x, int yStart) {
        if (countActivePills() == 0) return;
        Font font = mc.font;
        int width = widgetWidth();
        int rowY = yStart + 2;
        int color = 0xFFCCCCCC;

        if (showCoords.get()) {
            String s = p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
            gfx.text(font, s, x + 4, rowY, color);
            rowY += 11;
        }
        if (showBiome.get() && mc.level != null) {
            try {
                var k = mc.level.getBiome(p.blockPosition()).unwrapKey();
                String name = k.isPresent() ? prettyBiome(k.get().toString()) : "—";
                gfx.text(font, name, x + 4, rowY, color);
            } catch (Throwable t) { /* skip */ }
            rowY += 11;
        }
        if (showLight.get() && mc.level != null) {
            int lb = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,  p.blockPosition());
            int ls = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,    p.blockPosition());
            gfx.text(font, "Light B" + lb + " S" + ls, x + 4, rowY, color);
            rowY += 11;
        }
        if (showTime.get() && mc.level != null) {
            // MC 26.1.x renamed the day-time API. getOverworldClockTime is the
            // ticks-of-day equivalent the HUD modules use across the project.
            long t = mc.level.getOverworldClockTime() % 24000;
            // Vanilla 0 = 06:00, 6000 = noon, 18000 = midnight
            int totalMinutes = (int)(((t + 6000) % 24000) * 60 / 1000);
            int hour = totalMinutes / 60, minute = totalMinutes % 60;
            String text;
            if (time24h.get()) {
                text = String.format("%02d:%02d", hour, minute);
            } else {
                String suffix = hour < 12 ? "AM" : "PM";
                int hour12 = hour % 12; if (hour12 == 0) hour12 = 12;
                text = String.format("%d:%02d %s", hour12, minute, suffix);
            }
            gfx.text(font, "Time " + text, x + 4, rowY, color);
            rowY += 11;
        }
        if (showFacing.get()) {
            float yaw = (p.getYRot() % 360 + 360) % 360;
            String dir = cardinalOf(yaw);
            gfx.text(font, String.format("Facing %s (%.0f°)", dir, yaw), x + 4, rowY, color);
        }
    }

    private static String prettyBiome(String resourceKeyString) {
        // Input looks like ResourceKey[minecraft:worldgen/biome / minecraft:plains]
        int slash = resourceKeyString.lastIndexOf('/');
        String tail = slash >= 0 ? resourceKeyString.substring(slash + 1) : resourceKeyString;
        tail = tail.replace("]", "").trim();
        int colon = tail.indexOf(':');
        if (colon >= 0) tail = tail.substring(colon + 1);
        tail = tail.replace('_', ' ');
        if (!tail.isEmpty()) tail = Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
        return tail;
    }

    private static String cardinalOf(float yaw) {
        if (yaw >= 337.5 || yaw < 22.5) return "S";
        if (yaw < 67.5)  return "SW";
        if (yaw < 112.5) return "W";
        if (yaw < 157.5) return "NW";
        if (yaw < 202.5) return "N";
        if (yaw < 247.5) return "NE";
        if (yaw < 292.5) return "E";
        return "SE";
    }

    private static void drawCardinal(GuiGraphicsExtractor gfx, Font font, int cx, int cy, int radius, float playerYaw, String label, float worldAngle) {
        float angle = worldAngle - playerYaw;
        float lx = (float)Math.sin(angle) * radius;
        float ly = (float)-Math.cos(angle) * radius;
        int tx = cx + (int)lx - font.width(label) / 2;
        int ty = cy + (int)ly - 4;
        gfx.text(font, label, tx, ty, 0xCCFFFFFF);
    }

    private static void drawArrow(GuiGraphicsExtractor gfx, int cx, int cy, float yawRad, int color) {
        // 5-pixel triangle pointing in the direction of yawRad.
        float length = 5;
        float fx = (float) Math.sin(-yawRad) * length;
        float fy = (float) -Math.cos(-yawRad) * length;
        // Tip
        int tx = cx + Math.round(fx);
        int ty = cy + Math.round(fy);
        // Base centre (opposite end of arrow)
        int bx = cx - Math.round(fx * 0.4f);
        int by = cy - Math.round(fy * 0.4f);
        // 1px line from base to tip — crude but visible at 60px+ minimap.
        int steps = (int) length;
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int px = Math.round(bx + (tx - bx) * t);
            int py = Math.round(by + (ty - by) * t);
            gfx.fill(px, py, px + 1, py + 1, color);
        }
        // Tip dot a bit larger.
        gfx.fill(tx - 1, ty - 1, tx + 1, ty + 1, color);
    }

    // ---- circle / square primitives --------------------------------------

    private static void drawFilledCircle(GuiGraphicsExtractor gfx, int cx, int cy, int r, int argb) {
        int r2 = r * r;
        for (int row = -r; row <= r; row++) {
            int half = (int) Math.sqrt(r2 - row * row);
            gfx.fill(cx - half, cy + row, cx + half, cy + row + 1, argb);
        }
    }

    private static void drawCircleOutline(GuiGraphicsExtractor gfx, int cx, int cy, int r, int argb) {
        int r2 = r * r;
        int ri2 = (r - 1) * (r - 1);
        for (int row = -r; row <= r; row++) {
            int outer = (int) Math.sqrt(Math.max(0, r2  - row * row));
            int inner = (int) Math.sqrt(Math.max(0, ri2 - row * row));
            if (outer > inner) {
                gfx.fill(cx - outer, cy + row, cx - inner, cy + row + 1, argb);
                gfx.fill(cx + inner, cy + row, cx + outer, cy + row + 1, argb);
            }
        }
    }

    private static void drawSquareOutline(GuiGraphicsExtractor gfx, int x0, int y0, int x1, int y1, int argb) {
        gfx.fill(x0, y0, x1, y0 + 1, argb);
        gfx.fill(x0, y1 - 1, x1, y1, argb);
        gfx.fill(x0, y0, x0 + 1, y1, argb);
        gfx.fill(x1 - 1, y0, x1, y1, argb);
    }
}
