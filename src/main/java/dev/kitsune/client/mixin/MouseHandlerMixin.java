package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.movement.FreeLookModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects {@link LocalPlayer#turn(double, double)} during mouse input so
 * that {@link FreeLookModule} can absorb mouse deltas into its own camera
 * rotation instead of turning the player body.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Redirect(method = "turnPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void kitsune$redirectTurn(LocalPlayer player, double yRot, double xRot) {
        try {
            FreeLookModule mod = ModuleManager.getModule(FreeLookModule.class);
            if (mod != null && mod.isEnabled() && mod.isFreeLookActive()) {
                mod.applyMouseDelta(yRot, xRot);
                return;
            }
        } catch (Throwable t) {
            // Fall through to vanilla
        }
        player.turn(yRot, xRot);
    }
}
