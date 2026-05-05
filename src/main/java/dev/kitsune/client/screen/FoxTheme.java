package dev.kitsune.client.screen;

/**
 * Color palette and shared tokens for the Fox Client UI.
 * Centralized so re-theming is one file's worth of edits.
 */
public final class FoxTheme {
    private FoxTheme() {}

    // Primary palette — warm fox tones
    public static final int FOX_ORANGE  = 0xFFFFA552;
    public static final int FOX_DEEP    = 0xFFE07020;
    public static final int FOX_CREAM   = 0xFFFFE6C8;
    public static final int FOX_FOREST  = 0xFF294834;
    public static final int FOX_BLACK   = 0xFF1A130C;
    /** Dark bark for outlines/shadows on cream surfaces. */
    public static final int BARK        = 0xFF3A2410;

    // Semantic
    public static final int TEXT_PRIMARY   = FOX_CREAM;
    public static final int TEXT_HEADING   = FOX_ORANGE;
    public static final int TEXT_DANGER    = 0xFFFF6060;
    public static final int TEXT_MUTED     = 0xFF888888;
    public static final int PANEL_BG       = 0xC0000000;
    public static final int PANEL_BORDER   = FOX_DEEP;

    /** Capitalize the first letter of a string for display purposes. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
