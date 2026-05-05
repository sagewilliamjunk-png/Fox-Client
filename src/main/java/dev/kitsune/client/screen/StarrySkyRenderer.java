package dev.kitsune.client.screen;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Random;

/**
 * Procedural starry night sky for the Fox Client title screen.
 * Renders a dark navy-to-forest gradient with twinkling stars and a stylized moon.
 * No textures needed — pure fill/gradient calls.
 *
 * <p>Stars are seed-based so they stay consistent across frames but twinkle
 * via alpha modulation driven by the tick counter.
 */
public final class StarrySkyRenderer {
    private StarrySkyRenderer() {}

    private static final int STAR_COUNT = 300;
    private static final long SEED = 0xF0CAFEL;

    // Pre-generated star positions (fraction 0-1) and base brightness.
    // Everything that can be cached at class-init time is cached here so
    // the per-frame render loop does the bare minimum work (one Math.sin
    // per star plus a few mults).
    private static float[] starX;
    private static float[] starY;
    private static float[] starBrightness; // 0.3 - 1.0
    private static float[] starTwinkleSpeed; // how fast each star twinkles
    private static float[] starPhaseOffset; // pre-computed i*1.7 constant
    private static boolean[] starBright;    // true if brightness > 0.8 (draws cross)

    static {
        Random rng = new Random(SEED);
        starX = new float[STAR_COUNT];
        starY = new float[STAR_COUNT];
        starBrightness = new float[STAR_COUNT];
        starTwinkleSpeed = new float[STAR_COUNT];
        starPhaseOffset = new float[STAR_COUNT];
        starBright = new boolean[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = rng.nextFloat();
            starY[i] = rng.nextFloat();
            starBrightness[i] = 0.3f + rng.nextFloat() * 0.7f;
            starTwinkleSpeed[i] = 0.5f + rng.nextFloat() * 2.5f;
            starPhaseOffset[i] = i * 1.7f;
            starBright[i] = starBrightness[i] > 0.8f;
        }
    }

    /**
     * Render the full starry sky background. Call this BEFORE any other title screen
     * rendering so it replaces the panorama.
     *
     * @param gfx        the current GuiGraphics
     * @param width      screen width
     * @param height     screen height
     * @param tickCount  client tick counter for animation
     */
    public static void render(GuiGraphics gfx, int width, int height, float tickCount) {
        // === Sky gradient: dark navy top → deep forest bottom ===
        int topColor    = 0xFF0A0E1A; // very dark navy
        int midColor    = 0xFF0F1528; // midnight blue
        int bottomColor = 0xFF0C2218; // dark forest green

        // Top half: navy → midnight
        gfx.fillGradient(0, 0, width, height / 2, topColor, midColor);
        // Bottom half: midnight → forest
        gfx.fillGradient(0, height / 2, width, height, midColor, bottomColor);

        // === Stars ===
        // Hot-path: one Math.sin per star plus a handful of mults/casts.
        // Everything else is pulled from the cached arrays.
        final float twinkleBase = tickCount * 0.05f;
        for (int i = 0; i < STAR_COUNT; i++) {
            int sx = (int) (starX[i] * width);
            int sy = (int) (starY[i] * height);

            // Twinkle: sine wave modulating alpha
            float twinkle = (float) Math.sin(twinkleBase * starTwinkleSpeed[i] + starPhaseOffset[i]);
            float alpha = starBrightness[i] * (0.5f + 0.5f * twinkle);
            if (alpha < 0.05f) alpha = 0.05f; else if (alpha > 1.0f) alpha = 1.0f;

            int a = (int) (alpha * 255);
            int starColor = (a << 24) | 0xFFFFFF; // white star with variable alpha

            // Most stars are 1px, brighter ones get a 2px cross pattern
            gfx.fill(sx, sy, sx + 1, sy + 1, starColor);
            if (starBright[i]) {
                // Bright star: draw a small cross for a sparkle effect
                int dimA = (int) (alpha * 0.4f * 255);
                int dimColor = (dimA << 24) | 0xFFFFFF;
                gfx.fill(sx - 1, sy, sx, sy + 1, dimColor);
                gfx.fill(sx + 1, sy, sx + 2, sy + 1, dimColor);
                gfx.fill(sx, sy - 1, sx + 1, sy, dimColor);
                gfx.fill(sx, sy + 1, sx + 1, sy + 2, dimColor);
            }
        }

        // === Stylized crescent moon (upper-right area) ===
        drawMoon(gfx, (int) (width * 0.78f), (int) (height * 0.08f), tickCount);

        // === Subtle warm ember glow along the bottom horizon ===
        gfx.fillGradient(0, height - 40, width, height, 0x00000000, 0x30FF6020);

        // === Shooting star (occasional) ===
        drawShootingStar(gfx, width, height, tickCount);
    }

