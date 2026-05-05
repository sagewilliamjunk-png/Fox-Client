package dev.kitsune.client.hud;

import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Drag-and-drop HUD layout editor. Opened via {@code .fox hud}.
 *
 * <p>Each visible {@link HudWidget} is rendered at its current position with
 * a translucent selection box. Click and drag a box to reposition; drag near
 * a screen edge to snap. Position is persisted to {@code config/kitsune/hud.json}
 * via {@link HudManager}. The currently dragged widget's {@link HudManager.Anchor}
 * is automatically reassigned to the closest screen corner so it stays pinned.
 */
public class HudEditorScreen extends Screen {

    private static final int SNAP_PX = 6;
    /** Grid sizes the user can cycle through. 0 = grid disabled. */
    private static final int[] GRID_STEPS = { 0, 4, 8, 16 };
    /** Persisted across editor opens so the user doesn't have to re-enable it every time. */
    private static int gridSize = 8;

    private HudWidget dragging = null;
    private int dragOffsetX, dragOffsetY;
    private Button gridButton;

    public HudEditorScreen() {
        super(Component.literal("HUD Editor"));
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(
                Component.literal("Reset positions"),
                btn -> HudManager.resetAll()
        ).bounds(this.width - 130, 6, 120, 18).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                btn -> this.onClose()
        ).bounds(this.width - 130, 28, 120, 18).build());

        gridButton = Button.builder(
                gridLabel(),
                btn -> { cycleGrid(); btn.setMessage(gridLabel()); }
        ).bounds(this.width - 130, 50, 120, 18).build();
        this.addRenderableWidget(gridButton);
    }

    private static void cycleGrid() {
        int idx = 0;
        for (int i = 0; i < GRID_STEPS.length; i++) {
            if (GRID_STEPS[i] == gridSize) { idx = i; break; }
        }
        gridSize = GRID_STEPS[(idx + 1) % GRID_STEPS.length];
    }

    private static Component gridLabel() {
        return Component.literal("Grid: " + (gridSize == 0 ? "off" : gridSize + "px"));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // Dim background
        gfx.fill(0, 0, this.width, this.height, 0xA0000000);

        // Grid (under everything else so widget previews stay readable)
        if (gridSize > 0) drawGrid(gfx);

        // Title and hint
        gfx.drawString(this.font, "\u00a76Fox \u00a7eHUD Editor", 8, 8, 0xFFFFFFFF, true);
        gfx.drawString(this.font,
                "\u00a77Drag widgets to reposition. G cycles grid, edges snap. ESC to close.",
                8, 20, 0xFFCCCCCC, false);

        // Only show widgets that are actually enabled — they render exactly
        // as they appear in-game so the user is positioning the real thing,
        // not a placeholder. Disabled widgets are filtered out entirely.
        int sw = this.width;
        int sh = this.height;
        for (HudWidget w : HudManager.all()) {
            if (!w.isWidgetVisible()) continue;
            HudManager.Position p = HudManager.getPosition(w.widgetId());
            int x = p.absX(sw, w.widgetWidth());
            int y = p.absY(sh, w.widgetHeight());

            // Render the widget exactly as it would appear in-game. Defensive
            // try/catch — a buggy widget shouldn't break the whole editor.
            try { w.renderWidget(gfx, x, y); }
            catch (Throwable ignored) {}

            // Selection box (orange when actively dragged, white otherwise)
            int x2 = x + w.widgetWidth();
            int y2 = y + w.widgetHeight();
            int border = (dragging == w) ? FoxTheme.FOX_ORANGE : 0xFFFFFFFF;
            outline(gfx, x - 1, y - 1, x2 + 1, y2 + 1, border);

            // Label above the box
            gfx.drawString(this.font, w.displayName(), x, Math.max(0, y - 9), 0xFFFFFFFF, true);
        }

        super.render(gfx, mouseX, mouseY, delta);
    }

    private static void outline(GuiGraphics gfx, int x1, int y1, int x2, int y2, int color) {
        gfx.fill(x1, y1, x2, y1 + 1, color);
        gfx.fill(x1, y2 - 1, x2, y2, color);
        gfx.fill(x1, y1, x1 + 1, y2, color);
        gfx.fill(x2 - 1, y1, x2, y2, color);
    }

    private void drawGrid(GuiGraphics gfx) {
        // Faint cell lines + slightly stronger every-4th line so the eye can
        // pick out alignment columns without the screen looking like graph paper.
        int faint = 0x14FFFFFF;
        int strong = 0x28FFFFFF;
        for (int x = 0; x < this.width; x += gridSize) {
            int color = (x / gridSize) % 4 == 0 ? strong : faint;
            gfx.fill(x, 0, x + 1, this.height, color);
        }
        for (int y = 0; y < this.height; y += gridSize) {
            int color = (y / gridSize) % 4 == 0 ? strong : faint;
            gfx.fill(0, y, this.width, y + 1, color);
        }
    }

    private static int snapToGrid(int v) {
        if (gridSize <= 0) return v;
        return Math.round((float) v / gridSize) * gridSize;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // G cycles grid step (off → 4 → 8 → 16 → off).
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_G) {
            cycleGrid();
            if (gridButton != null) gridButton.setMessage(gridLabel());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (button == 0) {
            // Hit-test top-most first (last in registry order)
            var widgets = HudManager.all();
            for (int i = widgets.size() - 1; i >= 0; i--) {
                HudWidget w = widgets.get(i);
                // Only enabled widgets are interactive — disabled ones aren't drawn.
                if (!w.isWidgetVisible()) continue;
                HudManager.Position p = HudManager.getPosition(w.widgetId());
                int x = p.absX(this.width, w.widgetWidth());
                int y = p.absY(this.height, w.widgetHeight());
                if (mx >= x && mx < x + w.widgetWidth() && my >= y && my < y + w.widgetHeight()) {
                    dragging = w;
                    dragOffsetX = (int) (mx - x);
                    dragOffsetY = (int) (my - y);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != null) {
            int newAbsX = (int) (event.x() - dragOffsetX);
            int newAbsY = (int) (event.y() - dragOffsetY);
            int ww = dragging.widgetWidth();
            int wh = dragging.widgetHeight();

            // Clamp to screen
            newAbsX = Math.max(0, Math.min(this.width - ww, newAbsX));
            newAbsY = Math.max(0, Math.min(this.height - wh, newAbsY));

            // Grid snap (applied first so edge-snap can override at the edges).
            if (gridSize > 0) {
                newAbsX = Math.max(0, Math.min(this.width - ww, snapToGrid(newAbsX)));
                newAbsY = Math.max(0, Math.min(this.height - wh, snapToGrid(newAbsY)));
            }

            // Edge snap
            if (newAbsX < SNAP_PX) newAbsX = 0;
            if (newAbsY < SNAP_PX) newAbsY = 0;
            if (newAbsX + ww > this.width - SNAP_PX) newAbsX = this.width - ww;
            if (newAbsY + wh > this.height - SNAP_PX) newAbsY = this.height - wh;

            // Pick the closest corner as anchor so the widget reflows correctly
            // on resolution / GUI-scale changes.
            boolean left = (newAbsX + ww / 2) < this.width / 2;
            boolean top = (newAbsY + wh / 2) < this.height / 2;
            HudManager.Anchor anchor;
            int ox, oy;
            if (left && top) {
                anchor = HudManager.Anchor.TOP_LEFT;
                ox = newAbsX;
                oy = newAbsY;
            } else if (!left && top) {
                anchor = HudManager.Anchor.TOP_RIGHT;
                ox = this.width - ww - newAbsX;
                oy = newAbsY;
            } else if (left) {
                anchor = HudManager.Anchor.BOTTOM_LEFT;
                ox = newAbsX;
                oy = this.height - wh - newAbsY;
            } else {
                anchor = HudManager.Anchor.BOTTOM_RIGHT;
                ox = this.width - ww - newAbsX;
                oy = this.height - wh - newAbsY;
            }
            HudManager.setPosition(dragging.widgetId(), new HudManager.Position(anchor, ox, oy));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
