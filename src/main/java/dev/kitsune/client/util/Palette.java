package dev.kitsune.client.util;

/**
 * Shared ARGB constants for HUD widgets. These mirror the accent defaults the
 * widgets shipped with — referencing them here instead of repeating raw hex
 * keeps the palette consistent and greppable. (Menu/screen colours live in
 * {@link dev.kitsune.client.screen.FoxTheme}.)
 */
public final class Palette {

    private Palette() {}

    // ---- accents ----
    public static final int ACCENT_CYAN   = 0xFF44CCCC;
    public static final int ACCENT_ORANGE = 0xFFE87722;
    public static final int ACCENT_GREEN  = 0xFF44DD88;
    public static final int ACCENT_RED    = 0xFFE8472A;
    public static final int ACCENT_GOLD   = 0xFFFFAA33;
    public static final int ACCENT_MINT   = 0xFF44CC88;
    public static final int ACCENT_BLUE   = 0xFF44AAFF;
    public static final int ACCENT_PERIWINKLE = 0xFF8C8CFF;

    // ---- text ----
    public static final int TEXT_WHITE  = 0xFFFFFFFF;
    public static final int TEXT_LIGHT  = 0xFFCCCCCC;
    public static final int TEXT_MUTED  = 0xFFAAAAAA;
    public static final int TEXT_DIM    = 0xFF888888;
    public static final int TEXT_GOLD   = 0xFFDDAA55;

    // ---- status ----
    public static final int GOOD = 0xFF55FF55;
    public static final int WARN = 0xFFFFFF55;
    public static final int BAD  = 0xFFFF5555;

    // ---- panel ----
    /** The hardcoded background some pre-refactor widgets used (alpha 0x90 black). */
    public static final int PANEL_BG_LEGACY = 0x90000000;

    // ---- mouse-button split colors (CPS / Keystrokes) ----
    public static final int LMB_BLUE = 0xFF4090FF;
    public static final int RMB_RED  = 0xFFFF5050;
}
