package dev.kitsune.client.hud;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Read-only "ghost" {@link HudWidget}s that represent vanilla HUD elements
 * (hotbar, health, hunger, air, XP bar) inside the {@link HudEditorScreen}.
 *
 * <p>These widgets render nothing of their own — the actual draw is still
 * performed by vanilla {@code Gui}. The mixin {@code GuiVanillaHudMixin}
 * reads {@link HudManager#vanillaOffset(String, int, int)} and translates
 * the pose stack so the vanilla element ends up at the user-chosen position.
 *
 * <p>The proxies appear in {@link HudEditorScreen} like any other widget so
 * the user can drag them around.
 */
public final class VanillaHudProxies {
    private VanillaHudProxies() {}

    public static final String HOTBAR     = "vanilla.hotbar";
    public static final String HEALTH     = "vanilla.health";
    public static final String FOOD       = "vanilla.food";
    public static final String AIR        = "vanilla.air";
    public static final String EXPERIENCE = "vanilla.experience";

    public static void registerAll() {
        HudManager.register(new Proxy(HOTBAR,     "Hotbar",       182, 22));
        HudManager.register(new Proxy(HEALTH,     "Health Bar",    81, 9));
        HudManager.register(new Proxy(FOOD,       "Hunger Bar",    81, 9));
        HudManager.register(new Proxy(AIR,        "Air Bubbles",   81, 9));
        HudManager.register(new Proxy(EXPERIENCE, "Experience Bar", 182, 5));
    }

    private static final class Proxy implements HudWidget {
        private final String id;
        private final String label;
        private final int w;
        private final int h;

        Proxy(String id, String label, int w, int h) {
            this.id = id;
            this.label = label;
            this.w = w;
            this.h = h;
        }

        @Override public String widgetId()    { return id; }
        @Override public String displayName() { return label; }
        @Override public int widgetWidth()    { return w; }
        @Override public int widgetHeight()   { return h; }

        @Override
        public void renderWidget(GuiGraphics gfx, int x, int y) {
            // Intentionally empty. Vanilla Gui draws the real element; this
            // proxy only exists so the editor can reposition it via the
            // GuiVanillaHudMixin pose translate.
        }

        @Override
        public boolean isWidgetVisible() {
            // Proxies are always "registered" but never need to render
            // themselves outside of the editor. Returning false keeps
            // HudManager.renderAll from calling the no-op renderWidget every
            // frame; HudEditorScreen now shows widgets regardless of this flag.
            return false;
        }
    }
}
