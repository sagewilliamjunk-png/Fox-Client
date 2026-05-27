package dev.kitsune.client.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of waypoints keyed by sub-world id (server host or
 * "singleplayer:<world name>"). Persists to disk lazily on every mutation
 * (atomic write — same .tmp + rename pattern the launcher uses).
 *
 * <p>Format on disk: {@code configDir/kitsune/waypoints/<sanitized-subworld>.json}.
 * Schema:
 * <pre>{@code
 * {
 *   "waypoints": [
 *     {"id":"...","name":"Home","x":42,"y":68,"z":-100,"color":-3373515,
 *      "symbol":"H","global":false,"deathpoint":false},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>All access goes through the static API — there's only ever one
 * instance per JVM. Thread-safety: the map is a ConcurrentHashMap and the
 * per-sub-world lists are returned as defensive copies, so a render thread
 * iterating a list can't observe concurrent edits.
 */
public final class WaypointManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** subWorldId → ordered list (newest first). */
    private static final Map<String, List<Waypoint>> WORLDS = new ConcurrentHashMap<>();

    /** Sub-worlds we've already loaded from disk this session. */
    private static final java.util.Set<String> LOADED_FROM_DISK = ConcurrentHashMap.newKeySet();

    /** Currently active waypoint set for the UI / minimap. Special sentinel
     *  "All" (no quotes) means render every set together. */
    public static final String ALL_SETS = "All";
    private static volatile String activeSet = ALL_SETS;

    public static String activeSet() { return activeSet; }
    public static void setActiveSet(String name) {
        activeSet = (name == null || name.isEmpty()) ? ALL_SETS : name;
    }

    /** Cycle to the next set in the current sub-world's roster — used by
     *  the U+RightArrow / dedicated cycle keybind. Wraps. */
    public static void cycleActiveSet() {
        java.util.List<String> sets = knownSets();
        if (sets.isEmpty()) return;
        int idx = sets.indexOf(activeSet);
        activeSet = sets.get((idx + 1) % sets.size());
    }

    /** Distinct set names for the current sub-world, plus the "All" sentinel
     *  at index 0. Used to drive the set-cycle keybind and any UI dropdown. */
    public static java.util.List<String> knownSets() {
        String sub = currentSubWorldId();
        if (sub == null) return java.util.List.of(ALL_SETS, Waypoint.DEFAULT_SET);
        ensureLoaded(sub);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(ALL_SETS);
        out.add(Waypoint.DEFAULT_SET);
        List<Waypoint> list = WORLDS.get(sub);
        if (list != null) {
            for (Waypoint w : list) {
                String s = w.set();
                if (s != null && !s.isEmpty()) out.add(s);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    private WaypointManager() {}

    // ---- public API -------------------------------------------------------

    /** Waypoints for the current sub-world filtered by {@link #activeSet}
     *  ({@link #ALL_SETS} returns everything). Defensive copy. */
    public static List<Waypoint> current() {
        return currentInSet(activeSet);
    }

    /** Waypoints for the current sub-world, filtered to the given set name
     *  (or {@link #ALL_SETS} for all). Defensive copy. */
    public static List<Waypoint> currentInSet(String setName) {
        List<Waypoint> all = forSubWorld(currentSubWorldId());
        if (ALL_SETS.equals(setName) || setName == null) return all;
        List<Waypoint> filtered = new ArrayList<>();
        for (Waypoint w : all) {
            if (setName.equals(w.set())) filtered.add(w);
        }
        return filtered;
    }

    /** Full unfiltered list for a specific sub-world. */
    public static List<Waypoint> forSubWorld(String subWorldId) {
        if (subWorldId == null) return Collections.emptyList();
        ensureLoaded(subWorldId);
        List<Waypoint> list = WORLDS.get(subWorldId);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /** Add a waypoint to the current sub-world. Returns the new waypoint
     *  (with an auto-assigned id if the input didn't have one). */
    public static Waypoint addToCurrent(Waypoint w) {
        String sub = currentSubWorldId();
        if (sub == null) return w;
        ensureLoaded(sub);
        Waypoint stored = (w.id() == null || w.id().isBlank())
                ? new Waypoint(UUID.randomUUID().toString(), w.name(), w.x(), w.y(), w.z(),
                               w.color(), w.symbol(), w.global(), w.deathpoint())
                : w;
        WORLDS.computeIfAbsent(sub, k -> new ArrayList<>()).add(0, stored);
        save(sub);
        return stored;
    }

    /** Replace the waypoint with the matching id; no-op if not found. */
    public static void update(Waypoint w) {
        String sub = currentSubWorldId();
        if (sub == null) return;
        ensureLoaded(sub);
        List<Waypoint> list = WORLDS.get(sub);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(w.id())) {
                list.set(i, w);
                save(sub);
                return;
            }
        }
    }

    public static void delete(String id) {
        String sub = currentSubWorldId();
        if (sub == null) return;
        ensureLoaded(sub);
        List<Waypoint> list = WORLDS.get(sub);
        if (list == null) return;
        if (list.removeIf(w -> w.id().equals(id))) save(sub);
    }

    /** Sub-world identifier for the currently connected/loaded world.
     *  Returns null when neither a server nor a singleplayer world is active. */
    public static String currentSubWorldId() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null && !sd.ip.isEmpty()) {
            return "server:" + sanitize(sd.ip);
        }
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            String world = mc.getSingleplayerServer().getWorldData().getLevelName();
            return "singleplayer:" + sanitize(world);
        }
        return null;
    }

    // ---- disk persistence -------------------------------------------------

    private static void ensureLoaded(String subWorldId) {
        if (LOADED_FROM_DISK.contains(subWorldId)) return;
        LOADED_FROM_DISK.add(subWorldId);
        Path file = fileFor(subWorldId);
        if (!Files.exists(file)) {
            WORLDS.put(subWorldId, new ArrayList<>());
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray arr = root.has("waypoints") ? root.getAsJsonArray("waypoints") : new JsonArray();
            List<Waypoint> list = new ArrayList<>();
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                try {
                    list.add(new Waypoint(
                            o.has("id")     ? o.get("id").getAsString()     : UUID.randomUUID().toString(),
                            o.has("name")   ? o.get("name").getAsString()   : "WP",
                            o.get("x").getAsInt(),
                            o.get("y").getAsInt(),
                            o.get("z").getAsInt(),
                            o.has("color")  ? o.get("color").getAsInt()     : Waypoint.DEFAULT_COLOR,
                            o.has("symbol") ? o.get("symbol").getAsString() : "•",
                            o.has("global") && o.get("global").getAsBoolean(),
                            o.has("deathpoint") && o.get("deathpoint").getAsBoolean(),
                            o.has("set")    ? o.get("set").getAsString()    : Waypoint.DEFAULT_SET
                    ));
                } catch (Exception ignored) { /* skip malformed entry */ }
            }
            WORLDS.put(subWorldId, list);
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[Fox] waypoint load failed for {}: {}", subWorldId, e.getMessage());
            WORLDS.put(subWorldId, new ArrayList<>());
        }
    }

    private static synchronized void save(String subWorldId) {
        List<Waypoint> list = WORLDS.getOrDefault(subWorldId, Collections.emptyList());
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Waypoint w : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id",     w.id());
            o.addProperty("name",   w.name());
            o.addProperty("x",      w.x());
            o.addProperty("y",      w.y());
            o.addProperty("z",      w.z());
            o.addProperty("color",  w.color());
            o.addProperty("symbol", w.symbol());
            o.addProperty("global", w.global());
            o.addProperty("deathpoint", w.deathpoint());
            o.addProperty("set", w.set() == null ? Waypoint.DEFAULT_SET : w.set());
            arr.add(o);
        }
        root.add("waypoints", arr);
        Path target = fileFor(subWorldId);
        Path tmp    = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            KitsuneClient.LOGGER.warn("[Fox] waypoint save failed for {}: {}", subWorldId, e.getMessage());
        }
    }

    private static Path fileFor(String subWorldId) {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune")
                .resolve("waypoints")
                .resolve(sanitize(subWorldId) + ".json");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ---- helpers ----------------------------------------------------------

    /** Generate a default name like "WP-5" for the next waypoint in the
     *  current sub-world. */
    public static String nextDefaultName() {
        int n = current().size() + 1;
        return "WP-" + n;
    }
}
