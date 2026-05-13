package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Draggable shield-status widget.
 * Shows whether the offhand shield is raised, its durability bar,
 * and the disable cooldown after being axe-stunned.
 */
public class ShieldStatusHudModule extends Module implements HudWidget {

    private final BooleanSetting hideWhenFull   = addSetting(new BooleanSetting("Hide When Full",     false));
    private final BooleanSetting hideWhenAbsent = addSetting(new BooleanSetting("Hide If No Shield",  true));
    private final BooleanSetting showPercent    = addSetting(new BooleanSetting("Show Durability %",  true));
    private final BooleanSetting showCooldown   = addSetting(new BooleanSetting("Show Cooldown Bar",  true));
    private final BooleanSetting flashWhenLow   = addSetting(new BooleanSetting("Flash When Low",     true));
    private final ModeSetting    barStyle       = addSetting(new ModeSetting("Bar Style", "Gradient",
            List.of("Gradient", "Solid", "Segmented")));
    private final ColorSetting   raisedColor    = addSetting(new ColorSetting("Raised Color",  0xFF44DDFF));
    private final ColorSetting   readyColor     = addSetting(new ColorSetting("Ready Color",   0xFF55FF55));
    private final ColorSetting   disabledColor  = addSetting(new ColorSetting("Disabled Color",0xFFFF4444));

    public ShieldStatusHudModule() {
        super("Shield Status", "Shield durability, state, and cooldown", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "shield_status"; }
    @Override public String displayName() { return "Shield"; }
    @Override public int widgetWidth()    { return 100; }
    @Override public int widgetHeight()   { return showCooldown.get() ? 30 : 22; }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        boolean hasShield = !offhand.isEmpty() && offhand.getItem() == Items.SHIELD;

        // No shield
        if (!hasShield) {
            if (hideWhenAbsent.get()) return;
            gfx.fill(x - 2, y - 2, x + widgetWidth() + 2, y + widgetHeight() + 2, 0x70000000);
            gfx.fill(x - 2, y - 2, x + widgetWidth() + 2, y - 1, 0xFF555555);
            gfx.text(font, "\u26e8 No shield", x + 2, y + 5, 0xFF888888);
            return;
        }

        int maxDmg = offhand.getMaxDamage();
        int curDmg = offhand.getDamageValue();
        float pct  = maxDmg > 0 ? (maxDmg - curDmg) / (float) maxDmg : 1f;

        boolean blocking  = player.isBlocking();
        float   cdFrac    = mc.player.getCooldowns().getCooldownPercent(offhand, 0);
        boolean onCooldown = cdFrac > 0;

        // Hide when full and no action needed
        if (hideWhenFull.get() && pct >= 0.999f && !blocking && !onCooldown) return;

        // State color
        int stateColor;
        String stateLabel;
        if (onCooldown) {
            stateColor = disabledColor.get();
            stateLabel = "\u26a7 DISABLED";
        } else if (blocking) {
            stateColor = raisedColor.get();
            stateLabel = "\u26e8 RAISED";
        } else {
            stateColor = readyColor.get();
            stateLabel = "\u26e8 Ready";
        }

        // Background
        gfx.fill(x - 2, y - 2, x + widgetWidth() + 2, y + widgetHeight() + 2, 0x90000000);
        gfx.fill(x - 2, y - 2, x + widgetWidth() + 2, y - 1, stateColor);

        // Low durability flash background
        if (flashWhenLow.get() && pct < 0.15f && !onCooldown) {
            long ms = System.currentTimeMillis();
            int alpha = (int)(Math.abs(Math.sin(ms / 400.0)) * 50 + 10);
            gfx.fill(x - 2, y - 2, x + widgetWidth() + 2, y + widgetHeight() + 2, (alpha << 24) | 0xFF0000);
        }

        int w = widgetWidth();
        int curY = y + 2;

        // State label
        gfx.text(font, stateLabel, x + 2, curY, stateColor);

        // Durability % on right
        if (showPercent.get()) {
            String pctStr = (int)(pct * 100) + "%";
            int dw = font.width(pctStr);
            int pctCol = pct > 0.6f ? 0xFF44DD44 : pct > 0.3f ? 0xFFDDDD33 : 0xFFFF5555;
            gfx.text(font, pctStr, x + w - dw - 4, curY, pctCol);
        }
        curY += 10;

        // Durability bar
        int barW = w - 4;
        int barH = 4;
        int durColor = pct > 0.6f ? 0xFF44DD44 : pct > 0.3f ? 0xFFDDDD33 : 0xFFFF5555;

        switch (barStyle.get()) {
            case "Segmented" -> drawSegmented(gfx, x + 2, curY, barW, barH, pct, durColor);
            case "Solid"     -> {
                gfx.fill(x + 2, curY, x + 2 + barW, curY + barH, 0xFF222222);
                gfx.fill(x + 2, curY, x + 2 + Math.max(2, (int)(barW * pct)), curY + barH, durColor);
            }
            default -> { // Gradient
                gfx.fill(x + 2, curY, x + 2 + barW, curY + barH, 0xFF222222);
                int fill = Math.max(2, (int)(barW * pct));
                // Two-tone gradient: darker shade on left half
                int darkCol = darken(durColor, 0.6f);
                gfx.fill(x + 2,            curY, x + 2 + fill / 2, curY + barH, darkCol);
                gfx.fill(x + 2 + fill / 2, curY, x + 2 + fill,     curY + barH, durColor);
            }
        }
        curY += barH + 2;

        // Cooldown bar
        if (showCooldown.get() && onCooldown) {
            gfx.text(font, "CD", x + 2, curY, 0xFFFF8888);
            int cdW = barW - 18;
            gfx.fill(x + 20, curY, x + 20 + cdW, curY + barH, 0xFF222222);
            int fill = Math.max(2, (int)(cdW * cdFrac));
            gfx.fill(x + 20, curY, x + 20 + fill, curY + barH, 0xFFFF4444);
        }
    }

    // ---- helpers ----

    private static void drawSegmented(GuiGraphicsExtractor gfx, int x, int y, int w, int h, float pct, int color) {
        int segs   = 10;
        int sw     = (w - segs + 1) / segs;
        int filled = Math.round(pct * segs);
        for (int i = 0; i < segs; i++) {
            int px = x + i * (sw + 1);
            gfx.fill(px, y, px + sw, y + h, i < filled ? color : 0xFF222222);
        }
    }

    private static int darken(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = (int)(((argb >> 16) & 0xFF) * factor);
        int g = (int)(((argb >>  8) & 0xFF) * factor);
        int b = (int)((argb & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
