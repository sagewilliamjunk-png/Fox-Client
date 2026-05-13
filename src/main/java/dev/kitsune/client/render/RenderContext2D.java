package dev.kitsune.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thin convenience wrapper over {@link GuiGraphicsExtractor} that prevents three classes
 * of bug we keep tripping over in 2D HUD/GUI code:
 *
 * <ol>
 *   <li><b>Unbalanced scissor stacks.</b> Every call to {@code enableScissor}
 *       must be paired with {@code disableScissor}. {@link #pushScissor} returns
 *       a {@link Region} which auto-disables in {@link Region#close()} so a
 *       try-with-resources block can't leak the scissor across an exception
 *       boundary.</li>
 *   <li><b>Pose-stack drift.</b> {@link #pushPose} returns a {@link Pose} that
 *       balances pop on close, even if the body throws.</li>
 *   <li><b>Wrong colour-channel order at call sites.</b> Helpers like
 *       {@link #fillARGB} and {@link #withAlpha} make ARGB the obvious default
 *       so callers don't have to remember the bit layout each time.</li>
 * </ol>
 *
 * <p>Existing modules continue to use raw {@code GuiGraphicsExtractor} — this is a
 * convenience for new code, not a forced migration.
 */
public final class RenderContext2D {

    private final GuiGraphicsExtractor gfx;
    private final Font font;
    private final Deque<Boolean> scissorStack = new ArrayDeque<>();
    private int poseDepth = 0;

    public RenderContext2D(GuiGraphicsExtractor gfx) {
        this.gfx = gfx;
        this.font = Minecraft.getInstance().font;
    }

    public GuiGraphicsExtractor raw() { return gfx; }
    public Font font() { return font; }

    // ---- colour helpers -------------------------------------------------

    /** Pack 8-bit channels into a single ARGB int. */
    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** Replace the alpha channel of an existing colour. {@code alpha01} is 0–1. */
    public static int withAlpha(int rgbColor, float alpha01) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha01)) * 255f);
        return (a << 24) | (rgbColor & 0x00FFFFFF);
    }

    // ---- fills ---------------------------------------------------------

    public void fillARGB(int x1, int y1, int x2, int y2, int argb) {
        gfx.fill(x1, y1, x2, y2, argb);
    }

    /** Outlined rectangle (1px border, no fill). */
    public void outline(int x1, int y1, int x2, int y2, int argb) {
        gfx.fill(x1,         y1,         x2,     y1 + 1, argb);
        gfx.fill(x1,         y2 - 1,     x2,     y2,     argb);
        gfx.fill(x1,         y1,         x1 + 1, y2,     argb);
        gfx.fill(x2 - 1,     y1,         x2,     y2,     argb);
    }

    /** Fill the screen behind a rectangle to dim it (alpha ramp). */
    public void dim(int x1, int y1, int x2, int y2, float alpha01) {
        gfx.fill(x1, y1, x2, y2, withAlpha(0, alpha01));
    }

    // ---- text ----------------------------------------------------------

    public void text(String s, int x, int y, int argb) {
        gfx.text(font, s, x, y, argb);
    }

    public void textShadow(String s, int x, int y, int argb) {
        gfx.text(font, s, x, y, argb);
    }

    public int textWidth(String s) { return font.width(s); }
    public int lineHeight() { return font.lineHeight; }

    // ---- scissor (clip) ------------------------------------------------

    /**
     * Begin a clip region. Use in try-with-resources:
     * <pre>{@code try (var r = ctx.pushScissor(x, y, x+w, y+h)) { ... } }</pre>
     */
    public Region pushScissor(int x1, int y1, int x2, int y2) {
        gfx.enableScissor(x1, y1, x2, y2);
        scissorStack.push(Boolean.TRUE);
        return new Region(this);
    }

    private void popScissor() {
        if (scissorStack.isEmpty()) return;
        scissorStack.pop();
        gfx.disableScissor();
    }

    /** Returned by {@link #pushScissor}; closing pops the matching scissor. */
    public static final class Region implements AutoCloseable {
        private final RenderContext2D owner;
        private boolean closed = false;
        Region(RenderContext2D owner) { this.owner = owner; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            owner.popScissor();
        }
    }

    // ---- pose stack ----------------------------------------------------

    /**
     * Push the GuiGraphicsExtractor matrix stack. Use in try-with-resources to guarantee
     * a balanced pop on every code path including exceptions.
     */
    public Pose pushPose() {
        gfx.pose().pushMatrix();
        poseDepth++;
        return new Pose(this);
    }

    private void popPose() {
        if (poseDepth <= 0) return;
        gfx.pose().popMatrix();
        poseDepth--;
    }

    /** Returned by {@link #pushPose}; closing pops the matching matrix. */
    public static final class Pose implements AutoCloseable {
        private final RenderContext2D owner;
        private boolean closed = false;
        Pose(RenderContext2D owner) { this.owner = owner; }
        public Pose translate(float x, float y) {
            owner.gfx.pose().translate(x, y);
            return this;
        }
        public Pose scale(float sx, float sy) {
            owner.gfx.pose().scale(sx, sy);
            return this;
        }
        public Pose scale(float s) { return scale(s, s); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            owner.popPose();
        }
    }

    /**
     * Sanity-check that all push/pop calls are balanced before the context is
     * dropped. Call from a finally block in screen render() if you want a
     * runtime safety net during development.
     */
    public boolean isBalanced() {
        return scissorStack.isEmpty() && poseDepth == 0;
    }
}
