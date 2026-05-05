package dev.kitsune.client.features.qol;

import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

/**
 * Filled-map tooltip extension. Adds a "Map #N" line to filled maps so users
 * can identify them at a glance in chests / inventories.
 *
 * (A pixel-accurate inline map render in the tooltip is possible with a
 * custom TooltipComponent — left as a v2 enhancement.)
 *
 * Server-safety: client-only tooltip text. Allowed everywhere.
 */
public class MapTooltipFeature implements FoxFeature {

    public static final String ID = "map_tooltip";
    private static boolean callbackRegistered = false;

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Map Tooltip"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public String category() { return "qol"; }

    @Override
    public void onEnable() {
        ensureCallbackRegistered();
    }

    private static void ensureCallbackRegistered() {
        if (callbackRegistered) return;
        callbackRegistered = true;
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            if (!FeatureRegistry.isEnabled(ID)) return;
            if (stack.getItem() != Items.FILLED_MAP) return;
            // Map ID is exposed via the MAP_ID component on 1.21.x
            try {
                Object id = stack.get(net.minecraft.core.component.DataComponents.MAP_ID);
                if (id != null) {
                    lines.add(Component.literal("Map ID: " + id.toString())
                            .withStyle(s -> s.withColor(0xFFA060)));
                }
            } catch (Throwable ignored) {}
        });
    }
}