    /**
     * Draws a simple crescent moon using overlapping circles (filled rectangles
     * approximating a circle). The moon gently pulses in brightness.
     */
    private static void drawMoon(GuiGraphics gfx, int cx, int cy, float tickCount) {
        int radius = 12;
        float pulse = 0.85f + 0.15f * (float) Math.sin(tickCount * 0.02f);

        // Draw the main moon disc
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    // Carve out a crescent by skipping pixels in the upper-left
                    int offsetX = dx + 4;
                    int offsetY = dy - 3;
                    if (offsetX * offsetX + offsetY * offsetY <= (radius - 2) * (radius - 2)) {
                        continue; // this pixel is in the shadow bite
                    }

                    float dist = (float) Math.sqrt(dx * dx + dy * dy) / radius;
                    int alpha = (int) (pulse * (1.0f - dist * 0.3f) * 255);
                    alpha = Math.min(255, Math.max(0, alpha));

                    // Warm white-yellow moon color
                    int color = (alpha << 24) | 0xFFF0D0;
                    gfx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }

        // Subtle glow halo around the moon
        int glowRadius = radius + 8;
        for (int dy = -glowRadius; dy <= glowRadius; dy++) {
            for (int dx = -glowRadius; dx <= glowRadius; dx++) {
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > radius && dist <= glowRadius) {
                    float fade = 1.0f - (dist - radius) / (glowRadius - radius);
                    int alpha = (int) (pulse * fade * fade * 30);
                    if (alpha > 0) {
                        int color = (alpha << 24) | 0xFFE8C0;
                        gfx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                    }
                }
            }
        }
    }

    /**
     * Shooting-star variant dispatcher. Each cycle (~8 s) deterministically
     * picks one of several animations so the title screen stops feeling like
     * a single looped GIF:
     *
     * <ol>
     *   <li>Classic streak — the original animation</li>
     *   <li>Fast meteor — shorter, brighter, cool blue-white</li>
     *   <li>Slow comet — long dim tail with slight arc</li>
     *   <li>Twin meteors — two parallel streaks 8 px apart</li>
     *   <li>Meteor shower burst — 5 small streaks from a single vanishing point</li>
     *   <li>Aurora ripple — ~10% of cycles; no streak at all, a soft horizon wave</li>
     * </ol>
     */
    private static void drawShootingStar(GuiGraphics gfx, int width, int height, float tickCount) {
        final int CYCLE = 160;
        float cycle = tickCount % CYCLE;
        int cycleIdx = (int) (tickCount / CYCLE);
        Random rng = new Random(SEED + cycleIdx * 31L);

        // Pick one of the 5 streak variants which only render during the
        // first 30-ish ticks of the cycle. Aurora removed — it looked like
        // a giant green bar across the screen.
        if (cycle > 40) return; // no streak for the rest of the cycle
        int variant = rng.nextInt(5);
        switch (variant) {
            case 0 -> drawClassicStreak(gfx, width, height, cycle / 30f, rng, 0xFFFFFF, 40 + rng.nextInt(30));
            case 1 -> drawClassicStreak(gfx, width, height, cycle / 20f, rng, 0xCFE9FF, 25 + rng.nextInt(15));
            case 2 -> drawSlowComet(gfx, width, height, cycle / 40f, rng);
            case 3 -> drawTwinMeteors(gfx, width, height, cycle / 30f, rng);
            case 4 -> drawMeteorShower(gfx, width, height, cycle / 40f, rng);
        }
    }

    /** Original classic / fast-meteor code path, parameterized. */
    private static void drawClassicStreak(GuiGraphics gfx, int width, int height,
                                          float progress, Random rng, int rgb, int streakLen) {
        if (progress < 0f || progress > 1f) return;
        int startX = (int) (rng.nextFloat() * width * 0.6f) + (int) (width * 0.1f);
        int startY = (int) (rng.nextFloat() * height * 0.3f) + 10;
        float angle = 0.5f + rng.nextFloat() * 0.3f;
        int headX = startX + (int) (progress * streakLen * 3 * Math.cos(angle));
        int headY = startY + (int) (progress * streakLen * 3 * Math.sin(angle));

        int tailSegments = 15;
        for (int i = 0; i < tailSegments; i++) {
            float t = i / (float) tailSegments;
            float tailAlpha = (1.0f - t) * (1.0f - progress * 0.5f);
            int a = (int) (tailAlpha * 200);
            if (a <= 0) continue;
            int tx = headX - (int) (t * streakLen * Math.cos(angle));
            int ty = headY - (int) (t * streakLen * Math.sin(angle));
            gfx.fill(tx, ty, tx + 1, ty + 1, (a << 24) | rgb);
        }
        int headAlpha = (int) ((1.0f - progress * 0.3f) * 255);
        gfx.fill(headX, headY, headX + 2, headY + 2, (headAlpha << 24) | rgb);
    }

    /** Long dim tail, quadratic-bezier arc for a slight curve. */
    private static void drawSlowComet(GuiGraphics gfx, int width, int height, float progress, Random rng) {
        if (progress < 0f || progress > 1f) return;
        int startX = (int) (rng.nextFloat() * width * 0.5f) + (int) (width * 0.05f);
        int startY = (int) (rng.nextFloat() * height * 0.2f) + 5;
        int endX = startX + 180 + rng.nextInt(80);
        int endY = startY + 80 + rng.nextInt(40);
        int ctrlX = (startX + endX) / 2 + 30 - rng.nextInt(60);
        int ctrlY = startY - 25;

        int segments = 28;
        for (int i = 0; i < segments; i++) {
            float t = progress - i * 0.035f;
            if (t < 0f || t > 1f) continue;
            float mt = 1f - t;
            int px = (int) (mt * mt * startX + 2 * mt * t * ctrlX + t * t * endX);
            int py = (int) (mt * mt * startY + 2 * mt * t * ctrlY + t * t * endY);
            int a = (int) ((1f - i / (float) segments) * 140);
            if (a <= 0) continue;
            gfx.fill(px, py, px + 1, py + 1, (a << 24) | 0xCCCCFF);
        }
    }

    /** Two parallel streaks 8 px apart. */
    private static void drawTwinMeteors(GuiGraphics gfx, int width, int height, float progress, Random rng) {
        if (progress < 0f || progress > 1f) return;
        int startX = (int) (rng.nextFloat() * width * 0.6f) + (int) (width * 0.1f);
        int startY = (int) (rng.nextFloat() * height * 0.3f) + 10;
        float angle = 0.5f + rng.nextFloat() * 0.3f;
        int streakLen = 35 + rng.nextInt(15);

        // Perpendicular offset for the twin
        float perpX = -(float) Math.sin(angle) * 8f;
        float perpY =  (float) Math.cos(angle) * 8f;

        drawStreakLine(gfx, startX, startY, progress, angle, streakLen, 0xFFFFFF);
        drawStreakLine(gfx, startX + (int) perpX, startY + (int) perpY,
                       progress, angle, streakLen, 0xFFE0D0);
    }

    private static void drawStreakLine(GuiGraphics gfx, int startX, int startY,
                                       float progress, float angle, int streakLen, int rgb) {
        int headX = startX + (int) (progress * streakLen * 3 * Math.cos(angle));
        int headY = startY + (int) (progress * streakLen * 3 * Math.sin(angle));
        int tailSegments = 12;
        for (int i = 0; i < tailSegments; i++) {
            float t = i / (float) tailSegments;
            int a = (int) ((1f - t) * (1f - progress * 0.5f) * 180);
            if (a <= 0) continue;
            int tx = headX - (int) (t * streakLen * Math.cos(angle));
            int ty = headY - (int) (t * streakLen * Math.sin(angle));
            gfx.fill(tx, ty, tx + 1, ty + 1, (a << 24) | rgb);
        }
        int headAlpha = (int) ((1f - progress * 0.3f) * 255);
        gfx.fill(headX, headY, headX + 2, headY + 2, (headAlpha << 24) | rgb);
    }

    /** 5 small streaks emerging from a shared vanishing point. */
    private static void drawMeteorShower(GuiGraphics gfx, int width, int height, float progress, Random rng) {
        if (progress < 0f || progress > 1f) return;
        int vpX = (int) (rng.nextFloat() * width * 0.4f) + (int) (width * 0.1f);
        int vpY = (int) (rng.nextFloat() * height * 0.15f);
        for (int i = 0; i < 5; i++) {
            float subProgress = Math.max(0f, Math.min(1f, progress - i * 0.05f));
            float angle = 0.35f + i * 0.12f + rng.nextFloat() * 0.05f;
            drawStreakLine(gfx, vpX + i * 4, vpY + i * 2, subProgress, angle,
                           18 + rng.nextInt(10), 0xFFFFFF);
        }
    }

}
