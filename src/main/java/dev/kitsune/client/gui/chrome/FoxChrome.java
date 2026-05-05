package dev.kitsune.client.gui.chrome;

import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/**
 * Shared painter for Fox-themed vanilla widgets.
 *
 * <p>Used by the three render-mixins ({@code AbstractButtonRenderMixin},
 * {@code AbstractSliderButtonRenderMixin}, {@code EditBoxRenderMixin}) so that
 * every screen the user sees — title, pause, options, realms, chat — shares
 * one visual language instead of each widget carrying its own knobs.
 *
 * <p>Design: near-black dark bark panels, thin bark border, subtle top
 * highlight and bottom drop-shadow for depth, orange accent on hover. The
 * goal is the same modern-dark-pill feel as Lunar / Feather, adapted to
 * Fox's palette.
 */
public final class FoxChrome {
    private FoxChrome() {}

    // ---- Palette ------------------------------------------------------------
    /** Base panel fill for enabled widgets. Warm near-black. */
    public static final int BG_ACTIVE      = 0xFF15110D;
    /** Base panel fill for disabled widgets. */
    public static final int BG_DISABLED    = 0xFF0E0A07;
    /** Rest-state border. */
    public static final int BORDER_REST    = 0xFF2A1E14;
    /** Border color on hover / focus. */
    public static final int BORDER_HOVER   = FoxTheme.FOX_ORANGE;
    /** Subtle inner top highlight that gives the panel a light source. */
    public static final int TOP_HIGHLIGHT  = 0x18FFFFFF;
    /** Drop shadow under the widget (1 px). */
    public static final int DROP_SHADOW    = 0x60000000;
    /** Disabled text. */
    public static final int TEXT_DISABLED  = 0xFF5C5248;
    /** Active text, rest state. */
    public static final int TEXT_ACTIVE    = 0xFFE6E0D4;
    /** Active text when hovered — shifts warm amber. */
    public static final int TEXT_HOVER     = 0xFFFFD89C;
    /** Slider track fill. */
    public static final int SLIDER_TRACK   = 0xFF0B0807;
    /** Slider handle fill. */
    public static final int SLIDER_HANDLE  = 0xFF2A1E14;

    // ---- Primitives ---------------------------------------------------------

    /**
     * Paint the standard Fox panel chrome (bg, border, top highlight, drop shadow).
     * Does not draw any content — caller is responsible for text / icon / slider
     * thumb / etc on top.
     */
    public static void paintPanel(GuiGraphics gfx, int x, int y, int w, int h,
                                   boolean active, float hoverLerp) {
        if (w <= 0 || h <= 0) return;

        // Drop shadow under the widget
        gfx.fill(x + 1, y + h, x + w + 1, y + h + 1, DROP_SHADOW);

        // Base fill
        int bg = active ? BG_ACTIVE : BG_DISABLED;
        gfx.fill(x, y, x + w, y + h, bg);

        // Inner top highlight — stops 1px in from the border so it doesn't
        // fight the corner pixels
        gfx.fill(x + 1, y + 1, x + w - 1, y + 2, TOP_HIGHLIGHT);

        // Border — lerped rest → hover
        int border = lerpARGB(BORDER_REST, BORDER_HOVER, hoverLerp);
        drawRectBorder(gfx, x, y, w, h, border);

        // Hover accent: thin orange bar along the top + faint tint overlay
        if (hoverLerp > 0.01f) {
            int topA = (int) (hoverLerp * 210);
            int topColor = (topA << 24) | (FoxTheme.FOX_ORANGE & 0x00FFFFFF);
            gfx.fill(x + 2, y + 1, x + w - 2, y + 2, topColor);

            int tintA = (int) (hoverLerp * 22);
            int tintColor = (tintA << 24) | 0x00FF9040;
            gfx.fill(x + 1, y + 2, x + w - 1, y + h - 1, tintColor);
        }
    }

    /**
     * Paint standard centered text inside a panel — respects active + hover state.
     * Truncates with an ellipsis if the text is wider than the panel minus padding.
     */
    public static void paintCenteredText(GuiGraphics gfx, Font font, Component msg,
                                          int x, int y, int w, int h,
                                          boolean active, float hoverLerp) {
        int color = active
                ? lerpARGB(TEXT_ACTIVE, TEXT_HOVER, hoverLerp)
                : TEXT_DISABLED;
        int tx = x + w / 2;
        int ty = y + (h - 8) / 2;
        // Ellipsize if too wide
        FormattedText drawn = msg;
        int maxW = w - 6;
        if (font.width(msg) > maxW) {
            drawn = FormattedText.composite(
                    font.substrByWidth(msg, maxW - font.width("...")),
                    FormattedText.of("..."));
        }
        gfx.drawCenteredString(font, Component.literal(drawn.getString()), tx, ty, color);
    }

    /** Thin 1-px rectangular border with 1-px corner chamfer. */
    private static void drawRectBorder(GuiGraphics gfx, int x, int y, int w, int h, int color) {
        // Top / bottom edges, skipping corner pixels for a chamfered look
        gfx.fill(x + 1, y,         x + w - 1, y + 1,     color);
        gfx.fill(x + 1, y + h - 1, x + w - 1, y + h,     color);
        // Left / right edges
        gfx.fill(x,         y + 1, x + 1,     y + h - 1, color);
        gfx.fill(x + w - 1, y + 1, x + w,     y + h - 1, color);
    }

    /**
     * Per-channel linear interpolation between two packed ARGB colors.
     * {@code t} is clamped to [0,1].
     */
    public static int lerpARGB(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int ca = aa + (int) ((ba - aa) * t);
        int cr = ar + (int) ((br - ar) * t);
        int cg = ag + (int) ((bg - ag) * t);
        int cb = ab + (int) ((bb - ab) * t);
        return (ca << 24) | (cr << 16) | (cg << 8) | cb;
    }

    /**
     * Advance a hover-lerp value toward {@code target} (0 or 1) by the elapsed
     * frame time, full fade over 150 ms. Call this once per render frame.
     *
     * @param current previous lerp value in [0,1]
     * @param target desired lerp value (0 or 1)
     * @param dtMs milliseconds since last render (clamped to a reasonable range)
     * @return new lerp value in [0,1]
     */
    public static float stepHover(float current, float target, float dtMs) {
        float step = Math.max(1f, Math.min(80f, dtMs)) / 150f;
        if (current < target) return Math.min(target, current + step);
        if (current > target) return Math.max(target, current - step);
        return current;
    }
}
