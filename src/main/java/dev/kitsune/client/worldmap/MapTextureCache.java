package dev.kitsune.client.worldmap;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kitsune.client.KitsuneClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-chunk GPU texture cache for the minimap and world map.
 *
 * <p>The original implementation drew terrain via thousands of 1×1
 * {@code gfx.fill} calls per frame — each fill allocated a render-state
 * object, GC pressure tanked frame times, and opening the world map
 * froze the game for several seconds while the per-pixel fills queued.
 *
 * <p>This cache:
 * <ul>
 *   <li>Stores one 16×16 {@link DynamicTexture} per chunk, uploaded once</li>
 *   <li>Renderers do one {@code gfx.blit} per chunk in view — about a
 *       50–200x reduction in draw-call volume at typical minimap sizes</li>
 *   <li>{@link #upsert(ChunkPos, int[])} updates an existing tile's
 *       NativeImage in-place when the data changes — no GPU realloc</li>
 *   <li>{@link #closeAll()} fully releases all textures (called on
 *       module disable / sub-world swap)</li>
 * </ul>
 *
 * Pixel format note: Minecraft's NativeImage uses ABGR. We convert from
 * the rest of the codebase's ARGB packing in {@link #argbToAbgr}.
 *
 * <p>Memory: 16×16 RGBA = 1 KB per chunk. A 16-radius chunk view is
 * ~1024 chunks = 1 MB. Safe even on potato GPUs.
 */
public final class MapTextureCache {

    private final String namespace;
    private final Map<ChunkPos, Entry> entries = new HashMap<>();
    /** Monotonic counter so each chunk gets a unique texture id even when
     *  re-uploaded — DynamicTexture identity is per-texture, not per-position. */
    private int idCounter = 0;

    public MapTextureCache(String namespace) {
        this.namespace = namespace;
    }

    /** Insert or update a tile. Idempotent on identical data. */
    public synchronized void upsert(ChunkPos cp, int[] argb) {
        if (argb == null || argb.length != 256) return;
        Entry e = entries.get(cp);
        if (e == null) {
            e = new Entry();
            e.image   = new NativeImage(16, 16, false);
            e.texture = new DynamicTexture(() -> namespace + "/chunk_" + cp.x() + "_" + cp.z(), e.image);
            String key = namespace + "_" + idCounter++;
            e.id = Identifier.fromNamespaceAndPath("kitsune", key.toLowerCase());
            Minecraft.getInstance().getTextureManager().register(e.id, e.texture);
            entries.put(cp, e);
        }
        // Write all 256 pixels into the NativeImage in ABGR.
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                e.image.setPixelABGR(x, y, argbToAbgr(argb[y * 16 + x]));
            }
        }
        try { e.texture.upload(); }
        catch (Throwable t) {
            KitsuneClient.LOGGER.warn("[MapTextureCache] upload failed for {}: {}", cp, t.toString());
        }
    }

    /** Renderer side: returns the Identifier you can pass to
     *  {@code gfx.blit(...)}, or null when nothing's cached for that chunk. */
    public Identifier idFor(ChunkPos cp) {
        Entry e = entries.get(cp);
        return e == null ? null : e.id;
    }

    public boolean contains(ChunkPos cp) {
        return entries.containsKey(cp);
    }

    /** Evict + close every entry whose chunk is no longer needed.
     *  Caller supplies the "still-needed" set; everything else is freed. */
    public synchronized void retainAndCloseRest(java.util.Set<ChunkPos> needed) {
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!needed.contains(e.getKey())) {
                closeEntry(e.getValue());
                it.remove();
            }
        }
    }

    public synchronized void closeAll() {
        for (Entry e : entries.values()) closeEntry(e);
        entries.clear();
    }

    private void closeEntry(Entry e) {
        try {
            // Releasing the texture also closes its backing NativeImage.
            Minecraft.getInstance().getTextureManager().release(e.id);
        } catch (Throwable ignored) {}
    }

    /** ARGB (0xAARRGGBB) → ABGR (0xAABBGGRR) for NativeImage.setPixelABGR. */
    public static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>>  8) & 0xFF;
        int b =  argb         & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static final class Entry {
        NativeImage image;
        DynamicTexture texture;
        Identifier id;
    }
}
