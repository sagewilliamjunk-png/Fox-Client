package dev.kitsune.client;

import dev.kitsune.client.bridge.GameStateBridge;
import dev.kitsune.client.command.KitsuneCommand;
import dev.kitsune.client.config.ConfigManager;
import dev.kitsune.client.cosmetic.CosmeticRegistry;
import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.event.EventBusBridge;
import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.gui.clickgui.ClickGuiScreen;
import dev.kitsune.client.hud.HudEditorScreen;
import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.VanillaHudProxies;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.tooltip.ClientShulkerPreviewTooltip;
import dev.kitsune.client.tooltip.ShulkerPreviewTooltip;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import dev.kitsune.client.screen.FoxMainMenuScreen;
import dev.kitsune.client.server.ServerRuleStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fox Client main entrypoint. Registered in {@code fabric.mod.json}
 * under {@code entrypoints.client}.
 *
 * Responsibilities at init:
 *   1. Load top-level config + profiles + server rules from disk
 *   2. Register every {@link dev.kitsune.client.features.FoxFeature} with the registry
 *   3. Register key bindings (open Fox menu, zoom, full bright)
 *   4. Hook the client tick to sync features and process key presses
 */
public class KitsuneClient implements ClientModInitializer {

    public static final String MOD_ID = "kitsune";
    public static final Logger LOGGER = LoggerFactory.getLogger("KitsuneClient");

    public static KeyMapping openMenuKey;
    public static KeyMapping zoomKey;
    public static KeyMapping fullBrightKey;
    public static KeyMapping clickGuiKey;
    public static KeyMapping hudEditorKey;
    public static KeyMapping copyCoordsKey;
    public static KeyMapping minimapZoomInKey;
    public static KeyMapping minimapZoomOutKey;
    public static KeyMapping minimapEnlargeKey;

    private static final KeyMapping.Category FOX_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    /**
     * Guards the one-time initial feature sync. During {@code onInitializeClient}
     * the {@link Minecraft} instance is still under construction and {@code mc.options}
     * is {@code null} — features that touch options on enable (FullBright, Zoom)
     * would NPE. We defer the first sync to the first client tick, at which point
     * options is fully initialised.
     */
    private boolean initialSyncDone = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Fox Client initializing");

        // 1. Load all persistent state
        KitsuneConfig.load();
        ProfileManager.load();
        ServerRuleStore.load();

        // 1b. Register every (legacy Fox) feature first — ModuleManager.init()
        //     wraps each FoxFeature in a LegacyFeatureModule, so the registry
        //     must be populated before init() runs or no legacy modules appear
        //     in the ClickGUI.
        registerFeatures();

        // 1c. Kitsune module system (Phase 2)
        ModuleManager.init();
        ConfigManager.init();
        EventBusBridge.register();
        KitsuneCommand.register();

        // 2. Vanilla HUD proxy widgets — makes hotbar/health/food/air/xp
        //    draggable in the HUD editor via GuiVanillaHudMixin pose translate.
        VanillaHudProxies.registerAll();

