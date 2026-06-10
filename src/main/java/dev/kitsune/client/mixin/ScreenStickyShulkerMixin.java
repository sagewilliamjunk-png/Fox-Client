package dev.kitsune.client.mixin;

import dev.kitsune.client.tooltip.ShulkerPinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;

/**
 * Renders the "Alt+Shift sticky" shulker grid as a screen overlay so it
 * persists at its captured position while Alt+Shift is held, even when the
 * mouse moves off the shulker.
 *
 * <p>State machine (see {@link ShulkerPinManager}):
 * <ul>
 *   <li>Alt+Shift not held → clear everything.</li>
 *   <li>Alt+Shift held + recent shulker known + no pin yet → pin at the
 *       live gui-scaled mouse position, then render.</li>
 *   <li>Alt+Shift held + already pinned → just render at the captured
 *       position. The user can move the mouse anywhere; the grid stays put.</li>
 * </ul>
 *
 * <p>Drawn at the end of {@code Screen.extractRenderState} (MC 26.x's rename
 * of {@code Screen.render}) so it sits above the inventory but under the
 * deferred tooltips that follow the cursor — those are emitted afterwards by
 * {@code extractRenderStateWithTooltipAndSubtitles}.
 */
@Mixin(Screen.class)
public class ScreenStickyShulkerMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void kitsune$stickyShulker(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        try {
            if (!ShulkerPinManager.isAltShiftHeld()) {
                ShulkerPinManager.clear();
                return;
            }
            // First frame Alt+Shift was held over a shulker → lock at cursor.
            if (!ShulkerPinManager.hasPinned() && ShulkerPinManager.hasRecent()) {
                ShulkerPinManager.pinAt(mouseX, mouseY);
            }
            if (!ShulkerPinManager.hasPinned()) return;

            drawPinnedGrid(gfx, ShulkerPinManager.x(), ShulkerPinManager.y(), ShulkerPinManager.pinned());
        } catch (Throwable ignored) { /* fail safe — never crash rendering */ }
    }

    /** Inventory-style 9×3 grid. Each cell is 18 px (vanilla slot size). */
    private void drawPinnedGrid(GuiGraphicsExtractor gfx, int x, int y, ItemContainerContents contents) {
        final int cols = 9, rows = 3, slot = 18;
        final int w = cols * slot + 2;
        final int h = rows * slot + 2;
        // Slightly offset from the cursor so the click target isn't covered.
        int gx = Math.max(2, x + 10);
        int gy = Math.max(2, y + 10);
        // Clamp to screen so it doesn't render off the edge.
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (gx + w > screenW) gx = screenW - w - 2;
        if (gy + h > screenH) gy = screenH - h - 2;

        gfx.fill(gx, gy, gx + w, gy + h, 0xF0100010);
        gfx.fill(gx,         gy,         gx + w,     gy + 1,    0xFF5000FF);
        gfx.fill(gx,         gy + h - 1, gx + w,     gy + h,    0xFF28007F);
        gfx.fill(gx,         gy,         gx + 1,     gy + h,    0xFF28007F);
        gfx.fill(gx + w - 1, gy,         gx + w,     gy + h,    0xFF28007F);

        Font font = Minecraft.getInstance().font;
        Iterator<ItemStack> it = contents.nonEmptyItemCopyStream().iterator();
        for (int i = 0; i < cols * rows && it.hasNext(); i++) {
            ItemStack s = it.next();
            int cx = gx + 1 + (i % cols) * slot;
            int cy = gy + 1 + (i / cols) * slot;
            gfx.item(s, cx, cy);
            gfx.itemDecorations(font, s, cx, cy);
        }
    }
}
