package dev.kitsune.client.features;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.core.Profile;
import dev.kitsune.client.core.ProfileManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central registry of all Fox Client {@link FoxFeature}s.
 *
 * Features are registered at mod init by {@link dev.kitsune.client.KitsuneClient#onInitializeClient()}.
 * Their enabled state is sourced from the active {@link Profile} (per-profile config) and
 * may be temporarily overridden by server rules (see {@link dev.kitsune.client.server.ServerRuleStore}).
 *
 * Calling {@link #syncEnabledStates()} after a profile switch or server join will fire
 * onEnable/onDisable for any features whose effective enabled state changed.
 */
public class FeatureRegistry {

    private static final Map<String, FoxFeature> FEATURES = new LinkedHashMap<>();
    private static final Set<String> currentlyEnabled = new HashSet<>();

    /** Server-rule overrides: feature id -> forced enabled state. Cleared on disconnect. */
    private static final Map<String, Boolean> serverOverrides = new LinkedHashMap<>();

    public static void register(FoxFeature feature) {
        FEATURES.put(feature.id(), feature);
    }

    public static FoxFeature get(String id) {
        return FEATURES.get(id);
    }

    public static List<FoxFeature> all() {
        return Collections.unmodifiableList(new ArrayList<>(FEATURES.values()));
    }

    public static boolean isEnabled(String id) {
        return currentlyEnabled.contains(id);
    }

    /** Compute the effective desired-enabled state for a feature, given profile + server overrides. */
    public static boolean computeDesiredEnabled(String id) {
        if (serverOverrides.containsKey(id)) return serverOverrides.get(id);
        FoxFeature f = FEATURES.get(id);
        if (f == null) return false;
        Profile p = ProfileManager.getActiveProfile();
        if (p == null) return f.defaultEnabled();
        return p.isFeatureEnabled(id, f.defaultEnabled());
    }

    /**
     * Diff the desired enabled state against the current enabled state, firing
     * onEnable/onDisable for the differences. Call after any change to the active
     * profile, server overrides, or feature settings.
     */
    public static void syncEnabledStates() {
        for (FoxFeature f : FEATURES.values()) {
            boolean desired = computeDesiredEnabled(f.id());
            boolean active = currentlyEnabled.contains(f.id());
            if (desired && !active) {
                try { f.onEnable(); } catch (Throwable t) {
                    KitsuneClient.LOGGER.error("Feature " + f.id() + " onEnable threw", t);
                }
                currentlyEnabled.add(f.id());
            } else if (!desired && active) {
                try { f.onDisable(); } catch (Throwable t) {
                    KitsuneClient.LOGGER.error("Feature " + f.id() + " onDisable threw", t);
                }
                currentlyEnabled.remove(f.id());
            }
        }
    }

    /** Tick every enabled feature. Called from {@link dev.kitsune.client.KitsuneClient} on END_CLIENT_TICK. */
    public static void tickAll(Minecraft client) {
        for (FoxFeature f : FEATURES.values()) {
            if (currentlyEnabled.contains(f.id())) {
                try { f.tick(client); } catch (Throwable t) {
                    KitsuneClient.LOGGER.error("Feature " + f.id() + " tick threw", t);
                }
            }
        }
    }

    /** Apply a temporary server override (used by ServerRuleStore on connect). */
    public static void setServerOverride(String featureId, boolean enabled) {
        serverOverrides.put(featureId, enabled);
        syncEnabledStates();
    }

    /** Clear all server overrides (used on disconnect). */
    public static void clearServerOverrides() {
        if (serverOverrides.isEmpty()) return;
        serverOverrides.clear();
        syncEnabledStates();
    }

    /** Toggle persistently for the active profile and re-sync. */
    public static void toggleForActiveProfile(String featureId) {
        Profile p = ProfileManager.getActiveProfile();
        if (p == null) return;
        FoxFeature f = FEATURES.get(featureId);
        boolean current = p.isFeatureEnabled(featureId, f != null && f.defaultEnabled());
        p.setFeatureEnabled(featureId, !current);
        ProfileManager.save();
        syncEnabledStates();
    }
}
