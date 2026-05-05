package dev.kitsune.client.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Global set of "favorited" module names. Favorited modules sort to the top
 * of their ClickGUI panel and render with a small gold star marker.
 *
 * <p>Persisted to {@code config/kitsune/favorites.json}. Simple JSON array.
 */
public final class ModuleFavorites {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> FAVORITES = new LinkedHashSet<>();
    private static boolean loaded = false;

    private ModuleFavorites() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune").resolve("favorites.json");
    }

    public static synchronized void load() {
        Path f = file();
        FAVORITES.clear();
        if (!Files.exists(f)) { loaded = true; return; }
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
            arr.forEach(el -> FAVORITES.add(el.getAsString()));
        } catch (Exception e) {
            KitsuneClient.LOGGER.warn("[Favorites] Load failed: {}", e.getMessage());
        }
        loaded = true;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonArray arr = new JsonArray();
            for (String s : FAVORITES) arr.add(s);
            Files.writeString(file(), GSON.toJson(arr));
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[Favorites] Save failed: {}", e.getMessage());
        }
    }

    public static boolean isFavorite(Module m) {
        if (!loaded) load();
        return FAVORITES.contains(m.name());
    }

    public static void toggle(Module m) {
        if (!loaded) load();
        if (FAVORITES.contains(m.name())) {
            FAVORITES.remove(m.name());
        } else {
            FAVORITES.add(m.name());
        }
        save();
    }

    public static Set<String> all() {
        if (!loaded) load();
        return Collections.unmodifiableSet(FAVORITES);
    }
}
