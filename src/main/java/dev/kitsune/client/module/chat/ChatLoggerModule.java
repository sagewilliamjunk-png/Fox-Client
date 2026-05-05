package dev.kitsune.client.module.chat;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Per-server chat logger. When enabled, every chat message received is appended
 * to {@code configDir/kitsune/chatlogs/<sanitized-host>/YYYY-MM-DD.log}. Singleplayer
 * worlds log under {@code singleplayer/<world-name>}.
 *
 * <p>Writes are appended on the render/game thread but use a short-lived
 * {@link BufferedWriter} per message — simple and crash-safe at the cost of
 * a file open per message. Chat volume is low enough for this to be fine.
 *
 * <p>Hooked from {@code ChatComponentMixin}; this class is the sink.
 */
public class ChatLoggerModule extends Module {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final BooleanSetting stripFormatting = addSetting(
            new BooleanSetting("Strip Color Codes", true));
    private final BooleanSetting includeTimestamp = addSetting(
            new BooleanSetting("Timestamp", true));

    public ChatLoggerModule() {
        super("Chat Logger", "Log chat messages per server", Category.CHAT);
    }

    /** Called from ChatComponentMixin for every incoming chat message. */
    public void logMessage(String rawMessage) {
        if (!isEnabled() || rawMessage == null || rawMessage.isEmpty()) return;
        try {
            Path dir = logDirForCurrent();
            if (dir == null) return;
            Files.createDirectories(dir);
            Path file = dir.resolve(LocalDate.now() + ".log");

            StringBuilder sb = new StringBuilder();
            if (includeTimestamp.get()) {
                sb.append('[').append(LocalTime.now().format(TIME_FMT)).append("] ");
            }
            String text = stripFormatting.get() ? stripFormatCodes(rawMessage) : rawMessage;
            sb.append(text).append(System.lineSeparator());

            Files.writeString(file, sb.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[ChatLogger] Failed to write log: {}", e.getMessage());
        }
    }

    private Path logDirForCurrent() {
        Minecraft mc = Minecraft.getInstance();
        Path base = FabricLoader.getInstance().getConfigDir()
                .resolve("kitsune").resolve("chatlogs");

        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null && !sd.ip.isEmpty()) {
            return base.resolve(sanitize(sd.ip));
        }
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            String world = mc.getSingleplayerServer().getWorldData().getLevelName();
            return base.resolve("singleplayer").resolve(sanitize(world));
        }
        return null;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stripFormatCodes(String s) {
        return s.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "");
    }
}
