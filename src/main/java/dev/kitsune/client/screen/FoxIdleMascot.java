package dev.kitsune.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Idle fox mascot for the main menu corner. Blits the user-provided
 * {@code assets/kitsune/textures/gui/fox_head.png} with a subtle bob
 * animation and pulsing fireflies orbiting behind it.
 *
 * <p>Previously this class drew a procedural fox from hand-coded pixel
 * coordinates via {@link GuiGraphicsExtractor#fill}. Now that the user supplied
 * real pixel art, we use the texture directly; the procedural code is
 * removed to keep the file small.
 */
public final class FoxIdleMascot {

    /** Mod-local path to the fox head PNG. 16×16 px by default. */
    private static final Identifier FOX_HEAD =
            Identifier.fromNamespaceAndPath("kitsune", "textures/gui/fox_head.png");

    /** On-screen draw size. The source PNG is upscaled with nearest-neighbor. */
    private static final int DRAW_SIZE = 32;

    private FoxIdleMascot() {}

    /**
     * Draw the mascot with its bounding-box top-left at ({@code anchorX},
     * {@code anchorY}). {@code timeSec} drives the bob + firefly phases.
     */
    public static void draw(GuiGraphicsExtractor gfx, int anchorX, int anchorY, double timeSec) {
        // Fireflies first so the fox draws on top of them.
        drawFireflies(gfx, anchorX, anchorY, timeSec);

        // Subtle 1-pixel vertical bob so the mascot feels alive.
        int bob = (int) Math.round(Math.sin(timeSec * 1.8) * 1.0);

        // Signature: blit(pipeline, identifier, x, y, u, v, w, h, texW, texH)
        gfx.blit(
                RenderPipelines.GUI_TEXTURED,
                FOX_HEAD,
                anchorX, anchorY + bob,
                0f, 0f,
                DRAW_SIZE, DRAW_SIZE,
                DRAW_SIZE, DRAW_SIZE
        );
    }

    /** 6 fireflies orbit the mascot with pulsing alpha and a warm glow. */
    private static void drawFireflies(GuiGraphicsExtractor gfx, int anchorX, int anchorY, double timeSec) {
        int cx = anchorX + DRAW_SIZE / 2;
        int cy = anchorY + DRAW_SIZE / 2;
        for (int i = 0; i < 6; i++) {
            double phase = timeSec * (0.55 + i * 0.13) + i * 1.27;
            double rx = 34 + Math.sin(phase * 0.8) * 8;   // wider orbit so they're visible past the fox
            double ry = 18 + Math.cos(phase * 0.6) * 5;
            int fx = cx + (int) Math.round(Math.cos(phase) * rx);
            int fy = cy + (int) Math.round(Math.sin(phase) * ry);

            // pulse ∈ [0.35, 1.0] — never fully dark so they're always legible
            float pulse = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(timeSec * 2.4 + i));
            int coreA = (int) (pulse * 255f);
            int glowA = (int) (pulse * 140f);   // warm halo ~55 % of core
            int dimA  = (int) (pulse * 60f);    // soft outer ring

            int coreCol = (coreA << 24) | 0x00FFE088;  // warm amber core
            int glowCol = (glowA << 24) | 0x00FFB040;  // orange glow
            int dimCol  = (dimA  << 24) | 0x00FF8020;  // outer tint

            // Outer soft ring (3×3)
            gfx.fill(fx - 2, fy - 1, fx + 3, fy + 2, dimCol);
            gfx.fill(fx - 1, fy - 2, fx + 2, fy + 3, dimCol);
            // Warm halo (cross)
            gfx.fill(fx - 1, fy,     fx + 2, fy + 1, glowCol);
            gfx.fill(fx,     fy - 1, fx + 1, fy + 2, glowCol);
            // Bright core
            gfx.fill(fx, fy, fx + 1, fy + 1, coreCol);
        }
    }
}
