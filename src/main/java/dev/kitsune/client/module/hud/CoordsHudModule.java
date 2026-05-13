package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Draggable coordinate / facing-direction HUD widget.
 * Shows XYZ, block position, chunk, nether conversion, direction, and biome.
 */
public class CoordsHudModule extends Module implements HudWidget {

    private final BooleanSetting showDirection  = addSetting(new BooleanSetting("Show Direction", true));
    private final BooleanSetting showBlock       = addSetting(new BooleanSetting("Show Block Pos", true));
    private final BooleanSetting showChunk       = addSetting(new BooleanSetting("Show Chunk", false));
    private final BooleanSetting showNether      = addSetting(new BooleanSetting("Show Nether Equiv", true));
    private final BooleanSetting showBiome       = addSetting(new BooleanSetting("Show Biome", false));
    private final BooleanSetting facingArrow     = addSetting(new BooleanSetting("Facing Arrow", true));
    private final BooleanSetting compactMode     = addSetting(new BooleanSetting("Compact Mode", false));
    private final SliderSetting  bgOpacity       = addSetting(new SliderSetting("BG Opacity", 0.55, 0.0, 1.0, 0.05));
    private final ModeSetting    precision       = addSetting(new ModeSetting("Precision", "1 decimal",
            List.of("Integer", "1 decimal", "3 decimal")));
    private final ModeSetting    accentColor     = addSetting(new ModeSetting("Accent", "Teal",
            List.of("Teal", "Orange", "Pink", "Green", "White")));

    public CoordsHudModule() {
        super("Coords HUD", "Shows coordinates, direction, and biome", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "coords"; }
    @Override public String displayName() { return "Coords"; }

    @Override
    public int widgetWidth() {
        if (compactMode.get()) return 110;
        boolean showN = showNether.get();
        return showN ? 150 : 130;
    }

    @Override
    public int widgetHeight() {
        if (compactMode.get()) return 14;
        int rows = 1; // XYZ always
        if (showBlock.get())     rows++;
        if (showChunk.get())     rows++;
        if (showNether.get())    rows++;
        if (showDirection.get()) rows++;
        if (showBiome.get())     rows++;
        return rows * 10 + 8;
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        int w = widgetWidth();
        int h = widgetHeight();
        int bgAlpha = (int)(bgOpacity.get() * 255) << 24;
        int accent = accentArgb();

        // Background + accent bar
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bgAlpha | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent);

        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        boolean inNether = mc.level != null
                && mc.level.dimension().equals(Level.NETHER);
        boolean inOverworld = mc.level != null
                && mc.level.dimension().equals(Level.OVERWORLD);

        // ---- Compact mode: one-liner ----
        if (compactMode.get()) {
            String line = fmtCoord(px) + " / " + fmtCoord(py) + " / " + fmtCoord(pz);
            if (showDirection.get()) {
                float yaw = normalizeYaw(player.getYRot());
                line += "  " + getCardinal(yaw) + (facingArrow.get() ? arrowFor(yaw) : "");
            }
            gfx.text(font, line, x + 2, y + 3, 0xFFFFFFFF);
            return;
        }

        // ---- Full mode ----
        int curY = y + 2;
        int rowH = 10;

        // XYZ
        String xyz = "XYZ  " + fmtCoord(px) + "  " + fmtCoord(py) + "  " + fmtCoord(pz);
        gfx.text(font, xyz, x + 2, curY, 0xFFDDDDDD);
        curY += rowH;

        // Block position
        if (showBlock.get()) {
            String block = "Block " + player.getBlockX() + " / " + player.getBlockY() + " / " + player.getBlockZ();
            gfx.text(font, block, x + 2, curY, 0xFFAAAAAA);
            curY += rowH;
        }

        // Chunk position
        if (showChunk.get()) {
            int cx2 = player.getBlockX() >> 4;
            int cz2 = player.getBlockZ() >> 4;
            gfx.text(font, "Chunk " + cx2 + " / " + cz2, x + 2, curY, 0xFF888888);
            curY += rowH;
        }

        // Nether / overworld conversion
        if (showNether.get()) {
            String label;
            double nx, nz;
            if (inNether) {
                label = "Ovw";
                nx = px * 8;
                nz = pz * 8;
            } else if (inOverworld) {
                label = "Neth";
                nx = px / 8;
                nz = pz / 8;
            } else {
                label = null;
                nx = 0; nz = 0;
            }
            if (label != null) {
                String conv = label + " " + fmtCoord(nx) + " / " + fmtCoord(nz);
                gfx.text(font, conv, x + 2, curY, accent);
                curY += rowH;
            }
        }

        // Direction / facing
        if (showDirection.get()) {
            float yaw = normalizeYaw(player.getYRot());
            String dir = getCardinal(yaw);
            String arrow = facingArrow.get() ? " " + arrowFor(yaw) : "";
            String pitchStr = "";
            float pitch = player.getXRot();
            if (pitch > 30) pitchStr = " \u2193";       // looking down
            else if (pitch < -30) pitchStr = " \u2191"; // looking up
            String facing = dir + arrow + pitchStr + " (" + Math.round(yaw) + "\u00b0)";
            gfx.text(font, facing, x + 2, curY, accent);
            curY += rowH;
        }

        // Biome
        if (showBiome.get() && mc.level != null) {
            try {
                var key = mc.level.getBiome(player.blockPosition()).unwrapKey();
                if (key.isPresent()) {
                    String s = key.get().toString();
                    int colon = s.indexOf(':');
                    int end   = s.indexOf(']', colon);
                    String bn = (colon > 0 && end > colon)
                            ? s.substring(colon + 1, end) : s;
                    // Capitalize first letter, replace underscores
                    bn = bn.replace('_', ' ');
                    if (!bn.isEmpty()) bn = Character.toUpperCase(bn.charAt(0)) + bn.substring(1);
                    gfx.text(font, "Biome " + bn, x + 2, curY, 0xFF88BBAA);
                }
            } catch (Throwable ignored) {}
        }
    }

