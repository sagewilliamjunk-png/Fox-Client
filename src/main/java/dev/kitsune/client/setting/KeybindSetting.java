package dev.kitsune.client.setting;

/** GLFW key code, -1 = unbound. Used both for module keybinds and in-setting hotkeys. */
public class KeybindSetting extends Setting<Integer> {
    public KeybindSetting(String name, int defaultGlfwKey) {
        super(name, defaultGlfwKey);
    }

    @Override
    public String type() { return "keybind"; }
}
