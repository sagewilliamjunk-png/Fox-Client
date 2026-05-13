package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla Minecraft logo rendering when the Fox title screen is active,
 * so Fox Client owns that visual real estate.
 *
 * Only fires on the title screen — other screens that use LogoRenderer (e.g.
 * Realms intro) are untouched.
 */
@Mixin(LogoRenderer.class)
public class LogoRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V", at = @At("HEAD"), cancellable = true)
    private void kitsune$cancelOnTitle(GuiGraphicsExtractor gfx, int width, float fade, CallbackInfo ci) {
        if (kitsune$shouldHide()) ci.cancel();
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V", at = @At("HEAD"), cancellable = true)
    private void kitsune$cancelOnTitleAlt(GuiGraphicsExtractor gfx, int width, float fade, int yOffset, CallbackInfo ci) {
        if (kitsune$shouldHide()) ci.cancel();
    }

    private static boolean kitsune$shouldHide() {
        if (!KitsuneConfig.get().foxTitleScreen) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.screen instanceof TitleScreen;
    }
}
