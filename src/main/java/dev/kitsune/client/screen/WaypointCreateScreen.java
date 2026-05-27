package dev.kitsune.client.screen;

import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Xaero-style "New Waypoint" screen. Pops when the user hits the create
 * key (B by default). Lets the user pick a name, initials, color, edit the
 * snapped coordinates, choose Local vs Global, and assign a waypoint set.
 *
 * <p>Layout matches Xaero's reference closely so users coming from that mod
 * find every control where they expect it.
 */
public class WaypointCreateScreen extends Screen {

    /** Vanilla Minecraft 16-color text-code palette — same set Xaero uses
     *  and what most users have memorised as "waypoint colors". */
    private static final int[] PALETTE = {
            0xFF000000, // BLACK
            0xFF0000AA, // DARK_BLUE
            0xFF00AA00, // DARK_GREEN
            0xFF00AAAA, // DARK_AQUA
            0xFFAA0000, // DARK_RED
            0xFFAA00AA, // DARK_PURPLE
            0xFFFFAA00, // GOLD
            0xFFAAAAAA, // GRAY
            0xFF555555, // DARK_GRAY
            0xFF5555FF, // BLUE
            0xFF55FF55, // GREEN
            0xFF55FFFF, // AQUA
            0xFFFF5555, // RED
            0xFFFF55FF, // LIGHT_PURPLE
            0xFFFFFF55, // YELLOW
            0xFFFFFFFF, // WHITE
    };

    private final Screen parent;
    private final int initialX, initialY, initialZ;

    private EditBox nameBox;
    private EditBox initialsBox;
    private EditBox xBox, yBox, zBox;
    private EditBox setBox;
    private int    pickedColor = 0xFFCC8833; // amber default
    private boolean globalFlag = false;
    private boolean autoInitials = true;

    public WaypointCreateScreen(Screen parent, int x, int y, int z) {
        super(Component.literal("New Waypoint"));
        this.parent = parent;
        this.initialX = x;
        this.initialY = y;
        this.initialZ = z;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int rowY = 40;

        // Title is drawn in extractRenderState. Layout below.

        // Name + Initials (row 1)
        nameBox = new EditBox(this.font, cx - 200, rowY, 260, 20, Component.literal("Name"));
        nameBox.setValue(WaypointManager.nextDefaultName());
        nameBox.setMaxLength(48);
        nameBox.setCursorPosition(0);
        nameBox.setHighlightPos(nameBox.getValue().length());
        this.addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        initialsBox = new EditBox(this.font, cx + 70, rowY, 60, 20, Component.literal("Init"));
        initialsBox.setValue(autoInitialsFromName(nameBox.getValue()));
        initialsBox.setMaxLength(2);
        this.addRenderableWidget(initialsBox);

        // Hidden toggle: when "auto initials" is on we overwrite initialsBox
        // every time the name changes. Plain Button under initialsBox.
        this.addRenderableWidget(FoxButton.of(cx + 140, rowY, 60, 20,
                Component.literal(autoInitials ? "Auto ✓" : "Manual"),
                b -> { autoInitials = !autoInitials; if (autoInitials) initialsBox.setValue(autoInitialsFromName(nameBox.getValue())); this.rebuildWidgets(); }));

        rowY += 28;

        // Color palette — 16 swatches in a 2×8 grid, total width 192px.
        int swatch = 22, gap = 2;
        int paletteW = 8 * (swatch + gap) - gap;
        int paletteX = cx - paletteW / 2;
        for (int i = 0; i < PALETTE.length; i++) {
            int col = i % 8;
            int row = i / 8;
            final int color = PALETTE[i];
            this.addRenderableWidget(new SwatchButton(
                    paletteX + col * (swatch + gap),
                    rowY + row * (swatch + gap),
                    swatch, swatch,
                    color,
                    () -> pickedColor == color,
                    () -> { pickedColor = color; this.rebuildWidgets(); }));
        }
        rowY += 2 * (swatch + gap) + 8;

        // Coords (editable so the user can move them before confirming).
        int coordW = 60;
        xBox = makeNumberBox(cx - 200, rowY, coordW, initialX, "X"); this.addRenderableWidget(xBox);
        yBox = makeNumberBox(cx - 130, rowY, coordW, initialY, "Y"); this.addRenderableWidget(yBox);
        zBox = makeNumberBox(cx -  60, rowY, coordW, initialZ, "Z"); this.addRenderableWidget(zBox);
        // Set field
        setBox = new EditBox(this.font, cx + 10, rowY, 190, 20, Component.literal("Set"));
        setBox.setValue(WaypointManager.activeSet().equals(WaypointManager.ALL_SETS)
                ? Waypoint.DEFAULT_SET
                : WaypointManager.activeSet());
        setBox.setMaxLength(32);
        this.addRenderableWidget(setBox);

        rowY += 28;

        // Local/Global toggle
        this.addRenderableWidget(FoxButton.of(cx - 200, rowY, 130, 20,
                Component.literal(globalFlag ? "Global ✓ (always visible)" : "Local (respects max draw)"),
                b -> { globalFlag = !globalFlag; this.rebuildWidgets(); }));

        rowY += 36;

        // Confirm / Cancel
        this.addRenderableWidget(FoxButton.of(cx - 200, rowY, 195, 24,
                Component.literal("Cancel"),
                b -> this.onClose()));
        this.addRenderableWidget(FoxButton.of(cx + 5, rowY, 195, 24,
                Component.literal("✓ Confirm"),
                b -> confirm()));
    }

