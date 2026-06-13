package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.Setting;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved-config compatibility guard for the v1.5 BaseHudModule migration.
 *
 * <p>Module settings serialise BY NAME and HUD layouts persist BY WIDGET ID,
 * so renaming either silently resets users' saved values. This test pins the
 * names/ids of every migrated widget; if a refactor changes one, this fails
 * before a release does.
 *
 * <p>Runs headless: the constructors only build Setting objects and register
 * with HudManager's in-memory map — no Minecraft bootstrap required.
 */
class HudSettingsCompatTest {

    private static void assertWidget(Supplier<Module> ctor, String widgetId, String... settingNames) {
        Module m = ctor.get();
        assertTrue(m instanceof BaseHudModule, m.name() + " should extend BaseHudModule");
        assertEquals(widgetId, ((BaseHudModule) m).widgetId(), m.name() + " widget id");
        List<String> actual = m.settings().stream().map(Setting::name).toList();
        for (String expected : settingNames) {
            assertTrue(actual.contains(expected),
                    m.name() + " lost setting \"" + expected + "\" — saved configs would reset. Has: " + actual);
        }
    }

    @Test void clock() {
        assertWidget(ClockHudModule::new, "clock",
                "Format", "Show Seconds", "Show Date", "Show Game Time",
                "BG Opacity", "Accent", "Text Color");
    }

    @Test void cps() {
        assertWidget(CpsHudModule::new, "cps",
                "Show Left", "Show Right", "Show Labels", "Style",
                "Split L/R Colors", "Left Color", "Right Color",
                "BG Opacity", "Accent", "Text Color");
    }

    @Test void speedometer() {
        assertWidget(SpeedometerHudModule::new, "speedometer",
                "Axis", "Unit", "Show Peak", "Show Label",
                "BG Opacity", "Accent", "Text Color");
    }

    @Test void serverTps() {
        assertWidget(ServerTpsHudModule::new, "server_tps",
                "Show Label", "Colorize", "Show MSPT", "BG Opacity", "Accent");
    }

    @Test void entityCount() {
        assertWidget(EntityCountHudModule::new, "entity_count",
                "Living", "Items", "Players", "BG Opacity", "Accent");
    }

    @Test void xp() {
        assertWidget(XpHudModule::new, "xp_hud",
                "Show Bar", "Show Percent", "Show XP To Next", "BG Opacity", "Accent");
    }

    @Test void deathCoords() {
        assertWidget(DeathCoordsHudModule::new, "death_coords",
                "Show Dimension", "Show Distance", "Show Recent", "BG Opacity", "Accent");
    }

    @Test void totemCounter() {
        assertWidget(TotemCounterHudModule::new, "totem_counter",
                "Show Icon", "Warn Low", "Hide at Zero", "Warn At", "Warn Color",
                "BG Opacity", "Accent", "Text Color");
    }

    @Test void mountHud() {
        assertWidget(MountHudModule::new, "mount_hud",
                "Show on Boats", "Horse Jump/Speed", "Show Mount Name",
                "BG Opacity", "Accent");
    }

    @Test void coords() {
        assertWidget(CoordsHudModule::new, "coords",
                "Show Direction", "Show Block Pos", "Show Chunk", "Show Nether Equiv",
                "Show Biome", "Facing Arrow", "Compact Mode", "BG Opacity",
                "Precision", "Accent");
    }

    @Test void serverInfo() {
        assertWidget(ServerInfoHudModule::new, "server_info",
                "Show Server Name", "Show Ping Bar", "Show TPS Bar", "Show Numbers",
                "Show Player Count", "Compact Mode", "Good Ping (ms)", "Bad Ping (ms)", "Accent");
    }

    @Test void sessionStats() {
        assertWidget(SessionStatsModule::new, "session_stats",
                "Show Time", "Show Distance", "Show Speed", "Show XP Level",
                "Compact Mode", "Distance Unit");
    }

    @Test void killDeath() {
        assertWidget(KillDeathTrackerModule::new, "kill_death",
                "Show Kill Streak", "Show K/D Ratio", "Compact Mode", "Show Ratio Bar",
                "Good K/D", "OK K/D", "Accent");
    }

    @Test void keystrokes() {
        assertWidget(KeystrokesHudModule::new, "keystrokes",
                "Show Mouse", "Show CPS", "Show Space", "Key Size", "BG Opacity",
                "Idle Color", "Press Color", "Text Color", "Pressed Text", "CPS Tint");
    }

    // v1.6 PvP widgets — combat package but extend BaseHudModule.
    @Test void comboCounter() {
        assertWidget(dev.kitsune.client.module.combat.ComboCounterModule::new, "combo_counter",
                "Reset Window (s)", "Show Best", "BG Opacity", "Accent", "Text Color");
    }

    @Test void damageTally() {
        assertWidget(dev.kitsune.client.module.combat.DamageTallyModule::new, "damage_tally",
                "Show Ratio", "BG Opacity", "Accent", "Text Color");
    }
}
