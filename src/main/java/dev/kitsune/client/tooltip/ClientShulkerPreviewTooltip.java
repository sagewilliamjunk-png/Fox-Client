package dev.kitsune.client.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a 9×3 item grid inside an item tooltip for shulker boxes.
 *
 * <p>Each cell is 18×18 px (16px item icon + 1px border on each side). Items are
 * drawn in slot order (left→right, top→bottom), so the contents appear in the
 * same layout as the shulker-box inventory screen.
 */
public class ClientShulkerPreviewTooltip implements ClientTooltipComponent {

    private static final int COLS    = 9;
    private static final int ROWS    = 3;
    private static final int SLOT    = 18;   // cell size including border
    private static final int PAD     = 3;    // outer padding on each side
    private static final int SLOT_BG = 0xFF303030;
    private static final int SLOT_IN = 0xFF1A1A1A;

    private final List<ItemStack> slots; // one entry per shulker slot (may be empty)

    public ClientShulkerPreviewTooltip(ShulkerPreviewTooltip data) {
        this.slots = data.contents().allItemsCopyStream().collect(Collectors.toList());
    }

    @Override
    public int getWidth(Font font) {
        return COLS * SLOT + PAD * 2;
    }

    @Override
    public int getHeight(Font font) {
        return ROWS * SLOT + PAD * 2 + 2; // +2 so there's a gap between text and grid
    }

    @Override
    public void extractImage(Font font, int x, int y, int mouseX, int mouseY,
                             GuiGraphicsExtractor gfx) {
        // Outer dark frame
        gfx.fill(x, y + 2, x + getWidth(font), y + getHeight(font), 0xFF111111);

        for (int i = 0; i < ROWS * COLS; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int sx  = x + PAD + col * SLOT;
            int sy  = y + PAD + 2 + row * SLOT; // +2 for gap from text lines above

            // Slot background (outer border + inner recess)
            gfx.fill(sx,     sy,     sx + SLOT,     sy + SLOT,     SLOT_BG);
            gfx.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, SLOT_IN);

            // Item icon + count decoration
            if (i < slots.size() && !slots.get(i).isEmpty()) {
                ItemStack stack = slots.get(i);
                gfx.item(stack, sx + 1, sy + 1);
                gfx.itemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
    }
}
