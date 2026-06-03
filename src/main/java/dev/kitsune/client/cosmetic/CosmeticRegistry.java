package dev.kitsune.client.cosmetic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kitsune.client.KitsuneClient;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for Fox Client cosmetics.
 *
 * <p>Source of truth is a single JSON manifest shipped inside the mod jar at
 * {@code assets/kitsune/cosmetic-owners.json}. The schema is:
 * <pre>{@code
 * {
 *   "capes": {
 *     "<cape_id>": { "displayName": "Fox" }
 *   },
 *   "owners": {
 *     "<player_uuid>": ["cape_id_a", "cape_id_b"]
 *   }
 * }
 * }</pre>
 *
 * <p>UUIDs are accepted in any case and with or without dashes.
 *
 * <p>The registry is loaded once at boot and is intentionally read-only at
 * runtime: future cape grants ship in mod updates. This keeps cosmetic
 * delivery free of any backend or runtime network call. Selection (which of
 * a player's owned capes is currently shown for the local user) lives in
 * {@link dev.kitsune.client.core.KitsuneConfig} as a free-form string.
 */
public final class CosmeticRegistry {

    private static final Identifier MANIFEST = Identifier.fromNamespaceAndPath(
            KitsuneClient.MOD_ID, "cosmetic-owners.json");

    /** capeId → display name (insertion-order stable so the cape picker is predictable). */
    private static final Map<String, String> CAPES = new java.util.LinkedHashMap<>();

    /** UUID → set of cape ids owned by that player. */
    private static final Map<UUID, Set<String>> OWNERSHIP = new ConcurrentHashMap<>();

    /** Cached "is owner of any cape" lookup so the per-frame mixin is O(1). */
    private static final Set<UUID> ANY_OWNER = ConcurrentHashMap.newKeySet();

    /** Capes granted to ALL players via the special "*" owner key. */
    private static final Set<String> GLOBAL_CAPES = ConcurrentHashMap.newKeySet();

    private static volatile boolean loaded = false;

    private CosmeticRegistry() {}

    /**
     * Reload from the resource manager. Called once on client init and again
     * whenever resource packs reload. Failures degrade silently — no cosmetics
     * is a recoverable state, an exception in client init is not.
     */
    public static void reload(ResourceManager rm) {
        CAPES.clear();
        OWNERSHIP.clear();
        ANY_OWNER.clear();
        GLOBAL_CAPES.clear();
        loaded = true;

        if (rm == null) return;
        var opt = rm.getResource(MANIFEST);
        if (opt.isEmpty()) {
            KitsuneClient.LOGGER.info("[Fox] cosmetic-owners.json not present — no cosmetics will load");
            return;
        }
        try {
            parse(opt.get());
        } catch (Exception e) {
            KitsuneClient.LOGGER.warn("[Fox] failed to parse cosmetic-owners.json: {}", e.toString());
        }
    }

    private static void parse(Resource res) throws IOException {
        try (InputStream in = res.open()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();

            if (root.has("capes") && root.get("capes").isJsonObject()) {
                JsonObject capes = root.getAsJsonObject("capes");
                for (var e : capes.entrySet()) {
                    String id = e.getKey();
                    if (!isValidCapeId(id)) continue;
                    String displayName = id;
                    if (e.getValue().isJsonObject()) {
                        var dn = e.getValue().getAsJsonObject().get("displayName");
                        if (dn != null && dn.isJsonPrimitive()) displayName = dn.getAsString();
                    }
                    CAPES.put(id, displayName);
                }
            }

            if (root.has("owners") && root.get("owners").isJsonObject()) {
                JsonObject owners = root.getAsJsonObject("owners");
                for (var e : owners.entrySet()) {
                    JsonElement v = e.getValue();
                    Set<String> capeSet = new HashSet<>();
                    if (v.isJsonArray()) {
                        for (JsonElement item : v.getAsJsonArray()) {
                            if (!item.isJsonPrimitive()) continue;
                            String capeId = item.getAsString();
                            if (CAPES.containsKey(capeId)) capeSet.add(capeId);
                        }
                    }
                    if (capeSet.isEmpty()) continue;

                    // Special wildcard key "*" — grant these capes to every player.
                    if ("*".equals(e.getKey())) {
                        GLOBAL_CAPES.addAll(capeSet);
                        continue;
                    }

                    UUID uuid = parseUuid(e.getKey());
                    if (uuid == null) continue;
                    OWNERSHIP.put(uuid, capeSet);
                    ANY_OWNER.add(uuid);
                }
            }
            // Verbose-but-bounded summary so the user (and future devs) can
            // diagnose "my cape doesn't show up" without enabling DEBUG logs.
            String capeIds = String.join(",", CAPES.keySet());
            String sampleOwners = OWNERSHIP.keySet().stream()
                    .limit(3)
                    .map(UUID::toString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(none)");
            KitsuneClient.LOGGER.info(
                    "[Fox] cosmetics: {} cape(s) [{}], {} owner(s), {} globally-granted cape(s), sample owners: {}",
                    CAPES.size(), capeIds, OWNERSHIP.size(), GLOBAL_CAPES.size(), sampleOwners);
        }
    }

    /**
     * Logical id for a cape, intended for use as the argument to
     * {@link net.minecraft.core.ClientAsset.ResourceTexture}'s single-arg
     * constructor. That constructor derives the texture path via
     * {@code id.withPath(s -> "textures/" + s + ".png")}, so we MUST pass the
     * <em>short</em> id ({@code kitsune:cape/<capeId>}) — not the full path.
     *
     * <p>Passing the full path produced
     * {@code kitsune:textures/textures/cape/<id>.png.png}, which doesn't exist
     * in the jar, so MC fell back to the pink/black missing-texture pattern —
     * that's the "cape renders as black and purple" bug.
     *
     * <p>The PNG must live at {@code assets/kitsune/textures/cape/<id>.png}
     * and follow the standard 64×32 Minecraft cape layout (or any 2:1 HD
     * multiple — 128×64, 256×128).
     */
    public static Identifier capeTexture(String capeId) {
        return Identifier.fromNamespaceAndPath(KitsuneClient.MOD_ID, "cape/" + capeId);
    }

    /** Display name for a cape, or the id itself if unknown. */
    public static String capeDisplayName(String capeId) {
        if (capeId == null) return "—";
        return CAPES.getOrDefault(capeId, capeId);
    }

    /** Returns every defined cape id in registration order. */
    public static List<String> allCapeIds() {
        return List.copyOf(CAPES.keySet());
    }

    /** Quick yes/no — used inside the per-frame cape mixin. O(1) lookup. */
    public static boolean isOwner(UUID uuid) {
        if (uuid == null) return false;
        return !GLOBAL_CAPES.isEmpty() || ANY_OWNER.contains(uuid);
    }

    /** Cape ids owned by the given uuid; includes globally-granted capes. */
    public static Set<String> capesOwnedBy(UUID uuid) {
        if (uuid == null) return Collections.emptySet();
        Set<String> perUser = OWNERSHIP.getOrDefault(uuid, Collections.emptySet());
        if (GLOBAL_CAPES.isEmpty()) return perUser;
        if (perUser.isEmpty()) return Collections.unmodifiableSet(GLOBAL_CAPES);
        // Merge global + per-user capes
        Set<String> merged = new HashSet<>(GLOBAL_CAPES);
        merged.addAll(perUser);
        return Collections.unmodifiableSet(merged);
    }

    /** Whether reload() has run at least once (so callers can avoid redundant
     *  work when called before client resources are ready). */
    public static boolean isLoaded() { return loaded; }

    // ---- helpers ----

    private static boolean isValidCapeId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-') return false;
        }
        return true;
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        s = s.trim();
        try {
            if (s.length() == 32) {
                // dashless UUID — re-insert dashes
                s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                  + s.substring(12, 16) + "-" + s.substring(16, 20) + "-"
                  + s.substring(20);
            }
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
