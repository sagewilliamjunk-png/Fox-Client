package dev.kitsune.client.screen;

import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Press-U screen showing every waypoint in the current sub-world.
 *
 * <p>Layout: top bar (title + sub-world id + "+ New" + Close), main scrollable
 * list of waypoint rows, and a per-row delete button. Clicking a row's name
 * opens an inline rename. Bottom strip has a small new-waypoint form.
 *
 * <p>Teleportation isn't implemented here yet — it would need a chat command
 * send (which only works on creative / op servers anyway) and a confirmation
 * step. Listed in the v1.4 plan.
 */
public class WaypointsScreen extends Screen {

    private final Screen parent;
    private final List<Waypoint> snapshot = new ArrayList<>();
    private EditBox newNameBox;
    private int scrollOffset = 0;

    private static final int ROW_HEIGHT     = 22;
    private static final int LIST_TOP       = 60;
    private static final int LIST_BOTTOM    = 60; // reserved below the list

    public WaypointsScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        snapshot.clear();
        snapshot.addAll(WaypointManager.current());

        int cx = this.width / 2;

        // New-waypoint name input + button at the bottom.
        newNameBox = new EditBox(this.font, cx - 160, this.height - 40, 200, 20,
                Component.literal("Name"));
        newNameBox.setHint(Component.literal(WaypointManager.nextDefaultName()));
        newNameBox.setMaxLength(48);
        this.addRenderableWidget(newNameBox);

        this.addRenderableWidget(Button.builder(
                Component.literal("+ Create at current location"),
                b -> createAtCurrentLocation()
        ).bounds(cx + 50, this.height - 40, 200, 20).build());

        // Top bar: cycle set + close
        this.addRenderableWidget(FoxButton.of(this.width - 220, 8, 130, 20,
                Component.literal("Set: " + WaypointManager.activeSet()),
                b -> {
                    WaypointManager.cycleActiveSet();
                    snapshot.clear();
                    snapshot.addAll(WaypointManager.current());
                    this.rebuildWidgets();
                }));
        this.addRenderableWidget(FoxButton.of(this.width - 76, 8, 64, 20,
                Component.literal("Close"),
                b -> this.onClose()));

