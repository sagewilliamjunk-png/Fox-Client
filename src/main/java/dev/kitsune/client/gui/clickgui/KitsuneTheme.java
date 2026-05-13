package dev.kitsune.client.gui.clickgui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Fox-den palette + primitive draw helpers for the Kitsune ClickGUI. Pure
 * {@link GuiGraphicsExtractor#fill} calls — no texture assets, so the GUI works without
 * a resource pack reload.
 *
 * <p>Palette colors come directly from the user's spec.
 */
public final class KitsuneTheme {
    // ---- palette ----
    public static final int ORANGE    = 0xFFE87722; // primary accent
    public static final int ORANGE_HI = 0xFFFFC072; // hover highlight
    public static final int CREAM     = 0xFFFFF4E1; // panel background
    public static final int CREAM_DIM = 0xFFE8DCC5; // muted cream (disabled / alt row)
    public static final int BARK      = 0xFF3B2417; // dark text / border
    public static final int BARK_SOFT = 0xFF5C3A21; // softer border
    public static final int SHADOW    = 0x80000000; // drop shadow
    public static final int OVERLAY   = 0xC0101008; // ClickGUI full-screen wash

    private KitsuneTheme() {}

    /** Wooden burrow-card panel: cream fill, bark border, highlight corners. */
    public static void drawWoodenPanel(GuiGraphicsExtractor gfx, int x, int y, int w, int h) {
        // drop shadow
        gfx.fill(x + 2, y + 2, x + w + 2, y + h + 2, SHADOW);
        // bark outer border
        gfx.fill(x, y, x + w, y + h, BARK);
        // cream body (inset 1px)
        gfx.fill(x + 1, y + 1, x + w - 1, y + h - 1, CREAM);
        // bark-soft inner line (grain hint)
        gfx.fill(x + 2, y + 2, x + w - 2, y + 3, BARK_SOFT);
        // corner highlights
        gfx.fill(x + 1, y + 1, x + 3, y + 2, ORANGE_HI);
        gfx.fill(x + w - 3, y + 1, x + w - 1, y + 2, ORANGE_HI);
    }

    /** Fox-ear tab: triangle-topped header rectangle for category titles. */
    public static void drawFoxEarTab(GuiGraphicsExtractor gfx, int x, int y, int w, int h, boolean selected) {
        int fill = selected ? ORANGE : BARK_SOFT;
        // body
        gfx.fill(x, y + 3, x + w, y + h, fill);
        gfx.fill(x, y + h - 1, x + w, y + h, BARK);
        // triangular "ears" at top corners (stepped pixel art)
        for (int i = 0; i < 3; i++) {
            gfx.fill(x + i, y + 3 - i, x + i + 1, y + 3, fill);
            gfx.fill(x + w - 1 - i, y + 3 - i, x + w - i, y + 3, fill);
        }
        // inner cream dot on each ear when selected
        if (selected) {
            gfx.fill(x + 1, y + 2, x + 2, y + 3, CREAM);
            gfx.fill(x + w - 2, y + 2, x + w - 1, y + 3, CREAM);
        }
    }

    /** Paw-print bullet: 4 small dots arranged as a paw. 5x5 footprint. */
    public static void drawPawBullet(GuiGraphicsExtractor gfx, int x, int y, int color) {
        // main pad
        gfx.fill(x + 1, y + 2, x + 4, y + 5, color);
        // three toes
        gfx.fill(x,     y,     x + 1, y + 1, color);
        gfx.fill(x + 2, y,     x + 3, y + 1, color);
        gfx.fill(x + 4, y,     x + 5, y + 1, color);
    }

    /** Thin 1px underline in orange — used for category headers. */
    public static void drawOrangeRule(GuiGraphicsExtractor gfx, int x, int y, int w) {
        gfx.fill(x, y, x + w, y + 1, ORANGE);
    }

    /** Full-screen dim overlay behind the ClickGUI. */
    public static void drawOverlay(GuiGraphicsExtractor gfx, int w, int h) {
        gfx.fill(0, 0, w, h, OVERLAY);
    }
}
