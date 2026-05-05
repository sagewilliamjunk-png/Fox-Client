package dev.kitsune.client.module.render;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Renders coloured 4-bar signal icons in the player tab list, similar to the
 * bars shown by Lunar Client. Disabled by default — players who prefer the
 * clean numeric ms display from {@link dev.kitsune.client.module.hud.NumericPingModule}
 * don't see bars unless they explicitly turn this on.
 *
 * <p>Bars are drawn at the right edge of the ping icon slot:
 * <pre>
 *   ▓ ▓ ▓ ▓   ← all 4 bars lit = &lt;50 ms (green)
 *   ▓ ▓ ▓ ░   ← 3 bars = 50–150 ms (lime)
 *   ▓ ▓ ░ ░   ← 2 bars = 150–300 ms (yellow)
 *   ▓ ░ ░ ░   ← 1 bar  = &gt;300 ms  (red)
 * </pre>
 *
 * <p>When both this module and {@link dev.kitsune.client.module.hud.NumericPingModule}
 * are enabled, the number is rendered to the left of the bars. Enabling
 * {@code hideNumbers} suppresses the numeric display entirely.
 *
 * <p>Server-safe: reads only data the client already has
 * ({@code PlayerInfo#getLatency()}). No packets, no range extension.
 */
public class PingBarsModule extends Module {

    /** Total pixel width of the four-bar graphic (4×2 px bars + 3×1 px gaps). */
    public static final int BARS_TOTAL_W = 11;

    private final BooleanSetting hideNumbers = addSetting(
            new BooleanSetting("Hide Numbers", false));

    public PingBarsModule() {
        super("Ping Bars",
                "Shows coloured signal bars in the tab list alongside the numeric ping",
                Category.RENDER);
        // Off by default: Numeric Ping is the primary display; players opt in to bars.
        setEnabled(false);
    }

    /** Whether the ms number from NumericPingModule should be hidden. */
    public boolean hideNumbers() {
        return hideNumbers.get();
    }

    /**
     * Render four signal bars right-aligned so their right edge touches
     * {@code rightX}. Bars increase in height left→right (2/4/6/8 px) and
     * are bottom-anchored at {@code y + 8}.
     *
     * @param gfx     GuiGraphics to draw into
     * @param rightX  right edge of the drawing area
     * @param y       top-left Y of the 8-px-tall ping icon slot
     * @param ms      latency in milliseconds from {@code PlayerInfo#getLatency()}
     */
    public void drawBars(GuiGraphics gfx, int rightX, int y, int ms) {
        int litCount = litBars(ms);
        int litColor = litColor(litCount);
        int baseY    = y + 8; // bottom baseline of the 8-px icon slot

        // Each bar: 2 px wide, 1 px gap → stride = 3 px
        int[] heights = {2, 4, 6, 8};
        for (int i = 0; i < 4; i++) {
            int bx = rightX - BARS_TOTAL_W + i * 3;
            int bh = heights[i];
            int by = baseY - bh;
            int color = (i < litCount) ? litColor : 0xFF333333;
            gfx.fill(bx, by, bx + 2, by + bh, color);
        }
    }

    /** Number of bars to light up for the given ping. */
    private static int litBars(int ms) {
        if (ms < 50)  return 4;
        if (ms < 150) return 3;
        if (ms < 300) return 2;
        return 1;
    }

    /** Colour for the lit portion of the bars. */
    private static int litColor(int litCount) {
        return switch (litCount) {
            case 4 -> 0xFF55FF55; // green  (excellent)
            case 3 -> 0xFFB8E834; // lime   (good)
            case 2 -> 0xFFFFCC00; // yellow (fair)
            default -> 0xFFFF4040; // red   (poor)
        };
    }
}
