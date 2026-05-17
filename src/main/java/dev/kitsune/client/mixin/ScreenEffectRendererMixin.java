package dev.kitsune.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kitsune.client.module.render.LowFireModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements Low Fire by translating the fire overlay's PoseStack down so
 * only a thin strip at the bottom of the screen remains visible, instead
 * of the default full-screen orange wash.
 *
 * <p>Injects into the private {@code renderFire(PoseStack, MultiBufferSource,
 * TextureAtlasSprite)} method, which is called twice by {@code renderScreenEffect}
 * (once per fire sprite). Each call is independently push/translated/popped so
 * the two sprites stay in sync and the stack is always balanced.
 */
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    /** Guards against pop without push if the module state changes mid-frame. */
    private static boolean firePushed = false;

    @Inject(method = "renderFire",
            at = @At("HEAD"),
            require = 0)
    private static void kitsune$lowFirePre(PoseStack poseStack,
                                           MultiBufferSource bufferSource,
                                           TextureAtlasSprite sprite,
                                           CallbackInfo ci) {
        try {
            if (!LowFireModule.isActive()) return;
            poseStack.pushPose();
            float h = (float) Minecraft.getInstance().getWindow().getGuiScaledHeight();
            poseStack.translate(0f, h * 0.82f, 0f);
            firePushed = true;
        } catch (Throwable ignored) {}
    }

    @Inject(method = "renderFire",
            at = @At("RETURN"),
            require = 0)
    private static void kitsune$lowFirePost(PoseStack poseStack,
                                            MultiBufferSource bufferSource,
                                            TextureAtlasSprite sprite,
                                            CallbackInfo ci) {
        try {
            if (!firePushed) return;
            firePushed = false;
            poseStack.popPose();
        } catch (Throwable ignored) {}
    }
}
