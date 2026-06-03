package dev.kitsune.client.mixin;

import dev.kitsune.client.module.misc.ShulkerTooltipModule;
import dev.kitsune.client.tooltip.ShulkerPreviewTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Injects a {@link ShulkerPreviewTooltip} visual component into shulker-box
 * item tooltips when Alt is held (or Alt+Shift for the full grid view).
 *
 * <p>Only fires when {@link ShulkerTooltipFeature} is enabled. If the item
 * already has a tooltip image (e.g. bundle) we leave it untouched.
 */
@Mixin(Item.class)
public class ItemTooltipImageMixin {

    @Inject(method = "getTooltipImage",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void kitsune$shulkerPreviewImage(ItemStack stack,
                                              CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
        try {
            // Don't clobber existing tooltip images (bundles, maps, etc.)
            if (cir.getReturnValue().isPresent()) return;
            // Feature must be enabled
            if (!ShulkerTooltipModule.isActive()) return;

            // Per user spec: visual grid appears on Shift alone. (Alt+Shift
            // adds the sticky/pin behaviour, drawn separately by
            // ScreenStickyShulkerMixin; we still emit the tooltip path so the
            // grid initially renders normally and gets its screen position.)
            com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
            boolean shiftDown = InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
            if (!shiftDown) return;

            // Must be a shulker box BlockItem
            if (!((Object) this instanceof BlockItem bi)) return;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) return;
            // Must have stored contents
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null || contents.nonEmptyItemCopyStream().findAny().isEmpty()) return;

            // Remember the contents so ScreenStickyShulkerMixin can pin them
            // at the live mouse position when Alt+Shift is held. The mouse
            // coordinates aren't reliably gui-scaled inside Item.getTooltipImage
            // so the screen-render mixin handles that step.
            dev.kitsune.client.tooltip.ShulkerPinManager.touch(contents);

            cir.setReturnValue(Optional.of(new ShulkerPreviewTooltip(contents)));
        } catch (Throwable ignored) {}
    }
}
