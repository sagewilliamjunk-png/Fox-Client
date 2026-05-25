package dev.kitsune.client.setting;

import java.util.ArrayList;
import java.util.List;

public class ModeSetting extends Setting<String> {
    // Mutable so {@link #setOptions} can swap the list at runtime — required by
    // modules whose option set is data-driven (e.g. CapesModule populating the
    // user's owned-cape list as the CosmeticRegistry finishes loading).
    private List<String> options;

    public ModeSetting(String name, String defaultValue, List<String> options) {
        super(name, defaultValue);
        this.options = List.copyOf(options);
    }

    public List<String> options() { return options; }

    /**
     * Replace the option list. If the current value isn't in the new list,
     * resets to the first available option (or the previously-saved default).
     * Defensive against null / empty input — falls back to a single "(none)"
     * entry so the ModeSetting always has at least one valid choice.
     */
    public void setOptions(List<String> newOptions) {
        if (newOptions == null || newOptions.isEmpty()) {
            this.options = List.of("(none)");
            super.set("(none)");
            return;
        }
        this.options = List.copyOf(newOptions);
        String current = get();
        if (!this.options.contains(current)) {
            super.set(this.options.get(0));
        }
    }

    @Override
    public void set(String newValue) {
        if (options.contains(newValue)) super.set(newValue);
    }

    public void cycle() {
        int idx = options.indexOf(get());
        set(options.get((idx + 1) % options.size()));
    }

    @Override
    public String type() { return "mode"; }
}
