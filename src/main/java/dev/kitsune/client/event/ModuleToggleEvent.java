package dev.kitsune.client.event;

import dev.kitsune.client.module.Module;

public final class ModuleToggleEvent {
    public final Module module;
    public final boolean enabled;

    public ModuleToggleEvent(Module module, boolean enabled) {
        this.module = module;
        this.enabled = enabled;
    }
}
