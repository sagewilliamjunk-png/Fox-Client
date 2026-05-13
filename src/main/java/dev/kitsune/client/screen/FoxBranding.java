package dev.kitsune.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Pure-primitive Fox Client visual identity. Draws the wordmark, fox glyph,
 * and warm vignette using only {@link GuiGraphicsExtractor#fill}/{@code drawString} —
 * no texture assets required, so the client looks distinctly Fox out of the
 * box without shipping PNGs.
 *
 * The fox glyph is a stylized geometric face: triangular ears, diamond head,
 * cream cheek tufts, two eyes, dark snout. It's recognizable at sizes 16-64px.
 */
public final class FoxBranding {
    private FoxBranding() {}

    /** Heavy darkening + warm tint so the brand area pops on any panorama frame. */
    public static void drawVignette(GuiGraphicsExtractor gfx, int w, int h) {
        // Solid dark wash over everything (panorama still shows through faintly)
        gfx.fill(0, 0, w, h, 0xB0000000);
        // Warm ember band along the top
        gfx.fillGradient(0, 0, w, 60, 0x80FF8030, 0x00000000);
        // Forest band along the bottom
        gfx.fillGradient(0, h - 60, w, h, 0x00000000, 0x60294834);
        // Side fade so the center brand pops
        gfx.fillGradient(0, 0, 80, h, 0xC0000000, 0x00000000);
        gfx.fillGradient(w - 80, 0, w, h, 0x00000000, 0xC0000000);
    }

    /**
     * "FOX CLIENT" big wordmark, centered horizontally on cx with top edge at y.
     * Uses FoxTheme.FOX_ORANGE glyphs with a 1px dark-bark drop-shadow so it
     * reads cleanly on both the dim panorama and the cream card behind it.
     */
    public static void drawWordmark(GuiGraphicsExtractor gfx, Font font, int cx, int y) {
        // 3x scaled vanilla font, positioned so y is the top of the text
        float scale = 3.0f;
        Component title = Component.literal("FOX CLIENT");
        int tw = font.width(title);
        gfx.pose().pushMatrix();
        gfx.pose().translate(cx, y);
        gfx.pose().scale(scale, scale);
        // Bark shadow (manual 1px offset — the vanilla drop shadow is too dark/blue on cream)
        gfx.text(font, title, -tw / 2 + 1, 1, FoxTheme.BARK);
        gfx.text(font, title, -tw / 2,     0, FoxTheme.FOX_ORANGE);
        gfx.pose().popMatrix();
    }

    /** User-provided 16×16 fox head PNG. Replaces the previous procedural glyph. */
    private static final Identifier FOX_HEAD =
            Identifier.fromNamespaceAndPath("kitsune", "textures/gui/fox_head.png");

    /**
     * Draws the Fox Client mascot at (x,y), upscaled so the whole sprite is
     * {@code size} px wide. Uses nearest-neighbor via {@link GuiGraphicsExtractor#blit}.
     */
    public static void drawFoxGlyph(GuiGraphicsExtractor gfx, int x, int y, int size) {
        gfx.blit(
                RenderPipelines.GUI_TEXTURED,
                FOX_HEAD,
                x, y,
                0f, 0f,
                size, size,
                size, size
        );
    }
}
