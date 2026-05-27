package dev.kitsune.client.worldmap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Shared chunk → 16×16 ARGB tile computation. Used by both the minimap's
 * transient heightmap cache and the world map's persistent on-disk cache.
 *
 * <p>The world map and minimap render identical surface tiles, so keeping
 * the formula in one place stops them drifting apart over time.
 */
public final class ChunkColorTile {

    private ChunkColorTile() {}

    /** Color modes, mirroring Xaeros' Minimap settings. */
    public enum ColorMode {
        /** Pure altitude shading (the original v1.3 mode). */
        ALTITUDE,
        /** Altitude shading multiplied by biome grass colour. */
        BIOME_TINTED,
        /** Vanilla MC paper-map colours — per-block via {@code MapColor}.
         *  This is what Xaeros calls "Vanilla" and what people actually
         *  want when they say "looks like a real map". */
        VANILLA_MAP,
    }

    /** Back-compat — boolean → ColorMode adapter. */
    public static int[] surface(ClientLevel level, ChunkPos cp) {
        return surface(level, cp, ColorMode.ALTITUDE);
    }
    public static int[] surface(ClientLevel level, ChunkPos cp, boolean biomeTint) {
        return surface(level, cp, biomeTint ? ColorMode.BIOME_TINTED : ColorMode.ALTITUDE);
    }

    /**
     * Surface tile with the requested color mode. VANILLA_MAP samples the
     * top non-air block's MapColor — this is the colour vanilla paper maps
     * show, so it's the closest thing to "looks like a Minecraft map".
     */
    public static int[] surface(ClientLevel level, ChunkPos cp, ColorMode mode) {
        if (level == null) return null;
        var access = level.getChunk(cp.x(), cp.z(),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return null;
        int[] argb = new int[256];
        Heightmap.Types h = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int y = chunk.getHeight(h, lx, lz);
                int color;
                if (mode == ColorMode.VANILLA_MAP) {
                    color = mapColorAt(level, chunk, baseX + lx, y, baseZ + lz);
                    // Shade slightly by altitude so relief stays visible even
                    // in same-block terrain. Vanilla paper maps do this too
                    // (Brightness.LOW vs HIGH per N/S step), but we don't
                    // have neighbour access here so we fake via altitude.
                    color = applyAltitudeShade(color, level, y);
                } else if (mode == ColorMode.BIOME_TINTED) {
                    color = tintByBiome(level, baseX + lx, y, baseZ + lz, heightToColor(level, y));
                } else {
                    color = heightToColor(level, y);
                }
                argb[lz * 16 + lx] = color;
            }
        }
        return argb;
    }

    /** ARGB for the topmost non-air block's vanilla MapColor.
     *  Walks down at most 4 blocks from the heightmap to skip ignored
     *  non-solid tops (tall grass, flowers, snow) and find a meaningful
     *  surface. Falls back to grass green if nothing matches. */
    private static int mapColorAt(ClientLevel level, LevelChunk chunk, int x, int topY, int z) {
        try {
            int bot = Math.max(level.getMinY(), topY - 4);
            for (int y = topY; y >= bot; y--) {
                var pos = new net.minecraft.core.BlockPos(x, y, z);
                var state = chunk.getBlockState(pos);
                if (state.isAir()) continue;
                var mc = state.getMapColor(level, pos);
                if (mc == net.minecraft.world.level.material.MapColor.NONE) continue;
                return mc.calculateARGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL);
            }
        } catch (Throwable ignored) {}
        return 0xFF7FA651; // muted grass fallback
    }

    /** Tint MapColor slightly darker at low altitude and slightly lighter
     *  high up so a flat field of grass still shows hills. Cheap mix. */
    private static int applyAltitudeShade(int argb, ClientLevel level, int y) {
        int sea = level.getSeaLevel();
        // Range -0.15 (deep) to +0.15 (mountaintops).
        float t = Math.max(-1f, Math.min(1f, (y - sea) / 80f)) * 0.15f;
        int a = (argb >>> 24) & 0xFF;
        int r = clampByte(((argb >>> 16) & 0xFF) * (1f + t));
        int g = clampByte(((argb >>>  8) & 0xFF) * (1f + t));
        int b = clampByte(( argb         & 0xFF) * (1f + t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static int clampByte(float v) { return (int) Math.max(0, Math.min(255, v)); }

    /** Multiply altitude ARGB by the biome's grass colour at (x, y, z).
     *  Quietly returns the input on failure so a single-block lookup
     *  exception can't blank the whole tile. */
    private static int tintByBiome(ClientLevel level, int x, int y, int z, int altColor) {
        try {
            int biomeColor = level.getBiomeManager()
                    .getBiome(new BlockPos(x, y, z))
                    .value()
                    .getGrassColor(x, z);
            int br = (biomeColor >> 16) & 0xFF;
            int bg = (biomeColor >>  8) & 0xFF;
            int bb =  biomeColor        & 0xFF;
            int ar = (altColor >> 16) & 0xFF;
            int ag = (altColor >>  8) & 0xFF;
            int ab =  altColor        & 0xFF;
            // 60/40 mix toward the altitude shade — keeps relief visible.
            int r = Math.min(255, (ar * 6 + br * 4) / 10);
            int g = Math.min(255, (ag * 6 + bg * 4) / 10);
            int b = Math.min(255, (ab * 6 + bb * 4) / 10);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (Throwable t) {
            return altColor;
        }
    }

    /**
     * Cave cross-section tile — walk DOWN from playerY+1 for each (lx, lz)
     * until the first solid block. Used by the minimap's auto-cave mode.
     */
    public static int[] caveCrossSection(ClientLevel level, ChunkPos cp, int playerY) {
        if (level == null) return null;
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

    /** Altitude → ARGB. Lighter the higher you go. Sea level is mid-grey. */
    public static int heightToColor(ClientLevel level, int y) {
        int sea = level.getSeaLevel();
        int top = Math.min(320, sea + 96);
        int bot = Math.max(-64, sea - 32);
        float t = (float) Math.max(0, Math.min(1, (y - bot) / (float)(top - bot)));
        int r = Math.round(60 + t * 130);
        int g = Math.round(50 + t * 170);
        int b = Math.round(40 + t * 200);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
