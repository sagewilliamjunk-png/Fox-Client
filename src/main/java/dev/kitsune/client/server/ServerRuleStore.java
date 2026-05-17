package dev.kitsune.client.server;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.addon.AddonCatalog;
import dev.kitsune.client.addon.AddonFlags;
import dev.kitsune.client.core.ModJarSwapper;
import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.hud.NotificationManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists and matches {@link ServerRule}s.
 *
 * Storage: {@code config/kitsune/server_rules.json}
 *
 * Ships with a small starter set of rules for the largest known servers
 * (see {@link #defaults()}). Users add/remove their own rules via the
 * {@code PerServerRulesScreen}.
 */
public class ServerRuleStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<ServerRule> RULES = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve(KitsuneClient.MOD_ID).resolve("server_rules.json");
    }

    public static List<ServerRule> all() {
        return Collections.unmodifiableList(new ArrayList<>(RULES));
    }

    public static void add(ServerRule r) { RULES.add(r); save(); }
    public static void remove(ServerRule r) { RULES.remove(r); save(); }

    /** All rules whose host pattern matches the given server address (host:port). */
    public static List<ServerRule> matchesFor(String serverAddress) {
        String host = stripPort(serverAddress);
        List<ServerRule> out = new ArrayList<>();
        for (ServerRule r : RULES) if (r.matches(host)) out.add(r);
        return out;
    }

    private static String stripPort(String addr) {
        if (addr == null) return "";
        int colon = addr.lastIndexOf(':');
        return colon < 0 ? addr : addr.substring(0, colon);
    }

    /**
     * Compute which currently-loaded mods would need to be disabled to satisfy
     * all matching rules for a server. Used to drive the restart prompt.
     */
    public static Set<String> modsToDisableFor(String serverAddress) {
        Set<String> needed = new HashSet<>();
        for (ServerRule r : matchesFor(serverAddress)) {
            if (r.action != ServerRule.Action.DISABLE) continue;
            for (String mod : r.modIds) {
                if (ModJarSwapper.isLoaded(mod)) needed.add(mod);
            }
        }
        return needed;
    }

    /** Apply runtime feature overrides for the given server. Called on connect. */
    public static void applyFeatureOverridesFor(String serverAddress) {
        for (ServerRule r : matchesFor(serverAddress)) {
            if (r.action != ServerRule.Action.DISABLE) continue;

            // Legacy FoxFeature runtime overrides.
            for (String fid : r.featureIds) {
                FeatureRegistry.setServerOverride(fid, false);
            }

            // Addon/grayzone flags cannot be changed at runtime (they're read at
            // mod init). Warn the player if any banned addons are currently active
            // so they know to disable them in their profile before rejoining.
            for (String aid : r.addonIds) {
                if (!AddonFlags.isAddonEnabled(aid)) continue; // already off — silent
                NotificationManager.show(
                        r.name + ": " + AddonCatalog.displayName(aid)
                        + " is enabled — disable it in your profile settings",
                        NotificationManager.Type.WARNING);
            }
        }
    }

    /** Clear all runtime feature overrides. Called on disconnect. */
    public static void clearFeatureOverrides() {
        FeatureRegistry.clearServerOverrides();
    }

    public static void load() {
        Path f = file();
        RULES.clear();
        if (!Files.exists(f)) {
            RULES.addAll(defaults());
            save();
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (root.has("rules")) {
                for (JsonElement el : root.getAsJsonArray("rules")) {
                    RULES.add(ServerRule.fromJson(el.getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("Failed to load server_rules.json", e);
            RULES.addAll(defaults());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ServerRule r : RULES) arr.add(r.toJson());
            root.add("rules", arr);
            Files.writeString(file(), GSON.toJson(root));
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to save server_rules.json", e);
        }
    }

    /**
     * Conservative starter rules. These reflect publicly-stated bans on the
     * largest servers as of writing — users should review and add their own.
     * Fox Client makes no guarantee these are complete or up to date; the
     * SAFETY.md doc explains the disclaimer.
     */
    private static List<ServerRule> defaults() {
        List<ServerRule> out = new ArrayList<>();

        // Hypixel — broad mod restrictions, only "approved" client mods allowed.
        ServerRule hypixel = new ServerRule("Hypixel", "*.hypixel.net", ServerRule.Action.DISABLE);
        hypixel.note = "Hypixel only permits a curated allowlist of client mods. Disabling commonly-banned categories.";
        Collections.addAll(hypixel.modIds, "freecam", "minimap", "xaeros_minimap", "xaerosworldmap");
        Collections.addAll(hypixel.featureIds, "armor_trims_hud", "simple_culling");
        out.add(hypixel);

        // Mineplex — similar restrictions
        ServerRule mineplex = new ServerRule("Mineplex", "*.mineplex.com", ServerRule.Action.DISABLE);
        mineplex.note = "Mineplex bans most overlay/HUD mods on minigames.";
        Collections.addAll(mineplex.modIds, "freecam", "minimap", "xaeros_minimap");
        out.add(mineplex);

        // FlowPvP — competitive PvP server with a published banned-mods list.
        // Third-party mod JARs are moved out automatically; Fox Client's own
        // grayzone modules (Anti-AFK, Free Look, Reach, Hitboxes) cannot be
        // disabled at runtime and will show a warning toast if still active.
        ServerRule flowPvp = new ServerRule("FlowPvP", "*flowpvp.gg", ServerRule.Action.DISABLE);
        flowPvp.note = "FlowPvP bans movement automation, minimaps, freecam, schematic mods, "
                + "reach/hitbox display, and input-modifying mods. "
                + "Disable Anti-AFK, Free Look, Reach Display, and Hitboxes in your profile "
                + "settings before joining to avoid a warning.";
        Collections.addAll(flowPvp.modIds,
                // Freecam / camera detach
                "freecam",
                // Minimaps (all known variants)
                "xaeros_minimap", "minimap-fabric", "voxelmap", "voxelmap-updated",
                // X-Ray
                "xray",
                // Schematic / builder tools
                "litematica", "tweakaroo",
                // Inventory management (auto-sort / auto-refill touches inventory packets)
                "inventoryprofilesnext", "itemscroller", "mousetweaks",
                // Version-spoof / packet manipulation
                "viafabricplus",
                // VR — can bypass normal input constraints
                "vivecraft",
                // Controller input (can register as macro input)
                "midnightcontrols",
                // Camera decoupling (same category as Free Look)
                "shoulder-surfing-reloaded",
                // Anti-cheat bypass / sync manipulation
                "syncac",
                // Bedrock-edition feature backports (some affect hitboxes / movement)
                "bedrockify",
                // No-input-lag / tick-rate patches
                "debugify",
                // ESP / loot radar equivalents
                "lootbeams",
                // Crystal / combat automation
                "clickcrystals",
                // Block placement accuracy mods
                "accurateblockplacement"
        );
        // Fox Client grayzone addons that FlowPvP bans — warns on connect if active.
        Collections.addAll(flowPvp.addonIds,
                AddonCatalog.GRAYZONE_ANTI_AFK,
                AddonCatalog.GRAYZONE_FREE_LOOK,
                AddonCatalog.GRAYZONE_REACH_HUD,
                AddonCatalog.GRAYZONE_HITBOXES
        );
        out.add(flowPvp);

        // 2b2t — anarchy, but they have an official ban list (no x-ray, no fly, etc.)
        // Fox Client never ships those features in the first place, so no rule needed.

        // Generic warning rule for "vanilla anarchy" servers — informational only
        ServerRule anarchyWarn = new ServerRule("Anarchy server warning", "2b2t.org", ServerRule.Action.WARN);
        anarchyWarn.note = "Anarchy server. Fox Client ships no x-ray/fly/killaura, but double-check your mods folder.";
        out.add(anarchyWarn);

        return out;
    }
}
