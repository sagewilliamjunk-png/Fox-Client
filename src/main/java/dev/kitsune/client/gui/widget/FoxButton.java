package dev.kitsune.client.gui.widget;

import dev.kitsune.client.gui.clickgui.KitsuneTheme;
import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A themed replacement for vanilla {@link Button}, used inside the
 * Fox Client menus. Dark bark background with bright text for maximum
 * readability on any background.
 *
 * <p>Implemented directly on {@link AbstractWidget} — extending {@code Button}
 * doesn't work in 1.21.11 because {@code AbstractButton.renderWidget} is final.
 */
public class FoxButton extends AbstractWidget {

    public interface OnPress {
        void onPress(FoxButton btn);
    }

    private final OnPress onPress;

    /** 0..1 eased hover weight. Lerps toward {@code isHovered()} each frame. */
    private float hoverLerp = 0f;
    /** Wall-clock time of the last render so we can scale lerp to frame delta. */
    private long lastRenderMs = 0L;

    public FoxButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
    }

    public static FoxButton of(int x, int y, int w, int h, Component message, OnPress onPress) {
        return new FoxButton(x, y, w, h, message, onPress);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        int bx = this.getX();
        int by = this.getY();
        int bw = this.getWidth();
        int bh = this.getHeight();
        boolean hovered = this.isHovered() && this.active;

        // Smooth hover lerp — 150 ms full fade.
        long now = System.currentTimeMillis();
        float dt = lastRenderMs == 0L ? 16f : Math.min(100f, now - lastRenderMs);
        lastRenderMs = now;
        float target = hovered ? 1f : 0f;
        float step   = dt / 150f;
        if (hoverLerp < target) hoverLerp = Math.min(target, hoverLerp + step);
        else if (hoverLerp > target) hoverLerp = Math.max(target, hoverLerp - step);
        float h = hoverLerp; // 0..1

        // ---------------- Outer glow ring (hover only) ----------------
        // Three concentric rectangles outside the button bounds, each one
        // pixel bigger and progressively more transparent, so the edge
        // softly bleeds into the background. Scales with `h` so the glow
        // eases in/out with the hover fade instead of popping.
        if (h > 0.01f) {
            int glowRgb = FoxTheme.FOX_ORANGE & 0x00FFFFFF;
            // layer 1: closest + brightest
            int a1 = (int) (h * 110);
            int c1 = (a1 << 24) | glowRgb;
            drawRingOutside(gfx, bx, by, bw, bh, 1, c1);
            // layer 2: medium
            int a2 = (int) (h * 60);
            int c2 = (a2 << 24) | glowRgb;
            drawRingOutside(gfx, bx - 1, by - 1, bw + 2, bh + 2, 1, c2);
            // layer 3: outermost, warm amber tail
            int a3 = (int) (h * 28);
            int c3 = (a3 << 24) | 0x00FF9050;
            drawRingOutside(gfx, bx - 2, by - 2, bw + 4, bh + 4, 1, c3);
        }

        // Dark background for readability on any backdrop
        int bgColor = this.active ? 0xE0201810 : 0xC0181210;

        // Border lerps from bark → orange over the hover fade
        int borderColor = lerpRGB(0xFF5A4530, FoxTheme.FOX_ORANGE, h);

        // Outer border
        gfx.fill(bx, by, bx + bw, by + 1, borderColor);
        gfx.fill(bx, by + bh - 1, bx + bw, by + bh, borderColor);
        gfx.fill(bx, by, bx + 1, by + bh, borderColor);
        gfx.fill(bx + bw - 1, by, bx + bw, by + bh, borderColor);

        // Inner fill — dark bark
        gfx.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, bgColor);

        // Hover highlight — orange top bar + tint fade in as h ramps up
        if (h > 0.01f) {
            int topA = (int) (h * 255);
            int topCol = (topA << 24) | (FoxTheme.FOX_ORANGE & 0x00FFFFFF);
            gfx.fill(bx + 1, by + 1, bx + bw - 1, by + 3, topCol);
            int tintA = (int) (h * 0x20);
            int tintCol = (tintA << 24) | 0x00FFA050;
            gfx.fill(bx + 1, by + 3, bx + bw - 1, by + bh - 1, tintCol);
        }

        // Bright text — white when active, dim when disabled. Shifts toward
        // warm amber as the hover fade progresses.
        var font = Minecraft.getInstance().font;
        int baseText = this.active ? 0xFFFFFFFF : 0xFF888888;
        int textColor = this.active ? lerpRGB(baseText, 0xFFFFCC80, h) : baseText;
        int tx = bx + bw / 2;
        int ty = by + (bh - 8) / 2;
        gfx.centeredText(font, this.getMessage(), tx, ty, textColor);
    }

    /**
     * Draw a hollow rectangle one pixel thick just <em>outside</em> the
     * given rectangle. Used for the hover glow ring — cheap enough to
     * stack for a soft bloom effect.
     */
    private static void drawRingOutside(GuiGraphicsExtractor gfx, int rx, int ry, int rw, int rh, int thick, int color) {
        int x0 = rx - thick;
        int y0 = ry - thick;
        int x1 = rx + rw + thick;
        int y1 = ry + rh + thick;
        // top
        gfx.fill(x0, y0, x1, ry, color);
        // bottom
        gfx.fill(x0, ry + rh, x1, y1, color);
        // left
        gfx.fill(x0, ry, rx, ry + rh, color);
        // right
        gfx.fill(rx + rw, ry, x1, ry + rh, color);
    }

    /** Simple per-channel linear interpolation between two packed ARGB colors. */
    private static int lerpRGB(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int ar = (a >>> 24) & 0xFF, ag = (a >>> 16) & 0xFF, ab = (a >>> 8) & 0xFF, aa = a & 0xFF;
        int br = (b >>> 24) & 0xFF, bg = (b >>> 16) & 0xFF, bb = (b >>> 8) & 0xFF, ba = b & 0xFF;
        int rr = ar + (int) ((br - ar) * t);
        int gg = ag + (int) ((bg - ag) * t);
        int bbb = ab + (int) ((bb - ab) * t);
        int aaa = aa + (int) ((ba - aa) * t);
        return (rr << 24) | (gg << 16) | (bbb << 8) | aaa;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (onPress != null) onPress.onPress(this);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        this.defaultButtonNarrationText(out);
    }
}
