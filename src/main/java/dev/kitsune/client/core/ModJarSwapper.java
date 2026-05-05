package dev.kitsune.client.core;

import dev.kitsune.client.KitsuneClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves mod JARs between {@code mods/} and {@code mods/.kitsune-disabled/}.
 *
 * Mods cannot be unloaded mid-game (they're loaded once at JVM startup), so
 * this class only QUEUES moves to a pending file. The {@link dev.kitsune.client.PreLaunchBootstrap}
 * runs before mod loading on the next launch and processes the queue.
 *
 * Pending file location: {@code <gameDir>/mods/.kitsune-pending-moves.json}
 *
 * Layout:
 * <pre>
 *   {
 *     "moves": [
 *       { "modId": "freecam", "jarFileName": "freecam-1.2.3.jar", "to": "disabled" },
 *       { "modId": "minimap", "jarFileName": "xaero-23.4.jar",    "to": "enabled"  }
 *     ]
 *   }
 * </pre>
 */
public class ModJarSwapper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Direction { DISABLE, ENABLE }

    public static class PendingMove {
        public String modId;
        public String jarFileName;     // resolved at queue time, may be null until known
        public Direction direction;

        public PendingMove() {}
        public PendingMove(String modId, String jarFileName, Direction direction) {
            this.modId = modId;
            this.jarFileName = jarFileName;
            this.direction = direction;
        }
    }

    private static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    private static Path disabledDir() {
        return modsDir().resolve(".kitsune-disabled");
    }

    private static Path pendingFile() {
        return modsDir().resolve(".kitsune-pending-moves.json");
    }

    /**
     * Resolve the JAR file name on disk for a Fabric mod ID, by inspecting
     * the loaded mod container's origin path. Returns null if not found.
     */
    public static String resolveJarFileName(String modId) {
        Optional<ModContainer> opt = FabricLoader.getInstance().getModContainer(modId);
        if (opt.isEmpty()) return null;
        ModContainer mc = opt.get();
        try {
            // origin path is a JAR file path; basename is what lives in mods/
            for (Path p : mc.getOrigin().getPaths()) {
                if (p == null) continue;
                Path fileName = p.getFileName();
                if (fileName != null) return fileName.toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Resolve the JAR file name for a mod that may be currently disabled (lives in disabledDir). */
    public static String resolveDisabledJarFileName(String modId) {
        if (!Files.exists(disabledDir())) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(disabledDir(), "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                // Heuristic match: file name contains the mod ID. Cheap but works for common cases.
                if (name.toLowerCase().contains(modId.toLowerCase())) return name;
            }
        } catch (IOException ignored) {}
        return null;
    }

    public static boolean isLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    /**
     * Queue a move. Direction.DISABLE moves the JAR out of mods/, Direction.ENABLE moves it back.
     * Returns false if the queue couldn't be written.
     */
    public static boolean queueMove(String modId, Direction direction) {
        String jar;
        if (direction == Direction.DISABLE) {
            jar = resolveJarFileName(modId);
            if (jar == null) {
                KitsuneClient.LOGGER.warn("Cannot disable mod '" + modId + "': not loaded / jar not found");
                return false;
            }
        } else {
            jar = resolveDisabledJarFileName(modId);
            if (jar == null) {
                KitsuneClient.LOGGER.warn("Cannot enable mod '" + modId + "': no jar in disabled folder");
                return false;
            }
        }
        Map<String, PendingMove> pending = readPending();
        pending.put(modId, new PendingMove(modId, jar, direction));
        return writePending(pending);
    }

    /** Read all currently queued moves, keyed by mod ID. */
    public static Map<String, PendingMove> readPending() {
        Path f = pendingFile();
        Map<String, PendingMove> out = new LinkedHashMap<>();
        if (!Files.exists(f)) return out;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (root.has("moves")) {
                for (JsonElement el : root.getAsJsonArray("moves")) {
                    JsonObject obj = el.getAsJsonObject();
                    PendingMove pm = new PendingMove();
                    pm.modId = obj.get("modId").getAsString();
                    pm.jarFileName = obj.has("jarFileName") && !obj.get("jarFileName").isJsonNull()
                            ? obj.get("jarFileName").getAsString() : null;
                    pm.direction = Direction.valueOf(obj.get("direction").getAsString());
                    out.put(pm.modId, pm);
                }
            }
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("Failed to read pending moves", e);
        }
        return out;
    }

    private static boolean writePending(Map<String, PendingMove> pending) {
        try {
            Files.createDirectories(modsDir());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (PendingMove pm : pending.values()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("modId", pm.modId);
                if (pm.jarFileName != null) obj.addProperty("jarFileName", pm.jarFileName);
                obj.addProperty("direction", pm.direction.name());
                arr.add(obj);
            }
            root.add("moves", arr);
            Files.writeString(pendingFile(), GSON.toJson(root));
            return true;
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to write pending moves", e);
            return false;
        }
    }

    /**
     * Apply all pending moves NOW. Called by {@link dev.kitsune.client.PreLaunchBootstrap}
     * before the Fabric mod loader scans the mods folder. Must NOT be called from
     * the regular client init path.
     */
    public static void applyPendingMovesAtPreLaunch() {
        Path pending = pendingFile();
        if (!Files.exists(pending)) return;
        Map<String, PendingMove> moves = readPending();
        if (moves.isEmpty()) {
            try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
            return;
        }
        try {
            Files.createDirectories(disabledDir());
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to create disabled mods dir", e);
            return;
        }
        List<String> failures = new ArrayList<>();
        // Moves we couldn't apply this launch (e.g. Windows had the jar locked).
        // These are re-persisted to the pending queue so PreLaunchBootstrap will
        // try them again on the next launch instead of being silently dropped.
        Map<String, PendingMove> retry = new LinkedHashMap<>();
        for (PendingMove pm : moves.values()) {
            try {
                if (pm.jarFileName == null) continue;
                if (pm.direction == Direction.DISABLE) {
                    Path src = modsDir().resolve(pm.jarFileName);
                    Path dst = disabledDir().resolve(pm.jarFileName);
                    if (Files.exists(src)) {
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    Path src = disabledDir().resolve(pm.jarFileName);
                    Path dst = modsDir().resolve(pm.jarFileName);
                    if (Files.exists(src)) {
                        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException e) {
                // Most common cause: file-lock from a background AV scanner on
                // Windows. Log once, requeue for next launch, DO NOT rethrow.
                KitsuneClient.LOGGER.warn("[Fox] mod move deferred ({}): {}",
                        pm.modId, e.toString());
                failures.add(pm.modId + ": " + e.getMessage());
                retry.put(pm.modId, pm);
            } catch (Throwable t) {
                // Absolutely nothing in this loop may escape PreLaunchBootstrap.
                KitsuneClient.LOGGER.error("[Fox] mod move crashed ({})",
                        pm.modId, t);
                failures.add(pm.modId + ": " + t);
            }
        }
        try {
            Files.deleteIfExists(pending);
        } catch (IOException ignored) {}
        if (!retry.isEmpty()) {
            writePending(retry);
        }
        if (!failures.isEmpty()) {
            KitsuneClient.LOGGER.warn("[Fox] {} mod move(s) deferred to next launch: {}",
                    failures.size(), String.join(", ", failures));
        }
    }
}
