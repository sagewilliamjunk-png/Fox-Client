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
 * Ephemeral loot pickup tracker. Diffs the player's inventory each tick — any
 * item whose count increases produces a short-lived toast that includes the
 * item's icon and name. The widget is INVISIBLE when no toast is active so
 * it doesn't clutter the HUD between drops.
 *
 * <h3>v1.2 changes (user request)</h3>
 * <ul>
 *   <li>Dropped the "Show History List" mode entirely — the widget is now
 *       toast-only. The full history is still queryable via
 *       {@code .fox loot} which reads from {@link LootHistory#recent}.</li>
 *   <li>Each toast row renders the item icon at 16×16 via
 *       {@link GuiGraphicsExtractor#item} on the left, count + name to the right.</li>
 *   <li>Capped at 6 stacked toasts; oldest dropped when a 7th lands.</li>
 *   <li>{@link #isWidgetVisible()} returns false when the toast queue is empty,
 *       so the HUD slot doesn't reserve space when nothing's being picked up.</li>
 * </ul>
 *
 * <p>Hooked from the client tick. No mixins; no packets.
 */
public class LootHistoryModule extends Module implements HudWidget {

    private static final int MAX_TOASTS = 6;
    private static final int ROW_HEIGHT = 18;
    private static final int ICON_SIZE  = 16;

    private final BooleanSetting ignoreCommon  = addSetting(new BooleanSetting("Ignore Common Drops",  false));
    private final SliderSetting  toastDuration = addSetting(new SliderSetting("Toast Duration (s)", 4, 1, 10, 1));

    // Inventory state
    private final Map<Item, Integer> lastCounts = new HashMap<>();
    private boolean primed = false;

    /** A single pending pickup toast. The Item reference is the actual Item
     *  instance so {@link GuiGraphicsExtractor#item} can render its icon
     *  faithfully (including durability bar, enchantment glint, etc.). */
    private record Toast(Item item, String name, int count, long expiresAt) {}

    private final Deque<Toast> toasts = new ArrayDeque<>();

    public LootHistoryModule() {
        super("Loot History", "Brief on-screen toasts when you pick items up", Category.MISC);
        HudManager.register(this);
    }

    // ---- HudWidget --------------------------------------------------------

    @Override public String widgetId()    { return "loot_history"; }
    @Override public String displayName() { return "Loot"; }
    @Override public int widgetWidth()    { return 160; }

    @Override
    public int widgetHeight() {
        long now = System.currentTimeMillis();
        int alive = 0;
        for (Toast t : toasts) if (t.expiresAt() > now) alive++;
        return Math.max(1, alive) * ROW_HEIGHT + 4;
    }

    /** Invisible when no toast is alive — the widget doesn't reserve HUD
     *  space between pickups. The HUD editor still shows the widget bounds
     *  using its widgetHeight when the editor screen is open (the editor
     *  treats invisibility as a runtime state, not a layout signal). */
    @Override
    public boolean isWidgetVisible() {
        if (!isEnabled()) return false;
        long now = System.currentTimeMillis();
        for (Toast t : toasts) if (t.expiresAt() > now) return true;
        return false;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        long now = System.currentTimeMillis();
        // Drain expired toasts first.
        toasts.removeIf(t -> t.expiresAt() <= now);
        if (toasts.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x88000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFFCC8833); // amber accent

        int rowY = y + 2;
        for (Toast t : toasts) {
            float frac = (t.expiresAt() - now) / (toastDuration.get().floatValue() * 1000f);
            int alpha = (int) (Math.min(1f, frac * 3f) * 220);
            int textColor = (alpha << 24) | 0x88FF88;

            // Item icon on the left. Wrap in try/catch so a malformed modded
            // item can't crash the HUD pass.
            try {
                ItemStack icon = new ItemStack(t.item(), Math.max(1, t.count()));
                gfx.item(icon, x + 2, rowY);
                if (t.count() > 1) {
                    // Use the vanilla decorations path — gives us count text +
                    // durability bar styling consistent with inventory.
                    gfx.itemDecorations(font, icon, x + 2, rowY);
                }
            } catch (Throwable ignored) {}

            // Name (and count when icon stack-count would be visually noisy).
            String label = "+ " + t.count() + "× " + t.name();
            gfx.text(font, label, x + 2 + ICON_SIZE + 4, rowY + 4, textColor);
            rowY += ROW_HEIGHT;
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

        long expiresAt = System.currentTimeMillis() + (long)(toastDuration.get() * 1000);
        for (var e : current.entrySet()) {
            int prev  = lastCounts.getOrDefault(e.getKey(), 0);
            int delta = e.getValue() - prev;
            if (delta <= 0) continue;
            if (ignoreCommon.get() && isCommon(e.getKey())) continue;
            String name = safeItemName(e.getKey());
            // Push to the central history (still consumable via .fox loot).
            LootHistory.record(name, delta);
            // And queue a toast.
            toasts.addLast(new Toast(e.getKey(), name, delta, expiresAt));
            while (toasts.size() > MAX_TOASTS) toasts.removeFirst();
        }
        lastCounts.clear();
        lastCounts.putAll(current);
    }

    // ---- helpers ----

    /** Defensive name lookup — see ChatLogger / Tier 1 fix for the rationale. */
    private static String safeItemName(Item item) {
        try {
            ItemStack stack = item.getDefaultInstance();
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
