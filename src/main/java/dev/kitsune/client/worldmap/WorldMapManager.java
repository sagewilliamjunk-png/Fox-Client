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

    private static final Map<String, WorldMapData> SUBWORLDS = new HashMap<>();
    private static volatile WorldMapData active = null;
    private static long lastSaveMs = 0;

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
        LocalPlayer p = mc.player;
        int pcx = p.getBlockX() >> 4;
        int pcz = p.getBlockZ() >> 4;
        for (int dx = -DISCOVERY_RADIUS; dx <= DISCOVERY_RADIUS; dx++) {
            for (int dz = -DISCOVERY_RADIUS; dz <= DISCOVERY_RADIUS; dz++) {
                ChunkPos cp = new ChunkPos(pcx + dx, pcz + dz);
                if (active.contains(cp)) continue;
                int[] tile = ChunkColorTile.surface(mc.level, cp);
                if (tile != null) active.put(cp, tile);
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
