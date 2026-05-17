package dev.kitsune.client.bridge;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes a small JSON snapshot of the current in-game state every ~3 seconds
 * into {@code <gameDir>/config/kitsune/game-state.json} so the Fox Launcher
 * can read it for rich Discord presence updates.
 *
 * <p>The file is deleted whenever the player is not in a world (title screen,
 * loading, disconnected) so the launcher can detect a stale / absent file and
 * fall back to plain "Playing Minecraft" presence text.
 *
 * <p>JSON schema (all fields optional except {@code timestamp}):
 * <pre>{@code
 * {
 *   "server":      "hypixel.net",           // multiplayer server address
 *   "singleplayer": "My World",             // singleplayer world name
 *   "dimension":   "minecraft:the_nether",  // current dimension key
 *   "timestamp":   1234567890123            // ms — staleness guard in launcher
 * }
 * }</pre>
 */
public final class GameStateBridge {

    /** Write the state file at most once every 60 ticks (~3 s at 20 TPS). */
    private static final int WRITE_INTERVAL_TICKS = 60;
    private static int tickCount = 0;

    private GameStateBridge() {}

    /** Register the tick listener. Call once from {@link dev.kitsune.client.KitsuneClient}. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(GameStateBridge::onTick);
    }

    private static void onTick(Minecraft client) {
        if (++tickCount < WRITE_INTERVAL_TICKS) return;
        tickCount = 0;

        if (client.level == null || client.player == null) {
            // Not in a world — delete any stale file so the launcher knows.
            deleteStateFile();
            return;
        }
        writeStateFile(client);
    }

    private static void writeStateFile(Minecraft client) {
        try {
            JsonObject obj = new JsonObject();

            // Multiplayer server address
            var conn = client.getConnection();
            if (conn != null && conn.getServerData() != null) {
                String ip = conn.getServerData().ip;
                if (ip != null && !ip.isEmpty()) {
                    obj.addProperty("server", ip);
                }
            }

            // Singleplayer world name (only when no server address was found)
            if (!obj.has("server") && client.hasSingleplayerServer()) {
                var srv = client.getSingleplayerServer();
                if (srv != null) {
                    String name = srv.getWorldData().getLevelName();
                    if (name != null && !name.isEmpty()) {
                        obj.addProperty("singleplayer", name);
                    }
                }
            }

            // Current dimension key e.g. "minecraft:the_nether"
            if (client.level != null) {
                String dim = client.level.dimension().location().toString();
                obj.addProperty("dimension", dim);
            }

            obj.addProperty("timestamp", System.currentTimeMillis());

            Path file = stateFilePath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, obj.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
            // Best-effort — never interrupt the game tick for a presence update.
        }
    }

    private static void deleteStateFile() {
        try {
            Files.deleteIfExists(stateFilePath());
        } catch (Exception ignored) {}
    }

    /** {@code <gameDir>/config/kitsune/game-state.json} */
    private static Path stateFilePath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("config")
                .resolve("kitsune")
                .resolve("game-state.json");
    }
}
