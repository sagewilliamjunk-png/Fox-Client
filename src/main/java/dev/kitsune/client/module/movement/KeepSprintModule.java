package dev.kitsune.client.module.movement;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Keep Sprint — reasserts the sprint flag the tick after the player drops it
 * to damage. Lunar/Badlion ship the same feature; servers can't really tell
 * because we only set it when forward input is still present and the player
 * still has the food bar to allow sprinting.
 *
 * <p>Implemented without a mixin: track whether we were sprinting last tick;
 * if we're hurt this tick and the player is still trying to move forward,
 * flip sprint back on.
 */
public class KeepSprintModule extends Module {

    private boolean wantedSprint = false;

    public KeepSprintModule() {
        super("Keep Sprint", "Preserve sprint when you take damage.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        boolean sprintingNow = p.isSprinting();
        boolean wantsForward = false;
        try {
            // input.hasForwardImpulse() — the same field ToggleSprint reads.
            wantsForward = p.input != null && p.input.hasForwardImpulse();
        } catch (Throwable ignored) {}

        // We're being hurt and we WERE sprinting before — restore on the same
        // tick MC tries to drop it. Only when forward is still held and the
        // player isn't blocking / using item / sneaking (matches vanilla rules).
        boolean canSprint = wantsForward
                && !p.isCrouching()
                && !p.isUsingItem()
                && p.getFoodData().getFoodLevel() > 6;

        if (wantedSprint && canSprint && !sprintingNow && p.hurtTime > 0) {
            p.setSprinting(true);
            sprintingNow = true;
        }
        wantedSprint = sprintingNow;
    }

    @Override
    public void onDisable() {
        wantedSprint = false;
    }
}
