package dev.kitsune.client.module.misc;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * Filled-map tooltip extension. Adds a "Map ID: N" line to filled maps so
 * users can identify them at a glance in chests / inventories. Ported from
 * the legacy {@code MapTooltipFeature} into the proper module system in v1.2.
 *
 * <p>A pixel-accurate inline map render in the tooltip is technically possible
 * via a custom TooltipComponent — left as a future enhancement.
 */
public class MapTooltipModule extends Module {

    private static volatile MapTooltipModule INSTANCE = null;
    private static boolean callbackRegistered = false;

    public MapTooltipModule() {
        super("Map Tooltip", "Adds the map id to filled-map tooltips for quick identification.", Category.MISC);
        INSTANCE = this;
        ensureCallbackRegistered();
    }

    public static boolean isActive() {
        return INSTANCE != null && INSTANCE.isEffectivelyEnabled();
    }

    private static void ensureCallbackRegistered() {
        if (callbackRegistered) return;
        callbackRegistered = true;
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            if (!isActive()) return;
            if (stack.getItem() != Items.FILLED_MAP) return;
            try {
                Object id = stack.get(DataComponents.MAP_ID);
                if (id != null) {
                    lines.add(Component.literal("Map ID: " + id.toString())
                            .withStyle(s -> s.withColor(0xFFA060)));
                }
            } catch (Throwable ignored) {}
        });
    }
}
