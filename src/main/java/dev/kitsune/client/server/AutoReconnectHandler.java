package dev.kitsune.client.server;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.core.KitsuneConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * On first reach of the title screen after a Fox-initiated restart, read the
 * persisted {@link PendingJoin} (if any) and fire a connection straight to the
 * saved server. Disabled if {@link KitsuneConfig#autoReconnectAfterRestart} is off.
 *
 * If the connection fails (server moved, address invalid, etc.), the user just
 * sees the connect screen's normal failure dialog and is back on the title.
 */
public class AutoReconnectHandler {

    public static void tryReconnect(TitleScreen titleScreen) {
        if (!KitsuneConfig.get().autoReconnectAfterRestart) {
            // Still consume so we don't auto-reconnect on the next launch
            PendingJoin.consume();
            return;
        }
        String addr = PendingJoin.consume();
        if (addr == null || addr.isEmpty()) return;

        // Safety cap so a broken server + auto-relaunch loop can't DDoS.
        int attempt = PendingJoin.recordAttempt(addr);
        if (attempt > PendingJoin.MAX_ATTEMPTS) {
            KitsuneClient.LOGGER.warn(
                    "Auto-reconnect to {} exceeded {} failed attempts — giving up. "
                  + "Clear config/kitsune/reconnect_attempts.json to retry.",
                    addr, PendingJoin.MAX_ATTEMPTS);
            return;
        }

        KitsuneClient.LOGGER.info("Auto-reconnecting to {} (attempt {}/{}) after restart",
                addr, attempt, PendingJoin.MAX_ATTEMPTS);

        try {
            Minecraft mc = Minecraft.getInstance();
            ServerData data = new ServerData("Fox auto-reconnect", addr, ServerData.Type.OTHER);
            ServerAddress parsed = ServerAddress.parseString(addr);
            // ConnectScreen.startConnecting signature shifts across 1.21 patch versions.
            // Use the most common 1.21.x form first; if it fails, fall back to the
            // 5-arg form via reflection.
            try {
                ConnectScreen.startConnecting(titleScreen, mc, parsed, data, false, null);
            } catch (NoSuchMethodError ignored) {
                java.lang.reflect.Method m = findStartConnecting();
                if (m != null) m.invoke(null, titleScreen, mc, parsed, data, false);
            }
        } catch (Throwable t) {
            KitsuneClient.LOGGER.error("Auto-reconnect failed", t);
        }
    }

    private static java.lang.reflect.Method findStartConnecting() {
        for (java.lang.reflect.Method m : ConnectScreen.class.getDeclaredMethods()) {
            if (m.getName().equals("startConnecting")) return m;
        }
        return null;
    }
}
