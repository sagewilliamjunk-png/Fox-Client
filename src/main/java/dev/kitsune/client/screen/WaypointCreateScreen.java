package dev.kitsune.client.screen;

import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Tiny modal that pops up when the user hits B in-game — lets them type a
 * proper name for the waypoint instead of the auto-generated WP-N. Captures
 * the coordinates at the moment B was pressed so the waypoint lands where
 * the player was, not where they ended up after typing.
 *
 * <p>Esc or Cancel closes without creating; Enter or Save creates and
 * dismisses with a notification toast.
 */
public class WaypointCreateScreen extends Screen {

    private final Screen parent;
    private final int snapX, snapY, snapZ;
    private EditBox nameBox;

    public WaypointCreateScreen(Screen parent, int x, int y, int z) {
        super(Component.literal("Create waypoint"));
        this.parent = parent;
        this.snapX = x;
        this.snapY = y;
        this.snapZ = z;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        nameBox = new EditBox(this.font, cx - 150, 80, 300, 20, Component.literal("Name"));
        nameBox.setValue(WaypointManager.nextDefaultName());
        nameBox.setMaxLength(48);
        // Select-all the suggested name so the user can just start typing
        // their preferred label over the top.
        nameBox.setCursorPosition(0);
        nameBox.setHighlightPos(nameBox.getValue().length());
        this.addRenderableWidget(nameBox);
        this.setInitialFocus(nameBox);

        this.addRenderableWidget(FoxButton.of(cx - 150, 130, 145, 20,
                Component.literal("Cancel"),
                b -> this.onClose()));
        this.addRenderableWidget(FoxButton.of(cx + 5, 130, 145, 20,
                Component.literal("✓ Create"),
                b -> create()));
    }

    private void create() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = WaypointManager.nextDefaultName();
        String symbol = name.substring(0, 1).toUpperCase();
        Waypoint w = new Waypoint(
                null, name, snapX, snapY, snapZ,
                Waypoint.DEFAULT_COLOR, symbol,
                false, false);
        Waypoint stored = WaypointManager.addToCurrent(w);
        if (stored != null) {
            NotificationManager.show("Waypoint created: " + name,
                    NotificationManager.Type.SUCCESS);
        }
        this.onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        // Enter on the name field commits.
        if (e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || e.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            create();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partial) {
        super.extractRenderState(gfx, mouseX, mouseY, partial);
        int cx = this.width / 2;
        gfx.text(this.font, "New waypoint",
                cx - this.font.width("New waypoint") / 2, 28, 0xFFFFFFFF);
        gfx.text(this.font, "§7" + snapX + ", " + snapY + ", " + snapZ,
                cx - 60, 48, 0xFFAAAAAA);
        gfx.text(this.font, "Name", nameBox.getX(), nameBox.getY() - 12, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
