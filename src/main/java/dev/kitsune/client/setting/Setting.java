package dev.kitsune.client.setting;

/**
 * Base class for all module settings. Subclasses specialize on the value type
 * (boolean, double, enum-string, color, keybind). Intentionally not generic
 * at the field level beyond {@code T value} — keeps the ClickGUI render path
 * a simple {@code instanceof} dispatch.
 */
public abstract class Setting<T> {
    private final String name;
    private final T defaultValue;
    private T value;

    protected Setting(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String name() { return name; }
    public T get() { return value; }
    public void set(T newValue) { this.value = newValue; }
    public T defaultValue() { return defaultValue; }
    public void reset() { this.value = defaultValue; }

    /** Discriminator used by {@link SettingManager} for JSON (de)serialization. */
    public abstract String type();
}
