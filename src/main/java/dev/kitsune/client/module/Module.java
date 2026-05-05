package dev.kitsune.client.module;

import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.event.ModuleToggleEvent;
import dev.kitsune.client.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for every Kitsune module (feature you can toggle from the ClickGUI).
 * Subclasses override the lifecycle hooks and register settings in their constructor.
 *
 * <p>This is intentionally minimal — no reflection magic, no annotations. Modules
 * are registered explicitly in {@link ModuleManager#init()}.
 */
public abstract class Module {
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting<?>> settings = new ArrayList<>();

    private boolean enabled;
    private int keyBind; // GLFW key code, -1 = unbound

    protected Module(String name, String description, Category category) {
        this(name, description, category, -1);
    }

    protected Module(String name, String description, Category category, int defaultKeyBind) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keyBind = defaultKeyBind;
    }

    // ---- identity ----
    public String name() { return name; }
    public String description() { return description; }
    public Category category() { return category; }

    // ---- settings ----
    protected <S extends Setting<?>> S addSetting(S setting) {
        settings.add(setting);
        return setting;
    }
    public List<Setting<?>> settings() { return settings; }

    // ---- state ----
    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean value) {
        if (this.enabled == value) return;
        this.enabled = value;
        try {
            if (value) onEnable();
            else onDisable();
        } finally {
            EventBus.post(new ModuleToggleEvent(this, value));
        }
    }

    public void toggle() { setEnabled(!enabled); }

    /**
     * Update the underlying enabled flag without firing onEnable/onDisable
     * or posting a toggle event. Reserved for adapters (e.g. LegacyFeatureModule)
     * whose source-of-truth lives in another registry, so the parent state
     * just needs to mirror it.
     */
    protected final void setEnabledStateSilently(boolean value) {
        this.enabled = value;
    }

    // ---- keybind ----
    public int keyBind() { return keyBind; }
    public void setKeyBind(int glfwKey) { this.keyBind = glfwKey; }

    // ---- lifecycle hooks (override as needed) ----
    protected void onEnable() {}
    protected void onDisable() {}
    public void onTick() {}
}
