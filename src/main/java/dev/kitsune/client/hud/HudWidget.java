package dev.kitsune.client.hud;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A draggable HUD overlay element. Widgets register with {@link HudManager}
 * which owns persistent x/y positions and renders them in the HUD tail.
 *
 * <p>Implementations are typically HUD-category {@code Module}s; the
 * {@code HudModule} convenience base does the registration plumbing.
 */
public interface HudWidget {
    /** Stable identifier used as the JSON key for position persistence. */
    String widgetId();

    /** Display name shown in the HUD editor. */
    default String displayName() { return widgetId(); }

    /** Width in scaled pixels for hit-testing and snap. */
    int widgetWidth();

    /** Height in scaled pixels for hit-testing and snap. */
    int widgetHeight();

    /**
     * Render at the given top-left {@code (x, y)} (already resolved from the
     * stored anchor + offset by {@link HudManager}).
     */
    void renderWidget(GuiGraphics gfx, int x, int y);

    /** Whether this widget should currently be drawn (e.g. module enabled). */
    boolean isWidgetVisible();
}
