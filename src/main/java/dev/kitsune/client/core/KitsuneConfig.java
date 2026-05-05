package dev.kitsune.client.core;

import dev.kitsune.client.KitsuneClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Top-level Fox Client config (theme settings, global feature toggles, key bindings).
 * Profile-scoped settings live on {@link Profile}; everything global lives here.
 */
public class KitsuneConfig {

    /**
     * Schema version for the config JSON. Bump when adding/renaming fields
     * and add a migration branch in {@link #migrate(JsonObject, int)}.
     *
     * <ul>
     *   <li>0 — implicit (no version field); original schema</li>
     *   <li>1 — explicit version; added {@code schemaVersion} field</li>
     * </ul>
     */
    public static final int CURRENT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** When true, the Fox-themed title screen replaces the vanilla one. */
    public boolean foxTitleScreen = true;

    /** When true, ServerJoinInterceptor checks server rules before connecting. */
    public boolean autoCheckServerRules = true;

    /** When true, after a restart-for-server, automatically reconnect to the saved server. */
    public boolean autoReconnectAfterRestart = true;

    /** When true, the FPS limiter drops to backgroundFpsLimit when the window loses focus. */
    public boolean adaptiveFpsLimit = true;
    public int backgroundFpsLimit = 60;

    /**
     * The user's real (foreground) framerate cap, captured the first time
     * AdaptiveFpsLimitFeature enables. Persisted here so that if the game
     * ever shuts down while the feature's throttle is active, we can still
     * restore the right value next launch — otherwise vanilla's options.txt
     * would keep the throttled value as if it were the user's choice.
     * -1 = not yet captured.
     */
    public int foregroundFpsLimit = -1;

    /** When true, draws a small Fox Client logo + active profile in the top-left HUD. */
    public boolean showWatermark = false;

    /**
     * Where toast notifications anchor on screen. One of the
     * {@link dev.kitsune.client.hud.HudManager.Anchor} names. Defaults to
     * BOTTOM_RIGHT to match the historical fixed position. Stored as a string
     * so unknown values from a future build degrade to the default rather than
     * a load failure.
     */
    public String notificationAnchor = "BOTTOM_RIGHT";

    /** Cape id the local player has chosen to display (must be one they own
     *  per cosmetic-owners.json). Empty string disables. */
    public String selectedCapeId = "";

    /**
     * Maps server address glob patterns to profile names.
     * When connecting to a server matching a pattern, the bound profile auto-activates.
     * Example: {"*.hypixel.net": "pvp", "play.example.com": "vanilla"}
     */
    public Map<String, String> serverProfileBindings = new HashMap<>();

