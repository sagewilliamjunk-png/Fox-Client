package dev.kitsune.client.server;

import dev.kitsune.client.KitsuneClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On-disk record of "the user wanted to join server X, but we restarted MC to
 * disable some mods first." Read by {@link dev.kitsune.client.server.AutoReconnectHandler}
 * on the next launch to auto-rejoin once the user reaches the title screen.
 *
 * File: {@code config/kitsune/pending_join.json}
 */
public class PendingJoin {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static String inMemoryAddress;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(KitsuneClient.MOD_ID).resolve("pending_join.json");
    }

    public static void set(String address) {
        inMemoryAddress = address;
    }

    public static String getInMemory() {
        return inMemoryAddress;
    }

    public static void persist(String address) {
        try {
            Files.createDirectories(file().getParent());
            JsonObject o = new JsonObject();
            o.addProperty("address", address);
            o.addProperty("createdAt", System.currentTimeMillis());
            Files.writeString(file(), GSON.toJson(o));
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to persist pending join", e);
        }
    }

    /** Read and CONSUME the pending-join record. Returns null if none. */
    public static String consume() {
        Path f = file();
        if (!Files.exists(f)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            String addr = o.has("address") ? o.get("address").getAsString() : null;
            Files.deleteIfExists(f);
            return addr;
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("Failed to read pending_join.json", e);
            try { Files.deleteIfExists(f); } catch (IOException ignored) {}
            return null;
        }
    }

    // ---- Attempt tracking (safety backoff) ----
    //
    // The restart-auto-reconnect path is a one-shot: on each launch we consume
    // the pending_join record exactly once. But a misbehaving server (moved,
    // offline, banned) combined with a user who keeps relaunching can still
    // trip anti-DDoS on the server side. We persist a small attempt counter
    // keyed on the address so repeated failures give up entirely after
    // {@link #MAX_ATTEMPTS}, and surface a clear log line to the user.

    public static final int MAX_ATTEMPTS = 8;

    private static Path attemptFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(KitsuneClient.MOD_ID).resolve("reconnect_attempts.json");
    }

    /** Returns the post-increment attempt count for {@code address}. */
    public static int recordAttempt(String address) {
        Path f = attemptFile();
        JsonObject root = new JsonObject();
        try {
            if (Files.exists(f)) {
                root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            }
        } catch (Exception ignored) {}
        int prev = root.has(address) ? root.get(address).getAsInt() : 0;
        int next = prev + 1;
        root.addProperty(address, next);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(root));
        } catch (IOException ignored) {}
        return next;
    }

    /** Clears attempt count for an address — call on successful connect. */
    public static void clearAttempts(String address) {
        Path f = attemptFile();
        if (!Files.exists(f)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (root.has(address)) {
                root.remove(address);
                Files.writeString(f, GSON.toJson(root));
            }
        } catch (Exception ignored) {}
    }
}
