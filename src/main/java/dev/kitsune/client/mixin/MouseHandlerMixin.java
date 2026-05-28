package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.misc.HotbarScrollLockModule;
import dev.kitsune.client.module.movement.FreeLookModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Two mouse-input redirects:
 * <ul>
 *   <li>{@link LocalPlayer#turn(double, double)} during {@code turnPlayer} so
 *       {@link FreeLookModule} can absorb mouse deltas into its own camera
 *       rotation instead of turning the player body.</li>
 *   <li>{@link Inventory#setSelectedSlot(int)} during {@code onScroll} so
 *       {@link HotbarScrollLockModule} can stop the wheel from changing the
 *       held hotbar slot. This is the in-world scroll branch only — screen
 *       scrolling and creative fly-speed scroll are untouched.</li>
 * </ul>
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

    /**
     * The hotbar slot change lives only in {@code onScroll}'s in-world branch
     * (when no screen is open), so swallowing this single call is enough to
     * lock the wheel without affecting GUI scrolling. Wrapped in a try/catch so
     * a module-lookup failure can never break vanilla scrolling.
     */
    @Redirect(method = "onScroll",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
    private void kitsune$lockHotbarScroll(Inventory inventory, int slot) {
        try {
            if (HotbarScrollLockModule.shouldIntercept()) return; // keep the current slot
        } catch (Throwable t) {
            // Fall through to vanilla on any error
        }
        inventory.setSelectedSlot(slot);
    }
}
