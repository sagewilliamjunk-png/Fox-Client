package dev.kitsune.client.worldmap;

import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Drives the persistent {@link WorldMapData} cache for the currently-loaded
 * sub-world. Hooks into the client tick loop via {@link #tick}, which:
 *
 * <ol>
 *   <li>Switches the active {@link WorldMapData} on sub-world change
 *       (server connect, dimension transition, world load) — saves the
 *       previous and lazily loads the new one.</li>
 *   <li>Discovers chunks within {@link #DISCOVERY_RADIUS} of the player
 *       and feeds them through {@link ChunkColorTile#surface}.</li>
 *   <li>Throttles disk saves via {@link #SAVE_INTERVAL_MS}.</li>
 * </ol>
 *
 * <p>Save-on-quit is handled by the launcher's pre-exit shutdown hook —
 * call {@link #saveActive()} from {@code ClientLifecycleEvents.CLIENT_STOPPING}
 * (already wired in KitsuneClient).
 */
public final class WorldMapManager {

    /** Chunks within this radius of the player get auto-discovered. Same as
     *  vanilla's default chunk-load radius so we never lag the cache behind. */
    private static final int DISCOVERY_RADIUS = 6;
    private static final long SAVE_INTERVAL_MS = 30_000;
    /** Max new tiles computed per tick. Entering a fresh area discovers
     *  ~169 chunks; without a budget they were all computed in ONE tick
     *  (169 × 256 heightmap walks) — now it streams in over ~0.5 s. */
    private static final int DISCOVERY_TILES_PER_TICK = 16;
    /** Re-sweep the player's immediate 3×3 chunks at this cadence so world
     *  EDITS show up on the persistent map. WorldMapData.put() no-ops on
     *  identical tiles, so an idle player costs 9 cheap recomputes / 2 s. */
    private static final int NEARBY_REFRESH_TICKS = 40;

    /** (dx,dz) offsets within DISCOVERY_RADIUS, sorted nearest-first, so the
     *  budgeted discovery fills the chunks around the player before the rim. */
    private static final int[][] RING_OFFSETS = buildRingOffsets(DISCOVERY_RADIUS);

    private static final Map<String, WorldMapData> SUBWORLDS = new HashMap<>();
    private static volatile WorldMapData active = null;
    private static long lastSaveMs = 0;
    private static int refreshCounter = 0;

    private static int[][] buildRingOffsets(int radius) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                out.add(new int[]{dx, dz});
            }
        }
        out.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return out.toArray(new int[0][]);
    }

    private WorldMapManager() {}

    /** The currently-loaded sub-world's data, or null if not in a world. */
    public static WorldMapData active() { return active; }

    /** Per-tick driver — call from the client tick handler. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (active != null) {
                active.save();
                active = null;
            }
            return;
        }
        // Per-dimension cache id: host/world + "/" + dim path. Same overworld
        // map for the same server, but the Nether and End get their own caches
        // so the surface heightmap doesn't smear together at portal coords.
        String currentSub = perDimensionId(mc);
        if (currentSub == null) return;

        // Sub-world switch: save the old, load the new.
        if (active == null || !currentSub.equals(active.subWorldId)) {
            if (active != null) active.save();
            active = SUBWORLDS.computeIfAbsent(currentSub, id -> {
                WorldMapData d = new WorldMapData(id);
                d.load();
                return d;
            });
            lastSaveMs = System.currentTimeMillis();
        }

        // Discover new chunks. We only compute tiles for chunks the player
        // is currently near — distant chunks aren't loaded so getChunk() would
        // fail anyway. Once stored, they're persisted forever.
        // The minimap's biome-tint setting drives the world map too so the
        // two views stay visually consistent.
        ChunkColorTile.ColorMode colorMode = ChunkColorTile.ColorMode.ALTITUDE;
        try {
            var mm = dev.kitsune.client.module.hud.MinimapModule.instance();
            if (mm != null) colorMode = mm.currentColorMode();
        } catch (Throwable ignored) {}
        LocalPlayer p = mc.player;
        int pcx = p.getBlockX() >> 4;
        int pcz = p.getBlockZ() >> 4;
        // Budgeted, nearest-first discovery of unexplored chunks.
        int budget = DISCOVERY_TILES_PER_TICK;
        for (int[] off : RING_OFFSETS) {
            if (budget == 0) break;
            ChunkPos cp = new ChunkPos(pcx + off[0], pcz + off[1]);
            if (active.contains(cp)) continue;
            int[] tile = ChunkColorTile.surface(mc.level, cp, colorMode);
            if (tile != null) {
                active.put(cp, tile);
                budget--;
            }
        }
        // Staleness sweep: periodically recompute the player's 3×3 so mining
        // and building show up on the persistent map. put() skips identical
        // tiles, so this neither dirties the save nor re-uploads textures
        // when the terrain hasn't changed.
        if (++refreshCounter >= NEARBY_REFRESH_TICKS) {
            refreshCounter = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    ChunkPos cp = new ChunkPos(pcx + dx, pcz + dz);
                    int[] tile = ChunkColorTile.surface(mc.level, cp, colorMode);
                    if (tile != null) active.put(cp, tile);
                }
            }
        }

        // Footsteps sample — bounded ring buffer keyed by the same per-dim
        // sub-world id so the trail resets cleanly on dimension change.
        Footsteps.sample(currentSub, p.getX(), p.getZ());

        // Throttled save.
        long now = System.currentTimeMillis();
        if (now - lastSaveMs >= SAVE_INTERVAL_MS && active.isDirty()) {
            active.save();
            lastSaveMs = now;
        }
    }

    /** Called by the launcher shutdown hook so we don't lose the last few
     *  minutes of exploration on close. */
    public static void saveActive() {
        if (active != null) active.save();
    }

    /** Host/world id + "/" + dimension path. Null when no world is loaded. */
    private static String perDimensionId(Minecraft mc) {
        String host = WaypointManager.currentSubWorldId();
        if (host == null || mc.level == null) return null;
        ResourceKey<Level> dim = mc.level.dimension();
        String dimPath;
        if (dim.equals(Level.OVERWORLD)) dimPath = "overworld";
        else if (dim.equals(Level.NETHER)) dimPath = "nether";
        else if (dim.equals(Level.END)) dimPath = "the_end";
        else {
            // Custom / modded dimension — extract the path from the resource key.
            String s = dim.identifier().getPath();
            dimPath = s == null || s.isEmpty() ? "custom" : s;
        }
        return host + "/" + dimPath;
    }
}
