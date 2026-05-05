package dev.kitsune.client.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Import/export a single {@link Profile} as a self-contained JSON file so users
 * can share configs between installs. Export writes to
 * {@code config/kitsune/exports/<name>-<timestamp>.json}; import opens a native
 * file picker via LWJGL {@link TinyFileDialogs} and merges the file into a
 * newly-created profile.
 *
 * <p>Returned strings from every method are suitable for display in a toast.
 */
public final class ProfileIO {
    private ProfileIO() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static Path exportsDir() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(KitsuneClient.MOD_ID).resolve("exports");
    }

    /** Exports {@code profileName} — or the active profile if null — to the exports dir. */
    public static Result exportProfile(String profileName) {
        Profile p = profileName == null
                ? ProfileManager.getActiveProfile()
                : ProfileManager.get(profileName);
        if (p == null) return Result.error("No profile named '" + profileName + "'");
        try {
            Files.createDirectories(exportsDir());
            String stamp = LocalDateTime.now().format(STAMP);
            Path out = exportsDir().resolve(p.getName() + "-" + stamp + ".json");
            JsonObject envelope = new JsonObject();
            envelope.addProperty("kitsune_export", 1);
            envelope.addProperty("exported_at", stamp);
            envelope.add("profile", p.toJson());
            Files.writeString(out, GSON.toJson(envelope));
            return Result.ok("Exported to " + out.getFileName());
        } catch (IOException ex) {
            KitsuneClient.LOGGER.warn("[Fox] profile export failed", ex);
            return Result.error("Export failed: " + ex.getMessage());
        }
    }

    /**
     * Opens a native open-file dialog, parses the picked JSON, and registers
     * it as a new profile. If the name collides with an existing profile, an
     * incrementing numeric suffix is appended.
     *
     * <p>Safe to call from the client thread — the native dialog blocks that
     * thread briefly, which is acceptable here (happens from the Fox Settings
     * screen only, no gameplay in progress).
     */
    public static Result importProfile() {
        String picked;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json"));
            filters.flip();
            picked = TinyFileDialogs.tinyfd_openFileDialog(
                    "Import Fox Client Profile",
                    null,
                    filters,
                    "JSON files",
                    false
            );
        } catch (Throwable t) {
            KitsuneClient.LOGGER.warn("[Fox] file picker failed", t);
            return Result.error("File picker unavailable on this platform");
        }
        if (picked == null || picked.isEmpty()) return Result.error("Import cancelled");

        try {
            String text = Files.readString(Path.of(picked));
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject profileJson;
            if (root.has("kitsune_export") && root.has("profile")) {
                profileJson = root.getAsJsonObject("profile");
            } else if (root.has("name")) {
                // Allow importing a raw profile JSON (no envelope)
                profileJson = root;
            } else {
                return Result.error("Not a Fox Client profile file");
            }

            Profile imported = Profile.fromJson(profileJson);
            String finalName = uniqueName(imported.getName());

            // Create empty slot, then copy imported field values into it.
            Profile slot = ProfileManager.create(finalName);
            if (slot == null) return Result.error("Could not create profile slot");
            copyFields(imported, slot);
            ProfileManager.save();

            return Result.ok("Imported profile '" + finalName + "'");
        } catch (Exception ex) {
            KitsuneClient.LOGGER.warn("[Fox] profile import failed", ex);
            return Result.error("Import failed: " + ex.getMessage());
        }
    }

    private static String uniqueName(String baseName) {
        if (!ProfileManager.exists(baseName)) return baseName;
        for (int i = 2; i < 100; i++) {
            String candidate = baseName + "-" + i;
            if (!ProfileManager.exists(candidate)) return candidate;
        }
        return baseName + "-" + System.currentTimeMillis();
    }

    /** Copy every instance field on Profile from {@code src} → {@code dst}, preserving dst's name. */
    private static void copyFields(Profile src, Profile dst) {
        try {
            for (java.lang.reflect.Field f : Profile.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equals("name")) continue;
                f.setAccessible(true);
                try { f.set(dst, f.get(src)); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            KitsuneClient.LOGGER.warn("[Fox] profile copyFields failed: {}", t.toString());
        }
    }

    /** Small result object — each operation returns a message suitable for a toast. */
    public record Result(boolean ok, String message) {
        public static Result ok(String m)    { return new Result(true, m); }
        public static Result error(String m) { return new Result(false, m); }
    }
}
