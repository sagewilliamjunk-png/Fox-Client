package dev.kitsune.client.gui.clickgui;

import dev.kitsune.client.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A tiny floating HSV color picker for {@link ColorSetting}s in the ClickGUI.
 *
 * <p>Layout (all in logical pixels):
 * <pre>
 * ┌──────────────┐ 120 × 120 SV square (S on X, V on Y) for a fixed hue
 * │              │
 * └──────────────┘
 * ┌──────────────┐ 120 × 10 hue bar
 * └──────────────┘
 * ┌──────────────┐ 120 × 10 alpha bar (checkerboard + current color)
 * └──────────────┘
 * </pre>
 *
 * Clicking inside any region updates the backing {@link ColorSetting}. Click
 * outside the popup to dismiss. Only one popup can be open at a time; the
 * {@code Panel} owns it.
 */
public class ColorPickerPopup {

    public static final int WIDTH = 126;
    public static final int HEIGHT = 160;

    private static final int SV_SIZE = 120;
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 10;

    private final ColorSetting setting;
    private int x, y;

    // HSVA state (0..1 except hue which is 0..360)
    private float hue;
    private float sat;
    private float val;
    private float alpha;

    private int dragRegion = 0; // 0 none, 1 SV square, 2 hue bar, 3 alpha bar

    public ColorPickerPopup(ColorSetting setting, int x, int y) {
        this.setting = setting;
        this.x = x;
        this.y = y;
        unpack(setting.get());
    }

    public ColorSetting setting() { return setting; }
    public int x() { return x; }
    public int y() { return y; }

    private void unpack(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        this.alpha = a / 255f;
        float[] hsv = rgbToHsv(r / 255f, g / 255f, b / 255f);
        this.hue = hsv[0];
        this.sat = hsv[1];
        this.val = hsv[2];
    }

    private void commit() {
        int[] rgb = hsvToRgb(hue, sat, val);
        int a = Math.round(alpha * 255f);
        int packed = (a << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        setting.set(packed);
    }

    public void render(GuiGraphics gfx) {
        Font font = Minecraft.getInstance().font;

        // Backdrop
        gfx.fill(x - 2, y - 2, x + WIDTH + 2, y + HEIGHT + 2, 0xFF2A1A10);
        gfx.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF4A3020);

        int cx = x + 3;
        int cy = y + 3;

        // Title
        gfx.drawString(font, "\u00a76" + setting.name(), cx, cy, 0xFFFFFFFF, false);
        cy += 11;

        // SV square
        int svX = cx;
        int svY = cy;
        for (int py = 0; py < SV_SIZE; py++) {
            float v = 1.0f - (py / (float) SV_SIZE);
            for (int px = 0; px < SV_SIZE; px++) {
                float s = px / (float) SV_SIZE;
                int[] rgb = hsvToRgb(hue, s, v);
                int color = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
                gfx.fill(svX + px, svY + py, svX + px + 1, svY + py + 1, color);
            }
        }
        // Crosshair
        int ch = svX + Math.round(sat * SV_SIZE);
        int cv = svY + Math.round((1.0f - val) * SV_SIZE);
        gfx.fill(ch - 2, cv, ch + 3, cv + 1, 0xFFFFFFFF);
        gfx.fill(ch, cv - 2, ch + 1, cv + 3, 0xFFFFFFFF);
        gfx.fill(ch - 1, cv - 1, ch + 2, cv + 2, 0xFF000000);

        cy += SV_SIZE + 3;

        // Hue bar
        int hbY = cy;
        for (int px = 0; px < BAR_WIDTH; px++) {
            float h = (px / (float) BAR_WIDTH) * 360f;
            int[] rgb = hsvToRgb(h, 1f, 1f);
            int color = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            gfx.fill(cx + px, hbY, cx + px + 1, hbY + BAR_HEIGHT, color);
        }
        int huePx = cx + Math.round((hue / 360f) * BAR_WIDTH);
        gfx.fill(huePx - 1, hbY - 1, huePx + 2, hbY + BAR_HEIGHT + 1, 0xFFFFFFFF);
        gfx.fill(huePx, hbY, huePx + 1, hbY + BAR_HEIGHT, 0xFF000000);

        cy += BAR_HEIGHT + 3;

        // Alpha bar — checkerboard backdrop then gradient from transparent → color
        int abY = cy;
        int[] rgbNow = hsvToRgb(hue, sat, val);
        int colorNow = (rgbNow[0] << 16) | (rgbNow[1] << 8) | rgbNow[2];
        for (int px = 0; px < BAR_WIDTH; px++) {
            // Checker background
            for (int py = 0; py < BAR_HEIGHT; py++) {
                boolean dark = ((px / 3) + (py / 3)) % 2 == 0;
                gfx.fill(cx + px, abY + py, cx + px + 1, abY + py + 1,
                        dark ? 0xFF808080 : 0xFFC0C0C0);
            }
            int a = Math.round((px / (float) BAR_WIDTH) * 255f);
            int color = (a << 24) | colorNow;
            gfx.fill(cx + px, abY, cx + px + 1, abY + BAR_HEIGHT, color);
        }
        int alphaPx = cx + Math.round(alpha * BAR_WIDTH);
        gfx.fill(alphaPx - 1, abY - 1, alphaPx + 2, abY + BAR_HEIGHT + 1, 0xFFFFFFFF);
        gfx.fill(alphaPx, abY, alphaPx + 1, abY + BAR_HEIGHT, 0xFF000000);
    }

