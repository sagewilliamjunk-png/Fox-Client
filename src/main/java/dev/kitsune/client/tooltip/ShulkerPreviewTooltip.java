package dev.kitsune.client.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Data carrier for the shulker-box visual grid tooltip.
 * Passed through the Fabric {@code TooltipComponentCallback} to produce a
 * {@link ClientShulkerPreviewTooltip} on the client side.
 */
public record ShulkerPreviewTooltip(ItemContainerContents contents) implements TooltipComponent {}
