package dev.kitsune.client.features.qol;

import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
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

        // Alt+Shift → visual grid is showing; suppress text entirely
        if (isAltDown() && isShiftDown()) return;

        if (isShiftDown()) {
            // Compact count summary + hint for grid
            lines.add(Component.literal("Contains " + nonEmpty.size() + " stack" + (nonEmpty.size() == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Alt+Shift: view full contents")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        // No modifier → no extra lines (vanilla tooltip only)
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
