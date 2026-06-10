package dev.kitsune.client.module.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Shulker-box (and other container item) tooltip improvements. Ported from
 * the legacy {@code ShulkerTooltipFeature} into the proper module system in v1.2.
 *
 * <ul>
 *   <li><b>Default</b> — vanilla tooltip only.</li>
 *   <li><b>Shift held</b> — adds a "Contains N stack(s)" line + hint that
 *       Alt+Shift shows the full visual grid.</li>
 *   <li><b>Alt+Shift held</b> — the visual 9×3 grid is rendered by
 *       {@link dev.kitsune.client.tooltip.ClientShulkerPreviewTooltip}
 *       (injected via {@link dev.kitsune.client.mixin.ItemTooltipImageMixin});
 *       text tooltip is suppressed to avoid clutter.</li>
 * </ul>
 *
 * <p>Both the Fabric tooltip callback and the mixin's image-injection path
 * check {@link #isActive()} so the module can be toggled at runtime without
 * needing to unregister the callback.
 */
public class ShulkerTooltipModule extends Module {

    public static final String MODULE_NAME = "Shulker Tooltip";
    private static volatile ShulkerTooltipModule INSTANCE = null;
    private static boolean callbackRegistered = false;

    public ShulkerTooltipModule() {
        super(MODULE_NAME, "Compact stack count on Shift; full 9×3 grid on Alt+Shift.", Category.MISC);
        INSTANCE = this;
        ensureCallbackRegistered();
    }

    /** True when the module is registered, enabled, and not server-suppressed.
     *  Read by both the tooltip callback and ItemTooltipImageMixin. */
    public static boolean isActive() {
        return INSTANCE != null && INSTANCE.isEffectivelyEnabled();
    }

    private static void ensureCallbackRegistered() {
        if (callbackRegistered) return;
        callbackRegistered = true;
        ItemTooltipCallback.EVENT.register(ShulkerTooltipModule::appendTooltip);
    }

    private static void appendTooltip(ItemStack stack, Object ctx, Object flag, List<Component> lines) {
        if (!isActive()) return;
        if (!(stack.getItem() instanceof BlockItem bi)) return;
        if (!(bi.getBlock() instanceof ShulkerBoxBlock)) return;

        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return;

        List<ItemStack> nonEmpty = contents.nonEmptyItemCopyStream().collect(Collectors.toList());
        if (nonEmpty.isEmpty()) return;

        // Per the user-spec controls:
        //   • no modifier → text-list ("the letters") of every stack inside.
        //   • Shift         → visual grid renders via ItemTooltipImageMixin; we
        //     add no extra text so the popup stays clean.
        //   • Alt+Shift     → same grid, pinned: ShulkerPinManager +
        //     ScreenStickyShulkerMixin capture the tooltip at press time and
        //     keep drawing it until the keys are released (shipped in 1.4.1).
        if (isShiftDown()) return;

        // Compact item-by-item summary. Long lists get truncated so the
        // tooltip stays a sensible size; the user can still Shift for the grid.
        lines.add(Component.literal("Contents:").withStyle(ChatFormatting.GRAY));
        final int MAX_LINES = 8;
        int shown = 0;
        for (ItemStack s : nonEmpty) {
            if (shown >= MAX_LINES) {
                int remaining = nonEmpty.size() - shown;
                lines.add(Component.literal("  …and " + remaining + " more stack" + (remaining == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            lines.add(Component.literal("  " + s.getCount() + "× " + s.getHoverName().getString())
                    .withStyle(ChatFormatting.DARK_GRAY));
            shown++;
        }
        lines.add(Component.literal("[Shift] visual grid").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static boolean isShiftDown() {
        com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private static boolean isAltDown() {
        com.mojang.blaze3d.platform.Window w = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_ALT)
            || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_ALT);
    }
}
