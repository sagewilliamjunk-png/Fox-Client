package dev.kitsune.client.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command aliases. User-defined shorthand that expands when typed as the
 * first word of a chat or slash command. Expansions may chain multiple
 * commands separated by {@code ;}.
 *
 * <p>Persisted to {@code configDir/kitsune/aliases.json}.
 */
public final class AliasManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static boolean loaded = false;

    private AliasManager() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune").resolve("aliases.json");
    }

    public static synchronized void load() {
        Path f = file();
        ALIASES.clear();
        if (!Files.exists(f)) { loaded = true; return; }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (var e : obj.entrySet()) {
                ALIASES.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            KitsuneClient.LOGGER.warn("[Aliases] Failed to load: {}", e.getMessage());
        }
        loaded = true;
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject obj = new JsonObject();
            for (var e : ALIASES.entrySet()) obj.addProperty(e.getKey(), e.getValue());
            Files.writeString(file(), GSON.toJson(obj));
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[Aliases] Failed to save: {}", e.getMessage());
        }
    }

    public static void set(String name, String expansion) {
        if (!loaded) load();
        ALIASES.put(name.toLowerCase(), expansion);
        save();
    }

    public static boolean remove(String name) {
        if (!loaded) load();
        boolean removed = ALIASES.remove(name.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    public static Map<String, String> all() {
        if (!loaded) load();
        return Collections.unmodifiableMap(ALIASES);
    }

    /**
     * Attempt to expand {@code input} if its first token matches an alias.
     * Returns the expansion (may contain {@code ;}-separated commands) or
     * {@code null} if no alias matches.
     *
     * <p>Supports positional substitution: {@code $1 $2 ... $*} in the
     * expansion are replaced with the user-provided arguments.
     */
    public static String expand(String input) {
        if (!loaded) load();
        if (input == null || input.isEmpty()) return null;
        String trimmed = input.startsWith("/") ? input.substring(1) : input;
        int sp = trimmed.indexOf(' ');
        String head = (sp < 0 ? trimmed : trimmed.substring(0, sp)).toLowerCase();
        String expansion = ALIASES.get(head);
        if (expansion == null) return null;

        String rest = sp < 0 ? "" : trimmed.substring(sp + 1);
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");

        StringBuilder out = new StringBuilder(expansion);
        // $* → all args joined
        int idx;
        while ((idx = out.indexOf("$*")) >= 0) {
            out.replace(idx, idx + 2, rest);
        }
        // $1..$9 → positional
        for (int i = 1; i <= 9; i++) {
            String placeholder = "$" + i;
            String value = (i <= args.length) ? args[i - 1] : "";
            int start;
            while ((start = out.indexOf(placeholder)) >= 0) {
                out.replace(start, start + placeholder.length(), value);
            }
        }
        return out.toString();
    }
}
