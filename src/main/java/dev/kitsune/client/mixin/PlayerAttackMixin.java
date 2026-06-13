package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.combat.CrosshairDamageIndicatorModule;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Direct-capture feeder for {@link CrosshairDamageIndicatorModule}. The
 * module's original design relied on snapshotting the crosshair target's
 * health each tick and diffing vs {@code LivingEntity.getLastHurtMob}'s
 * post-hit health. That works on LAN / integrated-server, but on remote
 * servers the client-side health doesn't drop until a server packet lands
 * ~1–3 ticks later, which made the indicator silently drop a lot of hits.
 *
 * <p>Instead: at HEAD of {@link Player#attack(Entity)}, snapshot the
 * target's health; at TAIL, diff pre/post. The integrated-server case has
 * already applied damage clientside so the delta is non-zero immediately;
 * the remote-server case gets a zero delta here but the module's poll loop
 * still catches the eventual HP drop. Both paths feed the same indicator
 * queue, so whichever fires first wins and the other is a noop.
 *
 * <p>Only runs for {@code LocalPlayer} attacks — we don't care what other
 * players' clients are doing with their own attack calls (dedicated-server
 * path is only our local client anyway).
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Unique private float   kitsune$preHp        = 0f;
    @Unique private int     kitsune$preId        = -1;
    @Unique private boolean kitsune$preCaptured  = false;

    @Inject(method = "attack", at = @At("HEAD"))
    private void kitsune$capturePreHp(Entity target, CallbackInfo ci) {
        // Only the local player's attacks should feed our HUD — remote
        // "player" entities in multiplayer are logical proxies and attack()
        // won't be called on them client-side anyway, but guard just in case.
        Player self = (Player) (Object) this;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != self) {
            kitsune$preCaptured = false;
            return;
        }
        if (target instanceof LivingEntity le && !le.isRemoved()) {
            kitsune$preHp       = le.getHealth();
            kitsune$preId       = le.getId();
            kitsune$preCaptured = true;
        } else {
            kitsune$preCaptured = false;
        }
    }

    @Inject(method = "attack", at = @At("TAIL"))
    private void kitsune$emitDamage(Entity target, CallbackInfo ci) {
        if (!kitsune$preCaptured) return;
        kitsune$preCaptured = false;

        Player self = (Player) (Object) this;
        // Pre/post HP delta + crit — computed once and shared by every combat
        // consumer below, independent of which modules are enabled.
        float post;
        if (target instanceof LivingEntity le && le.getId() == kitsune$preId) {
            post = (le.isRemoved() || le.getHealth() <= 0f) ? 0f : le.getHealth();
        } else {
            post = 0f;
        }
        float delta = kitsune$preHp - post;
        boolean crit = self.fallDistance > 0.0f
                && !self.onGround()
                && !self.isInWater();

        // ---- v1.5/v1.6 combat module feeds (each no-ops when disabled) ----
        // Combat Timer + Combo counter fire on every landed attack on a living
        // target, regardless of whether a clientside damage delta is visible.
        var timer = ModuleManager.getModule(dev.kitsune.client.module.combat.CombatTimerModule.class);
        if (timer != null) timer.onLocalAttack();
        var combo = ModuleManager.getModule(dev.kitsune.client.module.combat.ComboCounterModule.class);
        if (combo != null) combo.onLocalHit();
        // Damage tally only counts a hit we can actually measure clientside.
        if (delta > 0.05f) {
            var tally = ModuleManager.getModule(dev.kitsune.client.module.combat.DamageTallyModule.class);
            if (tally != null) tally.onDamageDealt(delta);
        }
        // Target HUD "sticky after hit" — remember who we just struck.
        if (target instanceof LivingEntity hit) {
            var thud = ModuleManager.getModule(dev.kitsune.client.module.hud.TargetHudModule.class);
            if (thud != null) thud.noteAttacked(hit);
        }

        // ---- Crosshair damage indicator (v1.0) — unchanged behaviour ----
        CrosshairDamageIndicatorModule mod =
                ModuleManager.getModule(CrosshairDamageIndicatorModule.class);
        if (mod == null || !mod.isEnabled()) return;
        if (delta > 0.05f) {
            mod.submitDirectHit(delta, crit);
        } else {
            // Remote-server case: clientside HP didn't drop this frame. Arm
            // the module's poll loop so it can pick up the HP delta once the
            // server sends the health update.
            mod.armPendingWatch(kitsune$preId, kitsune$preHp, crit);
        }
    }
}
