package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.screen.StarrySkyRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paint the Fox starry-sky behind every main-menu screen (Select World,
 * Play Multiplayer, Options, Resource Packs, etc.) instead of the vanilla
 * dark dirt tile. Keeps the whole menu flow visually unified with the
 * title screen the player just came from.
 *
 * <p>Only runs when {@code minecraft.level == null} — in-world pause menus
 * continue to use vanilla's transparent-over-blur background (that's the
 * other {@code renderMenuBackground} overload on {@code Screen}, different
 * call site).
 *
 * <p>We hook the four-arg overload {@code renderMenuBackground(GuiGraphics,
 * int, int, int, int)} at HEAD + cancellable. The two-arg overload
 * delegates into this one, so a single hook covers both call paths.
 */
@Mixin(Screen.class)
public abstract class MenuBackgroundMixin {

    private float kitsune$bgTick = 0f;

    @Inject(
            method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;IIII)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void kitsune$paintStarryBg(GuiGraphics gfx, int x, int y, int w, int h,
                                        CallbackInfo ci) {
        if (!KitsuneConfig.get().foxTitleScreen) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) return; // in-world: leave vanilla blur alone

        // Continuous drift so stars twinkle even on static screens
        kitsune$bgTick += 1f;
        Screen self = (Screen) (Object) this;
        StarrySkyRenderer.render(gfx, self.width, self.height, kitsune$bgTick);
        ci.cancel();
    }
}
