package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    @Inject(method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V", at = @At("HEAD"), cancellable = true)
    private void kitsune$cancelOnTitle(GuiGraphics gfx, int width, float fade, CallbackInfo ci) {
        if (kitsune$shouldHide()) ci.cancel();
    }

    @Inject(method = "renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IFI)V", at = @At("HEAD"), cancellable = true)
    private void kitsune$cancelOnTitleAlt(GuiGraphics gfx, int width, float fade, int yOffset, CallbackInfo ci) {
        if (kitsune$shouldHide()) ci.cancel();
    }

    private static boolean kitsune$shouldHide() {
        if (!KitsuneConfig.get().foxTitleScreen) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.screen instanceof TitleScreen;
    }
}
