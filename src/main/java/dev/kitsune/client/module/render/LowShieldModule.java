package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleManager;

/**
 * Keeps the off-hand shield in its lowered (un-raised) visual position when
 * blocking, so it doesn't obscure the left half of the screen.
 *
 * <p>The player still blocks normally — this is a client-side render tweak only.
 * Implemented via {@link dev.kitsune.client.mixin.ItemInHandRendererMixin} which
 * forces the off-hand {@code equippedProgress} to {@code 0} when the player is
 * actively using a shield, keeping the item arm in its resting pose.
 */
public class LowShieldModule extends Module {

    public LowShieldModule() {
        super("Low Shield", "Keeps the shield lowered visually while blocking.", Category.RENDER);
    }

    /** Called by the mixin — avoids a {@link ModuleManager#getModule} lookup each frame. */
    public static boolean isActive() {
        LowShieldModule mod = ModuleManager.getModule(LowShieldModule.class);
        return mod != null && mod.isEffectivelyEnabled();
    }
}
