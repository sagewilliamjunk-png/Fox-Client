package dev.kitsune.client.features.qol;

import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shulker-box (and other container item) tooltip improvements.
 *
 * <ul>
 *   <li><b>Default</b> — compact "Contains N stack(s)" summary line plus a
 *       keybind hint.</li>
 *   <li><b>Shift held</b> — full item list (count × name) for every non-empty
 *       slot.</li>
 *   <li><b>Alt held</b> — the visual 9×3 grid is rendered by
 *       {@link dev.kitsune.client.tooltip.ClientShulkerPreviewTooltip} (injected
 *       via {@link dev.kitsune.client.mixin.ItemTooltipImageMixin}); the text
 *       tooltip is suppressed to avoid clutter.</li>
 * </ul>
 */
public class ShulkerTooltipFeature implements FoxFeature {

    public static final String ID = "shulker_tooltip";
    private static boolean callbackRegistered = false;

    @Override public String id()             { return ID; }
    @Override public String displayName()    { return "Shulker Box Tooltip"; }
    @Override public boolean defaultEnabled(){ return true; }
    @Override public String category()       { return "qol"; }

    @Override
    public void onEnable() {
        ensureCallbackRegistered();
    }

    private static void ensureCallbackRegistered() {
        if (callbackRegistered) return;
        callbackRegistered = true;
        ItemTooltipCallback.EVENT.register(ShulkerTooltipFeature::appendTooltip);
    }

    private static void appendTooltip(ItemStack stack, Object ctx, Object flag,
                                      List<Component> lines) {
        if (!FeatureRegistry.isEnabled(ID)) return;
        if (!(stack.getItem() instanceof BlockItem bi)) return;
        if (!(bi.getBlock() instanceof ShulkerBoxBlock)) return;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;

        List<ItemStack> nonEmpty = contents.nonEmptyItemCopyStream().collect(Collectors.toList());
        if (nonEmpty.isEmpty()) return;

        // Alt held → visual grid is showing; suppress the text list to avoid clutter
        if (Screen.hasAltDown()) return;

        if (Screen.hasShiftDown()) {
            // Full list
            for (ItemStack inner : nonEmpty) {
                lines.add(Component.literal(inner.getCount() + "× ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(inner.getHoverName().copy().withStyle(s -> s.withColor(0xFFA060))));
            }
        } else {
            // Compact summary
            lines.add(Component.literal("Contains " + nonEmpty.size() + " stack" + (nonEmpty.size() == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Shift: list  ·  Alt: grid view")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
