package dev.kitsune.client.event;

/** Fired when a raw key is pressed (before modules react to their bindings). */
public final class KeyInputEvent {
    public final int glfwKey;
    public final int scancode;
    public final int action;
    public final int modifiers;

    public KeyInputEvent(int glfwKey, int scancode, int action, int modifiers) {
        this.glfwKey = glfwKey;
        this.scancode = scancode;
        this.action = action;
        this.modifiers = modifiers;
    }
}
