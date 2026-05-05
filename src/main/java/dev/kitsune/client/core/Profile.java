package dev.kitsune.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A named, serializable profile of Fox Client settings.
 *
 * Holds:
 *   - vanilla MC option overrides (render distance, FOV, gamma, sound volumes, etc.)
 *   - which Fox features are enabled
 *   - which mod IDs should be disabled when this profile is active (for the
 *     per-server / per-profile mod toggle system)
 *
 * Persisted to config/kitsune/profiles.json by {@link ProfileManager}.
 *
 * Generalized from the original modeswitch ModeProfile (which hardcoded
 * exactly two profiles, "PVP" and "Survival"). Field set is intentionally
 * the same so the existing JSON can be migrated by renaming.
 */
public class Profile {

    private String name;

    // ----- Vanilla MC option overrides -----
    // Video
    public int renderDistance = 12;
    public int simulationDistance = 12;
    public int fov = 70;
    public int guiScale = 0;
    public double gamma = 0.5;
    public boolean fullscreen = false;
    public int maxFps = 120;
    public int particles = 0;     // 0=ALL, 1=DECREASED, 2=MINIMAL
    public int clouds = 2;        // 0=OFF, 1=FAST, 2=FANCY
    public boolean entityShadows = true;
    public double entityDistance = 1.0;
    public double fovEffectScale = 1.0;
    public double screenEffectScale = 1.0;
    public boolean bobView = true;
    public double damageTilt = 1.0;

    // Audio
    public float masterVolume = 1.0f;
    public float musicVolume = 1.0f;
    public float playerVolume = 1.0f;
    public float hostileVolume = 1.0f;
    public float ambientVolume = 1.0f;

    // Chat
    public int chatVisibility = 0;
    public double chatOpacity = 1.0;
    public double chatScale = 1.0;
    public double chatWidth = 1.0;
    public double chatHeightFocused = 1.0;
    public double chatHeightUnfocused = 0.44;
    public boolean chatColors = true;
    public boolean chatLinks = true;
    public boolean autoSuggestions = true;

    // Controls
    public double mouseSensitivity = 0.5;
    public boolean autoJump = false;
    public boolean toggleSprint = false;
    public boolean toggleCrouch = false;
    public int attackIndicator = 1;

    // Resource packs to enable when this profile is active
    public List<String> enabledResourcePacks = new ArrayList<>();

    // ----- Fox Client extras -----

    /**
     * Per-feature enabled state. Key = feature id (see {@link dev.kitsune.client.features.FoxFeature#id()}),
     * value = enabled. Missing key means "use feature's default".
     */
    public Map<String, Boolean> featureEnabled = new HashMap<>();

    /**
     * Per-native-module enabled state. Key = {@code Module.name()} lowercased,
     * value = enabled. Missing key means "leave the module's current state alone"
     * (so newly added modules don't get force-disabled when an old profile is loaded).
     */
    public Map<String, Boolean> moduleEnabled = new HashMap<>();

    /**
     * Mod IDs that should be disabled (jar moved to disabled folder) when this profile
     * is the active profile at game launch. Used by the per-profile mod toggle system.
     * Mod IDs match Fabric mod IDs (e.g. "freecam", "minimap").
     */
    public List<String> disabledModIds = new ArrayList<>();

