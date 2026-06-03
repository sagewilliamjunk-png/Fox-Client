package dev.kitsune.client.module.player;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;

/**
 * Auto-respawn — clicks the "Respawn" button after a short delay so you don't
 * have to stare at the death screen. Delay is configurable so it doesn't feel
 * instant (people often want a moment to read the death message).
 */
public class AutoRespawnModule extends Module {

    private final SliderSetting delayMs = addSetting(new SliderSetting("Delay (ms)", 500, 0, 5000, 100));

    private long deathTime = 0;

    public AutoRespawnModule() {
        super("Auto Respawn", "Click Respawn for you after a short delay.", Category.PLAYER);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof DeathScreen)) {
            deathTime = 0;
            return;
        }
        if (mc.player == null) return;

        if (deathTime == 0) deathTime = System.currentTimeMillis();
        if (System.currentTimeMillis() - deathTime < delayMs.get()) return;

        // Both calls are necessary: respawn() asks the server to spawn us, and
        // setScreen(null) closes the death screen so we're back in-world.
        try {
            mc.player.respawn();
            mc.setScreen(null);
        } catch (Throwable ignored) { /* mapping drift — fail safe, do nothing */ }
        deathTime = 0;
    }
}
