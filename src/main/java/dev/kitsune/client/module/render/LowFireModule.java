package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleManager;

/**
 * Suppresses the fire overlay that covers the screen when the player is burning.
 * The player still takes fire damage and will see fire particles on their body in
 * third-person — this only removes the visual obstruction in first-person view.
 *
 * <p>Implemented via {@link dev.kitsune.client.mixin.ScreenEffectRendererMixin}
 * which cancels {@code ScreenEffectRenderer.renderFireOverlay} at HEAD.
 */
public class LowFireModule extends Module {

    public LowFireModule() {
        super("Low Fire", "Hides the fire screen overlay when burning.", Category.RENDER);
    }

    /** Called by the mixin — avoids a {@link ModuleManager#getModule} lookup each frame. */
    public static boolean isActive() {
        LowFireModule mod = ModuleManager.getModule(LowFireModule.class);
        return mod != null && mod.isEffectivelyEnabled();
    }
}