    public Profile(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** Copy all settings from another profile (except name). */
    public void copyFrom(Profile other) {
        this.renderDistance = other.renderDistance;
        this.simulationDistance = other.simulationDistance;
        this.fov = other.fov;
        this.guiScale = other.guiScale;
        this.gamma = other.gamma;
        this.fullscreen = other.fullscreen;
        this.maxFps = other.maxFps;
        this.particles = other.particles;
        this.clouds = other.clouds;
        this.entityShadows = other.entityShadows;
        this.entityDistance = other.entityDistance;
        this.fovEffectScale = other.fovEffectScale;
        this.screenEffectScale = other.screenEffectScale;
        this.bobView = other.bobView;
        this.damageTilt = other.damageTilt;
        this.masterVolume = other.masterVolume;
        this.musicVolume = other.musicVolume;
        this.playerVolume = other.playerVolume;
        this.hostileVolume = other.hostileVolume;
        this.ambientVolume = other.ambientVolume;
        this.chatVisibility = other.chatVisibility;
        this.chatOpacity = other.chatOpacity;
        this.chatScale = other.chatScale;
        this.chatWidth = other.chatWidth;
        this.chatHeightFocused = other.chatHeightFocused;
        this.chatHeightUnfocused = other.chatHeightUnfocused;
        this.chatColors = other.chatColors;
        this.chatLinks = other.chatLinks;
        this.autoSuggestions = other.autoSuggestions;
        this.mouseSensitivity = other.mouseSensitivity;
        this.autoJump = other.autoJump;
        this.toggleSprint = other.toggleSprint;
        this.toggleCrouch = other.toggleCrouch;
        this.attackIndicator = other.attackIndicator;
        this.enabledResourcePacks = new ArrayList<>(other.enabledResourcePacks);
        this.featureEnabled = new HashMap<>(other.featureEnabled);
        this.moduleEnabled = new HashMap<>(other.moduleEnabled);
        this.disabledModIds = new ArrayList<>(other.disabledModIds);
    }

    public boolean isFeatureEnabled(String id, boolean defaultValue) {
        return featureEnabled.getOrDefault(id, defaultValue);
    }

    public void setFeatureEnabled(String id, boolean enabled) {
        featureEnabled.put(id, enabled);
    }

    public Boolean getModuleEnabled(String name) {
        return moduleEnabled.get(name == null ? null : name.toLowerCase());
    }

    public void setModuleEnabled(String name, boolean enabled) {
        if (name == null) return;
        moduleEnabled.put(name.toLowerCase(), enabled);
    }

    // ----- Serialization -----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);

        // Video
        o.addProperty("renderDistance", renderDistance);
        o.addProperty("simulationDistance", simulationDistance);
        o.addProperty("fov", fov);
        o.addProperty("guiScale", guiScale);
        o.addProperty("gamma", gamma);
        o.addProperty("fullscreen", fullscreen);
        o.addProperty("maxFps", maxFps);
        o.addProperty("particles", particles);
        o.addProperty("clouds", clouds);
        o.addProperty("entityShadows", entityShadows);
        o.addProperty("entityDistance", entityDistance);
        o.addProperty("fovEffectScale", fovEffectScale);
        o.addProperty("screenEffectScale", screenEffectScale);
        o.addProperty("bobView", bobView);
        o.addProperty("damageTilt", damageTilt);
        // Audio
        o.addProperty("masterVolume", masterVolume);
        o.addProperty("musicVolume", musicVolume);
        o.addProperty("playerVolume", playerVolume);
        o.addProperty("hostileVolume", hostileVolume);
        o.addProperty("ambientVolume", ambientVolume);
        // Chat
        o.addProperty("chatVisibility", chatVisibility);
        o.addProperty("chatOpacity", chatOpacity);
        o.addProperty("chatScale", chatScale);
        o.addProperty("chatWidth", chatWidth);
        o.addProperty("chatHeightFocused", chatHeightFocused);
        o.addProperty("chatHeightUnfocused", chatHeightUnfocused);
        o.addProperty("chatColors", chatColors);
        o.addProperty("chatLinks", chatLinks);
        o.addProperty("autoSuggestions", autoSuggestions);
        // Controls
        o.addProperty("mouseSensitivity", mouseSensitivity);
        o.addProperty("autoJump", autoJump);
        o.addProperty("toggleSprint", toggleSprint);
        o.addProperty("toggleCrouch", toggleCrouch);
        o.addProperty("attackIndicator", attackIndicator);

        JsonArray packs = new JsonArray();
        for (String p : enabledResourcePacks) packs.add(p);
        o.add("enabledResourcePacks", packs);

        JsonObject feats = new JsonObject();
        for (Map.Entry<String, Boolean> e : featureEnabled.entrySet()) {
            feats.addProperty(e.getKey(), e.getValue());
        }
        o.add("featureEnabled", feats);

        JsonObject mods = new JsonObject();
        for (Map.Entry<String, Boolean> e : moduleEnabled.entrySet()) {
            mods.addProperty(e.getKey(), e.getValue());
        }
        o.add("moduleEnabled", mods);

        JsonArray disabled = new JsonArray();
        for (String id : disabledModIds) disabled.add(id);
        o.add("disabledModIds", disabled);

        return o;
    }

