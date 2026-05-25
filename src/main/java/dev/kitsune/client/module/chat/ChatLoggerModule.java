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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Per-server chat logger. When enabled, every chat message received is appended
 * to {@code configDir/kitsune/chatlogs/<sanitized-host>/YYYY-MM-DD.log}. Singleplayer
 * worlds log under {@code singleplayer/<world-name>}.
 *
 * <p>Writes are dispatched to a single background worker thread via a
 * {@link LinkedBlockingQueue} — {@link #logMessage} is render-thread safe and
 * returns instantly. The worker batches writes, holds a long-lived
 * {@link BufferedWriter} open, and flushes every ~500 ms or when the queue
 * drains. This is the production fix for the "file-open per chat message on
 * the render thread" stutter the audit caught.
 *
 * <p>Hooked from {@code ChatComponentMixin}; this class is the sink.
 */
public class ChatLoggerModule extends Module {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long FLUSH_INTERVAL_MS = 500;
    /** Bound the queue so a runaway producer can't OOM us. Above this we drop the message. */
    private static final int MAX_QUEUE = 4096;

    private final BooleanSetting stripFormatting = addSetting(
            new BooleanSetting("Strip Color Codes", true));
    private final BooleanSetting includeTimestamp = addSetting(
            new BooleanSetting("Timestamp", true));

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private volatile Thread worker;
    private volatile boolean running;

    /** Cached file we're currently appending to. Worker reopens when the day or
     *  destination directory changes. */
    private Path currentFile;
    private BufferedWriter currentWriter;

    public ChatLoggerModule() {
        super("Chat Logger", "Log chat messages per server", Category.CHAT);
    }

    /** Called from ChatComponentMixin for every incoming chat message.
     *  Render-thread safe — formats the line and hands it off to the queue. */
    public void logMessage(String rawMessage) {
        if (!isEnabled() || rawMessage == null || rawMessage.isEmpty()) return;
        if (queue.size() >= MAX_QUEUE) return; // drop rather than block the render thread
        StringBuilder sb = new StringBuilder(rawMessage.length() + 16);
        if (includeTimestamp.get()) {
            sb.append('[').append(LocalTime.now().format(TIME_FMT)).append("] ");
        }
        String text = stripFormatting.get() ? stripFormatCodes(rawMessage) : rawMessage;
        sb.append(text).append(System.lineSeparator());
        queue.offer(sb.toString());
    }

    @Override
    protected void onEnable() {
        running = true;
        worker = new Thread(this::workerLoop, "Fox ChatLogger");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void onDisable() {
        running = false;
        // Nudge the worker out of its take() — null sentinel triggers drain + close.
        queue.offer("");
        Thread t = worker;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        worker = null;
        // Defensive close in case the worker didn't shut down cleanly.
        closeWriter();
        queue.clear();
    }

    // ---- worker thread loop ----

    private void workerLoop() {
        long lastFlush = System.currentTimeMillis();
        try {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (line != null && !line.isEmpty()) {
                    writeLine(line);
                }
                long now = System.currentTimeMillis();
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    flushWriter();
                    lastFlush = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeWriter();
        }
    }

    private void writeLine(String line) {
        Path target = currentLogFile();
        if (target == null) return; // not connected to a world/server
        try {
            if (!target.equals(currentFile)) {
                closeWriter();
                Files.createDirectories(target.getParent());
                currentWriter = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                currentFile = target;
            }
            currentWriter.write(line);
        } catch (IOException e) {
            KitsuneClient.LOGGER.warn("[ChatLogger] Failed to write log: {}", e.getMessage());
            closeWriter();
        }
    }

    private void flushWriter() {
        if (currentWriter == null) return;
        try { currentWriter.flush(); }
        catch (IOException e) { KitsuneClient.LOGGER.warn("[ChatLogger] flush failed: {}", e.getMessage()); }
    }

    private void closeWriter() {
        if (currentWriter == null) return;
        try { currentWriter.close(); } catch (IOException ignored) {}
        currentWriter = null;
        currentFile = null;
    }

    // ---- path resolution ----

    /** Resolve the log file for the current session + day. */
    private Path currentLogFile() {
        Path dir = logDirForCurrent();
        if (dir == null) return null;
        return dir.resolve(LocalDate.now() + ".log");
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
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
