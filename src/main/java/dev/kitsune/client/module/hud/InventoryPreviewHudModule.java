package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory Preview — renders the player's 9×3 main inventory (the part the
 * hotbar doesn't show) as a HUD grid, so you can check space and find items
 * without opening the inventory screen.
 *
 * <p>Displays exactly what the client-side inventory already contains —
 * the same information as pressing E — so it is fair-play by definition.
 */
public class InventoryPreviewHudModule extends BaseHudModule {

    /** Slot indices 9..35 are the three main-inventory rows above the hotbar. */
    private static final int FIRST_SLOT = 9;
    private static final int COLS = 9;
    private static final int ROWS = 3;
    private static final int SLOT_SIZE = 18;

    private final BooleanSetting hideWhenEmpty = addSetting(new BooleanSetting("Hide When Empty", false));
    private final BooleanSetting slotGrid      = addSetting(new BooleanSetting("Slot Grid", true));

    /** Cached per tick so visibility checks don't iterate 27 slots per frame. */
    private boolean anyItems = false;

    public InventoryPreviewHudModule() {
        super("Inventory Preview", "Shows your main inventory as a HUD grid", Category.HUD,
                "inventory_preview", "Inventory");
        useStandardPanel(0.50, Palette.ACCENT_GOLD);
    }

    @Override public int widgetWidth()  { return COLS * SLOT_SIZE; }
    @Override public int widgetHeight() { return ROWS * SLOT_SIZE; }

    @Override
    public boolean isWidgetVisible() {
        if (!isEnabled()) return false;
        if (hideWhenEmpty.get() && !anyItems) return false;
        return true;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { anyItems = false; return; }
        boolean any = false;
        var inv = p.getInventory();
        for (int i = 0; i < COLS * ROWS; i++) {
            if (!inv.getItem(FIRST_SLOT + i).isEmpty()) { any = true; break; }
        }
        anyItems = any;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        Font font = mc.font;
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        var inv = p.getInventory();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int sx = x + col * SLOT_SIZE;
                int sy = y + row * SLOT_SIZE;
                if (slotGrid.get()) {
                    gfx.fill(sx, sy, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0x40FFFFFF);
                    gfx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 2, sy + SLOT_SIZE - 2, 0x60000000);
                }
                ItemStack stack = inv.getItem(FIRST_SLOT + row * COLS + col);
                if (stack.isEmpty()) continue;
                gfx.item(stack, sx + 1, sy + 1);
                gfx.itemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
    }
}
