package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.misc.DisconnectConfirmModule;
import dev.kitsune.client.screen.FoxSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts the disconnect button on the pause screen to show a confirmation
 * dialog when DisconnectConfirmModule is enabled.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    @Shadow private Button disconnectButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "createPauseMenu", at = @At("TAIL"))
    private void kitsune$addFoxSettingsButton(CallbackInfo ci) {
        try {
            // Top-right pill: opens Fox Client settings without leaving the world.
            // Sized small so it doesn't fight the vanilla pause buttons.
            this.addRenderableWidget(Button.builder(
                    Component.literal("\u00a76Fox Settings"),
                    btn -> Minecraft.getInstance().setScreen(new FoxSettingsScreen((PauseScreen) (Object) this))
            ).bounds(this.width - 104, 6, 98, 18).build());
        } catch (Throwable t) {
            dev.kitsune.client.KitsuneClient.LOGGER.warn("[Fox] PauseScreenMixin add settings button failed: {}", t.toString());
        }
    }

    @Inject(method = "createPauseMenu", at = @At("TAIL"))
    private void kitsune$wrapDisconnect(CallbackInfo ci) {
        try {
            DisconnectConfirmModule module = ModuleManager.getModule(DisconnectConfirmModule.class);
            if (module == null || !module.isEnabled()) return;
            if (disconnectButton == null) return;

            // Get the original button's bounds
            int bx = disconnectButton.getX();
            int by = disconnectButton.getY();
            int bw = disconnectButton.getWidth();
            int bh = disconnectButton.getHeight();
            Component msg = disconnectButton.getMessage();

            // Remove the original button and add a replacement that shows confirmation
            this.removeWidget(disconnectButton);
            Button replacement = Button.builder(msg, btn -> {
                Minecraft mc = Minecraft.getInstance();
                mc.setScreen(new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                boolean sp = mc.isLocalServer();
                                mc.clearClientLevel(new GenericMessageScreen(
                                        Component.translatable(sp ? "menu.savingLevel" : "multiplayer.disconnect.quitting")));
                            } else {
                                mc.setScreen((PauseScreen) (Object) this);
                            }
                        },
                        Component.literal("\u00a76Fox Client"),
                        Component.literal("Are you sure you want to disconnect?")
                ));
            }).bounds(bx, by, bw, bh).build();

            this.disconnectButton = replacement;
            this.addRenderableWidget(replacement);
        } catch (Throwable t) {
            dev.kitsune.client.KitsuneClient.LOGGER.warn("[Fox] PauseScreenMixin failed: {}", t.toString());
        }
    }
}
