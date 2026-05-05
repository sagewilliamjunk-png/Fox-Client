package dev.kitsune.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.kitsune.client.gui.chrome.FoxChrome;
import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fox theme for vanilla selection lists (world list, server list,
 * resource-pack list, stat list, realms list). Replaces:
 * <ul>
 *   <li>The tiled stone/dirt list background (drawn in
 *       {@code renderListBackground}) with a flat dark-bark panel so the
 *       list reads as part of the Fox chrome instead of a separate vanilla
 *       surface.</li>
 *   <li>The two gray/white selection rectangles (drawn in
 *       {@code renderSelection} as an outer outline + inner black fill)
 *       with Fox-orange border + dark Fox interior, matching buttons and
 *       edit fields.</li>
 * </ul>
 *
 * <p>Entry content ({@code renderContent}) is left to each entry subclass —
 * icons, player heads, world thumbnails continue to render as vanilla
 * intends; only the framing chrome around them is re-skinned.
 *
 * <p>The selection chrome swap uses MixinExtras {@link WrapOperation} on the
 * two {@code GuiGraphics.fill} calls rather than a HEAD-cancellable inject,
 * because the target's {@code E entry} parameter resolves to the inner
 * {@code Entry} class which is {@code protected} at Yarn source level —
 * referencing it from a mixin in another package fails to compile. Wrapping
 * the two fill ops sidesteps the parameter signature entirely.
 */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {

    @Inject(method = "renderListBackground", at = @At("HEAD"), cancellable = true)
    private void kitsune$paintFoxListBackground(GuiGraphics gfx, CallbackInfo ci) {
        AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
        int x = self.getX();
        int y = self.getY();
        int r = self.getRight();
        int b = self.getBottom();
        // Flat dark bark panel instead of the tiled vanilla texture.
        gfx.fill(x, y, r, b, FoxChrome.BG_ACTIVE);
        // Subtle inner top highlight so the panel matches button chrome.
        gfx.fill(x + 1, y + 1, r - 1, y + 2, FoxChrome.TOP_HIGHLIGHT);
        ci.cancel();
    }

    /**
     * Wrap the first {@code fill(x0, y0, x1, y1, outlineColor)} call —
     * the outer selection rectangle — and replace it with a 1 px
     * Fox-orange border. We identify this call by its ordinal = 0 in the
     * target method (first of two).
     */
    @WrapOperation(
            method = "renderSelection",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V",
                     ordinal = 0)
    )
    private void kitsune$replaceOuterSelect(GuiGraphics gfx,
                                             int x0, int y0, int x1, int y1, int color,
                                             Operation<Void> original) {
        int accent = FoxTheme.FOX_ORANGE;
        gfx.fill(x0,     y0,     x1,     y0 + 1, accent);
        gfx.fill(x0,     y1 - 1, x1,     y1,     accent);
        gfx.fill(x0,     y0,     x0 + 1, y1,     accent);
        gfx.fill(x1 - 1, y0,     x1,     y1,     accent);
    }

    /**
     * Wrap the second {@code fill(...)} — the inner black rectangle — and
     * replace its color with Fox's dark bark so entry content reads on the
     * same palette as the rest of the UI instead of pure black.
     */
    @WrapOperation(
            method = "renderSelection",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V",
                     ordinal = 1)
    )
    private void kitsune$replaceInnerSelect(GuiGraphics gfx,
                                             int x0, int y0, int x1, int y1, int color,
                                             Operation<Void> original) {
        gfx.fill(x0, y0, x1, y1, FoxChrome.BG_ACTIVE);
    }
}