    /**
     * Find the profile name bound to the given server address (uses glob matching).
     * Returns null if no binding matches.
     */
    public String getProfileForServer(String serverAddress) {
        if (serverAddress == null) return null;
        for (Map.Entry<String, String> entry : serverProfileBindings.entrySet()) {
            if (dev.kitsune.client.server.ServerRule.globMatch(
                    entry.getKey().toLowerCase(), serverAddress.toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static KitsuneConfig INSTANCE;

    public static KitsuneConfig get() {
        if (INSTANCE == null) load();
        return INSTANCE;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(KitsuneClient.MOD_ID).resolve("config.json");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) {
            INSTANCE = new KitsuneConfig();
            save();
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            int fileVersion = o.has("schemaVersion") ? o.get("schemaVersion").getAsInt() : 0;
            if (fileVersion < CURRENT_VERSION) {
                o = migrate(o, fileVersion);
                KitsuneClient.LOGGER.info("[KitsuneConfig] Migrated from v{} to v{}",
                        fileVersion, CURRENT_VERSION);
            } else if (fileVersion > CURRENT_VERSION) {
                KitsuneClient.LOGGER.warn("[KitsuneConfig] Config file is newer (v{}) than "
                        + "this build (v{}); loading best-effort", fileVersion, CURRENT_VERSION);
            }
            KitsuneConfig c = new KitsuneConfig();
            if (o.has("foxTitleScreen")) c.foxTitleScreen = o.get("foxTitleScreen").getAsBoolean();
            if (o.has("autoCheckServerRules")) c.autoCheckServerRules = o.get("autoCheckServerRules").getAsBoolean();
            if (o.has("autoReconnectAfterRestart")) c.autoReconnectAfterRestart = o.get("autoReconnectAfterRestart").getAsBoolean();
            if (o.has("adaptiveFpsLimit")) c.adaptiveFpsLimit = o.get("adaptiveFpsLimit").getAsBoolean();
            if (o.has("backgroundFpsLimit")) c.backgroundFpsLimit = o.get("backgroundFpsLimit").getAsInt();
            if (o.has("foregroundFpsLimit")) c.foregroundFpsLimit = o.get("foregroundFpsLimit").getAsInt();
            if (o.has("showWatermark")) c.showWatermark = o.get("showWatermark").getAsBoolean();
            if (o.has("notificationAnchor")) c.notificationAnchor = o.get("notificationAnchor").getAsString();
            if (o.has("selectedCapeId"))     c.selectedCapeId     = o.get("selectedCapeId").getAsString();
            if (o.has("serverProfileBindings")) {
                JsonObject bindings = o.getAsJsonObject("serverProfileBindings");
                for (Map.Entry<String, JsonElement> e2 : bindings.entrySet()) {
                    c.serverProfileBindings.put(e2.getKey(), e2.getValue().getAsString());
                }
            }
            INSTANCE = c;
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("Failed to load KitsuneConfig, using defaults", e);
            INSTANCE = new KitsuneConfig();
        }
        applyRuntimeSettings();
    }

    /** Push runtime-only state (anchors, etc.) from the loaded config into the
     *  systems that consume it. Safe to call after every load. */
    private static void applyRuntimeSettings() {
        if (INSTANCE == null) return;
        try {
            dev.kitsune.client.hud.NotificationManager.setAnchor(
                    dev.kitsune.client.hud.HudManager.Anchor.valueOf(INSTANCE.notificationAnchor));
        } catch (IllegalArgumentException ignored) {
            // Fall back silently; the field already defaulted to BOTTOM_RIGHT.
        }
    }

    /**
     * Walk the raw JSON through each migration step in order.
     * Each case transforms a v{n} file into a v{n+1} file in place.
     */
    private static JsonObject migrate(JsonObject o, int fromVersion) {
        int v = fromVersion;
        // v0 -> v1: no field renames, just stamp the version.
        if (v < 1) {
            v = 1;
        }
        // future: if (v < 2) { ... rename/transform ... v = 2; }
        o.addProperty("schemaVersion", v);
        return o;
    }

    public static void save() {
        if (INSTANCE == null) return;
        try {
            Files.createDirectories(file().getParent());
            JsonObject o = new JsonObject();
            o.addProperty("schemaVersion", CURRENT_VERSION);
            o.addProperty("foxTitleScreen", INSTANCE.foxTitleScreen);
            o.addProperty("autoCheckServerRules", INSTANCE.autoCheckServerRules);
            o.addProperty("autoReconnectAfterRestart", INSTANCE.autoReconnectAfterRestart);
            o.addProperty("adaptiveFpsLimit", INSTANCE.adaptiveFpsLimit);
            o.addProperty("backgroundFpsLimit", INSTANCE.backgroundFpsLimit);
            o.addProperty("foregroundFpsLimit", INSTANCE.foregroundFpsLimit);
            o.addProperty("showWatermark", INSTANCE.showWatermark);
            o.addProperty("notificationAnchor", INSTANCE.notificationAnchor);
            o.addProperty("selectedCapeId",     INSTANCE.selectedCapeId);
            JsonObject bindings = new JsonObject();
            for (Map.Entry<String, String> e2 : INSTANCE.serverProfileBindings.entrySet()) {
                bindings.addProperty(e2.getKey(), e2.getValue());
            }
            o.add("serverProfileBindings", bindings);
            Files.writeString(file(), GSON.toJson(o));
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to save KitsuneConfig", e);
        }
    }
}
