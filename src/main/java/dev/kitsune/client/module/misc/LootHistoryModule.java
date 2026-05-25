package dev.kitsune.client.module.misc;

import dev.kitsune.client.command.LootHistory;
import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Loot History Tracker.
 * Diffs the player's inventory each tick — any item whose count increases is
 * recorded in {@link LootHistory} and shown as a brief on-screen toast.
 *
 * <p>Use {@code .fox loot} to view the most recent entries.
 */
public class LootHistoryModule extends Module implements HudWidget {

    private final BooleanSetting ignoreCommon  = addSetting(new BooleanSetting("Ignore Common Drops",  false));
    private final BooleanSetting showToast     = addSetting(new BooleanSetting("Show Toast Popup",     true));
    // History list is opt-in — most users only want the transient pickup
    // popup. The full scrolling history is still available via `.fox loot`.
    private final BooleanSetting showHistory   = addSetting(new BooleanSetting("Show History List",    false));
    private final SliderSetting  maxEntries    = addSetting(new SliderSetting("Max History", 5, 2, 10, 1));
    private final SliderSetting  toastDuration = addSetting(new SliderSetting("Toast Duration (s)", 4, 1, 10, 1));

    // Inventory state
    private final Map<Item, Integer> lastCounts = new HashMap<>();
    private boolean primed = false;

    // Toast queue: (name, count, expiry time ms)
    private record Toast(String name, int count, long expiresAt) {}
    private final Deque<Toast> toasts = new ArrayDeque<>();

    public LootHistoryModule() {
        super("Loot History", "Tracks items picked up with toast notifications", Category.MISC);
        HudManager.register(this);
    }

    // ---- HudWidget ----

    @Override public String widgetId()    { return "loot_history"; }
    @Override public String displayName() { return "Loot"; }
    @Override public int widgetWidth()    { return 140; }
    @Override public int widgetHeight() {
        int toastCount = (int) toasts.stream().filter(t -> t.expiresAt() > System.currentTimeMillis()).count();
        int limit      = maxEntries.get().intValue();
        int histCount  = Math.min(limit, LootHistory.recent(limit).size());
        int rows = (showToast.get() ? toastCount : 0) + (showHistory.get() ? histCount : 0);
        return Math.max(1, rows) * 11 + 8;
    }
    @Override public boolean isWidgetVisible() { return isEnabled() && (showToast.get() || showHistory.get()); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font    = mc.font;
        long now     = System.currentTimeMillis();

        int w = widgetWidth();
        int h = widgetHeight();

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x88000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFFCC8833); // amber accent

        int curY = y + 2;
        int rowH = 11;

        // Active toasts (recent pickups, fade out)
        if (showToast.get()) {
            toasts.removeIf(t -> t.expiresAt() <= now);
            for (Toast t : toasts) {
                float frac = (t.expiresAt() - now) / (toastDuration.get().floatValue() * 1000f);
                int alpha  = (int)(Math.min(1f, frac * 3) * 220);
                int color  = (alpha << 24) | 0x88FF88;
                gfx.text(font, "+ " + t.count() + "x " + t.name(), x + 2, curY, color);
                curY += rowH;
            }
        }

        // Scrolling history
        if (showHistory.get()) {
            int limit  = maxEntries.get().intValue();
            var recent = LootHistory.recent(limit);
            for (int i = recent.size() - 1; i >= 0; i--) {
                gfx.text(font, "\u00b7 " + recent.get(i), x + 2, curY, 0xFFAAAAAA);
                curY += rowH;
            }
        }
    }

    // ---- Module logic ----

    @Override
    protected void onEnable() {
        lastCounts.clear();
        toasts.clear();
        primed = false;
    }

    @Override
    protected void onDisable() {
        lastCounts.clear();
        toasts.clear();
        primed = false;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        Map<Item, Integer> current = new HashMap<>();
        var inv  = player.getInventory();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            current.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        if (!primed) {
            lastCounts.putAll(current);
            primed = true;
            return;
        }

        for (var e : current.entrySet()) {
            int prev  = lastCounts.getOrDefault(e.getKey(), 0);
            int delta = e.getValue() - prev;
            if (delta > 0) {
                if (ignoreCommon.get() && isCommon(e.getKey())) continue;
                String name = safeItemName(e.getKey());
                LootHistory.record(name, delta);
                if (showToast.get()) {
                    long expires = System.currentTimeMillis() + (long)(toastDuration.get() * 1000);
                    toasts.addLast(new Toast(name, delta, expires));
                    // Cap toast queue
                    while (toasts.size() > 8) toasts.removeFirst();
                }
            }
        }
        lastCounts.clear();
        lastCounts.putAll(current);
    }

    // ---- helpers ----

    /** Defensive name lookup. Modded items can throw or return null from their
     *  default-instance / hover-name path; fall back to the registry id so we
     *  never NPE on pickup. Uses getDefaultInstance() to avoid the per-pickup
     *  ItemStack allocation the old path had. */
    private static String safeItemName(Item item) {
        try {
            net.minecraft.world.item.ItemStack stack = item.getDefaultInstance();
            if (stack != null) {
                var hover = stack.getHoverName();
                if (hover != null) return hover.getString();
            }
        } catch (Throwable ignored) {}
        try {
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            return key != null ? key.getPath() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static boolean isCommon(Item item) {
        String path = item.toString().toLowerCase();
        return path.contains("cobblestone") || path.contains("dirt")
                || path.contains("stone")  || path.contains("gravel")
                || path.contains("sand")   || path.contains("netherrack");
    }
}
