package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Records the coordinates where the player has recently died and pins the
 * most recent (or last 5, if "Show Recent" is enabled) in a small HUD widget
 * so the player can navigate back to recover items.
 *
 * <p>Detection is purely client-side via edge detection on the local player's
 * health (transition from &gt;0 to &lt;=0). No packets sent, no API calls —
 * server-safe by definition.
 *
 * <p>The recorded deaths persist for the life of the JVM. Switching dimensions
 * or worlds doesn't clear them; the dimension key is captured alongside the
 * coords so the user can tell whether the entry belongs to the current world.
 */
public class DeathCoordsHudModule extends Module implements HudWidget {

    /** Cap on stored history. The widget renders at most 3 rows; the rest stay
     *  for the `.fox death` chat command (not implemented here yet). */
    private static final int HISTORY_CAP = 5;
    /** Rows actually drawn when showRecent is on. */
    private static final int VISIBLE_ROWS = 3;

    private final BooleanSetting showDimension = addSetting(new BooleanSetting("Show Dimension", true));
    private final BooleanSetting showDistance  = addSetting(new BooleanSetting("Show Distance",  true));
    private final BooleanSetting showRecent    = addSetting(new BooleanSetting("Show Recent",    false));
    private final SliderSetting  bgOpacity     = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent        = addSetting(new ColorSetting("Accent", 0xFFE8472A));

    /** A single recorded death. */
    private record DeathRecord(int x, int y, int z, ResourceKey<Level> dimension, long timestampMs) {}

    /** Newest-first list of recorded deaths. Capped at HISTORY_CAP. */
    private final Deque<DeathRecord> deaths = new ArrayDeque<>();
    private boolean prevAlive = true;

    public DeathCoordsHudModule() {
        super("Death Coords", "Pins your recent death coordinates so you can recover", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "death_coords"; }
    @Override public String displayName() { return "Death"; }
    @Override public int widgetWidth()    { return 130; }
    @Override public int widgetHeight() {
        int rowsForLatest = 1;
        if (showDimension.get()) rowsForLatest++;
        if (showDistance.get())  rowsForLatest++;
        int extra = (showRecent.get() ? Math.max(0, Math.min(VISIBLE_ROWS, deaths.size()) - 1) : 0);
        return 4 + (rowsForLatest + extra) * 10;
    }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            prevAlive = true;
            return;
        }
        boolean alive = p.getHealth() > 0f && !p.isDeadOrDying();
        if (prevAlive && !alive) {
            // Edge: just died — record at the head and cap.
            DeathRecord rec = new DeathRecord(
                    (int) Math.floor(p.getX()),
                    (int) Math.floor(p.getY()),
                    (int) Math.floor(p.getZ()),
                    mc.level.dimension(),
                    System.currentTimeMillis());
            deaths.addFirst(rec);
            while (deaths.size() > HISTORY_CAP) deaths.removeLast();
        }
        prevAlive = alive;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        int bg = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        int rowY = y + 2;
        if (deaths.isEmpty()) {
            gfx.text(font, "§8no death recorded", x + 2, rowY, 0xFFAAAAAA);
            return;
        }

        // Latest death (full detail).
        DeathRecord latest = deaths.peekFirst();
        gfx.text(font, String.format("☠ %d, %d, %d", latest.x, latest.y, latest.z),
                x + 2, rowY, 0xFFFFE8C8);
        rowY += 10;

        if (showDistance.get() && mc.player != null) {
            double ddx = mc.player.getX() - latest.x;
            double ddy = mc.player.getY() - latest.y;
            double ddz = mc.player.getZ() - latest.z;
            double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            String dimMatch = (mc.level != null
                    && latest.dimension != null
                    && latest.dimension.equals(mc.level.dimension()))
                    ? "" : " §8(other dim)";
            gfx.text(font, String.format("→ %.0f m%s", dist, dimMatch),
                    x + 2, rowY, 0xFFCCCCCC);
            rowY += 10;
        }

        if (showDimension.get() && latest.dimension != null) {
            gfx.text(font, "in " + dimShort(latest.dimension), x + 2, rowY, 0xFF888888);
            rowY += 10;
        }

        // Older entries, if any, when "Show Recent" is enabled.
        if (showRecent.get() && deaths.size() > 1) {
            int shown = 0;
            boolean first = true;
            for (DeathRecord rec : deaths) {
                if (first) { first = false; continue; } // skip latest
                if (shown >= VISIBLE_ROWS - 1) break;
                gfx.text(font, String.format("§8• %d, %d, %d", rec.x, rec.y, rec.z),
                        x + 2, rowY, 0xFF888888);
                rowY += 10;
                shown++;
            }
        }
    }

    @Override
    protected void onDisable() {
        // Keep death history across an enable/disable so the user doesn't lose
        // their recovery markers by accident — only clear on JVM exit.
    }

    private static String dimShort(ResourceKey<Level> dim) {
        if (dim.equals(Level.OVERWORLD)) return "overworld";
        if (dim.equals(Level.NETHER))    return "nether";
        if (dim.equals(Level.END))       return "the end";
        // Fallback: extract the path from the toString — robust across mapping
        // versions where ResourceKey's accessor names have changed.
        String s = dim.toString();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1).replace("]", "").trim() : s;
    }
}
