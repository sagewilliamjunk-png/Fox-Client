package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.hud.NumericPingModule;
import dev.kitsune.client.module.render.PingBarsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link PlayerTabOverlay#renderPingIcon} so Kitsune modules can
 * replace the vanilla 5-bar texture with richer displays.
 *
 * <p><b>NumericPingModule</b> (enabled by default) replaces bars with an
 * "{ms}" string — e.g. "42ms" — colour-coded by latency.
 *
 * <p><b>PingBarsModule</b> (opt-in, disabled by default) draws four coloured
 * signal bars that shade from green → red as ping rises.
 *
 * <p>When both are active, the number is drawn to the left of the bars.
 * When {@code PingBarsModule.hideNumbers} is on the number is suppressed.
 *
 * <p>If neither module is enabled the mixin returns early and vanilla draws
 * the bars as normal — no cancel, no side effects.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayPingMixin {

    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void kitsune$drawPingDisplay(GuiGraphics gfx, int width, int x, int y,
                                         PlayerInfo player, CallbackInfo ci) {
        NumericPingModule numMod  = ModuleManager.getModule(NumericPingModule.class);
        PingBarsModule    barsMod = ModuleManager.getModule(PingBarsModule.class);

        boolean numEnabled  = numMod  != null && numMod.isEnabled();
        boolean barsEnabled = barsMod != null && barsMod.isEnabled();

        // Neither module active → let vanilla render its texture atlas bars.
        if (!numEnabled && !barsEnabled) return;
        if (player == null) return;

        ci.cancel(); // we are taking full ownership of this slot

        int ms        = Math.max(0, player.getLatency());
        int rightEdge = x + width; // right boundary of the icon area

        // 1. Draw signal bars (if enabled) flush to the right edge.
        if (barsEnabled) {
            barsMod.drawBars(gfx, rightEdge, y, ms);
            rightEdge -= (PingBarsModule.BARS_TOTAL_W + 2); // reserve space + small gap
        }

        // 2. Draw the numeric ms display (if enabled and not suppressed by hideNumbers).
        boolean showNumber = numEnabled && !(barsEnabled && barsMod.hideNumbers());
        if (showNumber) {
            int cap = numMod.displayCap();
            String text = (ms > cap) ? (cap + "+") : String.valueOf(ms);
            if (numMod.withSuffix()) text += "ms";

            Font font  = Minecraft.getInstance().font;
            int  color = numMod.colorFor(ms);
            int  textW = font.width(text);
            // Right-align the number within the remaining space.
            gfx.drawString(font, text, rightEdge - textW, y + 1, color, true);
        }
    }
}
