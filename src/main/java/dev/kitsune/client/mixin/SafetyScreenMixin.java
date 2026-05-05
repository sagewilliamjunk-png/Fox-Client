package dev.kitsune.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skip the "Caution: Third-Party Online Play" warning screen. The user + their
 * friends know what multiplayer is and don't need a disclaimer between them
 * and the server list every time.
 *
 * <p>We also persist vanilla's {@code skipMultiplayerWarning} flag so vanilla
 * stops constructing the screen at the source (saves a frame), but the
 * redirect here is the authoritative skip — even if the flag is cleared by
 * some other mod or a settings reset, this mixin still dismisses the screen.
 *
 * <p>Target is {@code addFooterButtons()} because {@code init()} is declared
 * on the parent {@code WarningScreen} (not SafetyScreen) and mixin remap
 * fails for an inherited method. Vanilla's call order is
 * {@code Screen.init(mc, w, h)} → assigns {@code minecraft} field →
 * {@code WarningScreen.init()} → {@code addFooterButtons()}. By the time our
 * hook fires, {@code minecraft} is populated and {@code setScreen} is safe
 * to call. We don't even return a real Layout — {@code setScreen} replaces
 * this screen instance before the null-ish return value gets used.
 */
@Mixin(SafetyScreen.class)
public abstract class SafetyScreenMixin {

    @Shadow @Final private Screen previous;

    @Inject(method = "addFooterButtons", at = @At("HEAD"), cancellable = true)
    private void kitsune$autoProceed(CallbackInfoReturnable<Layout> cir) {
        Minecraft mc = Minecraft.getInstance();
        // Persist the flag so vanilla's next check short-circuits before
        // even constructing this screen. Matches what the vanilla "Do not
        // show this screen again" checkbox does on Proceed.
        if (!mc.options.skipMultiplayerWarning) {
            mc.options.skipMultiplayerWarning = true;
            mc.options.save();
        }
        mc.setScreen(new JoinMultiplayerScreen(this.previous));
        // Return an empty horizontal layout — harmless filler since the
        // screen is being replaced this same tick.
        cir.setReturnValue(net.minecraft.client.gui.layouts.LinearLayout.horizontal());
    }
}
