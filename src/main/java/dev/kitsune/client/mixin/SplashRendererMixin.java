package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla "Call your mother!" splash text when the Fox title screen
 * is active.
 */
@Mixin(SplashRenderer.class)
public class SplashRendererMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void kitsune$cancelOnTitle(GuiGraphicsExtractor gfx, int width, Font font, float fade, CallbackInfo ci) {
        if (!KitsuneConfig.get().foxTitleScreen) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof TitleScreen) ci.cancel();
    }
}
