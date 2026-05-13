package dev.kitsune.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the registry of {@link HudWidget}s and their persistent positions,
 * draws them all in HUD-tail order, and provides the data backing for
 * {@link HudEditorScreen}.
 *
 * <p>Positions are stored as anchor + offset so that a widget pinned to the
 * top-right corner stays pinned across resolution changes. Anchors are one of
 * the 4 corners; offsets are signed pixels relative to that corner.
 */
public final class HudManager {

    public enum Anchor {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        /** Centre-anchored variants used only as defaults for vanilla HUD
         *  proxies (the editor reassigns them to one of the four corners on
         *  first drag so we never need to persist a centre anchor). */
        TOP_CENTER, BOTTOM_CENTER
    }

    public static final class Position {
        public Anchor anchor;
        public int offsetX;
        public int offsetY;

        public Position(Anchor anchor, int ox, int oy) {
            this.anchor = anchor;
            this.offsetX = ox;
            this.offsetY = oy;
        }

        /** Compute absolute top-left for a widget of this size on screen WxH. */
        public int absX(int screenW, int widgetW) {
            return switch (anchor) {
                case TOP_LEFT, BOTTOM_LEFT -> offsetX;
                case TOP_RIGHT, BOTTOM_RIGHT -> screenW - widgetW - offsetX;
                case TOP_CENTER, BOTTOM_CENTER -> (screenW - widgetW) / 2 + offsetX;
            };
        }

