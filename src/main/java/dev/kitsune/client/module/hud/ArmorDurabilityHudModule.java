package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Armor & offhand HUD — shows the actual item icons in vanilla-style slot
 * boxes (helm → chest → legs → boots, optional offhand after a small gap),
 * with vanilla's built-in durability bar underneath each icon.
 *
 * <p>Earlier versions of this widget rendered "[icon] 100% [green bar]" text
 * rows per slot. That was technically more info (exact % + explicit bar) but
 * the player had no visual link back to "what's in that slot" — you had to
 * read the tiny unicode approximation of a helmet/chestplate/etc. Rendering
 * the real {@link ItemStack} via {@link GuiGraphics#renderItem} is what an
 * inventory looks like everywhere else in the game, so it reads instantly;
 * {@link GuiGraphics#renderItemDecorations} adds the coloured durability bar
 * and (for stacked items like elytra repair cost) the count, for free.
 *
 * <p>Layout is horizontal by default to match the vanilla inventory row, with
 * a Vertical option for players who want a slim side-pinned column.
 */
public class ArmorDurabilityHudModule extends Module implements HudWidget {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** One slot cell: 16 px item + 1 px padding each side. Matches vanilla inventory. */
    private static final int SLOT_SIZE = 18;
    /** Extra horizontal gap between the armor block and the offhand slot
     *  (mirrors the vanilla inventory screen layout). */
    private static final int OFFHAND_GAP = 6;

    private final BooleanSetting showOffhand = addSetting(new BooleanSetting("Show Offhand",     true));
    private final BooleanSetting showEmpty   = addSetting(new BooleanSetting("Show Empty Slots", true));
    private final BooleanSetting warnLow     = addSetting(new BooleanSetting("Warn When Low",    true));
    private final BooleanSetting drawBg      = addSetting(new BooleanSetting("Slot Background",  true));
    private final ModeSetting    layout      = addSetting(new ModeSetting("Layout", "Horizontal",
            List.of("Horizontal", "Vertical")));

    public ArmorDurabilityHudModule() {
        super("Armor HUD", "Vanilla-style armor + offhand slots with durability bars", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "armor_durability"; }
    @Override public String displayName() { return "Armor"; }

    /** Four armor slots, plus (optional) offhand after a small gap. */
    private int slotCount() {
        return ARMOR_SLOTS.length + (showOffhand.get() ? 1 : 0);
    }
    private boolean horizontal() { return "Horizontal".equals(layout.get()); }

    @Override public int widgetWidth() {
        if (horizontal()) {
            int main = ARMOR_SLOTS.length * SLOT_SIZE;
            int off  = showOffhand.get() ? OFFHAND_GAP + SLOT_SIZE : 0;
            return main + off;
        }
        return SLOT_SIZE;
    }

    @Override public int widgetHeight() {
        if (horizontal()) return SLOT_SIZE;
        int main = ARMOR_SLOTS.length * SLOT_SIZE;
        int off  = showOffhand.get() ? OFFHAND_GAP + SLOT_SIZE : 0;
        return main + off;
    }

    /**
     * Hide entirely when the player has no armor and no damageable offhand.
     * Floating empty slot boxes in the corner are pure visual noise.
     */
    @Override public boolean isWidgetVisible() {
        if (!isEnabled()) return false;
        if (showEmpty.get()) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        for (EquipmentSlot es : ARMOR_SLOTS) {
            if (!mc.player.getItemBySlot(es).isEmpty()) return true;
        }
        if (showOffhand.get() && !mc.player.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) return true;
        return false;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        boolean horizontal = horizontal();
        boolean showE = showEmpty.get();
        boolean warn  = warnLow.get();

        int cursorX = x;
        int cursorY = y;
        // Track whether *anything* was drawn in the armor block. If all four
        // slots got skipped (showEmpty=false + fully unarmored), we must not
        // add OFFHAND_GAP before the offhand — that would leave a dead 6-px
        // gutter pinned to the HUD edge with a lone offhand slot floating.
        boolean drewAny = false;

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack stack = player.getItemBySlot(ARMOR_SLOTS[i]);
            // Skip empty slots entirely when "Show Empty" is off, so the
            // remaining items pack tight against whatever side they're on.
            if (stack.isEmpty() && !showE) continue;
            drawSlot(gfx, font, cursorX, cursorY, stack, warn);
            if (horizontal) cursorX += SLOT_SIZE; else cursorY += SLOT_SIZE;
            drewAny = true;
        }

        if (showOffhand.get()) {
            ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
            if (offhand.isEmpty() && !showE) return;
            // Only insert the visual separator gap when something actually
            // sits to its left — otherwise the offhand is the only thing
            // drawn and should hug the HUD anchor like any other widget.
            if (drewAny) {
                if (horizontal) cursorX += OFFHAND_GAP; else cursorY += OFFHAND_GAP;
            }
            drawSlot(gfx, font, cursorX, cursorY, offhand, warn);
        }
    }

    /**
     * One slot cell: optional dark slot backdrop, item icon, vanilla
     * durability bar + count, and a pulsing red tint when the item's
     * durability falls below 15 %.
     */
    private void drawSlot(GuiGraphics gfx, Font font, int sx, int sy,
                           ItemStack stack, boolean warn) {
        if (drawBg.get()) {
            // Dark slot backdrop with a faint border — roughly vanilla's
            // container-slot sprite, but we don't need the sprite round-trip.
            gfx.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x80000000);
            gfx.fill(sx, sy, sx + SLOT_SIZE, sy + 1, 0x40FFFFFF);            // top highlight
            gfx.fill(sx, sy + SLOT_SIZE - 1, sx + SLOT_SIZE, sy + SLOT_SIZE, 0x40000000); // bottom shadow
        }

        // Low-durability pulse: red tint behind the icon so it's noticeable
        // even at a glance without obscuring the item itself.
        if (warn && !stack.isEmpty() && stack.isDamageableItem()) {
            int maxDmg = stack.getMaxDamage();
            if (maxDmg > 0) {
                float pct = (maxDmg - stack.getDamageValue()) / (float) maxDmg;
                if (pct < 0.15f) {
                    long ms = System.currentTimeMillis();
                    int pulse = (int) (Math.sin(ms / 300.0) * 40 + 80);
                    gfx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1,
                            (pulse << 24) | 0x00FF3030);
                }
            }
        }

        if (stack.isEmpty()) return;

        // Item icon (centred in the slot: 1 px pad all around).
        gfx.renderItem(stack, sx + 1, sy + 1);
        // Vanilla decorations: durability bar (green → red auto-coloured) and
        // stack count text. Free, matches every other inventory slot the
        // player has ever seen.
        gfx.renderItemDecorations(font, stack, sx + 1, sy + 1);
    }
}