        // Add a delete button per row.
        rebuildListWidgets();
    }

    private void rebuildListWidgets() {
        int listHeight = this.height - LIST_TOP - LIST_BOTTOM;
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        int firstIdx = scrollOffset;
        int lastIdx  = Math.min(snapshot.size(), firstIdx + visibleRows);

        // Renderable text rows are drawn in extractRenderState; only the
        // Share / Edit / Delete buttons per row are widgets so they can take clicks.
        for (int i = firstIdx; i < lastIdx; i++) {
            final int idx = i;
            final int y = LIST_TOP + (i - firstIdx) * ROW_HEIGHT;
            // Share — send a "name @ X, Y, Z (set)" line to chat. Useful for
            // calling out positions to friends; the launcher's ChatLogger
            // module persists the conversation so it ends up in your logs too.
            this.addRenderableWidget(FoxButton.of(this.width - 220, y, 64, 18,
                    Component.literal("Share"),
                    b -> shareToChat(snapshot.get(idx))));
            this.addRenderableWidget(FoxButton.of(this.width - 148, y, 64, 18,
                    Component.literal("Edit"),
                    b -> {
                        if (this.minecraft != null) {
                            this.minecraft.setScreen(
                                    new WaypointEditScreen(this, snapshot.get(idx)));
                        }
                    }));
            this.addRenderableWidget(FoxButton.of(this.width - 76, y, 64, 18,
                    Component.literal("Delete"),
                    b -> {
                        WaypointManager.delete(snapshot.get(idx).id());
                        snapshot.clear();
                        snapshot.addAll(WaypointManager.current());
                        this.rebuildWidgets();
                    }));
        }
    }

    /** Send a chat line describing the waypoint. We use the raw chat channel
     *  (not a command) so it goes into the public room — calling out coords
     *  to teammates is the entire reason this button exists. */
    private void shareToChat(dev.kitsune.client.waypoint.Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        String line = w.name() + " @ " + w.x() + ", " + w.y() + ", " + w.z()
                + (w.deathpoint() ? " (deathpoint)" : "")
                + (w.set() != null && !w.set().equals(dev.kitsune.client.waypoint.Waypoint.DEFAULT_SET)
                        ? " [" + w.set() + "]" : "");
        try {
            mc.player.connection.sendChat(line);
            dev.kitsune.client.hud.NotificationManager.show(
                    "Shared: " + w.name(),
                    dev.kitsune.client.hud.NotificationManager.Type.SUCCESS);
        } catch (Throwable t) {
            dev.kitsune.client.hud.NotificationManager.show(
                    "Share failed: " + t.getMessage(),
                    dev.kitsune.client.hud.NotificationManager.Type.WARNING);
        }
    }

    private void createAtCurrentLocation() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String name = newNameBox.getValue().trim();
        if (name.isEmpty()) name = WaypointManager.nextDefaultName();
        String symbol = name.length() > 0
                ? name.substring(0, 1).toUpperCase()
                : "•";
        Waypoint w = new Waypoint(
                null, // manager assigns
                name,
                mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(),
                Waypoint.DEFAULT_COLOR,
                symbol,
                false,
                false);
        WaypointManager.addToCurrent(w);
        snapshot.clear();
        snapshot.addAll(WaypointManager.current());
        newNameBox.setValue("");
        this.rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double sx, double sy) {
        int listHeight = this.height - LIST_TOP - LIST_BOTTOM;
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        int maxOffset = Math.max(0, snapshot.size() - visibleRows);
        if (sy > 0 && scrollOffset > 0) {
            scrollOffset--;
            this.rebuildWidgets();
            return true;
        }
        if (sy < 0 && scrollOffset < maxOffset) {
            scrollOffset++;
            this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, sx, sy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partial) {
        super.extractRenderState(gfx, mouseX, mouseY, partial);

        // Header
        gfx.text(this.font, "Waypoints — " + (WaypointManager.currentSubWorldId() == null ? "(no world)" : WaypointManager.currentSubWorldId()),
                14, 14, 0xFFFFFFFF);
        gfx.text(this.font, snapshot.size() + " saved · scroll to page",
                14, 28, 0xFFAAAAAA);

        // Rows
        int listHeight = this.height - LIST_TOP - LIST_BOTTOM;
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        int firstIdx = scrollOffset;
        int lastIdx  = Math.min(snapshot.size(), firstIdx + visibleRows);
        Minecraft mc = Minecraft.getInstance();
        int px = mc.player != null ? mc.player.getBlockX() : 0;
        int pz = mc.player != null ? mc.player.getBlockZ() : 0;
        for (int i = firstIdx; i < lastIdx; i++) {
            Waypoint w = snapshot.get(i);
            int y = LIST_TOP + (i - firstIdx) * ROW_HEIGHT;
            // Row background
            gfx.fill(10, y, this.width - 80, y + ROW_HEIGHT - 2, 0x66000000);
            // Color swatch
            gfx.fill(14, y + 4, 30, y + 18, w.color());
            // Name + coords + distance
            String label = (w.deathpoint() ? "☠ " : "") + w.name();
            gfx.text(this.font, label, 36, y + 3, 0xFFFFFFFF);
            String coords = w.x() + ", " + w.y() + ", " + w.z();
            int dist = (int) Math.sqrt((w.x() - px) * (w.x() - px) + (w.z() - pz) * (w.z() - pz));
            gfx.text(this.font, "§7" + coords + "  ·  §8" + dist + "m",
                    36, y + 12, 0xFFAAAAAA);
        }

        if (snapshot.isEmpty()) {
            gfx.text(this.font, "No waypoints yet. Press B in-game to create one at your current location.",
                    14, LIST_TOP + 4, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
