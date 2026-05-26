package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;

import java.util.List;

/**
 * Enhanced block selection outline.
 * Replaces the vanilla thin line with a customizable coloured outline and fill.
 *
 * <p>The actual rendering override happens via LevelRendererMixin — this module
 * holds settings and toggle state that the mixin reads each frame.
 */
public class BlockOverlayModule extends Module {

    private final BooleanSetting showFill     = addSetting(new BooleanSetting("Show Fill",       true));
    private final BooleanSetting thickOutline = addSetting(new BooleanSetting("Thick Outline",   true));
    private final BooleanSetting rainbow      = addSetting(new BooleanSetting("Rainbow Mode",     false));
    private final BooleanSetting hideVanilla  = addSetting(new BooleanSetting("Hide Vanilla Line",true));
    private final ColorSetting   outlineColor = addSetting(new ColorSetting("Outline Color", 0xFFFFFFFF));
    private final ColorSetting   fillColor    = addSetting(new ColorSetting("Fill Color",    0x30FFFFFF));
    private final SliderSetting  lineWidth    = addSetting(new SliderSetting("Line Width", 2.0, 0.5, 5.0, 0.5));
    // Style: Solid is the default vanilla-ish outline. Dashed alternates
    // visible/transparent segments. Glow draws a thicker, lower-alpha
    // outline on top of the regular one so it appears to bloom. The mixin
    // reads getStyle() / getOutlineColor() / getLineWidth() each frame; the
    // dashed/glow distinction is just an additional pass over the same
    // edge geometry — no shader work, no GL state leak.
    private final ModeSetting    style        = addSetting(new ModeSetting("Style", "Solid",
            List.of("Solid", "Dashed", "Glow")));
    /** Dashed mode: visible segment length in 1/16th-block units. */
    private final SliderSetting  dashLength   = addSetting(new SliderSetting("Dash length (1/16 block)", 4, 1, 16, 1));
    /** Glow mode: extra outline thickness on top of the base line. */
    private final SliderSetting  glowSpread   = addSetting(new SliderSetting("Glow spread (px)", 3, 1, 8, 1));

    public BlockOverlayModule() {
        super("Block Overlay", "Custom block selection outline with fill and colour options", Category.RENDER);
    }

    // ---- Accessors for mixin ----

    public boolean showFill()      { return showFill.get(); }
    public boolean thickOutline()  { return thickOutline.get(); }
    public boolean isRainbow()     { return rainbow.get(); }
    public boolean hideVanilla()   { return hideVanilla.get(); }
    public int     getOutlineColor() {
        if (rainbow.get()) {
            // Shift hue over time for rainbow effect
            float hue = (System.currentTimeMillis() % 4000) / 4000f;
            return hsvToArgb(hue, 1f, 1f, 1f);
        }
        return outlineColor.get();
    }
    public int    getFillColor()   { return fillColor.get(); }
    public double getLineWidth()   { return lineWidth.get(); }
    public String getStyle()       { return style.get(); }
    /** True when the current segment fraction (0..1) within a block edge should
     *  draw a visible dash. Mixin calls this once per edge sample. */
    public boolean isDashOn(double edgeFraction) {
        if (!"Dashed".equals(style.get())) return true;
        int dashLen = Math.max(1, dashLength.get().intValue()); // in 1/16 units
        int totalUnits = 16;
        int unit = (int) Math.floor((edgeFraction * totalUnits) % (dashLen * 2));
        return unit < dashLen;
    }
    /** Glow modifier — returns how many extra-pixel outline passes the mixin
     *  should draw. The mixin runs this many additional outlines with
     *  progressively lower alpha to fake a bloom without needing a shader. */
    public int getGlowPasses() {
        if (!"Glow".equals(style.get())) return 0;
        return glowSpread.get().intValue();
    }

    // ---- helpers ----

    private static int hsvToArgb(float h, float s, float v, float a) {
        int i = (int)(h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int)(a * 255) << 24) | ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(b * 255);
    }
}
