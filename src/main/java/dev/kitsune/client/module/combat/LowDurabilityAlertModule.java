package dev.kitsune.client.module.combat;

import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Low Durability Alert — fires a one-shot warning toast (and optional ding)
 * when a worn armor piece or the held tool crosses a durability threshold,
 * so gear doesn't silently shatter mid-fight.
 *
 * <p>Distinct from the passive Armor HUD readout: this is an event alert.
 * Equivalent to information vanilla already shows in the inventory screen —
 * fair-play by definition.
 */
public class LowDurabilityAlertModule extends Module {

    private static final EquipmentSlot[] WATCHED_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private final SliderSetting  thresholdPct = addSetting(new SliderSetting("Threshold %", 10, 1, 50, 1));
    private final BooleanSetting watchHands   = addSetting(new BooleanSetting("Watch Held Items", true));
    private final BooleanSetting playSound    = addSetting(new BooleanSetting("Play Sound", true));

    /** Per-slot "already warned" latch, keyed by the item we warned about so a
     *  swap or repair re-arms the alert. */
    private static final class Warned {
        final ItemStack item;
        Warned(ItemStack item) { this.item = item; }
    }
    private final Map<EquipmentSlot, Warned> warned = new EnumMap<>(EquipmentSlot.class);

    public LowDurabilityAlertModule() {
        super("Durability Alert", "Warns when armor or tools are about to break", Category.COMBAT);
    }

    @Override
    protected void onDisable() {
        warned.clear();
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { warned.clear(); return; }

        for (EquipmentSlot slot : WATCHED_SLOTS) {
            boolean handSlot = slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND;
            if (handSlot && !watchHands.get()) continue;

            ItemStack s = p.getItemBySlot(slot);
            Warned prev = warned.get(slot);

            if (s.isEmpty() || s.getMaxDamage() <= 0) {
                warned.remove(slot);
                continue;
            }
            // Re-arm when the slot now holds a different item than we warned about.
            if (prev != null && prev.item != s) warned.remove(slot);

            int max = s.getMaxDamage();
            int left = max - s.getDamageValue();
            double pct = left * 100.0 / max;
            if (pct > thresholdPct.get()) {
                // Repaired (mending / anvil) back above threshold — re-arm.
                warned.remove(slot);
                continue;
            }
            if (warned.containsKey(slot)) continue;

            warned.put(slot, new Warned(s));
            String name = s.getHoverName().getString();
            NotificationManager.show(
                    String.format("%s is low — %d durability left!", name, left),
                    NotificationManager.Type.WARNING);
            if (playSound.get()) {
                try {
                    p.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 0.6f);
                } catch (Throwable ignored) {}
            }
        }
    }
}
