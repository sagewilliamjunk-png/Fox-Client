package dev.kitsune.client;

import dev.kitsune.client.core.ModJarSwapper;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs BEFORE Fabric's mod loader scans the mods folder. Registered in
 * fabric.mod.json under {@code entrypoints.preLaunch}.
 *
 * Sole responsibility: process any pending mod-jar moves left over from
 * the previous session (queued by {@link dev.kitsune.client.server.RestartConfirmScreen}
 * or by manual profile switching).
 *
 * Cannot use Minecraft client classes here — the client is not yet loaded.
 */
public class PreLaunchBootstrap implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            ModJarSwapper.applyPendingMovesAtPreLaunch();
        } catch (Throwable t) {
            // Don't crash the JVM if a move fails — just log to stderr
            System.err.println("[Fox] PreLaunchBootstrap failed: " + t);
            t.printStackTrace();
        }
    }
}
