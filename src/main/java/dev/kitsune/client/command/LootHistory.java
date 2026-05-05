package dev.kitsune.client.command;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory ring of recent loot gains. The {@code LootHistoryModule}
 * writes here; {@code .fox loot} reads from here.
 */
public final class LootHistory {
    private static final int MAX = 100;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    private LootHistory() {}

    public static synchronized void record(String itemName, int count) {
        String line = "[" + LocalTime.now().format(TIME_FMT) + "] +" + count + " \u00a7e" + itemName;
        ENTRIES.addFirst(line);
        while (ENTRIES.size() > MAX) ENTRIES.removeLast();
    }

    public static synchronized List<String> recent(int n) {
        List<String> out = new ArrayList<>(Math.min(n, ENTRIES.size()));
        int i = 0;
        for (String e : ENTRIES) {
            if (i++ >= n) break;
            out.add(e);
        }
        return out;
    }

    public static synchronized void clear() { ENTRIES.clear(); }
}
