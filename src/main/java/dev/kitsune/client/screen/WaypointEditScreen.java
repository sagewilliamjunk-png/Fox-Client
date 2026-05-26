package dev.kitsune.client.screen;

import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Edit a single waypoint — opens from the per-row "Edit" button on
 * {@link WaypointsScreen}. The user can change name, symbol (1-2 chars),
 * color (via the 8-swatch palette), and global/local flag.
 *
 * <p>Persistence: clicking "Save" calls {@link WaypointManager#update(Waypoint)}
 * which is atomic. Cancel just navigates back without touching anything.
 */
public class WaypointEditScreen extends Screen {

    private static final int[] COLOR_PALETTE = {
            0xFFCC8833,  // amber (default)
            0xFFFF3333,  // red
            0xFFFF9933,  // orange
            0xFFFFCC33,  // yellow
            0xFF66CC44,  // green
            0xFF33CCCC,  // teal
            0xFF4488FF,  // blue
            0xFFCC44CC,  // pink
            0xFFFFFFFF,  // white
    };

    private final Screen parent;
    private final Waypoint original;
    /** Mutable working copy — applied to the manager on Save. */
    private int     editColor;
    private boolean editGlobal;

    private EditBox nameBox;
    private EditBox symbolBox;

    public WaypointEditScreen(Screen parent, Waypoint waypoint) {
        super(Component.literal("Edit waypoint"));
        this.parent  = parent;
        this.original = waypoint;
        this.editColor  = waypoint.color();
        this.editGlobal = waypoint.global();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // Name field
        nameBox = new EditBox(this.font, cx - 150, 60, 300, 20, Component.literal("Name"));
        nameBox.setValue(original.name());
        nameBox.setMaxLength(48);
        this.addRenderableWidget(nameBox);

        // Symbol field — 1-2 chars
        symbolBox = new EditBox(this.font, cx - 150, 100, 80, 20, Component.literal("Symbol"));
        symbolBox.setValue(original.symbol() == null ? "•" : original.symbol());
        symbolBox.setMaxLength(2);
        this.addRenderableWidget(symbolBox);

        // Color swatches — 9 palette entries in a single row.
        int swatchSize = 24;
        int totalW = COLOR_PALETTE.length * (swatchSize + 4) - 4;
        int startX = cx - totalW / 2;
        for (int i = 0; i < COLOR_PALETTE.length; i++) {
            final int color = COLOR_PALETTE[i];
            this.addRenderableWidget(new ColorSwatchButton(
                    startX + i * (swatchSize + 4), 140, swatchSize, swatchSize,
                    color, () -> editColor == color,
                    () -> { editColor = color; this.rebuildWidgets(); }));
        }

        // Global/Local toggle
        this.addRenderableWidget(Button.builder(
                Component.literal(editGlobal ? "Global ✓ (ignores Max Draw)" : "Local (respects Max Draw)"),
                b -> { editGlobal = !editGlobal; this.rebuildWidgets(); }
        ).bounds(cx - 150, 180, 300, 20).build());

        // Save / Cancel
        this.addRenderableWidget(FoxButton.of(cx - 150, this.height - 40, 145, 20,
                Component.literal("Cancel"), b -> this.onClose()));
        this.addRenderableWidget(FoxButton.of(cx + 5, this.height - 40, 145, 20,
                Component.literal("✓ Save"), b -> save()));
    }

    private void save() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = original.name();
        String symbol = symbolBox.getValue();
        if (symbol == null || symbol.isEmpty()) symbol = original.symbol();
        Waypoint updated = new Waypoint(
                original.id(), name,
                original.x(), original.y(), original.z(),
                editColor, symbol, editGlobal,
                original.deathpoint());
        WaypointManager.update(updated);
        this.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partial) {
        super.extractRenderState(gfx, mouseX, mouseY, partial);

        int cx = this.width / 2;
        gfx.text(this.font, "Edit waypoint", cx - this.font.width("Edit waypoint") / 2, 20, 0xFFFFFFFF);
        gfx.text(this.font, "§7" + original.x() + ", " + original.y() + ", " + original.z(),
                cx - 100, 40, 0xFFAAAAAA);

        gfx.text(this.font, "Name",   nameBox.getX(),   nameBox.getY()   - 12, 0xFFAAAAAA);
        gfx.text(this.font, "Symbol", symbolBox.getX(), symbolBox.getY() - 12, 0xFFAAAAAA);
        gfx.text(this.font, "Color",  cx - 100,         128,                   0xFFAAAAAA);
        gfx.text(this.font, "Scope",  cx - 150,         168,                   0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /** Minimal color-swatch button — fills the rect with its color and draws
     *  a thick light border when selected. Extends {@link net.minecraft.client.gui.components.AbstractButton}
     *  directly so we can override {@code extractContents} (the method
     *  Button itself uses to fill in its label area; the outer sprite is
     *  drawn by the framework). */
    private static class ColorSwatchButton extends net.minecraft.client.gui.components.AbstractButton {
        private final int color;
        private final java.util.function.BooleanSupplier selectedCheck;
        private final Runnable onClick;

        ColorSwatchButton(int x, int y, int w, int h, int color,
                          java.util.function.BooleanSupplier selectedCheck, Runnable onClick) {
            super(x, y, w, h, Component.empty());
            this.color = color;
            this.selectedCheck = selectedCheck;
            this.onClick = onClick;
        }

        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            onClick.run();
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput out) {
            this.defaultButtonNarrationText(out);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partial) {
            int x0 = this.getX();
            int y0 = this.getY();
            int x1 = x0 + this.getWidth();
            int y1 = y0 + this.getHeight();
            // Solid color fill (covers the inactive-vanilla-sprite the framework drew).
            gfx.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, color);
            // Border — bright when selected, dim otherwise.
            int border = selectedCheck.getAsBoolean() ? 0xFFFFFFFF : 0x55FFFFFF;
            gfx.fill(x0, y0, x1, y0 + 1, border);
            gfx.fill(x0, y1 - 1, x1, y1, border);
            gfx.fill(x0, y0, x0 + 1, y1, border);
            gfx.fill(x1 - 1, y0, x1, y1, border);
        }
    }
}
