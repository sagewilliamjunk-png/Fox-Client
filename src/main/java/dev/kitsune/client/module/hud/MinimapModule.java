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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combined minimap + entity radar. Replaces the old EntityRadarModule.
 * Three view modes:
 * <ul>
 *   <li><b>Dots</b> — colored dot for each nearby player / mob. Backward-compatible
 *       with the old radar.</li>
 *   <li><b>Heightmap</b> — top-down view of nearby chunks shaded by terrain
 *       altitude (lighter = higher). No mob/player overlay.</li>
 *   <li><b>Heightmap + Dots</b> — both stacked. Default.</li>
 * </ul>
 *
 * <p>Holding the player-list key (Tab by default) swaps every <i>player</i> dot
 * for that player's 8×8 head sprite (read from their actual skin texture), matching
 * Xaeros' minimap behaviour. Mob entities stay as dots — the bundled mob-head
 * sprite set will land in a later release.
 *
 * <p>All data is client-side only. No packets sent, no extra requests — safe
 * on every server.
 */
public class MinimapModule extends Module implements HudWidget {

    /** Rebuild the entity cache every CACHE_TICKS ticks. */
    private static final int CACHE_TICKS = 2;
    /** Rebuild the terrain cache when the player crosses a chunk boundary or
     *  every TERRAIN_REFRESH_TICKS ticks at minimum. */
    private static final int TERRAIN_REFRESH_TICKS = 40;

    // ---- settings ----

    private final ModeSetting    viewMode      = addSetting(new ModeSetting("View Mode", "Heightmap + Dots",
            List.of("Dots", "Heightmap", "Heightmap + Dots")));
    private final SliderSetting  rangeBlocks   = addSetting(new SliderSetting("Range (blocks)", 64, 16, 256, 8));
    private final SliderSetting  sizePixels    = addSetting(new SliderSetting("Size (pixels)",   60, 30, 150, 5));
    private final BooleanSetting showPlayers   = addSetting(new BooleanSetting("Show Players",   true));
    private final BooleanSetting showMobs      = addSetting(new BooleanSetting("Show Mobs",      true));
    private final BooleanSetting compassLines  = addSetting(new BooleanSetting("Compass Lines",  true));
    private final BooleanSetting cardinalLabels = addSetting(new BooleanSetting("N/S/E/W Labels", true));
    /** When on, holding the player-list key swaps each player's dot for their
     *  skin's head texture (8×8 face cube). Default Xaeros parity. */
    private final BooleanSetting tabSwapsHeads = addSetting(new BooleanSetting("Tab → Player Heads", true));

    // ---- cached entity entries ----

    /** Lightweight cached snapshot for an entity in radar range. */
    private record RadarEntry(double dx, double dz, boolean isPlayer, java.util.UUID uuid, int fallbackColor) {}

    private List<RadarEntry> entityCache = new ArrayList<>();
    private int entityCacheTick = 0;

    // ---- cached heightmap (Map<ChunkPos, int[16*16] of MOTION_BLOCKING heights>) ----
    //
    // We compute a brightness ARGB per (chunk-local x,z) from the surface block's
    // Y. Stored as raw heights so resizing the map view doesn't require recompute.
    // Map mode is intentionally synchronous to keep complexity down — the cache
    // refreshes once per chunk-cross or every TERRAIN_REFRESH_TICKS ticks, NOT
    // every frame, so the per-tick cost stays well under a millisecond.

    private final Map<ChunkPos, int[]> heightmapCache = new HashMap<>();
    private long lastPlayerChunkX = Long.MIN_VALUE;
    private long lastPlayerChunkZ = Long.MIN_VALUE;
    private int  terrainRefreshCounter = 0;

    public MinimapModule() {
        super("Minimap",
              "Combined minimap + entity radar. Hold Tab to see player heads.",
              Category.HUD);
        HudManager.register(this);
    }

    // ---- HudWidget --------------------------------------------------------

    @Override public String widgetId()    { return "minimap"; }
    @Override public String displayName() { return "Minimap"; }
    @Override public int widgetWidth()    { return sizePixels.get().intValue() * 2 + 8; }
    @Override public int widgetHeight()   { return sizePixels.get().intValue() * 2 + 8; }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    // ---- Module -----------------------------------------------------------

    @Override
    protected void onDisable() {
        entityCache.clear();
        heightmapCache.clear();
        lastPlayerChunkX = Long.MIN_VALUE;
        lastPlayerChunkZ = Long.MIN_VALUE;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            entityCache.clear();
            return;
        }

        // 1. Entity cache — throttled to every CACHE_TICKS ticks.
        if (++entityCacheTick % CACHE_TICKS == 0) {
            entityCache = collectEntities(mc, mc.player);
        }