        public int absY(int screenH, int widgetH) {
            return switch (anchor) {
                case TOP_LEFT, TOP_RIGHT, TOP_CENTER -> offsetY;
                case BOTTOM_LEFT, BOTTOM_RIGHT, BOTTOM_CENTER -> screenH - widgetH - offsetY;
            };
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, HudWidget> WIDGETS = new LinkedHashMap<>();
    private static final Map<String, Position> POSITIONS = new HashMap<>();
    private static boolean loaded = false;
    private static long lastAutoSaveMs = 0L;
    private static final long AUTO_SAVE_INTERVAL_MS = 30_000L;

    private HudManager() {}

    public static void register(HudWidget w) {
        WIDGETS.put(w.widgetId(), w);
        if (!POSITIONS.containsKey(w.widgetId())) {
            POSITIONS.put(w.widgetId(), defaultPosition(w.widgetId()));
        }
    }

    public static void unregister(HudWidget w) {
        WIDGETS.remove(w.widgetId());
    }

    public static List<HudWidget> all() {
        return new ArrayList<>(WIDGETS.values());
    }

    public static Position getPosition(String id) {
        return POSITIONS.computeIfAbsent(id, HudManager::defaultPosition);
    }

    public static void setPosition(String id, Position p) {
        POSITIONS.put(id, p);
        save();
    }

    /**
     * Compute the delta in scaled pixels between where the editor wants this
     * widget and where it would naturally render. Used by
     * {@link dev.kitsune.client.mixin.GuiVanillaHudMixin} to translate vanilla
     * HUD elements (hotbar, health, hunger, etc.) to the user-chosen position.
     *
     * <p>The "natural" position for a vanilla proxy widget is encoded as its
     * default Position via {@link #defaultPosition(String)}; the user's chosen
     * Position is whatever the editor wrote into the persistent map. The delta
     * is the simple absolute-position difference at the current screen size.
     *
     * <p>Returns {@code int[2] = { dx, dy }}. Returns {@code {0, 0}} if the
     * widget isn't registered or the user hasn't moved it.
     */
    public static int[] vanillaOffset(String id, int widgetW, int widgetH) {
        if (!loaded) load();
        Position chosen = POSITIONS.get(id);
        if (chosen == null) return new int[] {0, 0};
        Position natural = defaultPosition(id);
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int dx = chosen.absX(sw, widgetW) - natural.absX(sw, widgetW);
        int dy = chosen.absY(sh, widgetH) - natural.absY(sh, widgetH);
        return new int[] {dx, dy};
    }

    /**
     * Called from the client tick; defensively flushes the HUD layout to disk
     * every 30 s so layout is preserved if the client hard-crashes between
     * explicit saves (e.g. mid-drag). Cheap no-op when nothing has been loaded.
     */
    public static void tickAutoSave() {
        long now = System.currentTimeMillis();
        if (now - lastAutoSaveMs < AUTO_SAVE_INTERVAL_MS) return;
        lastAutoSaveMs = now;
        if (!loaded) return;
        save();
    }

    /** Reset all positions to defaults (used by the editor "Reset" button). */
    public static void resetAll() {
        POSITIONS.clear();
        for (String id : WIDGETS.keySet()) POSITIONS.put(id, defaultPosition(id));
        save();
    }

    /**
     * Render every registered visible widget. Called from {@code GuiMixin}'s
     * HUD tail.
     */
    public static void renderAll(GuiGraphicsExtractor gfx) {
        if (!loaded) load();
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        for (HudWidget w : WIDGETS.values()) {
            if (!w.isWidgetVisible()) continue;
            try {
                Position p = getPosition(w.widgetId());
                int x = p.absX(sw, w.widgetWidth());
                int y = p.absY(sh, w.widgetHeight());
                w.renderWidget(gfx, x, y);
            } catch (Throwable t) {
                KitsuneClient.LOGGER.warn("[Fox] HudWidget {} render failed: {}", w.widgetId(), t.toString());
            }
        }
    }

    // ---- defaults ----

    private static Position defaultPosition(String id) {
        // Sensible defaults so widgets don't all stack in one corner.
        return switch (id) {
            case "coords" -> new Position(Anchor.TOP_LEFT, 4, 30);
            // TOP_RIGHT vertical stack — gaps assume potion_timers stays small
            // (1–2 effects). Heavy users will see overlap and can drag in the
            // editor; defaults shouldn't cascade-collide on a fresh install.
            case "potion_timers" -> new Position(Anchor.TOP_RIGHT, 4, 4);
            case "fps_graph" -> new Position(Anchor.BOTTOM_RIGHT, 8, 20);
            case "server_info" -> new Position(Anchor.TOP_LEFT, 4, 70);
            case "armor_durability" -> new Position(Anchor.TOP_LEFT, 4, 100);
            case "kill_death" -> new Position(Anchor.TOP_RIGHT, 4, 80);
            case "session_stats" -> new Position(Anchor.BOTTOM_RIGHT, 4, 60);
            case "paper_doll" -> new Position(Anchor.TOP_LEFT, 4, 140);
            case "shield_status" -> new Position(Anchor.BOTTOM_LEFT, 4, 60);
            case "reach_cooldown" -> new Position(Anchor.BOTTOM_LEFT, 80, 60);
            case "keystrokes"     -> new Position(Anchor.BOTTOM_LEFT, 4, 80);
            case "cps"            -> new Position(Anchor.BOTTOM_LEFT, 90, 80);
            case "clock"          -> new Position(Anchor.TOP_RIGHT,  4, 40);
            case "speedometer"    -> new Position(Anchor.BOTTOM_RIGHT, 4, 40);
            // PaperDoll occupies TOP_LEFT y∈[140, 220]; pin TotemCounter to
            // BOTTOM_LEFT (small, naturally near the hotbar where it matters).
            case "totem_counter"  -> new Position(Anchor.BOTTOM_LEFT, 4, 130);
            case "server_tps"     -> new Position(Anchor.TOP_RIGHT,  4, 60);
            case "memory_cleaner" -> new Position(Anchor.BOTTOM_RIGHT, 4, 80);
            case "death_coords"   -> new Position(Anchor.BOTTOM_RIGHT, 4, 110);
            case "xp_hud"         -> new Position(Anchor.BOTTOM_CENTER, 0, 60);
            case "entity_count"   -> new Position(Anchor.TOP_RIGHT, 4, 110);
            case "perf_dashboard" -> new Position(Anchor.TOP_LEFT, 4, 200);
            // Vanilla HUD proxies — defaults match where vanilla draws them.
            // BOTTOM_CENTER means "centred horizontally, anchored to bottom";
            // offsets are relative to the centred reference rectangle.
            case "vanilla.hotbar"      -> new Position(Anchor.BOTTOM_CENTER, 0, 0);
            case "vanilla.health"      -> new Position(Anchor.BOTTOM_CENTER, -82, 39);
            case "vanilla.food"        -> new Position(Anchor.BOTTOM_CENTER,  82, 39);
            case "vanilla.air"         -> new Position(Anchor.BOTTOM_CENTER,  82, 49);
            case "vanilla.experience"  -> new Position(Anchor.BOTTOM_CENTER, 0, 29);
            default -> new Position(Anchor.TOP_LEFT, 4, 4);
        };
    }

    // ---- persistence ----

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune").resolve("hud.json");
    }

    public static synchronized void load() {
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (var e : root.entrySet()) {
                JsonObject po = e.getValue().getAsJsonObject();
                Anchor a = Anchor.valueOf(po.get("anchor").getAsString());
                int ox = po.get("ox").getAsInt();
                int oy = po.get("oy").getAsInt();
                POSITIONS.put(e.getKey(), new Position(a, ox, oy));
            }
        } catch (Exception ex) {
            KitsuneClient.LOGGER.warn("[Fox] Failed to load hud.json: {}", ex.toString());
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject root = new JsonObject();
            // Sorted for stable diffs
            List<String> keys = new ArrayList<>(POSITIONS.keySet());
            Collections.sort(keys);
            for (String id : keys) {
                Position p = POSITIONS.get(id);
                JsonObject po = new JsonObject();
                po.addProperty("anchor", p.anchor.name());
                po.addProperty("ox", p.offsetX);
                po.addProperty("oy", p.offsetY);
                root.add(id, po);
            }
            Path target = file();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            KitsuneClient.LOGGER.warn("[Fox] Failed to save hud.json: {}", ex.toString());
        }
    }
}
