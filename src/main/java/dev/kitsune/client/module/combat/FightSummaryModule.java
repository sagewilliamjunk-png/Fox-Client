package dev.kitsune.client.module.combat;

import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Fight Summary — when a fight ends (no hits dealt and no damage taken for a
 * short window), emits a one-line recap toast: hits landed, damage dealt vs.
 * taken, and the bout duration.
 *
 * <p>Self-contained: it tracks its own bout state from the shared
 * {@code PlayerAttackMixin} hit feed (hits + dealt) and its own health-delta
 * watch (taken), independent of Combo Counter / Damage Tally. Display-only —
 * a summary of information you already have. Fair-play.
 */
public class FightSummaryModule extends Module {

    private final SliderSetting  window     = addSetting(new SliderSetting("End After (s)", 4.0, 1.0, 15.0, 0.5));
    private final BooleanSetting countTaken = addSetting(new BooleanSetting("Track Damage Taken", true));

    private boolean inBout = false;
    private int     hits = 0;
    private double  dealt = 0;
    private double  taken = 0;
    private long    boutStartMs = 0;
    private long    lastActivityMs = 0;
    private float   lastHealth = -1f;

    public FightSummaryModule() {
        super("Fight Summary", "Posts a recap toast when a fight ends.", Category.COMBAT);
    }

    /** Called by PlayerAttackMixin on a landed attack (delta may be 0 on remote
     *  servers where the HP drop is deferred — the hit still counts). */
    public void onLocalHit(double damageDealt) {
        if (!isEffectivelyEnabled()) return;
        long now = System.currentTimeMillis();
        if (!inBout) startBout(now);
        hits++;
        if (damageDealt > 0) dealt += damageDealt;
        lastActivityMs = now;
    }

    private void startBout(long now) {
        inBout = true;
        hits = 0; dealt = 0; taken = 0;
        boutStartMs = now;
        lastActivityMs = now;
    }

    @Override
    protected void onDisable() {
        inBout = false;
        lastHealth = -1f;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { inBout = false; lastHealth = -1f; return; }

        // Damage taken keeps a bout alive too (being hit is "activity").
        float hp = p.getHealth();
        if (countTaken.get() && lastHealth >= 0f && hp < lastHealth) {
            double drop = lastHealth - hp;
            if (inBout) { taken += drop; lastActivityMs = System.currentTimeMillis(); }
        }
        lastHealth = hp;

        if (inBout) {
            long windowMs = (long) (window.get() * 1000);
            if (System.currentTimeMillis() - lastActivityMs > windowMs) endBout();
        }
    }

    private void endBout() {
        inBout = false;
        if (hits == 0 && taken <= 0) return; // nothing worth reporting
        double secs = (lastActivityMs - boutStartMs) / 1000.0;
        String msg = countTaken.get()
                ? String.format("Fight: %d hit%s · %.1f dealt / %.1f taken · %.0fs",
                        hits, hits == 1 ? "" : "s", dealt, taken, secs)
                : String.format("Fight: %d hit%s · %.1f dealt · %.0fs",
                        hits, hits == 1 ? "" : "s", dealt, secs);
        NotificationManager.show(msg, NotificationManager.Type.INFO);
    }
}
