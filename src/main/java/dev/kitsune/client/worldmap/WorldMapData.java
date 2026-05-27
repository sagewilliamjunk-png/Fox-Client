package dev.kitsune.client.worldmap;

import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.ChunkPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-sub-world persistent chunk-color cache. Maintains a
 * {@code Map<ChunkPos, int[256]>} of explored chunks for one sub-world id
 * (server host or singleplayer world).
 *
 * <h2>Disk format</h2>
 * Little-endian binary, lives at {@code configDir/kitsune/worldmaps/<sanitized>.dat}.
 * <pre>
 *   magic        4 bytes   = "FXWM"
 *   version      1 byte    = 1
 *   chunkCount   4 bytes   = N (signed int, big-endian via DataOutputStream)
 *   N × {
 *     chunkX     4 bytes   (signed int)
 *     chunkZ     4 bytes   (signed int)
 *     argb[256]  1024 bytes (256 × signed int)
 *   }
 * </pre>
 *
 * Total per chunk = 1032 bytes. 100×100 explored chunks ≈ 10 MB.
 *
 * <p>Concurrency: {@link #tiles} is a ConcurrentHashMap so render threads
 * can iterate while the discovery thread inserts. Saves are throttled via
 * the {@link #dirty} flag — {@link WorldMapManager#tickAutoSave} flushes
 * at most every 30s.
 */
public final class WorldMapData {

    private static final int MAGIC = 0x4658574D; // "FXWM" big-endian
    private static final int VERSION = 1;

    public final String subWorldId;
    private final Path file;
    /** ConcurrentHashMap — safe concurrent iteration during render. */
    public final Map<ChunkPos, int[]> tiles = new ConcurrentHashMap<>();
    /** True when an unsaved mutation is pending. */
    private volatile boolean dirty = false;
    /** Bounding box of stored chunks — used by the screen to fit-to-content. */
    private volatile int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
    private volatile int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;

    /** GPU texture cache so the world-map screen blits one quad per chunk
     *  instead of doing thousands of gfx.fill calls. Built lazily — the
     *  textures are only allocated when the user opens the map screen
     *  (or the persisted raw int[] data is loaded back from disk). */
    public final MapTextureCache textures = new MapTextureCache("kitsune_worldmap");

    public WorldMapData(String subWorldId) {
        this.subWorldId = subWorldId;
        this.file = FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune").resolve("worldmaps")
                .resolve(sanitize(subWorldId) + ".dat");
    }

    public boolean isDirty() { return dirty; }
    public int  count()   { return tiles.size(); }
    public int  minCx()   { return minCx == Integer.MAX_VALUE ? 0 : minCx; }
    public int  maxCx()   { return maxCx == Integer.MIN_VALUE ? 0 : maxCx; }
    public int  minCz()   { return minCz == Integer.MAX_VALUE ? 0 : minCz; }
    public int  maxCz()   { return maxCz == Integer.MIN_VALUE ? 0 : maxCz; }

    public void put(ChunkPos cp, int[] argb) {
        if (argb == null || argb.length != 256) return;
        tiles.put(cp, argb);
        // Upload the same data to the GPU cache so the screen can blit it.
        textures.upsert(cp, argb);
        if (cp.x() < minCx) minCx = cp.x();
        if (cp.x() > maxCx) maxCx = cp.x();
        if (cp.z() < minCz) minCz = cp.z();
        if (cp.z() > maxCz) maxCz = cp.z();
        dirty = true;
    }

    public int[] get(ChunkPos cp) { return tiles.get(cp); }
    public boolean contains(ChunkPos cp) { return tiles.containsKey(cp); }

    // ---- disk i/o ----

    public void load() {
        tiles.clear();
        if (!Files.exists(file)) return;
        try (var raw = new BufferedInputStream(new FileInputStream(file.toFile()));
             var in  = new DataInputStream(raw)) {
            int magic = in.readInt();
            int version = in.readUnsignedByte();
            if (magic != MAGIC || version != VERSION) {
                KitsuneClient.LOGGER.warn(
                        "[WorldMap] {}: bad header (magic 0x{}, v{}) — ignoring",
                        subWorldId, Integer.toHexString(magic), version);
                return;
            }
            int n = in.readInt();
            if (n < 0 || n > 5_000_000) { // sanity — 5M chunks = ~5GB; obvious corruption
                KitsuneClient.LOGGER.warn("[WorldMap] {}: chunk count {} out of range", subWorldId, n);
                return;
            }
            for (int i = 0; i < n; i++) {
                int cx = in.readInt();
                int cz = in.readInt();
                int[] argb = new int[256];
                for (int k = 0; k < 256; k++) argb[k] = in.readInt();
                ChunkPos cp = new ChunkPos(cx, cz);
                tiles.put(cp, argb);
                if (cx < minCx) minCx = cx;
                if (cx > maxCx) maxCx = cx;
                if (cz < minCz) minCz = cz;
                if (cz > maxCz) maxCz = cz;
            }
            KitsuneClient.LOGGER.info("[WorldMap] {}: loaded {} chunks", subWorldId, n);
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[WorldMap] {}: load failed: {}", subWorldId, e.getMessage());
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        // Snapshot to avoid mid-iteration mutation.
        Map<ChunkPos, int[]> snap = new HashMap<>(tiles);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var raw = new BufferedOutputStream(new FileOutputStream(tmp.toFile()));
                 var out = new DataOutputStream(raw)) {
                out.writeInt(MAGIC);
                out.writeByte(VERSION);
                out.writeInt(snap.size());
                for (var e : snap.entrySet()) {
                    out.writeInt(e.getKey().x());
                    out.writeInt(e.getKey().z());
                    int[] argb = e.getValue();
                    for (int k = 0; k < 256; k++) out.writeInt(argb[k]);
                }
            }
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[WorldMap] {}: save failed: {}", subWorldId, e.getMessage());
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
