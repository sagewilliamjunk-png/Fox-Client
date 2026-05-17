package dev.kitsune.client.mixin;

import dev.kitsune.client.module.render.LowShieldModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ShieldItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.client.renderer.ItemInHandRenderer;

/**
 * Keeps the off-hand shield in its lowered position while the player is blocking,
 * so it doesn't cover the left side of the screen.
 *
 * <p>Targets the second {@code renderArmWithItem} call inside
 * {@code renderHandsWithItems} (ordinal 1 = off-hand) and forces
 * {@code equippedProgress} to {@code 0.0f} when the player is actively
 * using a shield. This keeps the arm animation in its resting pose even
 * while the shield is raised server-side.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @ModifyArg(
            method = "renderHandsWithItems",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderArmWithItem"
                           + "(Lnet/minecraft/client/player/LocalPlayer;F"
                           + "Lnet/minecraft/world/InteractionHand;F"
                           + "Lnet/minecraft/world/item/ItemStack;"
                           + "FLcom/mojang/blaze3d/vertex/PoseStack;"
                           + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
                    ordinal = 1),
            index = 3,  // equippedProgress is the 4th parameter (0-indexed = 3)
            require = 0)
    private float kitsune$lowerShield(float equippedProgress) {
        try {
            if (!LowShieldModule.isActive()) return equippedProgress;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return equippedProgress;
            // Only lower if the player is actively blocking with a shield in the off-hand
            if (player.isUsingItem()
                    && player.getOffhandItem().getItem() instanceof ShieldItem) {
                return 0.0f;
            }
        } catch (Throwable ignored) {}
        return equippedProgress;
    }
}