    /**
     * Handle a mouse click. Returns true if the click was consumed by the
     * popup (i.e. inside its bounds), false if it fell outside (caller
     * should dismiss the popup).
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return withinBounds(mx, my);
        if (!withinBounds(mx, my)) return false;

        int cx = x + 3;
        int cy = y + 3 + 11;

        // SV square
        if (mx >= cx && mx < cx + SV_SIZE && my >= cy && my < cy + SV_SIZE) {
            sat = (float) ((mx - cx) / SV_SIZE);
            val = 1.0f - (float) ((my - cy) / SV_SIZE);
            dragRegion = 1;
            commit();
            return true;
        }
        cy += SV_SIZE + 3;
        // Hue bar
        if (mx >= cx && mx < cx + BAR_WIDTH && my >= cy && my < cy + BAR_HEIGHT) {
            hue = (float) ((mx - cx) / BAR_WIDTH) * 360f;
            dragRegion = 2;
            commit();
            return true;
        }
        cy += BAR_HEIGHT + 3;
        // Alpha bar
        if (mx >= cx && mx < cx + BAR_WIDTH && my >= cy && my < cy + BAR_HEIGHT) {
            alpha = (float) ((mx - cx) / BAR_WIDTH);
            dragRegion = 3;
            commit();
            return true;
        }
        return true; // inside backdrop, swallowed but no effect
    }

    public void mouseDragged(double mx, double my) {
        if (dragRegion == 0) return;
        int cx = x + 3;
        int cy = y + 3 + 11;
        switch (dragRegion) {
            case 1 -> {
                sat = clamp01((float) ((mx - cx) / SV_SIZE));
                val = clamp01(1.0f - (float) ((my - cy) / SV_SIZE));
            }
            case 2 -> {
                hue = clamp((float) ((mx - cx) / BAR_WIDTH) * 360f, 0, 360);
            }
            case 3 -> {
                cy += SV_SIZE + 3 + BAR_HEIGHT + 3;
                alpha = clamp01((float) ((mx - cx) / BAR_WIDTH));
            }
        }
        commit();
    }

    public void mouseReleased() { dragRegion = 0; }

    public boolean withinBounds(double mx, double my) {
        return mx >= x - 2 && mx < x + WIDTH + 2 && my >= y - 2 && my < y + HEIGHT + 2;
    }

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }
    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- HSV conversion ----

    private static float[] rgbToHsv(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h = 0f;
        if (delta > 1e-6) {
            if (max == r) h = 60f * (((g - b) / delta) % 6f);
            else if (max == g) h = 60f * (((b - r) / delta) + 2f);
            else h = 60f * (((r - g) / delta) + 4f);
        }
        if (h < 0) h += 360f;
        float s = (max <= 1e-6) ? 0f : delta / max;
        return new float[] { h, s, max };
    }

    private static int[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float hp = (h % 360f) / 60f;
        float xc = c * (1 - Math.abs((hp % 2) - 1));
        float r1, g1, b1;
        if (hp < 1) { r1 = c; g1 = xc; b1 = 0; }
        else if (hp < 2) { r1 = xc; g1 = c; b1 = 0; }
        else if (hp < 3) { r1 = 0; g1 = c; b1 = xc; }
        else if (hp < 4) { r1 = 0; g1 = xc; b1 = c; }
        else if (hp < 5) { r1 = xc; g1 = 0; b1 = c; }
        else { r1 = c; g1 = 0; b1 = xc; }
        float m = v - c;
        return new int[] {
                Math.round((r1 + m) * 255f),
                Math.round((g1 + m) * 255f),
                Math.round((b1 + m) * 255f)
        };
    }
}
