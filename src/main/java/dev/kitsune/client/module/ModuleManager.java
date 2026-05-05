package dev.kitsune.client.module;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.addon.AddonCatalog;
import dev.kitsune.client.addon.AddonFlags;
import dev.kitsune.client.core.Profile;
import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.hud.ArmorDurabilityHudModule;
import dev.kitsune.client.module.hud.ClockHudModule;
import dev.kitsune.client.module.hud.CoordsHudModule;
import dev.kitsune.client.module.hud.CpsHudModule;
import dev.kitsune.client.module.hud.DeathCoordsHudModule;
import dev.kitsune.client.module.hud.EntityCountHudModule;
import dev.kitsune.client.module.hud.EntityRadarModule;
import dev.kitsune.client.module.hud.TargetHudModule;
import dev.kitsune.client.module.hud.PerfDashboardModule;
import dev.kitsune.client.module.hud.XpHudModule;
import dev.kitsune.client.module.hud.FpsGraphModule;
import dev.kitsune.client.module.hud.KeystrokesHudModule;
import dev.kitsune.client.module.hud.KillDeathTrackerModule;
import dev.kitsune.client.module.hud.NumericPingModule;
import dev.kitsune.client.module.hud.PaperDollHudModule;
import dev.kitsune.client.module.hud.PotionTimersModule;
import dev.kitsune.client.module.hud.ReachCooldownHudModule;
import dev.kitsune.client.module.hud.ServerInfoHudModule;
import dev.kitsune.client.module.hud.ServerTpsHudModule;
import dev.kitsune.client.module.hud.SessionStatsModule;
import dev.kitsune.client.module.hud.ShieldStatusHudModule;
import dev.kitsune.client.module.hud.SpeedometerHudModule;
import dev.kitsune.client.module.hud.TotemCounterHudModule;
import dev.kitsune.client.module.combat.CrosshairDamageIndicatorModule;
import dev.kitsune.client.module.combat.WeaponSwapReminderModule;
import dev.kitsune.client.module.cosmetic.CapesModule;
import dev.kitsune.client.module.chat.ChatHighlightsModule;
import dev.kitsune.client.module.chat.ChatLoggerModule;
import dev.kitsune.client.module.chat.TransparentChatModule;
import dev.kitsune.client.module.misc.DeathScreenModule;
import dev.kitsune.client.module.misc.DisconnectConfirmModule;
import dev.kitsune.client.module.misc.LootHistoryModule;
import dev.kitsune.client.module.misc.MemoryCleanerModule;
import dev.kitsune.client.module.misc.QuickCommandsModule;
import dev.kitsune.client.module.movement.AntiAfkModule;
import dev.kitsune.client.module.movement.FreeLookModule;
import dev.kitsune.client.module.movement.ToggleSprintModule;
import dev.kitsune.client.module.render.BlockOverlayModule;
import dev.kitsune.client.module.render.ChunkBordersModule;
import dev.kitsune.client.module.render.DynamicCrosshairModule;
import dev.kitsune.client.module.render.HitColorFlashModule;
import dev.kitsune.client.module.render.HitboxModule;
import dev.kitsune.client.module.render.LightLevelModule;
import dev.kitsune.client.module.render.MenuBlurModule;
import dev.kitsune.client.module.render.PingBarsModule;
import dev.kitsune.client.module.render.ReachDisplayModule;
import dev.kitsune.client.module.render.SmoothScrollModule;
import dev.kitsune.client.module.render.WeatherTimeModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Central registry for all {@link Module}s. Registers native modules and
 * wraps legacy {@link FoxFeature}s via {@link LegacyFeatureModule}.
 */
