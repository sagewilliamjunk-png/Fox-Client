package dev.kitsune.client.setting;

/** Packed ARGB color, stored as an {@code int}. */
public class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, int defaultArgb) {
        super(name, defaultArgb);
    }

    @Override
    public String type() { return "color"; }
}
