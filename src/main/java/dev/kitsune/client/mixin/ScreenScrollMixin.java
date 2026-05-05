package dev.kitsune.client.mixin;

import dev.kitsune.client.module.render.SmoothScrollModule;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales the {@code scrollY} parameter passed to
 * {@link AbstractContainerScreen#mouseScrolled(double, double, double, double)}
 * by {@link SmoothScrollModule#multiplier()}, applying the user-configured
 * scroll speed inside chests, inventories, and other container screens.
 *
 * <p>{@code multiplier()} returns {@code 1.0} when the module is disabled,
 * so this mixin is a runtime no-op until the user enables Smooth Scroll.
 */
@Mixin(AbstractContainerScreen.class)
public class ScreenScrollMixin {

    // (mouseX, mouseY, scrollX, scrollY) — all doubles, ordinal 3 is scrollY.
    @ModifyVariable(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
    private double kitsune$smoothScrollY(double scrollY) {
        double m = SmoothScrollModule.multiplier();
        return m == 1.0 ? scrollY : scrollY * m;
    }
}