        // 3. Key bindings
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                FOX_CATEGORY
        ));
        zoomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.zoom",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                FOX_CATEGORY
        ));
        // Default UNBOUND — G collides with vanilla Open Social Interactions.
        // Users rebind in Options → Controls if they want a quick toggle.
        fullBrightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.full_bright",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                FOX_CATEGORY
        ));
        clickGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.clickgui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                FOX_CATEGORY
        ));
        hudEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.hud_editor",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_END,
                FOX_CATEGORY
        ));
        copyCoordsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.copy_coords",
                InputConstants.Type.KEYSYM,
                // Unbound by default — too easy to collide. Users bind in Options.
                InputConstants.UNKNOWN.getValue(),
                FOX_CATEGORY
        ));
        // Minimap controls — Xaeros parity (I/O/Z).
        minimapZoomInKey  = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.minimap_zoom_in",  InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I, FOX_CATEGORY));
        minimapZoomOutKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.minimap_zoom_out", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O, FOX_CATEGORY));
        minimapEnlargeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.kitsune.minimap_enlarge",  InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, FOX_CATEGORY));

        // 3b. Register visual tooltip component for shulker box grid preview.
        //     Must be done at init (not per-feature enable) — the callback is
        //     global and just returns null for any TooltipComponent it doesn't own.
        ClientTooltipComponentCallback.EVENT.register(data -> {
            if (data instanceof ShulkerPreviewTooltip sp) return new ClientShulkerPreviewTooltip(sp);
            return null;
        });

        // 4. Game-state bridge — writes config/kitsune/game-state.json every ~3 s
        //    so the Fox Launcher can show the current server / dimension in Discord.
        GameStateBridge.register();

        // 5. Tick hook
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // 6. Initial feature sync is deferred to the first tick — see `initialSyncDone`.
        //    Calling syncEnabledStates() here would crash because Minecraft.options
        //    is still null mid-construction.

        LOGGER.info("Fox Client init complete (active profile: {}, features: {})",
                ProfileManager.getActiveName(), FeatureRegistry.all().size());
    }

    private void registerFeatures() {
        // v1.2: All four remaining legacy features (Zoom, ShulkerTooltip,
        // MapTooltip, AdaptiveFpsLimit) were ported into proper Modules under
        // dev.kitsune.client.module.misc.*. They register themselves via
        // ModuleManager.init() in onInitializeClient (the call above), so
        // there's nothing to add here anymore.
        //
        // The FeatureRegistry / FoxFeature / LegacyFeatureModule infrastructure
        // is left in place as a no-op shell so older mixins and per-profile
        // config keys keep compiling. The full removal of that infrastructure
        // is scheduled for v1.3 once we've verified no in-the-wild config
        // depends on the old shape.
    }

    private void onClientTick(Minecraft client) {
        // Deferred initial sync — now that Minecraft is fully constructed,
        // options is non-null and features can safely enable.
        if (!initialSyncDone && client.options != null) {
            FeatureRegistry.syncEnabledStates();
            // Apply persisted native-module state now that mc.options is safe to
            // touch. Done here (not in onInitializeClient) because modules like
            // FullBrightness read mc.options.gamma() in their onEnable, which
            // would NPE if loaded during construction.
            try { ConfigManager.loadDeferred(); }
            catch (Throwable t) { LOGGER.warn("[Fox] module config load failed: {}", t.toString()); }
            // Cosmetics manifest is bundled in the mod jar, so it's available
            // via the resource manager as soon as MC has finished constructing
            // the resource manager. Same defer point as the feature sync.
            try { CosmeticRegistry.reload(client.getResourceManager()); }
            catch (Throwable t) { LOGGER.warn("[Fox] cosmetic reload failed: {}", t.toString()); }
            initialSyncDone = true;
            dev.kitsune.client.hud.NotificationManager.show(
                    "Fox Client ready · profile " + ProfileManager.getActiveName(),
                    dev.kitsune.client.hud.NotificationManager.Type.SUCCESS);
        }

        // Open legacy Fox menu
        while (openMenuKey.consumeClick()) {
            client.setScreen(new FoxMainMenuScreen(client.screen));
        }
        // Open Kitsune ClickGUI
        while (clickGuiKey.consumeClick()) {
            client.setScreen(new ClickGuiScreen());
        }
        // Open HUD Editor
        while (hudEditorKey.consumeClick()) {
            client.setScreen(new HudEditorScreen());
        }
        // F3: throttled auto-save for HUD layout + active profile
        HudManager.tickAutoSave();
        ProfileManager.tickAutoSave();
        // Quick toggles
        while (fullBrightKey.consumeClick()) {
            // Toggle the native FullBrightnessModule via the module manager.
            dev.kitsune.client.module.Module m = ModuleManager.getByName("Full Brightness");
            if (m != null) m.toggle();
        }
        // Copy coords to clipboard
        while (copyCoordsKey.consumeClick()) {
            dev.kitsune.client.module.Module m = ModuleManager.getByName("Coords HUD");
            if (m instanceof dev.kitsune.client.module.hud.CoordsHudModule c && c.isEffectivelyEnabled()) {
                c.copyCoordsToClipboard();
            }
        }
        // Minimap zoom (I = closer / smaller range, O = farther / larger range).
        // consumeClick semantics fit zoom steps perfectly — each press = one step.
        while (minimapZoomInKey.consumeClick()) {
            dev.kitsune.client.module.hud.MinimapModule mm = dev.kitsune.client.module.hud.MinimapModule.instance();
            if (mm != null && mm.isEffectivelyEnabled()) mm.adjustZoom(-16);
        }
        while (minimapZoomOutKey.consumeClick()) {
            dev.kitsune.client.module.hud.MinimapModule mm = dev.kitsune.client.module.hud.MinimapModule.instance();
            if (mm != null && mm.isEffectivelyEnabled()) mm.adjustZoom(+16);
        }
        // Enlarge — hold key, so we poll isDown() not consumeClick().
        {
            dev.kitsune.client.module.hud.MinimapModule mm = dev.kitsune.client.module.hud.MinimapModule.instance();
            if (mm != null) mm.setEnlargeActive(minimapEnlargeKey.isDown());
            // Drain any click queue so the editor doesn't process them.
            while (minimapEnlargeKey.consumeClick()) { /* noop */ }
        }
        // Tick features
        FeatureRegistry.tickAll(client);
    }
}
