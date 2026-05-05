package dev.kitsune.client.module;

/**
 * Top-level grouping for modules in the Kitsune ClickGUI. Each category
 * gets its own draggable panel. Order here is the display order.
 *
 * {@code FAVORITES} is a virtual category — no module is registered under it.
 * The ClickGUI Panel treats it specially, pulling from {@link ModuleFavorites}.
 */
public enum Category {
    FAVORITES("Favorites", "★"),  // ★ — virtual, populated from ModuleFavorites
    COMBAT("Combat", "⚔"),       // ⚔
    MOVEMENT("Movement", "➡"),   // ➡
    PLAYER("Player", "☺"),       // ☺
    RENDER("Render", "✨"),       // ✨
    HUD("HUD", "▣"),            // ▣
    CHAT("Chat", "✉"),          // ✉
    MISC("Misc", "⚙"),          // ⚙
    COSMETIC("Cosmetic", "♥");  // ♥

    private final String displayName;
    private final String icon;

    Category(String displayName, String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String displayName() {
        return displayName;
    }

    public String icon() {
        return icon;
    }

    /** Display name with icon prefix. */
    public String displayNameWithIcon() {
        return icon + " " + displayName;
    }
}
