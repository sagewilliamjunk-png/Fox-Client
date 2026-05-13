package dev.kitsune.client.mixin;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.VanillaHudProxies;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates vanilla HUD elements (hotbar, health, food, air, experience bar)
 * by the user-chosen delta from the {@link dev.kitsune.client.hud.HudEditorScreen}.
 *
 * <p>Each extract method of {@link Gui} we target is wrapped with a
 * {@code pose.pushMatrix() / translate / popMatrix()} pair so the underlying draw
 * code is untouched. The translation amount comes from
 * {@link HudManager#vanillaOffset(String, int, int)}.
 *
 * <p>Updated for Minecraft 26.x where render* methods were renamed to extract*
 * and parameters were reorganised (e.g. air bubbles now passes heartCount/top/left).
 *
 * <p>This is a no-op until the user actually moves a widget — the offset
 * lookup returns {@code (0, 0)} for unmoved proxies, so the pose stack work is
 * negligible for default layouts.
 */
@Mixin(Gui.class)
public abstract class GuiVanillaHudMixin {

    // ---------- Hotbar ----------

    @Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"))
    private void kitsune$pushHotbar(GuiGraphicsExtractor gfx, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HOTBAR, 182, 22);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "extractHotbarAndDecorations", at = @At("RETURN"))
    private void kitsune$popHotbar(GuiGraphicsExtractor gfx, net.minecraft.client.DeltaTracker delta, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HOTBAR, 182, 22);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }

    // ---------- Player health / experience (drawn together) ----------

    @Inject(method = "extractPlayerHealth", at = @At("HEAD"))
    private void kitsune$pushHealth(GuiGraphicsExtractor gfx, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HEALTH, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "extractPlayerHealth", at = @At("RETURN"))
    private void kitsune$popHealth(GuiGraphicsExtractor gfx, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.HEALTH, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }

    // ---------- Food ----------

    @Inject(method = "extractFood", at = @At("HEAD"))
    private void kitsune$pushFood(GuiGraphicsExtractor gfx, net.minecraft.world.entity.player.Player player,
                                  int top, int right, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.FOOD, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "extractFood", at = @At("RETURN"))
    private void kitsune$popFood(GuiGraphicsExtractor gfx, net.minecraft.world.entity.player.Player player,
                                 int top, int right, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.FOOD, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }

    // ---------- Air bubbles ----------

    @Inject(method = "extractAirBubbles", at = @At("HEAD"))
    private void kitsune$pushAir(GuiGraphicsExtractor gfx, net.minecraft.world.entity.player.Player player,
                                 int heartCount, int top, int left, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.AIR, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().pushMatrix();
        gfx.pose().translate(off[0], off[1]);
    }

    @Inject(method = "extractAirBubbles", at = @At("RETURN"))
    private void kitsune$popAir(GuiGraphicsExtractor gfx, net.minecraft.world.entity.player.Player player,
                                int heartCount, int top, int left, CallbackInfo ci) {
        int[] off = HudManager.vanillaOffset(VanillaHudProxies.AIR, 81, 9);
        if (off[0] == 0 && off[1] == 0) return;
        gfx.pose().popMatrix();
    }
}
