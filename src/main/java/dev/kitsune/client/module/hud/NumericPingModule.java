package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;

/**
 * Replaces the 5-bar signal icon in the player tab list with a numeric ping
 * (e.g. "42ms"). Lunar/Feather staple — useful for spotting laggy players in
 * PvP without squinting at the bars.
 *
 * <p>Pure render swap. The mixin reads {@code mc.options} latency from
 * {@code PlayerInfo.getLatency()} which is the same value vanilla uses to pick
 * the bar texture. No packets, no extra requests — server-safe.
 *
 * <p>Tab columns get a tiny bit wider with text than with the 11px bars; the
 * mixin draws inside the original icon rect so column geometry isn't disturbed.
 */
public class NumericPingModule extends Module {

    /** When true, color the number by ping range (green/yellow/orange/red). */
    private final BooleanSetting colorize = addSetting(new BooleanSetting("Colorize", true));
    /** Show "ms" suffix vs. just the number. */
    private final BooleanSetting suffix   = addSetting(new BooleanSetting("Show 'ms'", true));
    /** Cap displayed value (anything higher renders as "999+"). Keeps glyphs from
     *  overrunning when a player is timing out at 30s+ ping. */
    private final SliderSetting  cap      = addSetting(new SliderSetting("Display Cap", 999, 100, 9999, 1));
    /** Color used when {@link #colorize} is off. */
    private final ColorSetting   plain    = addSetting(new ColorSetting("Plain Color", 0xFFCCCCCC));

    public NumericPingModule() {
        super("Numeric Ping", "Replaces tab-list ping bars with a number in ms", Category.HUD);
    }

    public boolean colorize()      { return colorize.get(); }
    public boolean withSuffix()    { return suffix.get(); }
    public int displayCap()        { return cap.get().intValue(); }
    public int plainColor()        { return plain.get(); }

    /** Heat-map for ping. Tuned for typical Minecraft ranges. */
    public int colorFor(int ms) {
        if (!colorize.get()) return plain.get();
        if (ms < 60)  return 0xFF55FF55; // green
        if (ms < 120) return 0xFFB8E834; // lime
        if (ms < 200) return 0xFFFFB400; // amber
        if (ms < 350) return 0xFFFF7028; // orange
        return 0xFFFF4040;               // red
    }
}
