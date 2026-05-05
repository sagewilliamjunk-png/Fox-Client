package dev.kitsune.client.module;

import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import net.minecraft.client.Minecraft;

/**
 * Adapter that wraps an existing {@link FoxFeature} as a {@link Module} so it
 * appears in the ClickGUI alongside native modules.
 *
 * <p>The enabled state is bidirectionally synced: toggling in the ClickGUI
 * calls {@link FeatureRegistry#toggleForActiveProfile}, and the module's
 * {@code isEnabled()} reflects the feature registry state.
 *
 * <p>This avoids rewriting all 14 legacy features while giving them full
 * ClickGUI integration immediately.
 */
public class LegacyFeatureModule extends Module {

    private final FoxFeature feature;

    public LegacyFeatureModule(FoxFeature feature) {
        super(feature.displayName(), "Legacy feature: " + feature.id(), mapCategory(feature.category()));
        this.feature = feature;
    }

    public FoxFeature feature() { return feature; }

    /**
     * Override toggle to go through FeatureRegistry instead of Module's own state.
     * This keeps profile persistence working via the existing feature system.
     */
    @Override
    public void toggle() {
        FeatureRegistry.toggleForActiveProfile(feature.id());
        // Sync our internal state to match
        syncFromRegistry();
    }

    @Override
    public void setEnabled(boolean value) {
        boolean current = FeatureRegistry.isEnabled(feature.id());
        if (current != value) {
            FeatureRegistry.toggleForActiveProfile(feature.id());
        }
        syncFromRegistry();
    }

    @Override
    public boolean isEnabled() {
        return FeatureRegistry.isEnabled(feature.id());
    }

    /** Pull the enabled state from FeatureRegistry into the base Module field. */
    public void syncFromRegistry() {
        boolean desired = FeatureRegistry.isEnabled(feature.id());
        if (super.isEnabled() != desired) {
            setEnabledStateSilently(desired);
        }
    }

    @Override
    public void onTick() {
        if (isEnabled()) {
            feature.tick(Minecraft.getInstance());
        }
    }

    private static Category mapCategory(String cat) {
        if (cat == null) return Category.MISC;
        return switch (cat.toLowerCase()) {
            case "qol" -> Category.PLAYER;
            case "visual", "render" -> Category.RENDER;
            case "optimization" -> Category.MISC;
            case "hud" -> Category.HUD;
            case "chat" -> Category.CHAT;
            case "movement" -> Category.MOVEMENT;
            case "combat" -> Category.COMBAT;
            case "world" -> Category.HUD; // WORLD category was merged into HUD
            case "cosmetic" -> Category.COSMETIC;
            default -> Category.MISC;
        };
    }
}
