package dev.kitsune.client.module.misc;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action;

/**
 * Configurable death screen behaviour:
 * <ul>
 *   <li><b>Auto Respawn</b> — the moment a {@link DeathScreen} appears,
 *       send the respawn packet and close the screen. No clicking "Respawn".</li>
 *   <li><b>Show Stats</b> — keep the death stats readable for a moment before
 *       respawn (adds a ~1s delay so the user can glance at score/time).</li>
 * </ul>
 *
 * <p>Implemented as a tick-poll check — no mixin needed. Simple and robust.
 */
public class DeathScreenModule extends Module {

    // Defaults to OFF: some ranked-PvP servers treat instant-respawn as an
    // advantage and have banned for it. User opts in via the per-profile UI.
    private final BooleanSetting autoRespawn = addSetting(
            new BooleanSetting("Auto Respawn", false));
    private final BooleanSetting showStats = addSetting(
            new BooleanSetting("Brief Delay", false));
    // Only fire in single-player by default. Toggle off if you want it on
    // public servers (and check that server's rules first).
    private final BooleanSetting singlePlayerOnly = addSetting(
            new BooleanSetting("Single-player only", true));

    private int delayTicks = 0;

    public DeathScreenModule() {
        super("Death Screen", "Auto-respawn and death screen tweaks", Category.MISC);
    }

    @Override
    public void onTick() {
        if (!autoRespawn.get()) {
            delayTicks = 0;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        // Single-player gate: hasSingleplayerServer() is true only for locally
        // hosted integrated servers. LAN-opened single-player counts here too.
        if (singlePlayerOnly.get() && !mc.hasSingleplayerServer()) return;

        if (mc.screen instanceof DeathScreen) {
            if (showStats.get() && delayTicks < 20) {
                delayTicks++;
                return;
            }
            // Send the same respawn packet the vanilla button does
            mc.getConnection().send(new ServerboundClientCommandPacket(Action.PERFORM_RESPAWN));
            mc.setScreen(null);
            delayTicks = 0;
        } else {
            delayTicks = 0;
        }
    }
}
