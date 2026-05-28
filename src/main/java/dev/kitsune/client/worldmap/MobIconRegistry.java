package dev.kitsune.client.worldmap;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kitsune.client.KitsuneClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.HashMap;
import java.util.Map;

/**
 * Procedural pixel-art mob icons rendered into 16×16 DynamicTextures at
 * mod init. Used by the Minimap's Tab-held entity-icon mode.
 *
 * <p>Bundling actual PNGs would mean shipping ~16 art files; instead each
 * icon is drawn at runtime from a tiny pixel-pattern table. Same memory
 * footprint as bundled PNGs (32 KB total for all icons), zero asset files
 * in the jar, and adding a new mob is one map entry.
 *
 * <p>Lookup falls through to the spawn-egg fallback (handled by MinimapModule)
 * for any entity type we haven't drawn here.
 */
public final class MobIconRegistry {

    // ---- color palette ----
    private static final int TRANSPARENT = 0x00000000;
    private static final int BLACK       = 0xFF1A1A1A;
    private static final int WHITE       = 0xFFE9E9E9;
    private static final int RED         = 0xFFC0392B;
    private static final int GREEN       = 0xFF55A33A;
    private static final int DARK_GREEN  = 0xFF3F7A2C;
    private static final int ZOMBIE_GREEN= 0xFF7CAF49;
    private static final int CREEPER     = 0xFF6CBD58;
    private static final int CREEPER_DARK= 0xFF3F7A2C;
    private static final int PINK        = 0xFFEFB1AE;
    private static final int PIG_SNOUT   = 0xFFD08784;
    private static final int BROWN       = 0xFF704033;
    private static final int COW_SPOT    = 0xFF362417;
    private static final int VILLAGER    = 0xFF765539;
    private static final int VILLAGER_NOSE = 0xFF8C6A45;
    private static final int FOX_ORANGE  = 0xFFEE9A4D;
    private static final int FOX_BELLY   = 0xFFFFFFFF;
    private static final int FOX_DARK    = 0xFFA8633A;
    private static final int WOOL_WHITE  = 0xFFF1F1F1;
    private static final int SHEEP_FACE  = 0xFFE8C5A3;
    private static final int CHICKEN     = 0xFFEEEDED;
    private static final int CHICKEN_BEAK= 0xFFEFA53A;
    private static final int CHICKEN_CROWN = 0xFFC0392B;
    private static final int WITCH_HAT   = 0xFF2B0F4F;
    private static final int WITCH_SKIN  = 0xFF6F8758;
    private static final int ENDERMAN    = 0xFF181818;
    private static final int ENDERMAN_EYE= 0xFFE573FF;
    private static final int SPIDER      = 0xFF2C1A14;
    private static final int SPIDER_EYE  = 0xFFB81515;
    private static final int IRON_GOLEM  = 0xFFD9D5C7;
    private static final int IRON_GOLEM_ACCENT = 0xFFB6AC72;
    private static final int CAT_GINGER  = 0xFFD27C2C;
    private static final int WOLF_GRAY   = 0xFFCFC9BC;
    private static final int WOLF_DARK   = 0xFF5C5447;
    private static final int HORSE_BROWN = 0xFF815333;
    private static final int HORSE_MANE  = 0xFF362417;
    private static final int SLIME       = 0x9955BB55;

    /** Each entry is a 16×16 grid of palette indices; -1 means transparent.
     *  Stored sparse for readability — we paint a base color first then
     *  overlay the distinctive pixels. */
    private record Sketch(int baseColor, int[][] overlay) {}

    private static final Map<EntityType<?>, Sketch> SKETCHES = new HashMap<>();
    /** Built lazily on first request. */
    private static final Map<EntityType<?>, Identifier> TEXTURE_IDS = new HashMap<>();
    private static int idCounter = 0;

    static {
        // Each int[][] is { {x, y, color}, ... } — pixel-by-pixel overlay
        // after the base color is painted. Coordinates are 0..15 in the
        // 16×16 grid. Origin top-left.
        registerCreeper();
        registerZombie();
        registerSkeleton();
        registerSpider();
        registerEnderman();
        registerWitch();
        registerVillager();
        registerIronGolem();
        registerPig();
        registerCow();
        registerSheep();
        registerChicken();
        registerFox();
        registerWolf();
        registerCat();
        registerHorse();
        registerSlime();
    }

