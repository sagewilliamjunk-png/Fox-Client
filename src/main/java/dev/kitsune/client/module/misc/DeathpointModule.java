package dev.kitsune.client.module.misc;

import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Automatic skull-marked waypoint on every player death. Pure client-side —
 * watches the local player's hp on each tick and fires when it crosses
 * &gt;0 → &lt;=0 (matching the existing DeathCoordsHudModule's edge-detection,
 * but persisting via the WaypointManager so the marker survives a restart).
 *
 * <h3>Settings</h3>
 * <ul>
 *   <li><b>Notify</b> — show a toast when a deathpoint is created (default on)</li>
 *   <li><b>Keep last N</b> — automatically delete the oldest deathpoints when
 *       a new one lands; 0 = unlimited</li>
 * </ul>
 *
 * Deathpoints are rendered on the minimap with a skull symbol and a red
 * default color (see Waypoint.DEATHPOINT_COLOR).
 */
public class DeathpointModule extends Module {

    private final BooleanSetting notify    = addSetting(new BooleanSetting("Notify", true));
    private final SliderSetting  keepLast  = addSetting(new SliderSetting("Keep last N (0=unlimited)", 5, 0, 50, 1));

    private boolean prevAlive = true;
    /** FIFO queue of deathpoint ids for auto-pruning. */
    private final Deque<String> createdIds = new ArrayDeque<>();

    public DeathpointModule() {
        super("Deathpoint",
              "Auto-creates a skull waypoint at your last death location.",
              Category.MISC);
    }

    @Override
    protected void onEnable() {
        prevAlive = true;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { prevAlive = true; return; }
        boolean alive = p.getHealth() > 0f && !p.isDeadOrDying();
        if (prevAlive && !alive) {
            // Edge: just died — create the waypoint.
            String name = "Death " + java.time.LocalTime.now().withNano(0);
            Waypoint w = new Waypoint(
                    null, name,
                    p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                    Waypoint.DEATHPOINT_COLOR,
                    "☠", true, true);
            Waypoint stored = WaypointManager.addToCurrent(w);
            if (stored != null) {
                createdIds.addLast(stored.id());
                pruneOldDeathpoints();
                if (notify.get()) {
                    NotificationManager.show(
                            "☠ Deathpoint set at " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ(),
                            NotificationManager.Type.WARNING);
                }
            }
        }
        prevAlive = alive;
    }

    private void pruneOldDeathpoints() {
        int cap = keepLast.get().intValue();
        if (cap <= 0) return;
        while (createdIds.size() > cap) {
            String oldest = createdIds.removeFirst();
            try { WaypointManager.delete(oldest); } catch (Throwable ignored) {}
        }
    }
}
