package dev.kitsune.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Safe rendering utilities for Fox Client. Wraps render calls in null checks
 * and try/catch so one bad module can't crash the entire client.
 *
 * <p>Use {@link #safeRender(GuiGraphicsExtractor, RenderAction)} in HUD modules to protect
 * against null player, null world, dimension switches, loading screens, etc.
 */
public final class RenderUtils {
    private RenderUtils() {}

    @FunctionalInterface
    public interface RenderAction {
        void render(GuiGraphicsExtractor gfx, Minecraft mc);
    }

    /**
     * Safely execute a render action. If the player or world is null, or
     * the action throws, it's silently caught. Never crashes the game.
     */
    public static void safeRender(GuiGraphicsExtractor gfx, RenderAction action) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) return;
            if (mc.options != null && mc.options.hideGui) return;
            action.render(gfx, mc);
        } catch (Throwable t) {
            // Log but never crash
            System.err.println("[Fox] Render error: " + t.getMessage());
        }
    }

    /**
     * Safely execute a render action that only needs the screen dimensions
     * (works even without a player/world — e.g., on title screen).
     */
    public static void safeRenderAlways(GuiGraphicsExtractor gfx, RenderAction action) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            action.render(gfx, mc);
        } catch (Throwable t) {
            System.err.println("[Fox] Render error: " + t.getMessage());
        }
    }

    /**
     * Draw a horizontal color bar (like a progress/durability bar).
     */
    public static void drawBar(GuiGraphicsExtractor gfx, int x, int y, int width, int height,
                                float progress, int fillColor, int bgColor) {
        gfx.fill(x, y, x + width, y + height, bgColor);
        int fillW = (int) (width * Math.max(0, Math.min(1, progress)));
        if (fillW > 0) {
            gfx.fill(x, y, x + fillW, y + height, fillColor);
        }
    }

    /**
     * Blend between two colors based on a factor (0.0 = colorA, 1.0 = colorB).
     */
    public static int lerpColor(int colorA, int colorB, float t) {
        t = Math.max(0, Math.min(1, t));
        int aA = (colorA >> 24) & 0xFF, rA = (colorA >> 16) & 0xFF, gA = (colorA >> 8) & 0xFF, bA = colorA & 0xFF;
        int aB = (colorB >> 24) & 0xFF, rB = (colorB >> 16) & 0xFF, gB = (colorB >> 8) & 0xFF, bB = colorB & 0xFF;
        int a = (int) (aA + (aB - aA) * t);
        int r = (int) (rA + (rB - rA) * t);
        int g = (int) (gA + (gB - gA) * t);
        int b = (int) (bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
