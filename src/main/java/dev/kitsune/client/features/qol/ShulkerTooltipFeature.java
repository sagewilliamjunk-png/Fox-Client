package dev.kitsune.client.features.qol;

import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * When hovering a shulker box in an inventory, append a list of its contents
 * to the item's tooltip. Renders as text lines (item count + name) — simpler
 * than rendering an inline 3x9 grid but works on all GUI scales.
 *
 * Server-safety: client-only tooltip rendering, never sends packets. Allowed
 * everywhere.
 */
public class ShulkerTooltipFeature implements FoxFeature {

    public static final String ID = "shulker_tooltip";
    private static boolean callbackRegistered = false;

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Shulker Box Tooltip"; }
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
            if (!(stack.getItem() instanceof BlockItem bi)) return;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) return;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null) return;
            int idx = 0;
            for (ItemStackTemplate inner : contents.nonEmptyItems()) {
                ItemStack innerStack = inner.create();
                Component name = innerStack.getHoverName().copy().withStyle(s -> s.withColor(0xFFA060));
                Component line = Component.literal(inner.count() + "x ").append(name);
                lines.add(line);
                if (++idx >= 27) break;
            }
        });
    }
}
