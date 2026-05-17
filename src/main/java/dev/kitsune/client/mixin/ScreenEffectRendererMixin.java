package dev.kitsune.client.mixin;

import dev.kitsune.client.module.render.LowFireModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the fire overlay when {@link LowFireModule} is active.
 *
 * <p>Injects at the HEAD of {@code renderFireOverlay} and cancels it entirely.
 * The player still takes fire damage and sees fire particles in third-person;
 * only the first-person screen overlay is hidden.
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    @Inject(method = "renderFireOverlay",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void kitsune$lowFire(Minecraft mc, MultiBufferSource bufferSource, CallbackInfo ci) {
        try {
            if (LowFireModule.isActive()) ci.cancel();
        } catch (Throwable ignored) {}
    }
}
