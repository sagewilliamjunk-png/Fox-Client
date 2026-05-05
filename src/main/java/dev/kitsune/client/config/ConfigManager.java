package dev.kitsune.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.setting.SettingManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages named module profiles on disk at
 * {@code <.minecraft>/config/kitsune/profiles/<name>.json}.
 *
 * <p>A profile snapshots every registered module's enabled flag, keybind,
 * and setting values. On load, each module is reconstituted by name; unknown
 * modules in the file are ignored (forwards-compat).
 *
 * <p>Uses the hash-skip pattern to avoid redundant disk writes — {@link #saveProfile}
 * is a no-op when the serialized JSON matches the last write.
 */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_PROFILE = "default";
    private static final String ACTIVE_FILE = "active_profile.txt";

    private static String activeProfile = DEFAULT_PROFILE;
    private static String lastSerialized = null;

    private ConfigManager() {}

    public static void init() {
        try {
            Files.createDirectories(profilesDir());
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("[Fox] failed to create profiles dir", e);
        }
        activeProfile = readActiveName();
        Path p = profilePath(activeProfile);
        if (!Files.exists(p)) {
            // First run — snapshot current module state as "default"
            saveProfile(activeProfile);
        } else {
            // Subsequent runs — restore saved module states from disk
            loadProfile(activeProfile);
        }
        KitsuneClient.LOGGER.info("[Fox] ConfigManager init (active profile: {})", activeProfile);
    }

    public static void loadActiveProfile() {
        loadProfile(activeProfile);
    }

    public static void loadProfile(String name) {
        Path p = profilePath(name);
        if (!Files.exists(p)) {
            KitsuneClient.LOGGER.warn("[Fox] profile '{}' not found, ignoring", name);
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            JsonArray modulesArr = root.has("modules") ? root.getAsJsonArray("modules") : new JsonArray();
            for (var el : modulesArr) {
                if (!el.isJsonObject()) continue;
                JsonObject mod = el.getAsJsonObject();
                String modName = mod.get("name").getAsString();
                Module m = ModuleManager.getByName(modName);
                if (m == null) continue;
                if (mod.has("enabled")) m.setEnabled(mod.get("enabled").getAsBoolean());
                if (mod.has("keyBind")) m.setKeyBind(mod.get("keyBind").getAsInt());
                if (mod.has("settings")) {
                    SettingManager.deserialize(m.settings(), mod.getAsJsonArray("settings"));
                }
            }
            activeProfile = name;
            writeActiveName(name);
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("[Fox] failed to load profile '{}'", name, e);
        }
    }

    public static void saveProfile(String name) {
        JsonObject root = new JsonObject();
        JsonArray modulesArr = new JsonArray();
        for (Module m : ModuleManager.all()) {
            JsonObject mod = new JsonObject();
            mod.addProperty("name", m.name());
            mod.addProperty("enabled", m.isEnabled());
            mod.addProperty("keyBind", m.keyBind());
            mod.add("settings", SettingManager.serialize(m.settings()));
            modulesArr.add(mod);
        }
        root.add("modules", modulesArr);

        String json = GSON.toJson(root);
        if (json.equals(lastSerialized)) return; // hash-skip
        lastSerialized = json;

        try {
            Files.createDirectories(profilesDir());
            writeAtomic(profilePath(name), json);
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("[Fox] failed to save profile '{}'", name, e);
        }
    }

    /**
     * Write-to-temp then atomic move. Crashing or losing power mid-write leaves
     * either the old file intact or the new file complete — never a partial JSON
     * that fails to parse on next launch.
     */
    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            // Some filesystems (e.g. certain network mounts) don't support ATOMIC_MOVE.
            // Fall back to a plain replace — still safer than a torn file.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteProfile(String name) {
        if (DEFAULT_PROFILE.equals(name)) return;
        try {
            Files.deleteIfExists(profilePath(name));
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("[Fox] failed to delete profile '{}'", name, e);
        }
    }

    public static List<String> listProfiles() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(profilesDir())) return out;
        try (Stream<Path> s = Files.list(profilesDir())) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    public static String getActiveProfile() {
        return activeProfile;
    }

    public static void setActiveProfile(String name) {
        activeProfile = name;
        writeActiveName(name);
    }

    // ---- paths ----
    private static Path kitsuneDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(KitsuneClient.MOD_ID);
    }

    private static Path profilesDir() {
        return kitsuneDir().resolve("profiles");
    }

    private static Path profilePath(String name) {
        return profilesDir().resolve(name + ".json");
    }

    private static Path activeFile() {
        return kitsuneDir().resolve(ACTIVE_FILE);
    }

    private static String readActiveName() {
        try {
            if (Files.exists(activeFile())) {
                String s = Files.readString(activeFile()).trim();
                if (!s.isEmpty()) return s;
            }
        } catch (IOException ignored) {}
        return DEFAULT_PROFILE;
    }

    private static void writeActiveName(String name) {
        try {
            Files.createDirectories(kitsuneDir());
            writeAtomic(activeFile(), name);
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("[Fox] failed to write active_profile.txt", e);
        }
    }
}
