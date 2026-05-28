package dev.kitsune.client.module.misc;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;

/**
 * Hotbar scroll lock — when enabled, the mouse wheel no longer changes
 * the selected hotbar slot. Useful for PvP players who want to use the
 * scroll wheel for zoom / camera without accidentally swapping weapons.
 *
 * <p>The actual interception is in {@link dev.kitsune.client.mixin.MouseHandlerMixin}:
 * a {@code @Redirect} on the {@code Inventory.setSelectedSlot(int)} call inside
 * {@code MouseHandler.onScroll} swallows the slot change when
 * {@link #shouldIntercept()} returns true. That call only fires in the in-world
 * scroll branch, so GUI scrolling and creative fly-speed scroll are unaffected.
 *
 * <p>This is just the toggle + a "Allow with modifier" setting that lets
 * the user re-enable scrolling while holding Ctrl (handy override without
 * needing to disable the module).
 */
public class HotbarScrollLockModule extends Module {

    private final BooleanSetting allowWithCtrl = addSetting(new BooleanSetting("Allow with Ctrl", true));

    private static volatile HotbarScrollLockModule INSTANCE;

    public HotbarScrollLockModule() {
        super("Hotbar Scroll Lock",
              "Prevents the mouse wheel from changing your hotbar slot. Hold Ctrl to override (toggleable).",
              Category.MISC);
        INSTANCE = this;
    }

    /** True when scrolling should be intercepted. Mixin queries this. */
    public static boolean shouldIntercept() {
        if (INSTANCE == null || !INSTANCE.isEffectivelyEnabled()) return false;
        if (INSTANCE.allowWithCtrl.get()) {
            try {
                com.mojang.blaze3d.platform.Window window =
                        net.minecraft.client.Minecraft.getInstance().getWindow();
                boolean ctrl = com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                            org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
                            || com.mojang.blaze3d.platform.InputConstants.isKeyDown(window,
                            org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL);
                if (ctrl) return false;
            } catch (Throwable ignored) { /* fall through to intercept */ }
        }
        return true;
    }
}
