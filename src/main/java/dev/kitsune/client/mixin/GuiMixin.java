package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.event.RenderHudEvent;
import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.render.DynamicCrosshairModule;
import dev.kitsune.client.screen.FoxBranding;
import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a small Fox Client watermark + active profile chip in the top-left of
 * the in-game HUD. Hidden when F3 is open or hideGui is on. Toggleable via
 * {@link KitsuneConfig#showWatermark}.
 */
@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kitsune$drawHudOverlays(GuiGraphicsExtractor gfx, DeltaTracker delta, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.options.hideGui) return;

        // Watermark (optional)
        if (KitsuneConfig.get().showWatermark) {
            FoxBranding.drawFoxGlyph(gfx, 4, 4, 16);
            gfx.text(mc.font, "\u00a76Fox \u00a7eClient", 24, 4, FoxTheme.FOX_CREAM);
            gfx.text(mc.font, "\u00a78\u00b7 \u00a76" + FoxTheme.capitalize(ProfileManager.getActiveName()),
                    24, 14, FoxTheme.TEXT_MUTED);
        }

        // Draggable HUD widgets (coords, potions, fps, etc.)
        HudManager.renderAll(gfx);

        // Legacy event-bus HUD subscribers (kept for non-widget overlays)
        EventBus.post(new RenderHudEvent(gfx));

        // Notification toasts (always visible)
        NotificationManager.render(gfx);
    }

    /**
     * Suppress vanilla's crosshair render when DynamicCrosshairModule is
     * active — otherwise both crosshairs draw and the module's custom
     * styles look muddy on top of vanilla's white plus sign.
     */
    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void kitsune$maybeSuppressVanillaCrosshair(GuiGraphicsExtractor gfx, DeltaTracker delta, CallbackInfo ci) {
        DynamicCrosshairModule m = ModuleManager.getModule(DynamicCrosshairModule.class);
        if (m != null && m.isEnabled()) {
            ci.cancel();
        }
    }
}
