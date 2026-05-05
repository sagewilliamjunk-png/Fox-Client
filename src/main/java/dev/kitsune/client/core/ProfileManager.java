package dev.kitsune.client.core;

import dev.kitsune.client.KitsuneClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages an arbitrary number of named {@link Profile}s and the
 * "currently active" pointer.
 *
 * Persists to {@code config/kitsune/profiles.json}. The first time
 * Fox Client runs it creates two default profiles, "vanilla" and "fox".
 *
 * Replaces and generalizes the modeswitch {@code ModeManager} (which
 * hardcoded exactly two profiles, PVP and Survival).
 */
public class ProfileManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();
    private static String activeName = "fox";
    private static long lastAutoSaveMs = 0L;
    private static final long AUTO_SAVE_INTERVAL_MS = 30_000L;

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(KitsuneClient.MOD_ID);
    }

    private static Path profilesFile() {
        return configDir().resolve("profiles.json");
    }

    public static List<String> getProfileNames() {
        return new ArrayList<>(PROFILES.keySet());
    }

    public static Profile getActiveProfile() {
        return PROFILES.get(activeName);
    }

    public static String getActiveName() {
        return activeName;
    }

    public static Profile get(String name) {
        return PROFILES.get(name);
    }

    public static boolean exists(String name) {
        return PROFILES.containsKey(name);
    }

    public static Profile create(String name) {
        Profile p = new Profile(name);
        PROFILES.put(name, p);
        save();
        return p;
    }

    public static void delete(String name) {
        if (PROFILES.size() <= 1) return;   // never delete the last profile
        PROFILES.remove(name);
        if (name.equals(activeName)) {
            activeName = PROFILES.keySet().iterator().next();
        }
        save();
    }

    /**
     * Switch the active profile and apply its settings to the running client.
     * If the client is not yet initialized (called from preLaunch / before MC
     * startup), only the active pointer is updated and the apply happens on
     * the first client tick after launch.
     */
    public static void switchTo(String name, Minecraft client) {
        if (!PROFILES.containsKey(name)) return;
        String oldName = activeName;
        activeName = name;
        if (client != null) {
            applyProfile(getActiveProfile(), client);
        }
        // Sync native modules — was a long-standing bug where switching
        // profiles only updated MC video/audio settings and legacy features,
        // leaving native modules (Hitboxes, FpsGraph, etc.) frozen.
        try {
            dev.kitsune.client.module.ModuleManager.applyProfileState(getActiveProfile());
        } catch (Throwable t) {
            KitsuneClient.LOGGER.warn("[Fox] applyProfileState failed: {}", t.toString());
        }
        save();
        // Fire notification if we actually switched
        if (!name.equals(oldName)) {
            try {
                dev.kitsune.client.hud.NotificationManager.show(
                        "Profile: " + dev.kitsune.client.screen.FoxTheme.capitalize(name));
            } catch (Throwable ignored) { /* don't crash if notification system isn't ready */ }
        }
    }

    public static void applyProfile(Profile p, Minecraft client) {
        if (p == null || client == null) return;
        Options o = client.options;

        try {
            // Video
            o.renderDistance().set(p.renderDistance);
            o.simulationDistance().set(p.simulationDistance);
            o.fov().set(p.fov);
            o.guiScale().set(p.guiScale);
            o.gamma().set(p.gamma);
            o.framerateLimit().set(p.maxFps);
            o.particles().set(ParticleStatus.values()[clamp(p.particles, 0, ParticleStatus.values().length - 1)]);
            o.cloudStatus().set(CloudStatus.values()[clamp(p.clouds, 0, CloudStatus.values().length - 1)]);
            o.entityShadows().set(p.entityShadows);
            o.entityDistanceScaling().set(p.entityDistance);
            o.fovEffectScale().set(p.fovEffectScale);
            o.screenEffectScale().set(p.screenEffectScale);
            o.bobView().set(p.bobView);
            o.damageTiltStrength().set(p.damageTilt);

            // Audio
            o.getSoundSourceOptionInstance(SoundSource.MASTER).set((double) p.masterVolume);
            o.getSoundSourceOptionInstance(SoundSource.MUSIC).set((double) p.musicVolume);
            o.getSoundSourceOptionInstance(SoundSource.PLAYERS).set((double) p.playerVolume);
            o.getSoundSourceOptionInstance(SoundSource.HOSTILE).set((double) p.hostileVolume);
            o.getSoundSourceOptionInstance(SoundSource.AMBIENT).set((double) p.ambientVolume);

            // Chat
            o.chatVisibility().set(ChatVisiblity.values()[clamp(p.chatVisibility, 0, ChatVisiblity.values().length - 1)]);
            o.chatOpacity().set(p.chatOpacity);
            o.chatScale().set(p.chatScale);
            o.chatWidth().set(p.chatWidth);
            o.chatHeightFocused().set(p.chatHeightFocused);
            o.chatHeightUnfocused().set(p.chatHeightUnfocused);
            o.chatColors().set(p.chatColors);
            o.chatLinks().set(p.chatLinks);
            o.autoSuggestions().set(p.autoSuggestions);

            // Controls
            o.sensitivity().set(p.mouseSensitivity);
            o.autoJump().set(p.autoJump);
            o.toggleSprint().set(p.toggleSprint);
            o.toggleCrouch().set(p.toggleCrouch);
            o.attackIndicator().set(AttackIndicatorStatus.values()[clamp(p.attackIndicator, 0, AttackIndicatorStatus.values().length - 1)]);

            o.save();
        } catch (Throwable t) {
            KitsuneClient.LOGGER.error("Failed to apply profile '" + p.getName() + "'", t);
        }
    }

    public static void captureCurrentInto(Profile p, Minecraft client) {
        if (p == null || client == null) return;
        Options o = client.options;
        p.renderDistance = o.renderDistance().get();
        p.simulationDistance = o.simulationDistance().get();
        p.fov = o.fov().get();
        p.guiScale = o.guiScale().get();
        p.gamma = o.gamma().get();
        p.fullscreen = client.getWindow() != null && client.getWindow().isFullscreen();
        p.maxFps = o.framerateLimit().get();
        p.particles = o.particles().get().ordinal();
        p.clouds = o.cloudStatus().get().ordinal();
        p.entityShadows = o.entityShadows().get();
        p.entityDistance = o.entityDistanceScaling().get();
        p.fovEffectScale = o.fovEffectScale().get();
        p.screenEffectScale = o.screenEffectScale().get();
        p.bobView = o.bobView().get();
        p.damageTilt = o.damageTiltStrength().get();

        p.masterVolume = o.getSoundSourceVolume(SoundSource.MASTER);
        p.musicVolume = o.getSoundSourceVolume(SoundSource.MUSIC);
        p.playerVolume = o.getSoundSourceVolume(SoundSource.PLAYERS);
        p.hostileVolume = o.getSoundSourceVolume(SoundSource.HOSTILE);
        p.ambientVolume = o.getSoundSourceVolume(SoundSource.AMBIENT);

        p.chatVisibility = o.chatVisibility().get().ordinal();
        p.chatOpacity = o.chatOpacity().get();
        p.chatScale = o.chatScale().get();
        p.chatWidth = o.chatWidth().get();
        p.chatHeightFocused = o.chatHeightFocused().get();
        p.chatHeightUnfocused = o.chatHeightUnfocused().get();
        p.chatColors = o.chatColors().get();
        p.chatLinks = o.chatLinks().get();
        p.autoSuggestions = o.autoSuggestions().get();

        p.mouseSensitivity = o.sensitivity().get();
        p.autoJump = o.autoJump().get();
        p.toggleSprint = o.toggleSprint().get();
        p.toggleCrouch = o.toggleCrouch().get();
        p.attackIndicator = o.attackIndicator().get().ordinal();

        // Snapshot which native modules are currently enabled so the next
        // profile load can restore them.
        try {
            dev.kitsune.client.module.ModuleManager.snapshotInto(p);
        } catch (Throwable t) {
            KitsuneClient.LOGGER.warn("[Fox] snapshotInto failed: {}", t.toString());
        }

        save();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * Called each client tick; defensively flushes the profile file every 30 s
     * in case of a crash between explicit mutations.
     */
    public static void tickAutoSave() {
        long now = System.currentTimeMillis();
        if (now - lastAutoSaveMs < AUTO_SAVE_INTERVAL_MS) return;
        lastAutoSaveMs = now;
        if (PROFILES.isEmpty()) return;
        save();
    }

    // ----- Persistence -----

    public static void load() {
        Path file = profilesFile();
        if (!Files.exists(file)) {
            setupDefaults();
            save();
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            PROFILES.clear();
            if (root.has("profiles")) {
                JsonArray arr = root.getAsJsonArray("profiles");
                for (JsonElement el : arr) {
                    Profile p = Profile.fromJson(el.getAsJsonObject());
                    PROFILES.put(p.getName(), p);
                }
            }
            if (root.has("active")) {
                activeName = root.get("active").getAsString();
            }
            if (PROFILES.isEmpty() || !PROFILES.containsKey(activeName)) {
                setupDefaults();
            }
        } catch (Exception e) {
            KitsuneClient.LOGGER.error("Failed to load profiles.json, restoring defaults", e);
            setupDefaults();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(configDir());
            JsonObject root = new JsonObject();
            root.addProperty("active", activeName);
            JsonArray arr = new JsonArray();
            for (Profile p : PROFILES.values()) arr.add(p.toJson());
            root.add("profiles", arr);
            Path target = profilesFile();
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            KitsuneClient.LOGGER.error("Failed to save profiles.json", e);
        }
    }

    private static void setupDefaults() {
        PROFILES.clear();

        Profile vanilla = new Profile("vanilla");
        // "vanilla" leaves all defaults as-is — no features enabled
        PROFILES.put(vanilla.getName(), vanilla);

        Profile fox = new Profile("fox");
        // "fox" enables Fox QoL defaults
        fox.fov = 90;
        fox.maxFps = 240;
        fox.gamma = 1.0;
        fox.setFeatureEnabled("zoom", true);
        fox.setFeatureEnabled("armor_hud", true);
        fox.setFeatureEnabled("shulker_tooltip", true);
        fox.setFeatureEnabled("map_tooltip", true);
        fox.setFeatureEnabled("full_bright", false);
        PROFILES.put(fox.getName(), fox);

        activeName = "fox";
    }

    /** Read-only view of all profiles, in insertion order. */
    public static List<Profile> all() {
        return Collections.unmodifiableList(new ArrayList<>(PROFILES.values()));
    }
}
