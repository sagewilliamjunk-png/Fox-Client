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

    /**
     * Surface heightmap tile (altitude shading only).
     */
    public static int[] surface(ClientLevel level, ChunkPos cp) {
        return surface(level, cp, false);
    }

    /**
     * Surface tile with optional biome tinting. When {@code biomeTint} is true,
     * each block's altitude colour is multiplied by the biome's foliage/grass
     * colour at that position — gives forests a green cast, deserts yellow,
     * snowy biomes a cool blue-white. This is the "Accurate" mode in Xaeros
     * minimap parlance, except we approximate via biome colour rather than
     * per-block top-face texture sampling (that would need a renderer dump
     * we don't have a stable API for in 26.1.x).
     */
    public static int[] surface(ClientLevel level, ChunkPos cp, boolean biomeTint) {
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
                int altColor = heightToColor(level, y);
                if (biomeTint) {
                    altColor = tintByBiome(level, baseX + lx, y, baseZ + lz, altColor);
                }
                argb[lz * 16 + lx] = altColor;
            }
        }
        return argb;
    }

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
