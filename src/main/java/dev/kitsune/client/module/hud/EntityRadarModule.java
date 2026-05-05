package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/**
 * Circular radar HUD widget — shows nearby players (red dots) and hostile mobs
 * (orange dots) relative to the player. The radar rotates so the player's
 * current facing direction is always "up".
 *
 * Only uses data the client already has (players/entities the server has sent).
 * Zero server-side advantage — completely safe on all servers.
 */
public class EntityRadarModule extends Module implements HudWidget {

    // Rebuild the entity list every other tick to avoid scanning every frame.
    private static final int CACHE_TICKS = 2;

    private final SliderSetting  rangeBlocks = addSetting(new SliderSetting("Range", 64, 16, 128, 8));
    private final SliderSetting  sizePixels  = addSetting(new SliderSetting("Size",  50, 30, 100, 5));
    private final BooleanSetting showMobs    = addSetting(new BooleanSetting("Show Mobs",    true));
    private final BooleanSetting showCompass = addSetting(new BooleanSetting("Compass Lines", true));

    /** Lightweight cached snapshot: screen-relative offset + type flag. */
    private record RadarEntry(double dx, double dz, boolean isPlayer) {}

    private List<RadarEntry> cache = new ArrayList<>();
    private int tickCount = 0;

    public EntityRadarModule() {
        super("Entity Radar",
              "Minimap-style radar showing nearby players and mobs. Rotates with your facing direction.",
              Category.HUD);
        HudManager.register(this);
    }

    // ---- HudWidget --------------------------------------------------------

    @Override public String widgetId()    { return "entity_radar"; }
    @Override public String displayName() { return "Radar"; }
    @Override public int widgetWidth()    { return sizePixels.get().intValue() * 2 + 8; }
    @Override public int widgetHeight()   { return sizePixels.get().intValue() * 2 + 8; }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    // ---- Module -----------------------------------------------------------

    @Override
    public void onTick() {
        // Throttle — rebuild the list every CACHE_TICKS game ticks.
        if (++tickCount % CACHE_TICKS != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            cache = new ArrayList<>();
            return;
        }

        LocalPlayer self = mc.player;
        double px = self.getX();
        double pz = self.getZ();
        double maxR = rangeBlocks.get();
        double maxR2 = maxR * maxR;

        List<RadarEntry> next = new ArrayList<>();

        // Players — already separated by the server; always available client-side.
        for (var p : mc.level.players()) {
            if (p == self) continue;
            double dx = p.getX() - px;
            double dz = p.getZ() - pz;
            if (dx * dx + dz * dz <= maxR2) {
                next.add(new RadarEntry(dx, dz, true));
            }
        }

        // Mobs — iterate the render-visible entity set (client-side only).
        if (showMobs.get()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof Mob)) continue;
                double dx = e.getX() - px;
                double dz = e.getZ() - pz;
                if (dx * dx + dz * dz <= maxR2) {
                    next.add(new RadarEntry(dx, dz, false));
                }
            }
        }

        cache = next;
    }

    // ---- rendering --------------------------------------------------------

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        int r  = sizePixels.get().intValue(); // radar radius in pixels
        int cx = x + r + 4;                   // center pixel X
        int cy = y + r + 4;                   // center pixel Y

        // 1. Filled circle background
        drawFilledCircle(gfx, cx, cy, r, 0xBB0D0D14);

        // 2. Compass cross-hairs (faint, clipped to circle)
        if (showCompass.get()) {
            int lineColor = 0x33FFFFFF;
            gfx.fill(cx - 1, cy - r + 2, cx + 1, cy + r - 2, lineColor); // N–S
            gfx.fill(cx - r + 2, cy - 1, cx + r - 2, cy + 1, lineColor); // E–W
        }

        // 3. Entity dots — rotate by player yaw so forward = up.
        //
        //   In Minecraft: yaw=0 → south (+Z), yaw=90 → west (–X).
        //   look vector: fx = sin(–yaw), fz = cos(–yaw) = –sin(yaw), cos(yaw)
        //
        //   To rotate world (dx, dz) so that "forward" maps to screen "up":
        //     screenX =  dx * cos(yaw) + dz * sin(yaw)
        //     screenY =  dx * sin(yaw) – dz * cos(yaw)   (negative = up)
        double yawRad = Math.toRadians(player.getYRot());
        double cosY = Math.cos(yawRad);
        double sinY = Math.sin(yawRad);
        double scale = r / rangeBlocks.get();
        int innerR = r - 3; // stay inside the border ring

        for (RadarEntry e : cache) {
            double sx =  e.dx() * cosY + e.dz() * sinY;
            double sy =  e.dx() * sinY - e.dz() * cosY;

            int dotX = cx + (int)(sx * scale);
            int dotY = cy + (int)(sy * scale);

            // Clip to circle interior
            int ddx = dotX - cx, ddy = dotY - cy;
            if (ddx * ddx + ddy * ddy > innerR * innerR) continue;

            // Players = warm red, mobs = amber
            int color = e.isPlayer() ? 0xFFFF4444 : 0xFFFFAA22;
            gfx.fill(dotX - 1, dotY - 1, dotX + 1, dotY + 1, color);
        }

        // 4. Self dot (white 2×2) + forward indicator (1-pixel line above)
        gfx.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
        gfx.fill(cx,     cy - 4, cx + 1, cy - 1, 0xCCFFFFFF); // forward tick

        // 5. Circular border + second inner ring for depth
        drawCircleOutline(gfx, cx, cy, r,     0xFF22222E);
        drawCircleOutline(gfx, cx, cy, r - 1, 0xFF333340);

        // 6. Tiny compass label — "F" for Forward at the top of the widget
        if (showCompass.get()) {
            Font font = mc.font;
            gfx.drawString(font, "F", cx - 2, y + 2, 0x88FFFFFF, false);
        }
    }

    // ---- circle primitives ------------------------------------------------

    /**
     * Fills a solid circle using horizontal scan lines. O(r) fill calls —
     * fast enough for any practical radar size (r ≤ 100px).
     */
    private static void drawFilledCircle(GuiGraphics gfx, int cx, int cy, int r, int argb) {
        int r2 = r * r;
        for (int row = -r; row <= r; row++) {
            int half = (int) Math.sqrt(r2 - row * row);
            gfx.fill(cx - half, cy + row, cx + half, cy + row + 1, argb);
        }
    }

    /**
     * Draws a 1-pixel-wide circular outline by filling only the outermost ring
     * (between radius r and r-1) using horizontal scan pairs.
     */
    private static void drawCircleOutline(GuiGraphics gfx, int cx, int cy, int r, int argb) {
        int r2 = r * r;
        int ri2 = (r - 1) * (r - 1);
        for (int row = -r; row <= r; row++) {
            int outer = (int) Math.sqrt(Math.max(0, r2  - row * row));
            int inner = (int) Math.sqrt(Math.max(0, ri2 - row * row));
            if (outer > inner) {
                gfx.fill(cx - outer, cy + row, cx - inner, cy + row + 1, argb);
                gfx.fill(cx + inner, cy + row, cx + outer, cy + row + 1, argb);
            }
        }
    }
}
