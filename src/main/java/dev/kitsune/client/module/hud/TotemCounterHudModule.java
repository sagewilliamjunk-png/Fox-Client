package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Counts totems of undying across the player's entire inventory (main inventory,
 * hotbar, and both hands). Renders the item icon with a live count and turns
 * red when the total drops below a configurable warning threshold.
 */
public class TotemCounterHudModule extends BaseHudModule {

    private final BooleanSetting showIcon      = addSetting(new BooleanSetting("Show Icon",   true));
    private final BooleanSetting warnLow       = addSetting(new BooleanSetting("Warn Low",    true));
    private final BooleanSetting hideWhenZero  = addSetting(new BooleanSetting("Hide at Zero", false));
    private final SliderSetting  warnThreshold = addSetting(new SliderSetting("Warn At", 2, 0, 16, 1));
    private final ColorSetting   warnColor     = addSetting(new ColorSetting("Warn Color", 0xFFFF4444));

    private int totalTotems = 0;

    public TotemCounterHudModule() {
        super("Totem Counter", "Counts totems of undying in inventory", Category.HUD,
                "totem_counter", "Totems");
        useStandardPanel(0.50, Palette.ACCENT_GOLD);
        useTextColor();
    }

    @Override
    public int widgetWidth() {
        return showIcon.get() ? 38 : 22;
    }

    @Override
    public int widgetHeight() {
        return showIcon.get() ? 20 : 14;
    }

    @Override
    public boolean isWidgetVisible() {
        if (!isEnabled()) return false;
        if (hideWhenZero.get() && totalTotems == 0) return false;
        return true;
    }

    private boolean low() {
        return warnLow.get() && totalTotems <= warnThreshold.get().intValue();
    }

    /** Accent bar flips to the warn color when the count is low. */
    @Override
    protected int accentArgb() {
        return low() ? warnColor.get() : super.accentArgb();
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { totalTotems = 0; return; }

        int n = 0;
        var inv = p.getInventory();
        int size = inv.getContainerSize();
        // On MC 1.20.5+ Inventory.getContainerSize() returns 41:
        //   36 main + 4 armor + 1 offhand
        // So iterating 0..size-1 already covers the offhand slot at index 40 —
        // no separate offhand probe needed (and adding one would double-count).
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == Items.TOTEM_OF_UNDYING) n += s.getCount();
        }
        totalTotems = n;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        int textX = x + 2;
        if (showIcon.get()) {
            ItemStack icon = new ItemStack(Items.TOTEM_OF_UNDYING);
            gfx.item(icon, x + 2, y + 2);
            textX = x + 22;
        }

        int color = low() ? warnColor.get() : textArgb();
        String label = String.valueOf(totalTotems);
        int ty = y + (showIcon.get() ? 6 : 3);
        gfx.text(font, label, textX, ty, color);
    }
}
