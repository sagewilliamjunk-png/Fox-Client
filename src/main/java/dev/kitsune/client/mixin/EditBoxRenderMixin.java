package dev.kitsune.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.kitsune.client.gui.chrome.FoxChrome;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skin vanilla {@link EditBox} chrome (search bars, server-name input, book
 * text, command input) with the Fox dark-panel look. Vanilla draws the border
 * by {@code blitSprite}-ing a widget texture — we wrap that single call so our
 * panel chrome replaces it while all text / cursor / selection / highlight
 * rendering continues unchanged downstream.
 *
 * <p>Uses MixinExtras {@link WrapOperation} so we can fully swap out the sprite
 * call without a {@code cancellable} boolean.
 */
@Mixin(EditBox.class)
public abstract class EditBoxRenderMixin {

    @WrapOperation(
            method = "renderWidget",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
    )
    private void kitsune$replaceBorderSprite(GuiGraphics gfx,
                                              RenderPipeline pipeline,
                                              Identifier sprite,
                                              int x, int y, int w, int h,
                                              Operation<Void> original) {
        EditBox self = (EditBox) (Object) this;
        boolean focused = self.isFocused();
        // Use the focus state as the "hover" for border accent, so the orange
        // border lights up while the player is typing.
        float accent = focused ? 1f : 0f;
        FoxChrome.paintPanel(gfx, x, y, w, h, self.isActive(), accent);
    }
}
