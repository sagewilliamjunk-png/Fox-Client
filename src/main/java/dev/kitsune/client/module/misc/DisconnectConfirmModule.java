package dev.kitsune.client.module.misc;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;

/**
 * Adds a confirmation dialog before disconnecting from a server.
 * Prevents accidental disconnects when clicking too fast.
 *
 * <p>The actual interception happens via GameMenuScreenMixin (added in a future phase).
 * For now, this module serves as the toggle — when enabled, the mixin will check
 * {@code ModuleManager.getModule(DisconnectConfirmModule.class).isEnabled()}.
 */
public class DisconnectConfirmModule extends Module {

    public DisconnectConfirmModule() {
        super("Disconnect Confirm", "Confirm before disconnecting", Category.MISC);
    }
}
