package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.server.PendingJoin;
import dev.kitsune.client.server.RestartConfirmScreen;
import dev.kitsune.client.server.ServerRuleStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Intercepts the very first frame of {@code ConnectScreen.startConnecting} to
 * evaluate the user's {@link ServerRuleStore server rules} against the target
 * server. If any matching rule requires disabling currently-loaded mods, we
 * cancel the connection and route the user through the {@link RestartConfirmScreen}
 * instead.
 *
 * If no DISABLE rule matches but a WARN rule does, we let the connection proceed
 * and the warning surfaces via {@link dev.kitsune.client.server.ServerJoinHandler}.
 *
 * If the user has disabled {@code autoCheckServerRules} in {@link KitsuneConfig},
 * the mixin is a no-op.
 *
 * Note: 1.21.x's {@code ConnectScreen.startConnecting} signature has changed
 * across patch versions. The {@code remote=*} target on the {@link Inject} is
 * deliberately permissive — if the bytecode shape shifts in 1.21.11, this
 * mixin needs an update.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    // 1.21.11 uses the 6-arg form: (Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true, require = 0)
    private static void kitsune$checkRules(
            Screen screen,
            Minecraft minecraft,
            ServerAddress address,
            ServerData serverData,
            boolean quickPlay,
            TransferState transferState,
            CallbackInfo ci) {
        try {
            if (!KitsuneConfig.get().autoCheckServerRules) return;
            String addrStr = address != null ? (address.getHost() + ":" + address.getPort()) : null;

            // Auto-switch profile if a binding exists for this server
            String boundProfile = KitsuneConfig.get().getProfileForServer(addrStr);
            String activeName = ProfileManager.getActiveName();
            if (boundProfile != null && ProfileManager.exists(boundProfile)
                    && !boundProfile.equalsIgnoreCase(activeName == null ? "" : activeName)) {
                ProfileManager.switchTo(boundProfile, minecraft);
                FeatureRegistry.syncEnabledStates();
                dev.kitsune.client.hud.NotificationManager.show(
                        "Profile auto-switched to " + boundProfile,
                        dev.kitsune.client.hud.NotificationManager.Type.INFO);
            }

            Set<String> needDisable = ServerRuleStore.modsToDisableFor(addrStr);
            if (needDisable.isEmpty()) {
                // Apply runtime feature overrides (won't need a restart)
                ServerRuleStore.applyFeatureOverridesFor(addrStr);
                return;
            }
            // Cancel and redirect to the restart prompt
            PendingJoin.set(addrStr);
            minecraft.setScreen(new RestartConfirmScreen(screen, addrStr, needDisable));
            ci.cancel();
        } catch (Throwable t) {
            // Never crash the connect path on a Fox Client error — just log
            System.err.println("[Fox] ConnectScreenMixin failed: " + t);
        }
    }
}
