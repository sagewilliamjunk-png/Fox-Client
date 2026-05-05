package dev.kitsune.client.setting;

/**
 * Free-form text setting. Rendered as a single-line {@link net.minecraft.client.gui.components.EditBox}
 * in the ClickGUI panel. Stored verbatim in the profile JSON as a plain string.
 *
 * <p>Used by modules that need user-entered values that don't fit into booleans,
 * sliders, or mode choices — e.g. comma-separated keyword lists, custom label text,
 * server filter regex.
 */
public class StringSetting extends Setting<String> {
    /** Soft cap on input length so the UI doesn't explode. */
    public static final int MAX_LENGTH = 256;

    public StringSetting(String name, String defaultValue) {
        super(name, defaultValue == null ? "" : defaultValue);
    }

    @Override
    public void set(String newValue) {
        if (newValue == null) newValue = "";
        if (newValue.length() > MAX_LENGTH) newValue = newValue.substring(0, MAX_LENGTH);
        super.set(newValue);
    }

    @Override
    public String type() { return "string"; }
}