    /** Look up a runtime-generated mob icon. Returns null when no sketch is
     *  registered (caller falls back to spawn-egg or colored dot). */
    public static Identifier iconFor(EntityType<?> type) {
        if (type == null) return null;
        Identifier cached = TEXTURE_IDS.get(type);
        if (cached != null) return cached;
        Sketch s = SKETCHES.get(type);
        if (s == null) return null;
        try {
            Identifier id = buildAndRegister(type, s);
            TEXTURE_IDS.put(type, id);
            return id;
        } catch (Throwable t) {
            KitsuneClient.LOGGER.warn("[MobIconRegistry] failed for {}: {}", type, t.toString());
            return null;
        }
    }

    private static Identifier buildAndRegister(EntityType<?> type, Sketch sketch) {
        NativeImage img = new NativeImage(16, 16, false);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setPixelABGR(x, y, MapTextureCache.argbToAbgr(sketch.baseColor));
            }
        }
        for (int[] px : sketch.overlay) {
            int x = px[0], y = px[1], color = px[2];
            if (x < 0 || x >= 16 || y < 0 || y >= 16) continue;
            img.setPixelABGR(x, y, MapTextureCache.argbToAbgr(color));
        }
        DynamicTexture tex = new DynamicTexture(() -> "kitsune_mob_" + EntityType.getKey(type).getPath(), img);
        Identifier id = Identifier.fromNamespaceAndPath("kitsune", "mob_icon_" + (idCounter++));
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return id;
    }

    // ============================================================
    // Per-mob pixel sketches. Each is drawn to look distinctive at
    // 16×16 — faces / front-on, like Xaero's icons. The base color
    // fills the whole tile, then overlays add eyes / mouth / etc.
    // ============================================================

    private static void registerCreeper() {
        SKETCHES.put(EntityType.CREEPER, new Sketch(TRANSPARENT, joinAll(
                // 12×12 face roughly centered
                rect(2, 2, 12, 12, CREEPER),
                // Dark eyes
                rect(4, 5, 3, 3, CREEPER_DARK),
                rect(9, 5, 3, 3, CREEPER_DARK),
                // Frowny mouth
                rect(6, 9, 4, 2, CREEPER_DARK),
                rect(5, 10, 1, 2, CREEPER_DARK),
                rect(10, 10, 1, 2, CREEPER_DARK)
        )));
    }

    private static void registerZombie() {
        SKETCHES.put(EntityType.ZOMBIE, new Sketch(TRANSPARENT, joinAll(
                rect(2, 2, 12, 12, ZOMBIE_GREEN),
                rect(4, 6, 3, 2, BLACK),   // left eye
                rect(9, 6, 3, 2, BLACK),   // right eye
                rect(6, 11, 4, 1, DARK_GREEN) // mouth
        )));
    }

    private static void registerSkeleton() {
        SKETCHES.put(EntityType.SKELETON, new Sketch(TRANSPARENT, joinAll(
                rect(2, 2, 12, 12, WHITE),
                rect(4, 6, 3, 3, BLACK), // eye sockets
                rect(9, 6, 3, 3, BLACK),
                rect(6, 11, 4, 1, BLACK) // mouth
        )));
    }

    private static void registerSpider() {
        SKETCHES.put(EntityType.SPIDER, new Sketch(TRANSPARENT, joinAll(
                rect(3, 3, 10, 10, SPIDER),
                // Two pairs of red eyes
                px(5, 6, SPIDER_EYE), px(6, 6, SPIDER_EYE),
                px(9, 6, SPIDER_EYE), px(10, 6, SPIDER_EYE),
                px(5, 8, SPIDER_EYE), px(10, 8, SPIDER_EYE),
                // Legs poking out the corners
                px(2, 3, SPIDER), px(2, 4, SPIDER),
                px(13, 3, SPIDER), px(13, 4, SPIDER),
                px(2, 11, SPIDER), px(2, 12, SPIDER),
                px(13, 11, SPIDER), px(13, 12, SPIDER)
        )));
    }

    private static void registerEnderman() {
        SKETCHES.put(EntityType.ENDERMAN, new Sketch(TRANSPARENT, joinAll(
                rect(3, 1, 10, 14, ENDERMAN),
                rect(4, 6, 3, 2, ENDERMAN_EYE),
                rect(9, 6, 3, 2, ENDERMAN_EYE)
        )));
    }

    private static void registerWitch() {
        SKETCHES.put(EntityType.WITCH, new Sketch(TRANSPARENT, joinAll(
                // Hat (tall pointy)
                px(7, 0, WITCH_HAT), px(8, 0, WITCH_HAT),
                rect(6, 1, 4, 2, WITCH_HAT),
                rect(5, 3, 6, 2, WITCH_HAT),
                rect(4, 5, 8, 1, WITCH_HAT),
                // Face
                rect(4, 6, 8, 8, WITCH_SKIN),
                rect(5, 8, 2, 2, BLACK), // left eye
                rect(9, 8, 2, 2, BLACK), // right eye
                // Big nose
                rect(7, 10, 2, 3, VILLAGER_NOSE)
        )));
    }

    private static void registerVillager() {
        SKETCHES.put(EntityType.VILLAGER, new Sketch(TRANSPARENT, joinAll(
                rect(2, 2, 12, 12, VILLAGER),
                rect(4, 6, 2, 2, BLACK),
                rect(10, 6, 2, 2, BLACK),
                rect(6, 8, 4, 5, VILLAGER_NOSE) // signature big nose
        )));
    }

    private static void registerIronGolem() {
        SKETCHES.put(EntityType.IRON_GOLEM, new Sketch(TRANSPARENT, joinAll(
                rect(2, 1, 12, 13, IRON_GOLEM),
                rect(3, 8, 10, 1, IRON_GOLEM_ACCENT), // accent stripe
                rect(4, 6, 2, 2, BLACK),
                rect(10, 6, 2, 2, BLACK),
                rect(6, 11, 4, 1, IRON_GOLEM_ACCENT) // mouth
        )));
    }

    private static void registerPig() {
        SKETCHES.put(EntityType.PIG, new Sketch(TRANSPARENT, joinAll(
                rect(2, 3, 12, 11, PINK),
                rect(4, 6, 2, 2, BLACK),
                rect(10, 6, 2, 2, BLACK),
                rect(6, 9, 4, 3, PIG_SNOUT), // snout
                px(7, 10, BLACK), px(8, 10, BLACK) // nostrils
        )));
    }

    private static void registerCow() {
        SKETCHES.put(EntityType.COW, new Sketch(TRANSPARENT, joinAll(
                rect(2, 3, 12, 11, BROWN),
                rect(3, 5, 3, 2, COW_SPOT), // forehead spots
                rect(11, 4, 2, 3, COW_SPOT),
                rect(7, 8, 3, 2, COW_SPOT),
                rect(4, 6, 2, 2, WHITE), // eyes
                rect(10, 6, 2, 2, WHITE),
                px(5, 7, BLACK), px(11, 7, BLACK),
                rect(6, 11, 4, 2, PINK) // muzzle
        )));
    }

    private static void registerSheep() {
        SKETCHES.put(EntityType.SHEEP, new Sketch(TRANSPARENT, joinAll(
                // Fluffy wool ring around the face
                rect(1, 2, 14, 12, WOOL_WHITE),
                // Face panel slightly recessed
                rect(5, 6, 6, 6, SHEEP_FACE),
                px(7, 8, BLACK), px(8, 8, BLACK), // eyes
                rect(6, 10, 4, 1, BLACK) // mouth
        )));
    }

    private static void registerChicken() {
        SKETCHES.put(EntityType.CHICKEN, new Sketch(TRANSPARENT, joinAll(
                rect(3, 3, 10, 11, CHICKEN),
                rect(6, 1, 4, 2, CHICKEN_CROWN), // comb
                px(7, 0, CHICKEN_CROWN), px(8, 0, CHICKEN_CROWN),
                rect(5, 6, 2, 2, BLACK),
                rect(9, 6, 2, 2, BLACK),
                rect(6, 9, 4, 3, CHICKEN_BEAK) // beak
        )));
    }

    private static void registerFox() {
        SKETCHES.put(EntityType.FOX, new Sketch(TRANSPARENT, joinAll(
                // Pointed ears (top)
                rect(2, 0, 3, 4, FOX_DARK),
                rect(11, 0, 3, 4, FOX_DARK),
                rect(3, 1, 1, 2, FOX_ORANGE),
                rect(12, 1, 1, 2, FOX_ORANGE),
                // Face
                rect(3, 3, 10, 11, FOX_ORANGE),
                // White muzzle / belly
                rect(5, 9, 6, 5, FOX_BELLY),
                // Eyes
                rect(5, 6, 2, 2, BLACK),
                rect(9, 6, 2, 2, BLACK),
                // Black nose tip
                rect(7, 10, 2, 2, BLACK)
        )));
    }

    private static void registerWolf() {
        SKETCHES.put(EntityType.WOLF, new Sketch(TRANSPARENT, joinAll(
                rect(2, 1, 3, 4, WOLF_DARK), // ears
                rect(11, 1, 3, 4, WOLF_DARK),
                rect(3, 3, 10, 11, WOLF_GRAY),
                rect(5, 6, 2, 2, BLACK),
                rect(9, 6, 2, 2, BLACK),
                rect(7, 10, 2, 2, BLACK) // nose
        )));
    }

    private static void registerCat() {
        SKETCHES.put(EntityType.CAT, new Sketch(TRANSPARENT, joinAll(
                // Pointy triangular ears
                rect(2, 1, 2, 3, CAT_GINGER),
                rect(4, 0, 1, 1, CAT_GINGER),
                rect(12, 1, 2, 3, CAT_GINGER),
                rect(11, 0, 1, 1, CAT_GINGER),
                rect(3, 3, 10, 11, CAT_GINGER),
                // Slit eyes
                rect(5, 6, 2, 3, GREEN),
                rect(9, 6, 2, 3, GREEN),
                px(6, 7, BLACK), px(10, 7, BLACK),
                px(7, 10, PINK), px(8, 10, PINK) // tiny nose
        )));
    }

    private static void registerHorse() {
        SKETCHES.put(EntityType.HORSE, new Sketch(TRANSPARENT, joinAll(
                // Long face shape
                rect(4, 1, 8, 14, HORSE_BROWN),
                // Mane sticking up
                rect(6, 0, 4, 2, HORSE_MANE),
                px(5, 1, HORSE_MANE), px(10, 1, HORSE_MANE),
                // Eyes
                px(5, 6, BLACK), px(10, 6, BLACK),
                // Nose tip
                rect(6, 12, 4, 2, COW_SPOT)
        )));
    }

    private static void registerSlime() {
        SKETCHES.put(EntityType.SLIME, new Sketch(TRANSPARENT, joinAll(
                rect(3, 4, 10, 10, SLIME),
                rect(4, 7, 2, 2, BLACK),
                rect(10, 7, 2, 2, BLACK),
                rect(6, 11, 4, 1, BLACK)
        )));
    }

    // ============================================================
    // Tiny DSL helpers — every drawing routine above composes from
    // these so the sketches stay readable.
    // ============================================================

    /** Single pixel. */
    private static int[][] px(int x, int y, int color) {
        return new int[][] { {x, y, color} };
    }

    /** Filled rectangle (w, h). */
    private static int[][] rect(int x, int y, int w, int h, int color) {
        int[][] out = new int[w * h][];
        int i = 0;
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                out[i++] = new int[] {x + dx, y + dy, color};
            }
        }
        return out;
    }

    /** Concatenate pixel groups. */
    private static int[][] joinAll(int[][]... groups) {
        int total = 0;
        for (int[][] g : groups) total += g.length;
        int[][] out = new int[total][];
        int i = 0;
        for (int[][] g : groups) for (int[] px : g) out[i++] = px;
        return out;
    }
}
