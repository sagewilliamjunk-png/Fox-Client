package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Toggles the vanilla entity hitbox debug renderer (the same toggle F3+B uses).
 * Purely client-side and visual; no hitbox <em>expansion</em>.
 *
 * <p>In 1.21.11 the {@code renderHitBoxes} boolean moved out of
 * {@code EntityRenderDispatcher} and is now driven by
 * {@code Options.keyDebugShowHitboxes} (a toggle-style KeyMapping). We
 * synthetically hold / release that key to activate it.
 *
 * <p>Previously exposed "show eye line / show look vector / line color"
 * settings, but those required 3D world-space line rendering that doesn't
 * fit an "allowed mod" scope and were never implemented. Removed for v1.0.
 */
public class HitboxModule extends Module {

    public HitboxModule() {
        super("Hitboxes", "Toggle vanilla F3+B entity hitboxes", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        setHitBoxes(true);
    }

    @Override
    protected void onDisable() {
        setHitBoxes(false);
    }

    private static void setHitBoxes(boolean value) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return;
            KeyMapping key = mc.options.keyDebugShowHitboxes;
            if (key == null) return;
            // Toggle keys in 1.21.11 use setDown(boolean) to hold them active.
            key.setDown(value);
        } catch (Throwable t) {
            System.err.println("[Fox] HitboxModule toggle failed: " + t);
        }
    }
}