public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();

    private ModuleManager() {}

    public static void init() {
        // Read the per-profile addon flags written by the launcher BEFORE we
        // start registering modules. Anything gated through registerAddon()
        // gets a quick AddonFlags lookup; if disabled the module isn't
        // constructed at all (ctor side-effects skipped) and never enters
        // MODULES, so it doesn't tick / render / post events.
        AddonFlags.reload();

        // ---- Native modules ----
        register(new ToggleSprintModule());
        register(new CoordsHudModule());
        registerAddon(AddonCatalog.GRAYZONE_FREE_LOOK, FreeLookModule::new);
        register(new PotionTimersModule());
        register(new FpsGraphModule());
        register(new ServerInfoHudModule());
        register(new ArmorDurabilityHudModule());
        register(new DynamicCrosshairModule());
        register(new ChunkBordersModule());
        register(new DisconnectConfirmModule());
        register(new BlockOverlayModule());
        register(new ChatHighlightsModule());
        register(new TransparentChatModule());
        register(new KillDeathTrackerModule());
        register(new SessionStatsModule());
        register(new LightLevelModule());
        register(new WeatherTimeModule());
        register(new QuickCommandsModule());
        register(new ChatLoggerModule());
        register(new DeathScreenModule());
        register(new LootHistoryModule());

        // ---- Step 7G B-tier additions ----
        register(new PaperDollHudModule());
        register(new ShieldStatusHudModule());
        register(new ReachCooldownHudModule());
        register(new HitColorFlashModule());
        register(new SmoothScrollModule());
        register(new MenuBlurModule());

        // ---- Gray-zone (server-safe but flagged because some servers ban
        //      them anyway). Each is gated by an addon flag the launcher
        //      writes per-profile, so a "Hypixel-safe" profile can disable
        //      all four without touching the core jar. ----
        registerAddon(AddonCatalog.GRAYZONE_REACH_HUD,  ReachDisplayModule::new);
        registerAddon(AddonCatalog.GRAYZONE_HITBOXES,   HitboxModule::new);
        registerAddon(AddonCatalog.GRAYZONE_ANTI_AFK,   AntiAfkModule::new);

        // ---- Combat ----
        register(new CrosshairDamageIndicatorModule());
        register(new WeaponSwapReminderModule());

        // ---- Client-tier HUD additions (keystrokes/cps/clock/speed/totems/tps) ----
        register(new KeystrokesHudModule());
        register(new CpsHudModule());
        register(new ClockHudModule());
        register(new SpeedometerHudModule());
        register(new TotemCounterHudModule());
        register(new ServerTpsHudModule());
        register(new MemoryCleanerModule());

        // ---- Quality-of-life HUDs ----
        register(new DeathCoordsHudModule());
        register(new XpHudModule());
        register(new NumericPingModule());
        register(new PingBarsModule()); // opt-in coloured bars alongside numeric ping

        // ---- Radar + entity awareness ----
        register(new EntityRadarModule());
        register(new TargetHudModule());

        // ---- Performance ----
        register(new EntityCountHudModule());
        register(new PerfDashboardModule());

        // ---- Cosmetics ----
        register(new CapesModule());

        // ---- Legacy FoxFeature wrappers ----
        for (FoxFeature f : FeatureRegistry.all()) {
            register(new LegacyFeatureModule(f));
        }

        KitsuneClient.LOGGER.info("[Fox] ModuleManager init ({} modules)", MODULES.size());
    }

    public static void register(Module module) {
        MODULES.add(module);
    }

    /**
     * Conditionally register a module based on an {@link AddonFlags} flag.
     * Uses a supplier so the module's constructor — and any side effects
     * it has, like {@link dev.kitsune.client.hud.HudManager#register} —
     * runs only when the addon is enabled.
     *
     * <p>When the flag is disabled the module never enters MODULES, isn't
     * tickable, isn't visible in the ClickGUI, and posts no events. The
     * bytecode is still loaded by the JVM (bytecode-presence is undetectable
     * by behavioral anti-cheats but visible to class-scanning ones; if the
     * latter matters for your server, ALSO disable the whole jar in the
     * launcher's per-profile mod toggle).
     */
    public static void registerAddon(String flagId, java.util.function.Supplier<Module> ctor) {
        if (!AddonFlags.isAddonEnabled(flagId)) {
            KitsuneClient.LOGGER.info("[Fox] addon {} disabled — module not registered", flagId);
            return;
        }
        register(ctor.get());
    }

    public static List<Module> all() {
        return MODULES;
    }

    public static Module getByName(String name) {
        for (Module m : MODULES) {
            if (m.name().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T getModule(Class<T> type) {
        for (Module m : MODULES) {
            if (type.isInstance(m)) return (T) m;
        }
        return null;
    }

    public static List<Module> getByCategory(Category category) {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) {
            if (m.category() == category) out.add(m);
        }
        return out;
    }

    /** Called on raw key input — toggle any module bound to that key. */
    public static void onKeyPress(int glfwKey) {
        if (glfwKey < 0) return;
        for (Module m : MODULES) {
            if (m.keyBind() == glfwKey) {
                m.toggle();
                // Show notification for native modules (not legacy — those have their own system)
                if (!(m instanceof LegacyFeatureModule)) {
                    NotificationManager.show(
                            m.name() + (m.isEnabled() ? " enabled" : " disabled"),
                            m.isEnabled() ? NotificationManager.Type.SUCCESS : NotificationManager.Type.INFO);
                }
            }
        }
    }

    /**
     * Apply the per-module enabled state stored on a profile. Modules absent
     * from the profile map are left in their current state (so newly added
     * modules don't get force-disabled the first time an old profile loads).
     * Legacy {@link LegacyFeatureModule}s are skipped — those flow through
     * {@link FeatureRegistry#syncEnabledStates()}.
     */
    public static void applyProfileState(Profile p) {
        if (p == null) return;
        for (Module m : MODULES) {
            if (m instanceof LegacyFeatureModule) continue;
            Boolean want = p.getModuleEnabled(m.name());
            if (want == null) continue;
            if (m.isEnabled() != want) {
                try {
                    m.setEnabled(want);
                } catch (Throwable t) {
                    KitsuneClient.LOGGER.warn("[Fox] applyProfileState failed for {}: {}",
                            m.name(), t.toString());
                }
            }
        }
    }

    /**
     * Snapshot the current enabled state of every native module into the given
     * profile. Called when the user saves a profile so the saved JSON reflects
     * what's actually on right now.
     */
    public static void snapshotInto(Profile p) {
        if (p == null) return;
        for (Module m : MODULES) {
            if (m instanceof LegacyFeatureModule) continue;
            p.setModuleEnabled(m.name(), m.isEnabled());
        }
    }

    /** Called once per client tick by the {@code TickEvent} subscriber. */
    public static void tickAll() {
        for (Module m : MODULES) {
            if (m.isEnabled()) {
                try {
                    m.onTick();
                } catch (Throwable t) {
                    System.err.println("[Fox] Module " + m.name() + " tick threw: " + t);
                }
            }
        }
    }
}
