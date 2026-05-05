package dev.kitsune.client.addon;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-profile feature gates for the modules Fox Client groups under
 * "addons" — gray-zone or otherwise opt-in features that some users
 * (or some servers) want completely off.
 *
 * <p>Storage: a single JSON file at {@code <configDir>/kitsune/addons.json}
 * which the Fox Launcher writes immediately before launch based on the
 * active profile's choices. Schema:
 * <pre>{@code
 *   { "disabled": ["grayzone.anti_afk", "grayzone.free_look"] }
 * }</pre>
 *
 * <p>An addon flag's <em>id</em> is namespaced as {@code <group>.<short_name>}
 * so future addon families (e.g. {@code visual.*}, {@code experimental.*})
 * stay grouped. Modules consult {@link #isAddonEnabled(String)} at
 * registration time; when an addon is disabled, the module is never
 * inserted into {@link dev.kitsune.client.module.ModuleManager}, so it
 * doesn't tick, render, post events, or appear in the ClickGUI.
 *
 * <p>Loaded once on client init via {@link #reload()}. The launcher's
 * profile-switch flow already requires a restart to apply, matching the
 * mod-toggle UX, so on-the-fly reloading is intentionally not supported.
 */
public final class AddonFlags {

    private static volatile Set<String> DISABLED = Collections.emptySet();
    private static volatile boolean LOADED = false;

    private AddonFlags() {}

    /** Read the addons.json from disk. Safe to call multiple times. */
    public static synchronized void reload() {
        Set<String> next = new HashSet<>();
        Path file = file();
        if (Files.exists(file)) {
            try {
                String raw = Files.readString(file);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("disabled") && root.get("disabled").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("disabled")) {
                        if (el.isJsonPrimitive()) next.add(el.getAsString());
                    }
                }
            } catch (Exception ex) {
                KitsuneClient.LOGGER.warn("[Fox] addons.json unreadable, defaulting to all enabled: {}", ex.toString());
            }
        }
        DISABLED = Collections.unmodifiableSet(next);
        LOADED = true;
        KitsuneClient.LOGGER.info("[Fox] addons.json: {} disabled flag(s)", DISABLED.size());
    }

    /** Is this addon enabled? Defaults to true for any unknown id. */
    public static boolean isAddonEnabled(String id) {
        if (!LOADED) reload();
        return id == null || !DISABLED.contains(id);
    }

    public static Set<String> disabledIds() { return DISABLED; }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune").resolve("addons.json");
    }
}
