package dev.kitsune.client;

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
import dev.kitsune.client.features.qol.FullBrightFeature;
import dev.kitsune.client.features.qol.MapTooltipFeature;
import dev.kitsune.client.features.qol.ShulkerTooltipFeature;
import dev.kitsune.client.features.qol.ZoomFeature;
import dev.kitsune.client.features.optimization.AdaptiveFpsLimitFeature;
import dev.kitsune.client.screen.FoxMainMenuScreen;
import dev.kitsune.client.server.ServerRuleStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.kitsune.menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                FOX_CATEGORY
        ));
        zoomKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.kitsune.zoom",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                FOX_CATEGORY
        ));
        fullBrightKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.kitsune.full_bright",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                FOX_CATEGORY
        ));
        clickGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.kitsune.clickgui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                FOX_CATEGORY
        ));
        hudEditorKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.kitsune.hud_editor",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_END,
                FOX_CATEGORY
        ));

        // 4. Tick hook
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // 5. Initial feature sync is deferred to the first tick — see `initialSyncDone`.
        //    Calling syncEnabledStates() here would crash because Minecraft.options
        //    is still null mid-construction.

        LOGGER.info("Fox Client init complete (active profile: {}, features: {})",
                ProfileManager.getActiveName(), FeatureRegistry.all().size());
    }

    private void registerFeatures() {
        // QoL — only legacy features that actually do something. The stub
        // FoxFeatures (ArmorHud, ChatHeads, LowFire, LowShield, ArmorTrims,
        // ContainerRecolor, CapitalizedFont, SimpleCulling, ParticleCull)
        // were deleted in the module revamp — their functionality is either
        // covered by native modules in module/ or was never implemented.
        FeatureRegistry.register(new ZoomFeature());
        FeatureRegistry.register(new FullBrightFeature());
        FeatureRegistry.register(new ShulkerTooltipFeature());
        FeatureRegistry.register(new MapTooltipFeature());
        // Optimization
        FeatureRegistry.register(new AdaptiveFpsLimitFeature());
    }

    private void onClientTick(Minecraft client) {
        // Deferred initial sync — now that Minecraft is fully constructed,
        // options is non-null and features can safely enable.
        if (!initialSyncDone && client.options != null) {
            FeatureRegistry.syncEnabledStates();
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
            FeatureRegistry.toggleForActiveProfile("full_bright");
        }
        // Tick features
        FeatureRegistry.tickAll(client);
    }
}
