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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Records the coordinates where the player most recently died and pins them
 * in a small HUD widget so the player can navigate back to recover items.
 *
 * <p>Detection is purely client-side via edge detection on the local player's
 * health (transition from &gt;0 to &lt;=0). No packets sent, no API calls —
 * server-safe by definition.
 *
 * <p>The recorded coords persist for the life of the JVM. Switching dimensions
 * or worlds doesn't clear them; the dimension name is captured alongside the
 * coords so the user can tell whether they belong to the current world.
 */
public class DeathCoordsHudModule extends Module implements HudWidget {

    private final BooleanSetting showDimension = addSetting(new BooleanSetting("Show Dimension", true));
    private final BooleanSetting showDistance  = addSetting(new BooleanSetting("Show Distance",  true));
    private final SliderSetting  bgOpacity     = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent        = addSetting(new ColorSetting("Accent", 0xFFE8472A));

    private boolean prevAlive = true;
    private boolean haveDeath = false;
    private int dx, dy, dz;
    private ResourceKey<Level> deathDimension = null;

    public DeathCoordsHudModule() {
        super("Death Coords", "Pins your last death coordinates so you can recover", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "death_coords"; }
    @Override public String displayName() { return "Death"; }
    @Override public int widgetWidth()    { return 130; }
    @Override public int widgetHeight() {
        int rows = 1;
        if (showDimension.get()) rows++;
        if (showDistance.get())  rows++;
        return 4 + rows * 10;
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
            // Edge: just died.
            dx = (int) Math.floor(p.getX());
            dy = (int) Math.floor(p.getY());
            dz = (int) Math.floor(p.getZ());
            deathDimension = mc.level.dimension();
            haveDeath = true;
        }
        prevAlive = alive;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        int bg = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        int rowY = y + 2;
        if (!haveDeath) {
            gfx.drawString(font, "§8no death recorded", x + 2, rowY, 0xFFAAAAAA, false);
            return;
        }
        gfx.drawString(font, String.format("☠ %d, %d, %d", dx, dy, dz),
                x + 2, rowY, 0xFFFFE8C8, false);
        rowY += 10;

        if (showDistance.get() && mc.player != null) {
            double ddx = mc.player.getX() - dx;
            double ddy = mc.player.getY() - dy;
            double ddz = mc.player.getZ() - dz;
            double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            String dimMatch = (mc.level != null
                    && deathDimension != null
                    && deathDimension.equals(mc.level.dimension()))
                    ? "" : " §8(other dim)";
            gfx.drawString(font, String.format("→ %.0f m%s", dist, dimMatch),
                    x + 2, rowY, 0xFFCCCCCC, false);
            rowY += 10;
        }

        if (showDimension.get() && deathDimension != null) {
            gfx.drawString(font, "in " + dimShort(deathDimension), x + 2, rowY, 0xFF888888, false);
        }
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
