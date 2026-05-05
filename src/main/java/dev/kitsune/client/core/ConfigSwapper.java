package dev.kitsune.client.core;

import dev.kitsune.client.KitsuneClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks a list of mod config file names (relative to {@code .minecraft/config/}).
 * For each tracked file, stores one snapshot per profile under
 * {@code .minecraft/config/kitsune/profiles/<profile>/<file>}.
 *
 * When the active profile switches, the matching snapshot is copied back over
 * the real config file.
 *
 * Generalized from the modeswitch {@code ModConfigSwapper}, which only handled
 * two hardcoded modes (PVP/Survival).
 */
public class ConfigSwapper {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<String> trackedFiles = new ArrayList<>();

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static Path foxDir() {
        return configDir().resolve(KitsuneClient.MOD_ID);
    }

    private static Path profilesRoot() {
        return foxDir().resolve("profiles");
    }

    private static Path profileDir(String profileName) {
        return profilesRoot().resolve(profileName);
    }

    private static Path trackedListFile() {
        return foxDir().resolve("tracked_configs.json");
    }

    public static List<String> getTrackedFiles() {
        return new ArrayList<>(trackedFiles);
    }

    public static void addTrackedFile(String relativePath) {
        if (!trackedFiles.contains(relativePath)) {
            trackedFiles.add(relativePath);
            saveTrackedList();
        }
    }

    public static void removeTrackedFile(String relativePath) {
        trackedFiles.remove(relativePath);
        saveTrackedList();
        // Clean up snapshots in every profile dir
        for (String name : ProfileManager.getProfileNames()) {
            try {
                Files.deleteIfExists(profileDir(name).resolve(relativePath));
            } catch (IOException ignored) {}
        }
    }

    /** Capture every tracked file's current state into the given profile's snapshot dir. */
    public static void captureForProfile(String profileName) {
        Path target = profileDir(profileName);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to create snapshot dir for " + profileName, e);
            return;
        }
        for (String f : trackedFiles) {
            Path src = configDir().resolve(f);
            Path dst = target.resolve(f);
            if (!Files.exists(src)) continue;
            try {
                if (dst.getParent() != null) Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                KitsuneClient.LOGGER.error("Failed to capture " + f + " for " + profileName, e);
            }
        }
    }

    /** Apply (overwrite real config files with) the given profile's snapshots. */
    public static void applyForProfile(String profileName) {
        Path src = profileDir(profileName);
        if (!Files.exists(src)) return;
        for (String f : trackedFiles) {
            Path s = src.resolve(f);
            Path d = configDir().resolve(f);
            if (!Files.exists(s)) continue;
            try {
                if (d.getParent() != null) Files.createDirectories(d.getParent());
                Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                KitsuneClient.LOGGER.error("Failed to apply " + f + " from " + profileName, e);
            }
        }
    }

    public static void saveTrackedList() {
        try {
            Files.createDirectories(foxDir());
            JsonArray arr = new JsonArray();
            for (String s : trackedFiles) arr.add(s);
            Files.writeString(trackedListFile(), GSON.toJson(arr));
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to save tracked config list", e);
        }
    }

    public static void loadTrackedList() {
        Path file = trackedListFile();
        trackedFiles.clear();
        if (!Files.exists(file)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement el : arr) trackedFiles.add(el.getAsString());
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("Failed to load tracked config list", e);
        }
    }
}
