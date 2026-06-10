package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Convenience base for HUD-widget modules. Owns the {@link HudManager}
 * registration plumbing, the widget identity, and the shared
 * background-panel + accent-bar rendering that every text-row widget repeats.
 *
 * <p>Two usage styles:
 * <ul>
 *   <li><b>Standard panel</b> — call {@link #useStandardPanel} (and optionally
 *       {@link #useTextColor}) in the constructor body to get the common
 *       "BG Opacity" / "Accent" / "Text Color" settings. Because Java runs
 *       field initializers before the constructor body, the module's own
 *       settings keep their position ahead of the appearance trio — the
 *       ClickGUI order is unchanged from the pre-refactor layout.</li>
 *   <li><b>Bespoke appearance</b> — skip {@code useStandardPanel} and override
 *       {@link #bgArgb()} / {@link #accentArgb()} to feed {@link #drawPanel}
 *       from the module's own settings (e.g. a {@code ModeSetting} accent).</li>
 * </ul>
 */
public abstract class BaseHudModule extends Module implements HudWidget {

    private final String widgetId;
    private final String widgetDisplayName;

    /** Created by {@link #useStandardPanel}; null when the subclass manages its own. */
    protected SliderSetting bgOpacity;
    /** Created by {@link #useStandardPanel}; null when the subclass manages its own. */
    protected ColorSetting accent;
    /** Created by {@link #useTextColor}; null when the subclass manages its own. */
    protected ColorSetting textColor;

    protected BaseHudModule(String name, String description, Category category,
                            String widgetId, String widgetDisplayName) {
        super(name, description, category);
        this.widgetId = widgetId;
        this.widgetDisplayName = widgetDisplayName;
        HudManager.register(this);
    }

    // ---- HudWidget identity ----
    @Override public final String widgetId()    { return widgetId; }
    @Override public String displayName()       { return widgetDisplayName; }
    @Override public boolean isWidgetVisible()  { return isEnabled(); }

    // ---- standard appearance settings ----

    /** Adds the shared "BG Opacity" + "Accent" settings with the given defaults. */
    protected void useStandardPanel(double defaultBgOpacity, int defaultAccent) {
        bgOpacity = addSetting(new SliderSetting("BG Opacity", defaultBgOpacity, 0.0, 1.0, 0.05));
        accent    = addSetting(new ColorSetting("Accent", defaultAccent));
    }

    /** Adds the shared "Text Color" setting (default white). */
    protected void useTextColor() {
        textColor = addSetting(new ColorSetting("Text Color", Palette.TEXT_WHITE));
    }

    // ---- appearance hooks (override for bespoke settings) ----

    /** Panel background ARGB. Defaults to the standard opacity slider, or the legacy 0x90 black. */
    protected int bgArgb() {
        return bgOpacity != null ? (int) (bgOpacity.get() * 255) << 24 : Palette.PANEL_BG_LEGACY;
    }

    /** Accent-bar ARGB. */
    protected int accentArgb() {
        return accent != null ? accent.get() : Palette.ACCENT_CYAN;
    }

    /** Primary text ARGB. */
    protected int textArgb() {
        return textColor != null ? textColor.get() : Palette.TEXT_WHITE;
    }

    // ---- render helpers ----

    /**
     * The shared widget chrome: background fill with a 2 px margin around the
     * content box plus the 1 px accent bar across the top.
     */
    protected void drawPanel(GuiGraphicsExtractor gfx, int x, int y, int w, int h) {
        drawPanel(gfx, x, y, w, h, bgArgb(), accentArgb());
    }

    /** Static variant for widgets that don't extend this base. */
    public static void drawPanel(GuiGraphicsExtractor gfx, int x, int y, int w, int h,
                                 int bgArgb, int accentArgb) {
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bgArgb);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accentArgb);
    }

    /** Height of {@code rows} standard 10 px text rows inside the panel. */
    protected static int rowsHeight(int rows) {
        return rows * 10 + 6;
    }

    /** Draws one text row at the standard 2 px indent; returns the next row's y. */
    protected int drawRow(GuiGraphicsExtractor gfx, Font font, String text, int x, int y, int color) {
        gfx.text(font, text, x + 2, y, color);
        return y + 10;
    }
}
