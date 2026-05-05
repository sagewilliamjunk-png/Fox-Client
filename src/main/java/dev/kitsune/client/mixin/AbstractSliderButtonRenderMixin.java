package dev.kitsune.client.mixin;

import dev.kitsune.client.gui.chrome.FoxChrome;
import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fox theme for vanilla sliders (e.g. FOV in the Options screen, music /
 * sound volumes, render distance). Draws the Fox panel chrome, a dark
 * track, and an orange-accented handle positioned by the slider's current
 * value. Text is centered over the handle just like vanilla.
 */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonRenderMixin {

    @Shadow protected double value;

    @Unique private float kitsune$hoverLerp = 0f;
    @Unique private long  kitsune$lastRenderMs = 0L;

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void kitsune$paintFoxSlider(GuiGraphics gfx, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
        AbstractSliderButton s = (AbstractSliderButton) (Object) this;
        int x = s.getX();
        int y = s.getY();
        int w = s.getWidth();
        int h = s.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean hovered = s.isHovered() && s.active;

        long now = System.currentTimeMillis();
        float dt = kitsune$lastRenderMs == 0L ? 16f : (now - kitsune$lastRenderMs);
        kitsune$lastRenderMs = now;
        kitsune$hoverLerp = FoxChrome.stepHover(
                kitsune$hoverLerp, hovered ? 1f : 0f, dt);

        // Base panel (shadow, bg, border, top highlight)
        FoxChrome.paintPanel(gfx, x, y, w, h, s.active, kitsune$hoverLerp);

        // Track — a slightly inset darker rect so the value is legible
        int trackX = x + 3;
        int trackY = y + h - 5;
        int trackW = w - 6;
        if (trackW > 0) {
            gfx.fill(trackX, trackY, trackX + trackW, trackY + 2, FoxChrome.SLIDER_TRACK);
            // Filled portion up to current value — orange progress
            double v = Math.max(0.0, Math.min(1.0, value));
            int fillW = (int) (trackW * v);
            if (fillW > 0) {
                int progressColor = s.active
                        ? FoxTheme.FOX_ORANGE
                        : 0xFF6A5030;
                gfx.fill(trackX, trackY, trackX + fillW, trackY + 2, progressColor);
            }
        }

        // Handle — thin vertical bar at the value position
        double v = Math.max(0.0, Math.min(1.0, value));
        int handleW = 4;
        int handleX = x + 3 + (int) ((w - 6 - handleW) * v);
        int handleTop = y + 2;
        int handleBot = y + h - 2;
        int handleFill = s.active ? FoxChrome.SLIDER_HANDLE : FoxChrome.BG_DISABLED;
        gfx.fill(handleX, handleTop, handleX + handleW, handleBot, handleFill);
        // Handle border — bright on hover
        int handleBorder = FoxChrome.lerpARGB(0xFF4A3520, FoxTheme.FOX_ORANGE, kitsune$hoverLerp);
        gfx.fill(handleX, handleTop, handleX + handleW, handleTop + 1, handleBorder);
        gfx.fill(handleX, handleBot - 1, handleX + handleW, handleBot, handleBorder);
        gfx.fill(handleX, handleTop, handleX + 1, handleBot, handleBorder);
        gfx.fill(handleX + handleW - 1, handleTop, handleX + handleW, handleBot, handleBorder);

        // Text — same centered style as buttons, but a little higher so it
        // doesn't collide with the track at the bottom
        int textColor = s.active
                ? FoxChrome.lerpARGB(FoxChrome.TEXT_ACTIVE, FoxChrome.TEXT_HOVER, kitsune$hoverLerp)
                : FoxChrome.TEXT_DISABLED;
        int tx = x + w / 2;
        int ty = y + 3;
        gfx.drawCenteredString(Minecraft.getInstance().font, s.getMessage(), tx, ty, textColor);

        ci.cancel();
    }
}