    // ---- helpers ----

    private String fmtCoord(double v) {
        return switch (precision.get()) {
            case "Integer"   -> String.valueOf((int) v);
            case "3 decimal" -> String.format("%.3f", v);
            default          -> String.format("%.1f", v);
        };
    }

    private int accentArgb() {
        return switch (accentColor.get()) {
            case "Orange" -> 0xFFE87722;
            case "Pink"   -> 0xFFFF88CC;
            case "Green"  -> 0xFF44DD88;
            case "White"  -> 0xFFDDDDDD;
            default       -> 0xFF44CCCC; // Teal
        };
    }

    private static float normalizeYaw(float yaw) {
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;
        return yaw;
    }

    private static String arrowFor(float yaw) {
        if (yaw >= 337.5 || yaw < 22.5)  return "\u2193"; // S
        if (yaw < 67.5)                   return "\u2199";
        if (yaw < 112.5)                  return "\u2190"; // W
        if (yaw < 157.5)                  return "\u2196";
        if (yaw < 202.5)                  return "\u2191"; // N
        if (yaw < 247.5)                  return "\u2197";
        if (yaw < 292.5)                  return "\u2192"; // E
        return "\u2198";
    }

    private static String getCardinal(float yaw) {
        if (yaw >= 337.5 || yaw < 22.5)   return "S";
        if (yaw >= 22.5  && yaw < 67.5)   return "SW";
        if (yaw >= 67.5  && yaw < 112.5)  return "W";
        if (yaw >= 112.5 && yaw < 157.5)  return "NW";
        if (yaw >= 157.5 && yaw < 202.5)  return "N";
        if (yaw >= 202.5 && yaw < 247.5)  return "NE";
        if (yaw >= 247.5 && yaw < 292.5)  return "E";
        if (yaw >= 292.5 && yaw < 337.5)  return "SE";
        return "?";
    }
}
