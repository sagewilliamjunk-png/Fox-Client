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
 * <p>Performance (v1.5): terrain work is queued nearest-chunk-first and
 * drained on a per-tick budget (32 surface / 8 cave tiles), so a refresh
 * never recomputes the whole view in one tick — and because tiles are
 * re-swept periodically, terrain <i>edits</i> now appear on the map within
 * ~2 s. Identical recomputes skip the GPU upload entirely. Cave columns scan
 * at most {@code CAVE_SCAN_DEPTH} blocks. The light overlay samples on a
 * 10-tick cadence in onTick (never per frame), and slime chunks render as
 * one pose-transformed fill per chunk from a cached set. Entity scan runs
 * every other tick. Per-frame cost is one blit per visible chunk plus dots
 * and pill text — comfortably under a millisecond even at max size.
 */
public class MinimapModule extends Module implements HudWidget {

    private static final int CACHE_TICKS = 2;
    private static final int TERRAIN_REFRESH_TICKS = 40;
    /** Terrain tiles computed per tick. Surface tiles are cheap (heightmap
     *  reads); cave tiles column-scan and get a smaller budget. At max range
     *  (~1 200 chunks in view) a full surface sweep amortises to ~2 s instead
     *  of stalling a single tick. */
    private static final int SURFACE_TILES_PER_TICK = 32;
    private static final int CAVE_TILES_PER_TICK    = 8;
    /** Cave cross-section scan depth below the player. Anything deeper than
     *  this renders as the darkest shade — bounding the per-column walk that
     *  used to go all the way to world bottom (-64). */
    private static final int CAVE_SCAN_DEPTH = 48;
    private static final int LIGHT_REFRESH_TICKS = 10;

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

    /** Distance-prioritised terrain work queue, drained {@code *_TILES_PER_TICK}
     *  at a time so a refresh never recomputes the whole view in one tick.
     *  Because re-enqueued surface tiles usually compute identical pixels,
     *  {@link dev.kitsune.client.worldmap.MapTextureCache#upsert} skips the GPU
     *  upload — the periodic sweep is mostly heightmap reads. It also means
     *  terrain EDITS now show up within one sweep (~2 s) instead of only when
     *  the chunk left the view entirely. */
    private final java.util.ArrayDeque<ChunkPos> terrainQueue = new java.util.ArrayDeque<>();
    private boolean queuedCave = false;
    private int     queuedPlayerY = 0;

    /** Slime-chunk positions within range — recomputed on terrain refresh so
     *  the render pass is one fill per chunk with zero RNG allocation. */
    private final java.util.HashSet<ChunkPos> slimeCache = new java.util.HashSet<>();

    /** Light-overlay sample cache: packed (worldX, worldZ) of low-light spots.
     *  Sampling 16k+ light queries belongs in the tick, not the render loop. */
    private long[] lightCache = new long[0];
    private int lightCacheTick = 0;

    // Runtime-controlled zoom (keybinds adjust this) — saved into rangeBlocks
    // on tick for persistence. enlargeActive is true while the user holds the
    // enlarge key; rendering multiplies sizePixels by enlargeFactor.
    private volatile boolean enlargeActive = false;