        // 2. Heightmap cache — rebuild when player crosses a chunk boundary OR
        //    every TERRAIN_REFRESH_TICKS ticks (catches block changes within
        //    the same chunk). Both bounded; the cache itself is sparse.
        long cx = mc.player.getBlockX() >> 4;
        long cz = mc.player.getBlockZ() >> 4;
        terrainRefreshCounter++;
        boolean refresh = (cx != lastPlayerChunkX)
                       || (cz != lastPlayerChunkZ)
                       || terrainRefreshCounter >= TERRAIN_REFRESH_TICKS;
        if (refresh && needsTerrain()) {
            lastPlayerChunkX = cx;
            lastPlayerChunkZ = cz;
            terrainRefreshCounter = 0;
            rebuildHeightmapCache(mc, cx, cz);
        }
    }

    private boolean needsTerrain() {
        return !"Dots".equals(viewMode.get());
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
                    out.add(new RadarEntry(dx, dz, true, p.getUUID(), 0xFFFF4444));
                }
            }
        }

        if (showMobs.get()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof Mob)) continue;
                double dx = e.getX() - px;
                double dz = e.getZ() - pz;
                if (dx * dx + dz * dz <= maxR2) {
                    out.add(new RadarEntry(dx, dz, false, e.getUUID(), 0xFFFFAA22));
                }
            }
        }
        return out;
    }

    // ---- heightmap cache --------------------------------------------------

    /** Rebuild the chunk-tile colour cache around the player. Visits chunks
     *  within ⌈range/16⌉+1 of the player and evicts the rest. */
    private void rebuildHeightmapCache(Minecraft mc, long playerCx, long playerCz) {
        ClientLevel level = mc.level;
        if (level == null) return;
        int chunkRange = (int) Math.ceil(rangeBlocks.get() / 16.0) + 1;

        // Build a fresh set of needed chunks.
        java.util.HashSet<ChunkPos> needed = new java.util.HashSet<>();
        for (int dx = -chunkRange; dx <= chunkRange; dx++) {
            for (int dz = -chunkRange; dz <= chunkRange; dz++) {
                needed.add(new ChunkPos((int)(playerCx + dx), (int)(playerCz + dz)));
            }
        }
        // Evict stale.
        heightmapCache.keySet().removeIf(p -> !needed.contains(p));
        // Insert missing.
        for (ChunkPos cp : needed) {
            if (heightmapCache.containsKey(cp)) continue;
            int[] tile = computeChunkTile(level, cp);
            if (tile != null) heightmapCache.put(cp, tile);
        }
    }

    /** Compute the 16×16 colour grid for one chunk. Returns null if the chunk
     *  isn't loaded — callers skip it. Cheap: ~256 height lookups + a small
     *  amount of arithmetic. */
    private static int[] computeChunkTile(ClientLevel level, ChunkPos cp) {
        // getChunk overloads return ChunkAccess in MC 26.1.x — cast down once we
        // confirm the chunk is loaded. null means unloaded; skip.
        net.minecraft.world.level.chunk.ChunkAccess access =
                level.getChunk(cp.x(), cp.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return null;
        int[] argb = new int[16 * 16];
        Heightmap.Types h = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldY = chunk.getHeight(h, lx, lz);
                argb[lz * 16 + lx] = heightToColor(level, worldY);
            }
        }
        return argb;
    }

    /** Lighter the higher you go. Sea level is mid-grey. */
    private static int heightToColor(ClientLevel level, int y) {
        int sea = level.getSeaLevel();
        int top = Math.min(320, sea + 96);
        int bot = Math.max(-64, sea - 32);
        float t = (float) Math.max(0, Math.min(1, (y - bot) / (float)(top - bot)));
        int b = Math.round(40 + t * 200);    // 40..240
        int g = Math.round(50 + t * 170);    // 50..220
        int r = Math.round(60 + t * 130);    // 60..190
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        int r  = sizePixels.get().intValue();
        int cx = x + r + 4;
        int cy = y + r + 4;
        String mode = viewMode.get();
        boolean drawTerrain = !"Dots".equals(mode);
        boolean drawDots    = !"Heightmap".equals(mode);

        // 1. Background — solid dim under terrain so loaded-edge fade looks tidy.
        drawFilledCircle(gfx, cx, cy, r, 0xBB0D0D14);

        // 2. Terrain heightmap (if mode permits)
        if (drawTerrain) drawTerrain(gfx, mc, cx, cy, r);

        // 3. Compass cross-hairs (faint).
        if (compassLines.get()) {
            int lineColor = 0x33FFFFFF;
            gfx.fill(cx - 1, cy - r + 2, cx + 1, cy + r - 2, lineColor);
            gfx.fill(cx - r + 2, cy - 1, cx + r - 2, cy + 1, lineColor);
        }

        // 4. Entity dots / heads (rotated to "forward = up").
        if (drawDots) drawEntities(gfx, mc, player, cx, cy, r);

        // 5. Self marker.
        gfx.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
        gfx.fill(cx,     cy - 4, cx + 1, cy - 1, 0xCCFFFFFF);

        // 6. Circular border.
        drawCircleOutline(gfx, cx, cy, r,     0xFF22222E);
        drawCircleOutline(gfx, cx, cy, r - 1, 0xFF333340);

        // 7. Cardinal labels.
        if (cardinalLabels.get()) {
            Font font = mc.font;
            // Compute label positions in screen space using player yaw so they
            // stay anchored to true compass directions rather than to the
            // rotating map.
            float yaw = (float) Math.toRadians(player.getYRot());
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "N", (float) Math.PI);          // north = world -Z = screen up after rotation
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "S", 0f);
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "E", (float) -Math.PI / 2);
            drawCardinal(gfx, font, cx, cy, r - 1, yaw, "W", (float) Math.PI / 2);
        }
    }

    /** Render every cached chunk tile, rotated to keep forward-facing pointing
     *  up. Skips pixels that fall outside the circular minimap. */
    private void drawTerrain(GuiGraphicsExtractor gfx, Minecraft mc, int cx, int cy, int r) {
        LocalPlayer p = mc.player;
        if (p == null || heightmapCache.isEmpty()) return;
        double yawRad = Math.toRadians(p.getYRot());
        double cosY = Math.cos(yawRad);
        double sinY = Math.sin(yawRad);
        double scale = r / rangeBlocks.get();
        int innerR2 = (r - 2) * (r - 2);
        double playerX = p.getX();
        double playerZ = p.getZ();

        for (var entry : heightmapCache.entrySet()) {
            ChunkPos cp = entry.getKey();
            int[] tile = entry.getValue();
            int baseX = cp.getMinBlockX();
            int baseZ = cp.getMinBlockZ();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    double dx = (baseX + lx) - playerX;
                    double dz = (baseZ + lz) - playerZ;
                    double sx =  dx * cosY + dz * sinY;
                    double sy =  dx * sinY - dz * cosY;
                    int px = cx + (int)(sx * scale);
                    int py = cy + (int)(sy * scale);
                    int ddx = px - cx, ddy = py - cy;
                    if (ddx * ddx + ddy * ddy > innerR2) continue;
                    int color = tile[lz * 16 + lx];
                    gfx.fill(px, py, px + 1, py + 1, color);
                }
            }
        }
    }

    /** Draw cached entities as dots OR, when Tab is held and tabSwapsHeads is on,
     *  swap player dots for the player's 8×8 head sprite from their skin. Mobs
     *  always remain dots in v1.2. */
    private void drawEntities(GuiGraphicsExtractor gfx, Minecraft mc, LocalPlayer self, int cx, int cy, int r) {
        double yawRad = Math.toRadians(self.getYRot());
        double cosY = Math.cos(yawRad);
        double sinY = Math.sin(yawRad);
        double scale = r / rangeBlocks.get();
        int innerR = r - 3;
        boolean tabHeld = tabSwapsHeads.get()
                && mc.options != null
                && mc.options.keyPlayerList != null
                && mc.options.keyPlayerList.isDown();

        for (RadarEntry e : entityCache) {
            double sx =  e.dx() * cosY + e.dz() * sinY;
            double sy =  e.dx() * sinY - e.dz() * cosY;
            int dotX = cx + (int)(sx * scale);
            int dotY = cy + (int)(sy * scale);
            int ddx = dotX - cx, ddy = dotY - cy;
            if (ddx * ddx + ddy * ddy > innerR * innerR) continue;

            if (tabHeld && e.isPlayer() && drawPlayerHead(gfx, mc, e.uuid(), dotX, dotY)) {
                continue;
            }
            // Default: a 2×2 colour dot.
            gfx.fill(dotX - 1, dotY - 1, dotX + 1, dotY + 1, e.fallbackColor());
        }
    }

    /** Draw an 8×8 head face from the player's skin texture, centred at (px,py).
     *  Returns false if the skin isn't available (caller falls back to a dot). */
    private static boolean drawPlayerHead(GuiGraphicsExtractor gfx, Minecraft mc, java.util.UUID uuid, int px, int py) {
        try {
            if (mc.getConnection() == null) return false;
            PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
            if (info == null) return false;
            Identifier skinId = info.getSkin().body().texturePath();
            if (skinId == null) return false;
            // 8×8 face cube at (8,8)..(16,16) on the 64×64 skin atlas; hat overlay at (40,8)..(48,16).
            int size = 8;
            int half = size / 2;
            // Underlying face
            gfx.blit(skinId,
                    px - half,     py - half,
                    px - half + 8, py - half + 8,
                    8f / 64f, 16f / 64f, 8f / 64f, 16f / 64f);
            // Hat overlay (transparency comes through).
            gfx.blit(skinId,
                    px - half,     py - half,
                    px - half + 8, py - half + 8,
                    40f / 64f, 48f / 64f, 8f / 64f, 16f / 64f);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Place a single-character cardinal label on the minimap border at the
     *  given world direction (radians around +Z). */
    private static void drawCardinal(GuiGraphicsExtractor gfx, Font font, int cx, int cy, int radius, float playerYaw, String label, float worldAngle) {
        // Place at the rim of the inner circle. We rotate by playerYaw so "N"
        // ends up where north actually is relative to the player's facing.
        float angle = worldAngle - playerYaw;
        float lx = (float)Math.sin(angle) * radius;
        float ly = (float)-Math.cos(angle) * radius;
        int tx = cx + (int)lx - font.width(label) / 2;
        int ty = cy + (int)ly - 4;
        gfx.text(font, label, tx, ty, 0xCCFFFFFF);
    }

    // ---- circle primitives ------------------------------------------------

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
}
