package dev.kitsune.client.setting;

import java.util.List;

public class ModeSetting extends Setting<String> {
    private final List<String> options;

    public ModeSetting(String name, String defaultValue, List<String> options) {
        super(name, defaultValue);
        this.options = List.copyOf(options);
    }

    public List<String> options() { return options; }

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
