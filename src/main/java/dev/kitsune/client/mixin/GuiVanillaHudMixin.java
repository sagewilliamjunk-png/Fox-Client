package dev.kitsune.client.mixin;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.VanillaHudProxies;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates vanilla HUD elements (hotbar, health, food, air, experience bar)
 * by the user-chosen delta from the {@link dev.kitsune.client.hud.HudEditorScreen}.
 *
 * <p>Each render method of {@link Gui} we target is wrapped with a
 * {@code pose.pushPose() / translate / popPose()} pair so the underlying draw
 * code is untouched. The translation amount comes from
 * {@link HudManager#vanillaOffset(String, int, int)}.
 *
 * <p>This is a no-op until the user actually moves a widget — the offset
 * lookup returns {@code (0, 0)} for unmoved proxies, so the pose stack work is
 * negligible for default layouts.
 */
@Mixin(Gui.class)
public abstract class GuiVanillaHudMixin {

    // ---------- Hotbar ----------

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"))
    private void kitsune$pushHotbar(GuiGraphics gfx, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HOTBAR, 182, 22);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "renderHotbarAndDecorations", at = @At("RETURN"))
    private void kitsune$popHotbar(GuiGraphics gfx, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HOTBAR, 182, 22);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }

    // ---------- Player health / experience (drawn together) ----------

    @Inject(method = "renderPlayerHealth", at = @At("HEAD"))
    private void kitsune$pushHealth(GuiGraphics gfx, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HEALTH, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "renderPlayerHealth", at = @At("RETURN"))
    private void kitsune$popHealth(GuiGraphics gfx, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HEALTH, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }

    // ---------- Food ----------

    @Inject(method = "renderFood", at = @At("HEAD"))
    private void kitsune$pushFood(GuiGraphics gfx, net.minecraft.world.entity.player.Player player,
                                  int top, int right, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.FOOD, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "renderFood", at = @At("RETURN"))
    private void kitsune$popFood(GuiGraphics gfx, net.minecraft.world.entity.player.Player player,
                                 int top, int right, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.FOOD, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }

    // ---------- Air bubbles ----------

    @Inject(method = "renderAirBubbles", at = @At("HEAD"))
    private void kitsune$pushAir(GuiGraphics gfx, net.minecraft.world.entity.player.Player player,
                                 int maxAir, int right, int top, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.AIR, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "renderAirBubbles", at = @At("RETURN"))
    private void kitsune$popAir(GuiGraphics gfx, net.minecraft.world.entity.player.Player player,
                                int maxAir, int right, int top, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.AIR, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }
}