    private static String autoInitialsFromName(String name) {
        if (name == null || name.isEmpty()) return "•";
        // Take first letter of first word and (optionally) first letter of second.
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) return words[0].substring(0, Math.min(2, words[0].length())).toUpperCase();
        return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase();
    }

    private EditBox makeNumberBox(int x, int y, int w, int value, String hint) {
        EditBox box = new EditBox(this.font, x, y, w, 20, Component.literal(hint));
        box.setValue(String.valueOf(value));
        box.setMaxLength(7);
        return box;
    }

    private int parseIntSafe(EditBox box, int fallback) {
        try { return Integer.parseInt(box.getValue().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void confirm() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = WaypointManager.nextDefaultName();
        String initials = initialsBox.getValue();
        if (initials == null || initials.isEmpty()) initials = autoInitialsFromName(name);
        String set = setBox.getValue().trim();
        if (set.isEmpty()) set = Waypoint.DEFAULT_SET;
        int x = parseIntSafe(xBox, initialX);
        int y = parseIntSafe(yBox, initialY);
        int z = parseIntSafe(zBox, initialZ);

        Waypoint w = new Waypoint(
                null, name, x, y, z,
                pickedColor, initials,
                globalFlag, false, set);
        Waypoint stored = WaypointManager.addToCurrent(w);
        if (stored != null) {
            NotificationManager.show("Waypoint created: " + name,
                    NotificationManager.Type.SUCCESS);
        }
        this.onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        // Enter on any field commits the waypoint.
        if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            confirm();
            return true;
        }
        // While the name field is focused and auto-initials is on, mirror
        // each keystroke into the initials box. Best-effort — we re-derive
        // after the underlying key has been processed by the EditBox.
        boolean handled = super.keyPressed(e);
        if (autoInitials && nameBox != null && nameBox.isFocused()) {
            initialsBox.setValue(autoInitialsFromName(nameBox.getValue()));
        }
        return handled;
    }

    // Auto-initials sync happens in keyPressed only; if the user pastes into
    // the name field via a clipboard menu the initials won't refresh until
    // the next key event — acceptable trade-off for not chasing the renamed
    // charTyped API across MC versions.

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partial) {
        super.extractRenderState(gfx, mouseX, mouseY, partial);
        int cx = this.width / 2;
        // Title
        gfx.text(this.font, "New Waypoint",
                cx - this.font.width("New Waypoint") / 2, 20, 0xFFFFFFFF);
        // Field labels (above each EditBox)
        if (nameBox != null) {
            gfx.text(this.font, "Name",     nameBox.getX(),     nameBox.getY() - 10,     0xFFAAAAAA);
            gfx.text(this.font, "Initials", initialsBox.getX(), initialsBox.getY() - 10, 0xFFAAAAAA);
            gfx.text(this.font, "Color (" + colorNameFor(pickedColor) + ")",
                    cx - 60, nameBox.getY() + 28, 0xFFAAAAAA);
            gfx.text(this.font, "X", xBox.getX(), xBox.getY() - 10, 0xFFAAAAAA);
            gfx.text(this.font, "Y", yBox.getX(), yBox.getY() - 10, 0xFFAAAAAA);
            gfx.text(this.font, "Z", zBox.getX(), zBox.getY() - 10, 0xFFAAAAAA);
            gfx.text(this.font, "Set", setBox.getX(), setBox.getY() - 10, 0xFFAAAAAA);
        }
    }

    /** Best-match name for the currently picked color. Falls back to hex. */
    private static String colorNameFor(int argb) {
        String[] names = {"Black","Dark Blue","Dark Green","Dark Aqua","Dark Red",
                          "Dark Purple","Gold","Gray","Dark Gray","Blue","Green",
                          "Aqua","Red","Pink","Yellow","White"};
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == argb) return names[i];
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /** Color-picker swatch — colored square with bright border when selected. */
    private static class SwatchButton extends AbstractButton {
        private final int color;
        private final java.util.function.BooleanSupplier selectedCheck;
        private final Runnable onClick;

        SwatchButton(int x, int y, int w, int h, int color,
                     java.util.function.BooleanSupplier selectedCheck, Runnable onClick) {
            super(x, y, w, h, Component.empty());
            this.color = color;
            this.selectedCheck = selectedCheck;
            this.onClick = onClick;
        }

        @Override public void onPress(net.minecraft.client.input.InputWithModifiers input) { onClick.run(); }

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
            gfx.fill(x0 + 2, y0 + 2, x1 - 2, y1 - 2, color);
            int border = selectedCheck.getAsBoolean() ? 0xFFFFFFFF : 0x44FFFFFF;
            gfx.fill(x0, y0, x1, y0 + 1, border);
            gfx.fill(x0, y1 - 1, x1, y1, border);
            gfx.fill(x0, y0, x0 + 1, y1, border);
            gfx.fill(x1 - 1, y0, x1, y1, border);
        }
    }
}
