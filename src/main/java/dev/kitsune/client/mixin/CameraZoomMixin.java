package dev.kitsune.client.mixin;

import dev.kitsune.client.module.misc.ZoomModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-frame FOV override for {@link ZoomModule}.
 *
 * <p>Injects at the return of {@code Camera.getFov()} and divides the returned
 * FOV by {@link ZoomModule#getEffectiveZoomFactor()} when zoomed. In MC 26.x
 * the old {@code GameRenderer.getFov(Camera, float, boolean)} was removed;
 * FOV is now read from {@code Camera.getFov()} inside {@code
 * GameRenderer.extractCamera} and forwarded to the Projection / CameraRenderState.
 *
 * <p>Priority bumped to 1100 so we run after Sodium / Iris / other vendor
 * adjustments — their math sees vanilla, then ours scales the final result.
 */
@Mixin(value = Camera.class, priority = 1100)
public class CameraZoomMixin {

    @Inject(method = "getFov()F",
            at = @At("RETURN"),
            cancellable = true)
    private void kitsune$applyZoom(CallbackInfoReturnable<Float> cir) {
        double mult = ZoomModule.getEffectiveZoomFactor();
        if (mult > 1.001) {
            float fov = cir.getReturnValueF();
            cir.setReturnValue((float) (fov / mult));
        }
    }
}
