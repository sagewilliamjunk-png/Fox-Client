package dev.kitsune.client.module.player;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto-eat — when hunger drops at or below a threshold, the module picks the
 * best food in the hotbar, switches to that slot, and holds right-click until
 * hunger recovers. Original slot is restored when eating finishes.
 *
 * <p>Server-safe: just simulates the same input the player would do. Avoids
 * golden apples by default (people usually want those for combat). Halts
 * cleanly on disable so the held-use key never gets stuck.
 */
public class AutoEatModule extends Module {

    private final SliderSetting threshold = addSetting(new SliderSetting("Eat when hunger ≤", 15.0, 1.0, 20.0, 1.0));
    private final BooleanSetting skipGold  = addSetting(new BooleanSetting("Skip golden apples", true));

    /** Slot we were on before eating started; -1 = not eating. */
    private int restoreSlot = -1;

    public AutoEatModule() {
        super("Auto Eat", "Eat from the hotbar when hunger is low.", Category.PLAYER);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        // Bail when typing / browsing GUIs — never want to grief the player.
        if (mc.screen != null) { stop(mc); return; }
        if (p.getFoodData().getFoodLevel() > threshold.get()) { stop(mc); return; }
        if (p.isUsingItem() && restoreSlot < 0) return; // already using something else

        int foodSlot = findFoodSlot(p);
        if (foodSlot < 0) { stop(mc); return; }

        Inventory inv = p.getInventory();
        if (restoreSlot < 0) restoreSlot = inv.getSelectedSlot();
        inv.setSelectedSlot(foodSlot);
        // Simulate held right-click — vanilla MC handles the rest.
        mc.options.keyUse.setDown(true);
    }

    private int findFoodSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.has(DataComponents.FOOD)) continue;
            if (skipGold.get() && (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE))) continue;
            return i;
        }
        return -1;
    }

    private void stop(Minecraft mc) {
        if (restoreSlot < 0) return;
        mc.options.keyUse.setDown(false);
        if (mc.player != null) mc.player.getInventory().setSelectedSlot(restoreSlot);
        restoreSlot = -1;
    }

    @Override
    public void onDisable() {
        stop(Minecraft.getInstance());
    }
}
