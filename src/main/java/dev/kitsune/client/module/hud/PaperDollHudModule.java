package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Draggable paper-doll widget — renders the local player's full body
 * so you can inspect your armour and equipment without opening the inventory.
 */
public class PaperDollHudModule extends Module implements HudWidget {

    private final SliderSetting  scale       = addSetting(new SliderSetting("Scale", 30, 10, 80, 2));
    private final SliderSetting  widthPad    = addSetting(new SliderSetting("Width",  50, 30, 120, 5));
    private final SliderSetting  heightPad   = addSetting(new SliderSetting("Height", 80, 40, 160, 5));
    private final BooleanSetting followMouse = addSetting(new BooleanSetting("Follow Mouse",     false));
    private final BooleanSetting showBg      = addSetting(new BooleanSetting("Show Background",  true));
    private final BooleanSetting showBorder  = addSetting(new BooleanSetting("Show Border",      true));
    private final ModeSetting    bgStyle     = addSetting(new ModeSetting("BG Style", "Dark",
            List.of("Dark", "Darker", "None")));
    private final ColorSetting   borderColor = addSetting(new ColorSetting("Border Color", 0xFFE87722));

    public PaperDollHudModule() {
        super("Paper Doll", "Renders your character as a HUD widget", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "paper_doll"; }
    @Override public String displayName() { return "Paper Doll"; }
    @Override public int widgetWidth()    { return widthPad.get().intValue(); }
    @Override public int widgetHeight()   { return heightPad.get().intValue(); }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();

        // Background
        if (showBg.get()) {
            int bgColor = switch (bgStyle.get()) {
                case "Darker" -> 0xA0000000;
                case "None"   -> 0;
                default       -> 0x60000000;
            };
            if (bgColor != 0) gfx.fill(x, y, x + w, y + h, bgColor);
        }

        // Border
        if (showBorder.get()) {
            int bc = borderColor.get();
            gfx.fill(x,     y,     x + w, y + 1, bc); // top
            gfx.fill(x,     y + h, x + w, y + h + 1, bc); // bottom
            gfx.fill(x,     y,     x + 1, y + h, bc); // left
            gfx.fill(x + w, y,     x + w + 1, y + h, bc); // right
        }

        int x1 = x + 4;
        int y1 = y + 4;
        int x2 = x + w - 4;
        int y2 = y + h - 4;
        int s  = scale.get().intValue();

        try {
            float mx, my;
            if (followMouse.get()) {
                double sw = mc.getWindow().getGuiScaledWidth();
                double sh = mc.getWindow().getGuiScaledHeight();
                double mdx = mc.mouseHandler.xpos() * sw / mc.getWindow().getScreenWidth();
                double mdy = mc.mouseHandler.ypos() * sh / mc.getWindow().getScreenHeight();
                mx = (float) mdx;
                my = (float) mdy;
            } else {
                // Fixed look-direction based on player yaw/pitch
                mx = (x1 + x2) / 2f - 30;
                my = (y1 + y2) / 2f - 30;
            }
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    gfx, x1, y1, x2, y2, s, 0.0625f, mx, my, player);
        } catch (Throwable t) {
            // Render fallback placeholder if API signature changed
            gfx.text(font, "\u00a77[player]", x + 4, y + h / 2 - 4, 0xFFFFFFFF);
        }
    }
}
