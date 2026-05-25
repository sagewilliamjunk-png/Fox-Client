package dev.kitsune.client.module.misc;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;

/**
 * Adds a confirmation dialog before disconnecting from a server. Prevents
 * accidental disconnects when misclicking the pause menu.
 *
 * <p>The interception itself lives in {@code PauseScreenMixin#kitsune$wrapDisconnect}
 * — that mixin replaces the vanilla Disconnect button with one that opens a
 * {@code ConfirmScreen} when this module is enabled, falling back to vanilla
 * behaviour when disabled. No work happens in this class beyond holding the
 * enabled flag.
 */
public class DisconnectConfirmModule extends Module {

    public DisconnectConfirmModule() {
        super("Disconnect Confirm", "Confirm before disconnecting", Category.MISC);
    }
}
