package dev.kitsune.client.util;

import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central keybind conflict detection and management.
 * Scans all registered modules and reports any keybind overlaps.
 */
public final class KeybindManager {
    private KeybindManager() {}

    /**
     * Returns a map of GLFW key code → list of module names bound to that key.
     * Only includes entries with 2+ modules (actual conflicts).
     */
    public static Map<Integer, List<String>> getConflicts() {
        Map<Integer, List<String>> bindings = new HashMap<>();
        for (Module m : ModuleManager.all()) {
            int key = m.keyBind();
            if (key > 0) {
                bindings.computeIfAbsent(key, k -> new ArrayList<>()).add(m.name());
            }
        }
        // Filter to only conflicts
        Map<Integer, List<String>> conflicts = new HashMap<>();
        for (Map.Entry<Integer, List<String>> e : bindings.entrySet()) {
            if (e.getValue().size() > 1) {
                conflicts.put(e.getKey(), e.getValue());
            }
        }
        return conflicts;
    }

    /**
     * Check if a specific key has conflicts.
     */
    public static boolean hasConflict(int glfwKey) {
        if (glfwKey <= 0) return false;
        int count = 0;
        for (Module m : ModuleManager.all()) {
            if (m.keyBind() == glfwKey) count++;
        }
        return count > 1;
    }

    /**
     * Get all modules bound to a specific key.
     */
    public static List<Module> getModulesForKey(int glfwKey) {
        List<Module> result = new ArrayList<>();
        if (glfwKey <= 0) return result;
        for (Module m : ModuleManager.all()) {
            if (m.keyBind() == glfwKey) result.add(m);
        }
        return result;
    }

    /**
     * Get the key name for a GLFW key code (basic mapping).
     */
    public static String getKeyName(int glfwKey) {
        if (glfwKey <= 0) return "None";
        String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(glfwKey, 0);
        if (name != null) return name.toUpperCase();
        // Fallback for special keys
        return switch (glfwKey) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT -> "L-Shift";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT -> "R-Shift";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL -> "L-Ctrl";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL -> "R-Ctrl";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT -> "L-Alt";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT -> "R-Alt";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> "Tab";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> "Space";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER -> "Enter";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE -> "Del";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            default -> "Key" + glfwKey;
        };
    }
}
