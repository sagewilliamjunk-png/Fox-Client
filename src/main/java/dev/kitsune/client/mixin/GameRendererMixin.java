package dev.kitsune.client.mixin;

import dev.kitsune.client.features.qol.ZoomFeature;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-frame FOV override for {@link ZoomFeature}.
 *
 * <p>Injects at the return of {@code GameRenderer.getFov(Camera, float, boolean)}
 * and divides the returned FOV by {@link ZoomFeature#getEffectiveZoomFactor()}
 * when zoomed. Crucially we DO NOT touch {@code mc.options.fov()} — a crash
 * mid-zoom never corrupts the user's saved FOV setting.
 *
 * <p>Only active when {@code useFovSetting == true} so that hand-held maps,
 * loading screens, and other off-FOV draws aren't affected.
 *
 * <p>Priority bumped to 1100 so we run after Sodium / Iris / other vendor
 * adjustments — their math sees vanilla, then ours scales the final result.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public class GameRendererMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F",
            at = @At("RETURN"),
            cancellable = true)
    private void kitsune$applyZoom(Camera camera, float partialTick, boolean useFovSetting,
                                   CallbackInfoReturnable<Float> cir) {
        if (!useFovSetting) return;
        double mult = ZoomFeature.getEffectiveZoomFactor();
        if (mult > 1.001) {
            float fov = cir.getReturnValueF();
            cir.setReturnValue((float) (fov / mult));
        }
    }
}