    // ---- cached circle textures -------------------------------------------
    // Circle mode used to draw the background disc + two outline rings as
    // ~900 row fills per FRAME. Both are static for a given (radius, cave)
    // pair, so they're baked into two textures rebuilt only when the size
    // slider / enlarge key / cave flag changes: one blit each per frame.
    // The frame texture also paints the corners outside the circle dark,
    // masking the square terrain spill the bbox scissor used to leave there.
    private net.minecraft.resources.Identifier circleBgId;
    private net.minecraft.resources.Identifier circleFrameId;
    private int circleCachedR = -1;
    private boolean circleCachedCave = false;
    private int circleIdCounter = 0;

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
        releaseCircleTextures();
        activeChunkSet.clear();
        terrainQueue.clear();
        slimeCache.clear();
        lightCache = new long[0];
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
            terrainQueue.clear();
            // Drop world-keyed caches so joining a different world/server
            // can't briefly show the previous world's terrain at matching
            // chunk coordinates. Resetting the last-chunk markers forces a
            // full re-enqueue on the first tick of the next world.
            if (!activeChunkSet.isEmpty()) {
                terrainTextures.closeAll();
                activeChunkSet.clear();
                slimeCache.clear();
                lastPlayerChunkX = Long.MIN_VALUE;
                lastPlayerChunkZ = Long.MIN_VALUE;
            }
            return;
        }

        // 1. Entity cache — throttled.
        if (++entityCacheTick % CACHE_TICKS == 0) {
            entityCache = collectEntities(mc, mc.player);
        }

        // 2. Terrain cache — (re)enqueue work on chunk-cross, Y-band-cross
        //    (cave mode), cave-flag flip, or every TERRAIN_REFRESH_TICKS
        //    ticks; then drain a bounded slice of the queue each tick.
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
                enqueueTerrainWork(cx, cz, cave, py);
            }
            drainTerrainQueue(mc);
        } else if (!terrainQueue.isEmpty()) {
            terrainQueue.clear();
        }

        // 3. Light-overlay sample cache — heavy light queries happen here, at
        //    a low cadence, instead of per frame in the render path.
        if (lightOverlay.get()) {
            if (++lightCacheTick >= LIGHT_REFRESH_TICKS) {
                lightCacheTick = 0;
                rebuildLightCache(mc);
            }
        } else if (lightCache.length > 0) {
            lightCache = new long[0];
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

    /**
     * Rebuild the active-chunk set, evict out-of-range GPU tiles, refresh the
     * slime-chunk cache, and refill the work queue nearest-chunk-first. The
     * actual tile computation happens incrementally in
     * {@link #drainTerrainQueue} so no single tick pays for the whole view.
     */
    private void enqueueTerrainWork(long playerCx, long playerCz, boolean cave, int playerY) {
        int chunkRange = (int) Math.ceil(rangeBlocks.get() / 16.0) + 1;
        activeChunkSet.clear();
        List<ChunkPos> work = new ArrayList<>((2 * chunkRange + 1) * (2 * chunkRange + 1));
        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                ChunkPos cp = new ChunkPos((int)(playerCx + dx), (int)(playerCz + dz));
                activeChunkSet.add(cp);
                work.add(cp);
            }
        }
        // Evict GPU textures for chunks outside the new range.
        terrainTextures.retainAndCloseRest(activeChunkSet);

        // Slime-chunk overlay cache — derived from the same chunk window.
        slimeCache.clear();
        if (slimeChunks.get()) {
            for (ChunkPos cp : activeChunkSet) {
                if (isSlimeChunk(0, cp.x(), cp.z())) slimeCache.add(cp);
            }
        }

        // Nearest-first so the centre of the map fills in immediately and the
        // periphery streams in behind it.
        work.sort(java.util.Comparator.comparingLong(cp -> {
            long dx = cp.x() - playerCx;
            long dz = cp.z() - playerCz;
            return dx * dx + dz * dz;
        }));
        terrainQueue.clear();
        terrainQueue.addAll(work);
        queuedCave = cave;
        queuedPlayerY = playerY;
    }

    /** Compute up to the per-tick budget of queued tiles. Unchanged surface
     *  tiles cost one heightmap sweep + an array compare (the GPU upload is
     *  skipped by MapTextureCache when pixels are identical). */
    private void drainTerrainQueue(Minecraft mc) {
        if (terrainQueue.isEmpty()) return;
        ClientLevel level = mc.level;
        if (level == null) { terrainQueue.clear(); return; }
        dev.kitsune.client.worldmap.ChunkColorTile.ColorMode mode = currentColorMode();
        int budget = queuedCave ? CAVE_TILES_PER_TICK : SURFACE_TILES_PER_TICK;
        while (budget-- > 0) {
            ChunkPos cp = terrainQueue.poll();
            if (cp == null) return;
            if (!activeChunkSet.contains(cp)) continue; // stale entry after a re-enqueue
            int[] tile = queuedCave
                    ? computeCaveTile(level, cp, queuedPlayerY)
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
     *  until we hit a solid block; render its floor in altitude shade. The
     *  walk is capped at {@link #CAVE_SCAN_DEPTH} blocks below the player —
     *  anything deeper renders as the darkest shade, which is visually
     *  identical for a cross-section and bounds the per-column cost (it used
     *  to scan all the way to world bottom, ~380 levels in a 1.18+ world). */
    private static int[] computeCaveTile(ClientLevel level, ChunkPos cp, int playerY) {
        var access = level.getChunk(cp.x(), cp.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return null;
        int[] argb = new int[256];
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        int minY  = Math.max(level.getMinY(), playerY - CAVE_SCAN_DEPTH);
        int searchTop = Math.min(playerY + 1, level.getMaxY());
        // Reused cursor avoids 256 × scan-depth BlockPos allocations per tile.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int floorY = minY;
                for (int y = searchTop; y >= minY; y--) {
                    BlockState s = chunk.getBlockState(cursor.set(baseX + lx, y, baseZ + lz));
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
        boolean circleTex   = !square && ensureCircleTextures(r, cave);

        // 1. Background (square or circle). Circle mode blits the cached disc
        //    texture — one draw call instead of ~300 row fills.
        if (square) {
            gfx.fill(cx - r, cy - r, cx + r, cy + r, 0xBB0D0D14);
        } else if (circleTex) {
            gfx.blit(circleBgId, cx - r, cy - r, cx + r, cy + r, 0f, 1f, 0f, 1f);
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

        // 7. Border. Circle mode blits the cached frame texture, whose
        //    near-opaque corners also mask the square terrain spill that the
        //    bounding-box scissor leaves outside the circle.
        int border1 = cave ? 0xFF553322 : 0xFF22222E;
        int border2 = cave ? 0xFF775544 : 0xFF333340;
        if (square) {
            drawSquareOutline(gfx, cx - r, cy - r, cx + r, cy + r, border1);
            drawSquareOutline(gfx, cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1, border2);
        } else if (circleTex) {
            gfx.blit(circleFrameId, cx - r, cy - r, cx + r, cy + r, 0f, 1f, 0f, 1f);
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

        // World-space pose: scissor clips to the minimap's bounding box; the
        // circular border drawn afterwards hides the corner overflow.
        openWorldSpace(gfx, p, cx, cy, r, cosY, sinY);
        for (ChunkPos cp : activeChunkSet) {
            net.minecraft.resources.Identifier id = terrainTextures.idFor(cp);
            if (id == null) continue;
            int baseX = cp.getMinBlockX();
            int baseZ = cp.getMinBlockZ();
            // Each chunk: one 16×16 blit in WORLD space. The pose transform
            // handles all rotation/scale; we just give it world coords.
            gfx.blit(id, baseX, baseZ, baseX + 16, baseZ + 16, 0f, 1f, 0f, 1f);
        }
        closeWorldSpace(gfx);
    }

    /**
     * Open the same world-space pose transform {@link #drawTerrain} uses —
     * scissor to the map's bounding box, rotate/scale around the centre,
     * translate so the player sits at the origin. Every fill issued inside
     * is specified in WORLD coordinates. Callers MUST call
     * {@link #closeWorldSpace} afterwards.
     */
    private void openWorldSpace(GuiGraphicsExtractor gfx, LocalPlayer p, int cx, int cy, int r, double cosY, double sinY) {
        float scale = (float)(r / rangeBlocks.get());
        float yaw = (float) Math.atan2(sinY, cosY);
        gfx.enableScissor(cx - r, cy - r, cx + r, cy + r);
        gfx.pose().pushMatrix();
        gfx.pose().translate((float) cx, (float) cy);
        gfx.pose().rotate(yaw);
        gfx.pose().scale(scale, scale);
        gfx.pose().translate((float) -p.getX(), (float) -p.getZ());
    }

    private void closeWorldSpace(GuiGraphicsExtractor gfx) {
        gfx.pose().popMatrix();
        gfx.disableScissor();
    }

    /** Chunk boundary lines drawn as world-space fills under the map's pose
     *  transform — ~70 fills for the whole grid instead of one fill per chunk
     *  corner, and they render as actual lines rather than dots. */
    private void drawChunkGrid(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        double scale = r / rangeBlocks.get();
        int chunkRange = (int) Math.ceil(rangeBlocks.get() / 16.0) + 1;
        int color = 0x44FFFFFF;
        long pcx = p.getBlockX() >> 4;
        long pcz = p.getBlockZ() >> 4;
        // Line thickness in world blocks — keeps the line ≥1 px on screen
        // when zoomed out (scale < 1 block/px).
        int t = Math.max(1, (int) Math.ceil(1.0 / scale));
        int x0 = (int)((pcx - chunkRange) << 4), x1 = (int)((pcx + chunkRange + 1) << 4);
        int z0 = (int)((pcz - chunkRange) << 4), z1 = (int)((pcz + chunkRange + 1) << 4);

        openWorldSpace(gfx, p, cx, cy, r, cosY, sinY);
        for (long cxw = pcx - chunkRange; cxw <= pcx + chunkRange + 1; cxw++) {
            int wx = (int)(cxw << 4);
            gfx.fill(wx, z0, wx + t, z1, color);
        }
        for (long czw = pcz - chunkRange; czw <= pcz + chunkRange + 1; czw++) {
            int wz = (int)(czw << 4);
            gfx.fill(x0, wz, x1, wz + t, color);
        }
        closeWorldSpace(gfx);
    }

    /** Slime chunks from the tick-built {@link #slimeCache} — one translucent
     *  fill per chunk under the world-space pose (was 256 per-pixel fills per
     *  chunk per frame, plus a Random allocation per chunk per frame). */
    private void drawSlimeChunks(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null || slimeCache.isEmpty()) return;
        int color = 0x44229922;
        openWorldSpace(gfx, p, cx, cy, r, cosY, sinY);
        for (ChunkPos cp : slimeCache) {
            int wx = cp.getMinBlockX();
            int wz = cp.getMinBlockZ();
            gfx.fill(wx, wz, wx + 16, wz + 16, color);
        }
        closeWorldSpace(gfx);
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

    /** Re-sample low-light surface spots into {@link #lightCache}. Runs on a
     *  {@link #LIGHT_REFRESH_TICKS} cadence from onTick — the render pass
     *  only projects cached world positions. Sampling step widens with range
     *  so the worst case stays ~4k queries per refresh instead of ~16k per
     *  FRAME, which is what the old in-render sampling cost at max range. */
    private void rebuildLightCache(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { lightCache = new long[0]; return; }
        int range = (int) Math.ceil(rangeBlocks.get());
        int step = range > 96 ? 4 : 2;
        Heightmap.Types h = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        int px0 = p.getBlockX();
        int pz0 = p.getBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        long[] out = new long[1024];
        int n = 0;
        for (int dx = -range; dx <= range; dx += step) {
            for (int dz = -range; dz <= range; dz += step) {
                int wx = px0 + dx;
                int wz = pz0 + dz;
                int wy = mc.level.getHeight(h, wx, wz);
                int blockLight = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,
                        cursor.set(wx, wy, wz));
                if (blockLight > 7) continue;
                if (n == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                out[n++] = ((long)(wx & 0xFFFFFFFFL) << 32) | (wz & 0xFFFFFFFFL);
            }
        }
        lightCache = java.util.Arrays.copyOf(out, n);
    }

    private void drawLightOverlay(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r, boolean square, double cosY, double sinY) {
        LocalPlayer p = mc.player;
        if (p == null || lightCache.length == 0) return;
        double scale = r / rangeBlocks.get();
        int innerLim = r - 2;
        double playerX = p.getX();
        double playerZ = p.getZ();
        int color = 0x66FF2222;
        for (long packed : lightCache) {
            int wx = (int)(packed >> 32);
            int wz = (int) packed;
            double dx = wx - playerX;
            double dz = wz - playerZ;
            double sx = dx * cosY + dz * sinY;
            double sy = dx * sinY - dz * cosY;
            int spx = cx + (int)(sx * scale);
            int spy = cy + (int)(sy * scale);
            if (!insideShape(spx - cx, spy - cy, innerLim, square)) continue;
            gfx.fill(spx, spy, spx + 2, spy + 2, color);
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
                // Mob icon: first try the pixel-art registry (Xaero-style
                // recognisable faces for 16 vanilla mobs), then fall back
                // to the spawn-egg sprite for anything else.
                if (e.kind() != EntityKind.PLAYER) {
                    if (drawMobIcon(gfx, e.entityType(), dotX, dotY)) continue;
                    if (drawSpawnEggIcon(gfx, e.entityType(), dotX, dotY)) continue;
                }
            }
            gfx.fill(dotX - 1, dotY - 1, dotX + 1, dotY + 1, colorFor(e.kind()));
        }
    }

    /** Pixel-art mob icon from {@link dev.kitsune.client.worldmap.MobIconRegistry}.
     *  Returns false when no sketch is registered for this type so the caller
     *  can fall back to the spawn-egg path. */
    private static boolean drawMobIcon(GuiGraphicsExtractor gfx, net.minecraft.world.entity.EntityType<?> type, int px, int py) {
        if (type == null) return false;
        net.minecraft.resources.Identifier id = dev.kitsune.client.worldmap.MobIconRegistry.iconFor(type);
        if (id == null) return false;
        try {
            int half = 5; // 10×10 — slightly bigger than spawn-egg fallback so heads stand out
            gfx.blit(id, px - half, py - half, px - half + 10, py - half + 10, 0f, 1f, 0f, 1f);
            return true;
        } catch (Throwable t) {
            return false;
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

    /** (Re)build the cached circle background + frame textures for radius r.
     *  Returns false if texture creation failed (render falls back to fills). */
    private boolean ensureCircleTextures(int r, boolean cave) {
        if (circleBgId != null && circleCachedR == r && circleCachedCave == cave) return true;
        releaseCircleTextures();
        try {
            int d = 2 * r;
            int r2  = r * r;
            int ri1 = (r - 1) * (r - 1);
            int ri2 = (r - 2) * (r - 2);
            int border1 = cave ? 0xFF553322 : 0xFF22222E;
            int border2 = cave ? 0xFF775544 : 0xFF333340;
            int bgArgb = 0xBB0D0D14;
            int cornerArgb = 0xEE0D0D14; // near-opaque — masks square terrain spill

            var bg    = new com.mojang.blaze3d.platform.NativeImage(d, d, true);
            var frame = new com.mojang.blaze3d.platform.NativeImage(d, d, true);
            for (int py = 0; py < d; py++) {
                for (int px = 0; px < d; px++) {
                    // Sample at the pixel centre relative to the circle centre.
                    float fx = px - r + 0.5f;
                    float fy = py - r + 0.5f;
                    float d2 = fx * fx + fy * fy;
                    int bgPix    = d2 <= r2 ? bgArgb : 0;
                    int framePix;
                    if (d2 > r2)       framePix = cornerArgb;
                    else if (d2 > ri1) framePix = border1;
                    else if (d2 > ri2) framePix = border2;
                    else               framePix = 0;
                    bg.setPixelABGR(px, py, dev.kitsune.client.worldmap.MapTextureCache.argbToAbgr(bgPix));
                    frame.setPixelABGR(px, py, dev.kitsune.client.worldmap.MapTextureCache.argbToAbgr(framePix));
                }
            }
            circleBgId = registerCircleTexture("circle_bg", bg);
            circleFrameId = registerCircleTexture("circle_frame", frame);
            circleCachedR = r;
            circleCachedCave = cave;
            return circleBgId != null && circleFrameId != null;
        } catch (Throwable t) {
            releaseCircleTextures();
            return false;
        }
    }

    private net.minecraft.resources.Identifier registerCircleTexture(String kind, com.mojang.blaze3d.platform.NativeImage img) {
        var tex = new net.minecraft.client.renderer.texture.DynamicTexture(
                () -> "kitsune_minimap/" + kind, img);
        var id = Identifier.fromNamespaceAndPath("kitsune",
                "minimap_" + kind + "_" + circleIdCounter++);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        tex.upload();
        return id;
    }

    private void releaseCircleTextures() {
        try {
            if (circleBgId != null) Minecraft.getInstance().getTextureManager().release(circleBgId);
            if (circleFrameId != null) Minecraft.getInstance().getTextureManager().release(circleFrameId);
        } catch (Throwable ignored) {}
        circleBgId = null;
        circleFrameId = null;
        circleCachedR = -1;
    }

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
