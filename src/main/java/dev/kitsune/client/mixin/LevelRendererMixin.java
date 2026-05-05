package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.render.BlockOverlayModule;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Hooks {@link LevelRenderer#renderBlockOutline} so {@link BlockOverlayModule}
 * can swap the block selection outline color/line width. The vanilla code
 * makes two {@code renderHitOutline} calls (shadow + main outline); we only
 * modify the main (second) call so the black backdrop still gives contrast.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    private static final String RENDER_HIT_OUTLINE_TARGET =
            "Lnet/minecraft/client/renderer/LevelRenderer;renderHitOutline("
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                    + "DDD"
                    + "Lnet/minecraft/client/renderer/state/BlockOutlineRenderState;"
                    + "IF)V";

    // Color int argument (index 6 in the method signature)
    @ModifyArg(method = "renderBlockOutline",
            at = @At(value = "INVOKE", target = RENDER_HIT_OUTLINE_TARGET, ordinal = 1),
            index = 6,
            require = 0)
    private int kitsune$overrideOutlineColor(int original) {
        try {
            BlockOverlayModule mod = ModuleManager.getModule(BlockOverlayModule.class);
            if (mod != null && mod.isEnabled()) {
                // Fox orange, fully opaque: 0xFFFFA552
                return 0xFFFFA552;
            }
        } catch (Throwable t) {
            // ignore
        }
        return original;
    }

    // Line width argument (index 7)
    @ModifyArg(method = "renderBlockOutline",
            at = @At(value = "INVOKE", target = RENDER_HIT_OUTLINE_TARGET, ordinal = 1),
            index = 7,
            require = 0)
    private float kitsune$overrideOutlineWidth(float original) {
        try {
            BlockOverlayModule mod = ModuleManager.getModule(BlockOverlayModule.class);
            if (mod != null && mod.isEnabled() && mod.thickOutline()) {
                return Math.max(original, 3.5f);
            }
        } catch (Throwable t) {
            // ignore
        }
        return original;
    }
}
