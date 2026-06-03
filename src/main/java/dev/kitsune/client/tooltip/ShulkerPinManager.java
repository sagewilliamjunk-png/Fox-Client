package dev.kitsune.client.tooltip;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.component.ItemContainerContents;
import org.lwjgl.glfw.GLFW;

/**
 * State machine for the "Alt+Shift sticky" shulker preview.
 *
 * <p>The flow is:
 * <ol>
 *   <li>While the user hovers any shulker box, the tooltip-image mixin sets
 *       {@code recentHovered} to that shulker's contents.</li>
 *   <li>When Alt+Shift is held <em>and</em> {@code recentHovered} is set
 *       <em>and</em> we haven't pinned yet, the screen-render mixin
 *       captures the current GUI-scaled mouse position into
 *       {@code pinnedX}/{@code pinnedY} and treats that as the locked
 *       position the grid will render at for every subsequent frame.</li>
 *   <li>When Alt+Shift releases, everything clears.</li>
 * </ol>
 *
 * <p>All access is via {@code volatile} fields — the mixin reads/writes from
 * the render thread; the tooltip mixin writes from the tooltip-build thread,
 * which for vanilla MC is the same thread but the volatile is cheap and
 * defends against future changes.
 */
public final class ShulkerPinManager {
    private ShulkerPinManager() {}

    /** Set by the tooltip mixin every time a shulker grid is about to draw. */
    private static volatile ItemContainerContents recentHovered;

    /** Non-null = grid is currently pinned at (pinnedX, pinnedY). */
    private static volatile ItemContainerContents pinned;
    private static volatile int pinnedX = 0, pinnedY = 0;

    /** Called by {@link dev.kitsune.client.mixin.ItemTooltipImageMixin} whenever
     *  it renders a shulker grid (Shift is held and the cursor is over one). */
    public static void touch(ItemContainerContents contents) {
        recentHovered = contents;
    }

    /** Called by {@link dev.kitsune.client.mixin.ScreenStickyShulkerMixin} on
     *  the first frame Alt+Shift is held to lock the grid in place. */
    public static void pinAt(int x, int y) {
        if (recentHovered == null) return;
        pinned = recentHovered;
        pinnedX = x;
        pinnedY = y;
    }

    public static boolean isAltShiftHeld() {
        try {
            Window w = Minecraft.getInstance().getWindow();
            boolean alt   = InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_ALT)
                         || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_ALT);
            boolean shift = InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_SHIFT)
                         || InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_SHIFT);
            return alt && shift;
        } catch (Throwable t) { return false; }
    }

    public static boolean hasPinned()        { return pinned != null; }
    public static boolean hasRecent()        { return recentHovered != null; }
    public static ItemContainerContents pinned() { return pinned; }
    public static int x() { return pinnedX; }
    public static int y() { return pinnedY; }

    /** Forget everything — pinned grid and most-recent hover. Called when
     *  Alt+Shift is released so the next capture starts fresh. */
    public static void clear() {
        pinned = null;
        recentHovered = null;
    }
}
