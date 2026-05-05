package dev.kitsune.client.features;

import net.minecraft.client.Minecraft;

/**
 * A toggleable Fox Client feature module. Implementations are registered with
 * {@link FeatureRegistry} at mod init.
 *
 * Features may be enabled or disabled per-profile (see {@link dev.kitsune.client.core.Profile#featureEnabled})
 * and overridden per-server (see {@link dev.kitsune.client.server.ServerRule}).
 *
 * Features should NOT keep their own enabled state — query {@link FeatureRegistry#isEnabled(String)}.
 * They may listen for {@link #onEnable()} / {@link #onDisable()} hooks if they need to
 * register/unregister listeners or restore vanilla state.
 */
public interface FoxFeature {

    /** Stable, unique ID. Used as the key in profile JSON and server rules. */
    String id();

    /** Human-readable name (translated via lang/en_us.json key {@code kitsune.feature.<id>}). */
    String displayName();

    /** Whether this feature is enabled by default in fresh profiles. */
    default boolean defaultEnabled() { return false; }

    /** Called once when the feature is enabled (either at startup or via toggle). */
    default void onEnable() {}

    /** Called when the feature is disabled. Should restore any vanilla state it changed. */
    default void onDisable() {}

    /** Called every client tick while the feature is enabled. */
    default void tick(Minecraft client) {}

    /** Brief tag describing what category this is — "qol", "visual", "optimization", "hud". */
    default String category() { return "qol"; }
}
