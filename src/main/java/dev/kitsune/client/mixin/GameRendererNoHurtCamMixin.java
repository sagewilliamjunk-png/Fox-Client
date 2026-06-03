package dev.kitsune.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kitsune.client.module.player.NoHurtCamModule;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels (or scales down) vanilla's damage camera shake.
 *
 * <p>When {@link NoHurtCamModule#multiplier()} is at 0 we short-circuit
 * {@code bobHurt} entirely — the GameRenderer renders the world without the
 * pitch/roll tilt and the screen stays steady. Non-zero values fall through to
 * vanilla; mathematical scaling of the tilt requires touching internals that
 * shift between MC patch versions, so we keep this mixin minimal and let
 * the slider be an on/off toggle effectively.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererNoHurtCamMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void kitsune$noHurtCam(PoseStack pose, float partialTick, CallbackInfo ci) {
        try {
            if (NoHurtCamModule.multiplier() <= 0.001f) ci.cancel();
        } catch (Throwable ignored) { /* fail safe */ }
    }
}
