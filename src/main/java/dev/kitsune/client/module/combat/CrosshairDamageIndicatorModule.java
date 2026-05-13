package dev.kitsune.client.module.combat;

import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.event.RenderHudEvent;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Floating "-X.X" damage numbers near the crosshair when the player deals
 * damage. Inspired by Hypixel-style hit indicators.
 *
 * <p>Snapshots the crosshair-picked entity's health each tick (one entity,
 * not the whole world) so that when {@link LocalPlayer#getLastHurtMob()}
 * flips on the next tick we can diff pre-/post-hit HP for that one target.
 * World→screen projection would require a render-world hook we don't have
 * yet, so indicators stack vertically just above the crosshair instead —
 * close enough for feedback and cheap to draw.
 */
public class CrosshairDamageIndicatorModule extends Module {

    private final ColorSetting   color        = addSetting(new ColorSetting("Color",         0xFFFF5252));
    private final ColorSetting   critColor    = addSetting(new ColorSetting("Crit Color",    0xFFFFD54A));
    private final SliderSetting  duration     = addSetting(new SliderSetting("Duration (ticks)", 20, 5, 60, 1));
    private final SliderSetting  scale        = addSetting(new SliderSetting("Scale",        1.0, 0.5, 2.0, 0.1));
    private final SliderSetting  riseSpeed    = addSetting(new SliderSetting("Rise (px/tick)", 0.8, 0.0, 3.0, 0.1));
    private final BooleanSetting showCritMark = addSetting(new BooleanSetting("Crit Marker", true));
    private final BooleanSetting dropShadow   = addSetting(new BooleanSetting("Drop Shadow", true));

    /**
     * Snapshot of the single entity the crosshair was on last tick. We keep
     * exactly one health sample — the target the player is looking at — so
     * the common "swing at a mob you're aiming at" case has a valid pre-hit
     * reading without us walking the level's entity list every tick.
     */
    private int   snapshotEntityId = -1;
    private float snapshotHealth   = 0f;
    private int   lastHurtTimestamp = -1;

    /**
     * When a swing lands, damage is server-authoritative — the hit timestamp
     * flips on the same tick as the click, but the target's client-side
     * health doesn't drop until the server's health-update packet arrives
     * (~1–3 ticks later). So we latch the pre-hit snapshot here and poll
     * the target for a short window, firing the indicator the frame its
     * health actually drops.
     */
    private int     pendingTargetId = -1;
    private float   pendingPreHitHp = 0f;
    private int     pendingTicksLeft = 0;
    private boolean pendingCrit = false;
    private static final int HIT_WATCH_TICKS = 12;

    private static final class Indicator {
        final float damage;
        final boolean crit;
        int ticks;   // ticks alive
        Indicator(float d, boolean c) { this.damage = d; this.crit = c; this.ticks = 0; }
    }

    /** Newest indicator at the tail, oldest at head. Rendered bottom-up. */
    private final Deque<Indicator> indicators = new ArrayDeque<>();

    private final Consumer<RenderHudEvent> renderHandler = this::onRender;

    public CrosshairDamageIndicatorModule() {
        super("Damage Indicator",
              "Floating damage numbers at the crosshair when you deal damage",
              Category.COMBAT);
    }

    /**
     * Entry point for {@code PlayerAttackMixin} — a confirmed hit with a
     * concrete damage value. This is the authoritative path when available
     * (LAN / integrated server); the poll-loop path below is the fallback
     * for remote servers where client-side HP lags.
     */
    public void submitDirectHit(float damage, boolean crit) {
        if (damage <= 0.05f) return;
        indicators.addLast(new Indicator(damage, crit));
        while (indicators.size() > 12) indicators.pollFirst();
        // Cancel any pending poll for this target — the mixin already fired.
        pendingTargetId  = -1;
        pendingTicksLeft = 0;
    }

    /**
     * Entry point for {@code PlayerAttackMixin} when the immediate
     * client-side health delta was zero (remote server — damage pending a
     * server health-update packet). Arms the per-tick poll loop in
     * {@link #onTick()} to watch the target's HP for the next
     * {@value #HIT_WATCH_TICKS} ticks.
     */
    public void armPendingWatch(int targetId, float preHitHp, boolean crit) {
        pendingTargetId  = targetId;
        pendingPreHitHp  = preHitHp;
        pendingTicksLeft = HIT_WATCH_TICKS;
        pendingCrit      = crit;
    }

    @Override
    protected void onEnable()  { EventBus.subscribe(RenderHudEvent.class, renderHandler); }

    @Override
    protected void onDisable() {
        EventBus.unsubscribe(RenderHudEvent.class, renderHandler);
        indicators.clear();
        snapshotEntityId = -1;
        snapshotHealth = 0f;
        lastHurtTimestamp = -1;
        pendingTargetId = -1;
        pendingTicksLeft = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // 1. Detect a fresh attack via getLastHurtMobTimestamp — this field
        //    only increments when the player actually hurts something.
        int ts = player.getLastHurtMobTimestamp();
        LivingEntity target = player.getLastHurtMob();

        if (target != null && ts != lastHurtTimestamp) {
            lastHurtTimestamp = ts;
            // Only arm when the pre-hit snapshot matches the mob we just
            // swung at. If the player is being splashed / thorns'd etc. and
            // not looking at the target, we simply don't show a number.
            if (snapshotEntityId == target.getId()) {
                pendingTargetId = target.getId();
                pendingPreHitHp = snapshotHealth;
                pendingTicksLeft = HIT_WATCH_TICKS;
                pendingCrit = player.fallDistance > 0.0f
                        && !player.onGround()
                        && !player.isInWater();
            }
        }

        // 1b. Poll the pending target for a health drop. Damage is server-
        //     authoritative so the client's mob.health lags the hit tick by
        //     the round-trip — typically 1–3 ticks on LAN, longer on remote
        //     servers. We watch for HIT_WATCH_TICKS then give up quietly.
        if (pendingTicksLeft > 0) {
            LivingEntity pt = player.getLastHurtMob();
            if (pt != null && pt.getId() == pendingTargetId && !pt.isRemoved()) {
                float hp = pt.getHealth();
                float delta = pendingPreHitHp - hp;
                if (delta > 0.05f) {
                    indicators.addLast(new Indicator(delta, pendingCrit));
                    while (indicators.size() > 12) indicators.pollFirst();
                    pendingTicksLeft = 0;
                    pendingTargetId  = -1;
                } else if (pt.getHealth() <= 0f) {
                    // Mob died — the full remaining HP was the damage.
                    if (pendingPreHitHp > 0.05f) {
                        indicators.addLast(new Indicator(pendingPreHitHp, pendingCrit));
                        while (indicators.size() > 12) indicators.pollFirst();
                    }
                    pendingTicksLeft = 0;
                    pendingTargetId  = -1;
                } else {
                    pendingTicksLeft--;
                }
            } else {
                pendingTicksLeft--;
            }
        }

        // 2. Snapshot the *one* entity the crosshair is currently on. Single
        //    field access — constant-time per tick, regardless of entity count.
        snapshotEntityId = -1;
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() == HitResult.Type.ENTITY && hr instanceof EntityHitResult ehr) {
            if (ehr.getEntity() instanceof LivingEntity le && !le.isRemoved()) {
                snapshotEntityId = le.getId();
                snapshotHealth   = le.getHealth();
            }
        }

        // 3. Tick existing indicators.
        int maxAge = duration.get().intValue();
        Iterator<Indicator> it = indicators.iterator();
        while (it.hasNext()) {
            Indicator ind = it.next();
            ind.ticks++;
            if (ind.ticks > maxAge) it.remove();
        }
    }

    private void onRender(RenderHudEvent event) {
        if (indicators.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        GuiGraphicsExtractor gfx = event.graphics;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2 - 18;

        int maxAge = duration.get().intValue();
        float s = scale.get().floatValue();
        float rise = riseSpeed.get().floatValue();
        boolean shadow = dropShadow.get();
        boolean critMark = showCritMark.get();

        var pose = gfx.pose();

        // Render newest at the crosshair, older ones progressively higher
        // and faded — last → first so newer draws on top visually.
        int idx = indicators.size();
        for (Indicator ind : indicators) {
            idx--;
            float t = ind.ticks / (float) maxAge;       // 0..1
            float alpha = 1.0f - t;
            if (alpha <= 0f) continue;

            float yOff = -(ind.ticks * rise) - idx * 8f;
            int a = Math.max(0, Math.min(255, (int)(alpha * 255)));
            int rgb = ind.crit ? critColor.get() : color.get();
            int col = (a << 24) | (rgb & 0x00FFFFFF);

            String text = formatDamage(ind.damage);
            if (ind.crit && critMark) text = "\u2605 " + text; // star prefix

            int tw = mc.font.width(text);
            pose.pushMatrix();
            pose.translate(cx, cy + yOff);
            pose.scale(s, s);
            pose.translate(-tw / 2f, 0);
            if (shadow) {
                int shadowCol = (a << 24);
                gfx.text(mc.font, text, 1, 1, shadowCol);
            }
            gfx.text(mc.font, text, 0, 0, col);
            pose.popMatrix();
        }
    }

    private static String formatDamage(float d) {
        // "-4.5" style with 1 decimal, trim trailing .0
        if (Math.abs(d - Math.round(d)) < 0.05f) {
            return "-" + Math.round(d);
        }
        return String.format("-%.1f", d);
    }
}
