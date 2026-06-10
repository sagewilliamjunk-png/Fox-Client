package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Compass HUD — a Lunar-style sliding cardinal strip centred on the player's
 * yaw, or a compact "NE (-45°)" text readout.
 *
 * <p>Reads only the local player's rotation — trivially server-safe.
 */
public class CompassHudModule extends BaseHudModule {

    /** Cardinal labels at their Minecraft yaw angle (yaw 0 = south). */
    private static final String[] LABELS = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };

    private final ModeSetting    style       = addSetting(new ModeSetting("Style", "Strip",
            List.of("Strip", "Text")));
    private final BooleanSetting showDegrees = addSetting(new BooleanSetting("Show Degrees", true));

    public CompassHudModule() {
        super("Compass", "Sliding cardinal-direction strip centred on your view", Category.HUD,
                "compass", "Compass");
        useStandardPanel(0.50, Palette.ACCENT_CYAN);
        useTextColor();
    }

    private boolean strip() { return "Strip".equals(style.get()); }

    @Override public int widgetWidth()  { return strip() ? 140 : (showDegrees.get() ? 76 : 36); }
    @Override public int widgetHeight() { return strip() ? 18 : 14; }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        drawPanel(gfx, x, y, w, h);
        if (p == null) return;

        float yaw = normalizeYaw(p.getYRot());

        if (!strip()) {
            String s = cardinalFor(yaw);
            if (showDegrees.get()) s += String.format(" (%d°)", Math.round(yaw));
            gfx.text(font, s, x + 2, y + 3, textArgb());
            return;
        }

        // Sliding strip: labels live every 45°; a label whose angular distance
        // from the current yaw is within ±90° maps linearly onto the strip.
        int cx = x + w / 2;
        float pxPerDeg = (w - 8) / 180f;
        for (int i = 0; i < LABELS.length; i++) {
            float labelYaw = i * 45f;
            float d = angleDelta(labelYaw, yaw);
            if (Math.abs(d) > 90f) continue;
            int lx = cx + Math.round(d * pxPerDeg);
            String label = LABELS[i];
            int lw = font.width(label);
            boolean major = label.length() == 1;
            // Fade towards the edges so labels don't pop in/out harshly.
            float edge = 1f - Math.min(1f, Math.abs(d) / 90f);
            int alpha = (int) (80 + 175 * edge);
            int base = major ? textArgb() : Palette.TEXT_MUTED;
            int color = (alpha << 24) | (base & 0x00FFFFFF);
            gfx.text(font, label, lx - lw / 2, y + 5, color);
        }
        // Centre marker: small accent tick above the strip.
        gfx.fill(cx, y, cx + 1, y + 3, accentArgb());

        if (showDegrees.get()) {
            String deg = Math.round(yaw) + "°";
            gfx.text(font, deg, x + w - font.width(deg) - 2, y + 5, Palette.TEXT_DIM);
        }
    }

    private static float normalizeYaw(float yaw) {
        yaw = yaw % 360f;
        if (yaw < 0) yaw += 360f;
        return yaw;
    }

    /** Signed shortest angular distance a→b in degrees, in [-180, 180). */
    private static float angleDelta(float a, float b) {
        float d = (a - b) % 360f;
        if (d < -180f) d += 360f;
        if (d >= 180f) d -= 360f;
        return d;
    }

    private static String cardinalFor(float yaw) {
        int idx = Math.round(yaw / 45f) % 8;
        return LABELS[idx];
    }
}