    public static Profile fromJson(JsonObject o) {
        Profile p = new Profile(o.has("name") ? o.get("name").getAsString() : "Unnamed");
        // Video
        if (o.has("renderDistance")) p.renderDistance = o.get("renderDistance").getAsInt();
        if (o.has("simulationDistance")) p.simulationDistance = o.get("simulationDistance").getAsInt();
        if (o.has("fov")) p.fov = o.get("fov").getAsInt();
        if (o.has("guiScale")) p.guiScale = o.get("guiScale").getAsInt();
        if (o.has("gamma")) p.gamma = o.get("gamma").getAsDouble();
        if (o.has("fullscreen")) p.fullscreen = o.get("fullscreen").getAsBoolean();
        if (o.has("maxFps")) p.maxFps = o.get("maxFps").getAsInt();
        if (o.has("particles")) p.particles = o.get("particles").getAsInt();
        if (o.has("clouds")) p.clouds = o.get("clouds").getAsInt();
        if (o.has("entityShadows")) p.entityShadows = o.get("entityShadows").getAsBoolean();
        if (o.has("entityDistance")) p.entityDistance = o.get("entityDistance").getAsDouble();
        if (o.has("fovEffectScale")) p.fovEffectScale = o.get("fovEffectScale").getAsDouble();
        if (o.has("screenEffectScale")) p.screenEffectScale = o.get("screenEffectScale").getAsDouble();
        if (o.has("bobView")) p.bobView = o.get("bobView").getAsBoolean();
        if (o.has("damageTilt")) p.damageTilt = o.get("damageTilt").getAsDouble();
        // Audio
        if (o.has("masterVolume")) p.masterVolume = o.get("masterVolume").getAsFloat();
        if (o.has("musicVolume")) p.musicVolume = o.get("musicVolume").getAsFloat();
        if (o.has("playerVolume")) p.playerVolume = o.get("playerVolume").getAsFloat();
        if (o.has("hostileVolume")) p.hostileVolume = o.get("hostileVolume").getAsFloat();
        if (o.has("ambientVolume")) p.ambientVolume = o.get("ambientVolume").getAsFloat();
        // Chat
        if (o.has("chatVisibility")) p.chatVisibility = o.get("chatVisibility").getAsInt();
        if (o.has("chatOpacity")) p.chatOpacity = o.get("chatOpacity").getAsDouble();
        if (o.has("chatScale")) p.chatScale = o.get("chatScale").getAsDouble();
        if (o.has("chatWidth")) p.chatWidth = o.get("chatWidth").getAsDouble();
        if (o.has("chatHeightFocused")) p.chatHeightFocused = o.get("chatHeightFocused").getAsDouble();
        if (o.has("chatHeightUnfocused")) p.chatHeightUnfocused = o.get("chatHeightUnfocused").getAsDouble();
        if (o.has("chatColors")) p.chatColors = o.get("chatColors").getAsBoolean();
        if (o.has("chatLinks")) p.chatLinks = o.get("chatLinks").getAsBoolean();
        if (o.has("autoSuggestions")) p.autoSuggestions = o.get("autoSuggestions").getAsBoolean();
        // Controls
        if (o.has("mouseSensitivity")) p.mouseSensitivity = o.get("mouseSensitivity").getAsDouble();
        if (o.has("autoJump")) p.autoJump = o.get("autoJump").getAsBoolean();
        if (o.has("toggleSprint")) p.toggleSprint = o.get("toggleSprint").getAsBoolean();
        if (o.has("toggleCrouch")) p.toggleCrouch = o.get("toggleCrouch").getAsBoolean();
        if (o.has("attackIndicator")) p.attackIndicator = o.get("attackIndicator").getAsInt();

        if (o.has("enabledResourcePacks")) {
            for (JsonElement el : o.getAsJsonArray("enabledResourcePacks")) {
                p.enabledResourcePacks.add(el.getAsString());
            }
        }
        if (o.has("featureEnabled")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("featureEnabled").entrySet()) {
                p.featureEnabled.put(e.getKey(), e.getValue().getAsBoolean());
            }
        }
        if (o.has("moduleEnabled")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("moduleEnabled").entrySet()) {
                p.moduleEnabled.put(e.getKey().toLowerCase(), e.getValue().getAsBoolean());
            }
        }
        if (o.has("disabledModIds")) {
            for (JsonElement el : o.getAsJsonArray("disabledModIds")) {
                p.disabledModIds.add(el.getAsString());
            }
        }

        return p;
    }
}
