package dev.kitsune.client.module.cosmetic;

import dev.kitsune.client.cosmetic.CosmeticRegistry;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;

import java.util.Set;
import java.util.UUID;

/**
 * Particle Trail — the second Fox Client cosmetic type (after capes). Players
 * who own a "trail" cosmetic in {@link CosmeticRegistry} emit a small particle
 * trail behind them while moving.
 *
 * <p>Pure client-side cosmetic: it only calls {@code level.addParticle}, which
 * spawns visual-only particles on the local client (no entities, no packets,
 * no model/render-layer mixin — unlike hats). Trivially SAFETY.md-compliant.
 *
 * <p>Throttled to every other tick and gated on horizontal movement so a
 * standing player doesn't fountain particles.
 */
public class ParticleTrailModule extends Module {

    private final BooleanSetting showOnSelf       = addSetting(new BooleanSetting("Show On Self", true));
    private final BooleanSetting showOtherPlayers = addSetting(new BooleanSetting("Show On Other Players", true));

    private int tickCounter = 0;

    public ParticleTrailModule() {
        super("Particle Trail", "Emits a cosmetic particle trail for trail-cosmetic owners.",
                Category.COSMETIC);
    }

    @Override
    public void onTick() {
        if ((++tickCounter & 1) != 0) return;          // every other tick
        if (!CosmeticRegistry.isLoaded() || !CosmeticRegistry.anyTrails()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        UUID selfId = mc.player.getUUID();
        for (Player p : mc.level.players()) {
            boolean isSelf = p.getUUID().equals(selfId);
            if (isSelf && !showOnSelf.get()) continue;
            if (!isSelf && !showOtherPlayers.get()) continue;

            Set<String> owned = CosmeticRegistry.trailsOwnedBy(p.getUUID());
            if (owned.isEmpty()) continue;

            // Only trail while actually moving horizontally (a real trail).
            var v = p.getDeltaMovement();
            if (v.x * v.x + v.z * v.z < 0.0025) continue; // ~0.05 b/t threshold

            ParticleOptions particle = particleFor(CosmeticRegistry.trailParticle(owned.iterator().next()));
            try {
                // Spawn near the feet with a tiny spread, drifting opposite motion.
                double px = p.getX() - v.x * 2;
                double py = p.getY() + 0.1;
                double pz = p.getZ() - v.z * 2;
                mc.level.addParticle(particle, px, py, pz, 0, 0.01, 0);
            } catch (Throwable ignored) { /* never break the tick over a cosmetic */ }
        }
    }

    /** Map a manifest particle key to a vanilla simple particle. Unknown keys
     *  fall back to flame. Only ParticleTypes that are themselves ParticleOptions
     *  (SimpleParticleType) are used, so no extra data is required. */
    private static ParticleOptions particleFor(String key) {
        if (key == null) return ParticleTypes.FLAME;
        return switch (key.toLowerCase()) {
            case "heart"     -> ParticleTypes.HEART;
            case "crit"      -> ParticleTypes.CRIT;
            case "soul"      -> ParticleTypes.SOUL_FIRE_FLAME;
            case "happy"     -> ParticleTypes.HAPPY_VILLAGER;
            case "portal"    -> ParticleTypes.PORTAL;
            case "end_rod"   -> ParticleTypes.END_ROD;
            case "smoke"     -> ParticleTypes.SMOKE;
            case "snowflake" -> ParticleTypes.SNOWFLAKE;
            case "enchant"   -> ParticleTypes.ENCHANT;
            default           -> ParticleTypes.FLAME;
        };
    }
}
