package dev.kitsune.client.mixin;

import dev.kitsune.client.cosmetic.CosmeticRegistry;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.cosmetic.CapesModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces the cape texture in {@link AvatarRenderState} when the rendered
 * player is a Fox Client cosmetic owner and the {@link CapesModule} is on.
 *
 * <p>Touches {@code state.skin} after vanilla's {@code extractRenderState}
 * has run, then sets {@code showCape = true} so the {@link
 * net.minecraft.client.renderer.entity.layers.CapeLayer} actually draws it.
 *
 * <p>Defensive ordering: every condition is checked before any mutation, so
 * a non-owner / disabled-module path costs only a UUID set lookup.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererCapeMixin {

    @Inject(method = "extractRenderState",
            at = @At("TAIL"))
    private void kitsune$injectCape(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        // Module gate.
        CapesModule mod = ModuleManager.getModule(CapesModule.class);
        if (mod == null || !mod.isEnabled()) return;
        if (!CosmeticRegistry.isLoaded()) return;
        if (!(entity instanceof Player)) return;

        UUID uuid = entity.getUUID();
        if (!CosmeticRegistry.isOwner(uuid)) return;

        // Self vs. other player toggles.
        Minecraft mc = Minecraft.getInstance();
        boolean isSelf = mc != null && mc.player != null && uuid.equals(mc.player.getUUID());
        if (isSelf && !mod.showOnSelf()) return;
        if (!isSelf && !mod.showOtherPlayers()) return;

        // Pick the cape: for self use the chosen id (if owned), for others pick
        // the first owned cape. Stable order comes from the manifest, so the
        // result for a given UUID doesn't flicker between frames.
        String capeId;
        if (isSelf) {
            capeId = mod.localCapeId();
            if (capeId == null) return;
            if (!CosmeticRegistry.capesOwnedBy(uuid).contains(capeId)) return;
        } else {
            Set<String> owned = CosmeticRegistry.capesOwnedBy(uuid);
            if (owned.isEmpty()) return;
            capeId = owned.iterator().next();
        }

        // Build a new PlayerSkin that swaps in our cape. PlayerSkin is a
        // record, so we can't mutate it directly — but it has a Patch helper
        // that produces a new instance with selected fields replaced.
        var capeAsset = new ClientAsset.ResourceTexture(CosmeticRegistry.capeTexture(capeId));
        try {
            PlayerSkin original = state.skin;
            if (original == null) return;
            PlayerSkin patched = original.with(PlayerSkin.Patch.create(
                    Optional.empty(),
                    Optional.of(capeAsset),
                    Optional.empty(),
                    Optional.empty()));
            state.skin = patched;
            state.showCape = true;

            // Physics sway: drive capeLean / capeLean2 / capeFlap from the
            // module's smoothed per-player tuple. Skip silently when the
            // module isn't running physics or the player has no entry yet.
            if (mod.physicsEnabled()) {
                float[] sway = mod.swayFor(uuid);
                if (sway != null) {
                    state.capeLean  = sway[0];
                    state.capeLean2 = sway[1];
                    state.capeFlap  = sway[2];
                }
            }
        } catch (Throwable t) {
            // Mapping drift between MC patch versions — fail safe rather than
            // crash the renderer. Worst case the user just doesn't see capes.
        }
    }
}
