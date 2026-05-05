package dev.kitsune.client.gui.clickgui;

import dev.kitsune.client.config.ConfigManager;
import dev.kitsune.client.hud.NotificationManager;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.LegacyFeatureModule;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.module.ModuleFavorites;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.KeybindSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.Setting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.setting.StringSetting;
import dev.kitsune.client.util.KeybindManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Renders and handles interaction for one category's module list.
 * Used by ClickGuiScreen in a single-panel tabbed layout.
 *
 * <ul>
 *   <li>Left-click module row → toggle enabled</li>
 *   <li>Right-click module row → expand/collapse inline settings</li>
 *   <li>Middle-click → toggle favorite</li>
 *   <li>Scroll wheel → scroll the list</li>
 * </ul>
 */
public class Panel {
    public static final int WIDTH = 130; // default, but render uses dynamic width
    public static final int HEADER_HEIGHT = 16;
    public static final int ROW_HEIGHT = 16;
    public static final int SETTING_ROW_HEIGHT = 15;
    public static final int PADDING = 4;
    public static final int DEFAULT_MAX_BODY_HEIGHT = 400;

    private static final int BAR_HEIGHT = 4;

    private final Category category;
    private final int x, y;
    private int height;
    private String searchFilter = "";
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private int maxBodyHeight = DEFAULT_MAX_BODY_HEIGHT;

    /** Name of the module whose settings are currently expanded, or null. */
    private String expandedModule = null;

    /** Slider currently being dragged. */
    private SliderSetting draggingSlider = null;
    private int draggingSliderBarX = 0;
    private int draggingSliderBarW = 0;

    /** KeybindSetting waiting for a key press. */
    private KeybindSetting capturingKeybind = null;

    /** StringSetting currently being edited inline, or null. */
    private StringSetting editingString = null;

    /** Working buffer for {@link #editingString}; flushed on Enter, discarded on Esc. */
    private String editingBuffer = "";

    /**
     * Tooltip hover tracking: exactly one of {@link #hoverSetting} / {@link #hoverModule}
     * is non-null per frame (or both null when not hovering anything tooltipable).
     * The dwell timer {@link #hoverStartMs} only resets when the hovered target
     * actually changes — so sustained hover accumulates across frames.
     */
    private Setting<?> hoverSetting = null;
    private Module     hoverModule  = null;
    private long       hoverStartMs = 0L;

    /** Currently open color picker popup. */
    private ColorPickerPopup colorPopup = null;

    // Animation state
    private float settingsAnim = 0.0f;
    /** Per-module enable/disable animation progress (0.0 = fully disabled, 1.0 = fully enabled). */
    private final HashMap<String, Float> enableAnim = new HashMap<>();

    public Panel(Category category, int x, int y) {
        this.category = category;
        this.x = x;
        this.y = y;
        recalcHeight();
    }

    public Category category() { return category; }
    public int x() { return x; }
    public int y() { return y; }
    public int height() { return height; }

    public void setMaxBodyHeight(int h) { this.maxBodyHeight = Math.max(60, h); }

    public void setSearchFilter(String filter) {
        this.searchFilter = filter == null ? "" : filter.toLowerCase().trim();
    }

    /** Dynamically computed width — fills available space from ClickGuiScreen. */
    private int getWidth() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            // Fill from our x position to the right margin
            return mc.screen.width - x - 8;
        }
        return WIDTH;
    }

    private List<Module> getFilteredModules() {
        // FAVORITES is a virtual category — pull from all modules whose names
        // appear in ModuleFavorites, then apply the search filter as usual.
        List<Module> source;
        if (category == Category.FAVORITES) {
            java.util.Set<String> favNames = ModuleFavorites.all();
            source = new ArrayList<>();
            for (Module m : ModuleManager.all()) {
                if (favNames.contains(m.name())) source.add(m);
            }
        } else {
            source = ModuleManager.getByCategory(category);
        }

        List<Module> filtered = new ArrayList<>();
        for (Module m : source) {
            if (searchFilter.isEmpty()
                    || m.name().toLowerCase().contains(searchFilter)
                    || (m.description() != null
                        && m.description().toLowerCase().contains(searchFilter))) {
                filtered.add(m);
            }
        }

        // Non-favorites panels still sort favorites to the top of their list.
        if (category != Category.FAVORITES) {
            filtered.sort((a, b) -> {
                boolean fa = ModuleFavorites.isFavorite(a);
                boolean fb = ModuleFavorites.isFavorite(b);
                if (fa == fb) return 0;
                return fa ? -1 : 1;
            });
        }
        return filtered;
    }

    private void recalcHeight() {
        List<Module> mods = getFilteredModules();
        int rows = Math.max(1, mods.size());
        int body = rows * ROW_HEIGHT;

        if (expandedModule != null) {
            for (Module m : mods) {
                if (m.name().equals(expandedModule) && !m.settings().isEmpty()) {
                    int full = m.settings().size() * SETTING_ROW_HEIGHT;
                    body += Math.round(full * easeOutQuad(settingsAnim));
                    break;
                }
            }
        }
        this.contentHeight = body;
        int visibleBody = Math.min(body, maxBodyHeight);
        this.height = HEADER_HEIGHT + PADDING * 2 + visibleBody;

        int maxScroll = Math.max(0, contentHeight - visibleBody);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    private int maxScroll() {
        int visibleBody = Math.min(contentHeight, maxBodyHeight);
        return Math.max(0, contentHeight - visibleBody);
    }

    public void tickAnimations(float dtSeconds) {
        float target = expandedModule != null ? 1f : 0f;
        settingsAnim = approach(settingsAnim, target, dtSeconds * 8f);

        // Per-module enable/disable toggle animation — lerp toward 0 or 1.
        // FAVORITES uses getFilteredModules() because no modules are registered
        // under that category in ModuleManager. Other categories use the full
        // (unfiltered) list so animations complete even when hidden by search.
        java.util.List<Module> animSrc = (category == Category.FAVORITES)
                ? getFilteredModules()
                : ModuleManager.getByCategory(category);
        for (Module m : animSrc) {
            float cur = enableAnim.getOrDefault(m.name(), m.isEnabled() ? 1f : 0f);
            float tgt = m.isEnabled() ? 1f : 0f;
            enableAnim.put(m.name(), approach(cur, tgt, dtSeconds * 10f)); // ~100 ms at 60 fps
        }
    }

    private static float approach(float cur, float target, float step) {
        if (cur < target) return Math.min(target, cur + step);
        if (cur > target) return Math.max(target, cur - step);
        return cur;
    }

    private static float easeOutQuad(float t) {
        return 1 - (1 - t) * (1 - t);
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        recalcHeight();
        int w = getWidth();
        Font font = Minecraft.getInstance().font;

        // F4: reset hover targets for this frame; renderSetting / the module-row
        // loop below re-set them if actually hovered.
        Setting<?> prevHoverSetting = hoverSetting;
        Module     prevHoverModule  = hoverModule;
        hoverSetting = null;
        hoverModule  = null;

        // Panel background
        gfx.fill(x, y, x + w, y + height, 0xC0201810);
        // Header
        gfx.fill(x, y, x + w, y + HEADER_HEIGHT, 0xE0302820);
        gfx.fill(x, y + HEADER_HEIGHT - 1, x + w, y + HEADER_HEIGHT, KitsuneTheme.ORANGE);

        // Category title
        String title = category.icon() + " " + category.displayName();
        gfx.drawString(font, title, x + 8, y + 4, KitsuneTheme.ORANGE, false);

        // Module count
        List<Module> mods = getFilteredModules();
        String countStr = (category == Category.FAVORITES)
                ? mods.size() + " favorites"
                : mods.size() + " modules";
        int cw = font.width(countStr);
        gfx.drawString(font, countStr, x + w - cw - 8, y + 4, 0xFF888888, false);

        int bodyTop = y + HEADER_HEIGHT + PADDING;
        int bodyBottom = y + height - PADDING;

        if (mods.isEmpty()) {
            // Favorites panel shows a helpful hint rather than a generic message.
            if (category == Category.FAVORITES) {
                gfx.drawString(font, "\u00a78No favorites yet.", x + 12, bodyTop + 4, 0xFF666666, false);
                gfx.drawString(font, "\u00a78Middle-click any module to favorite it.",
                        x + 12, bodyTop + 14, 0xFF555555, false);
            } else {
                gfx.drawString(font, "\u00a78No modules in this category",
                        x + 12, bodyTop + 4, 0xFF666666, false);
            }
            return;
        }

        // Scissor — wrapped in try/finally so a renderSetting throw down the
        // line can't leak the clip rect into subsequent draws (everything
        // would render clipped to this panel until the next disableScissor).
        gfx.enableScissor(x, bodyTop, x + w, bodyBottom);
        try {
        int rowY = bodyTop - scrollOffset;

        // Scrollbar
        int max = maxScroll();
        if (max > 0) {
            int railX = x + w - 4;
            int railH = bodyBottom - bodyTop;
            int thumbH = Math.max(12, railH * railH / Math.max(1, contentHeight));
            int thumbY = bodyTop + (int) ((railH - thumbH) * (scrollOffset / (double) max));
            gfx.fill(railX, bodyTop, railX + 3, bodyTop + railH, 0x30FFFFFF);
            gfx.fill(railX, thumbY, railX + 3, thumbY + thumbH, KitsuneTheme.ORANGE);
        }

        for (Module m : mods) {
            boolean rowHovered = mouseX >= x + 4 && mouseX <= x + w - 4
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseY >= bodyTop && mouseY < bodyBottom;

            // F4: track the hovered module so renderTooltip() can surface the
            // full description when the truncated one-liner in the row is
            // cut off with an ellipsis.
            if (rowHovered) hoverModule = m;

            // Row background — tint alpha lerps from 0 (disabled) to 0x30 (enabled)
            float anim = enableAnim.getOrDefault(m.name(), m.isEnabled() ? 1f : 0f);
            int bgAlpha = Math.round(anim * 0x30);
            if (bgAlpha > 0) {
                gfx.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT, (bgAlpha << 24) | 0x00FF8830);
            }
            if (rowHovered) {
                gfx.fill(x + 1, rowY, x + w - 1, rowY + ROW_HEIGHT, 0x20FFFFFF);
            }

            // Enabled indicator dot — color grey→green, size 2×4→4×6 px, centered
            int dotGreen = (int) (0x55 + anim * 0xAA);          // 0x55 (grey) → 0xFF (bright)
            int dotClr   = 0xFF000000 | (0x55 << 16) | (dotGreen << 8) | 0x55;
            int dotHalfW = Math.max(1, Math.round(anim * 2));    // 1 → 2  (2 px → 4 px wide)
            int dotHalfH = Math.max(2, Math.round(2 + anim));    // 2 → 3  (4 px → 6 px tall)
            int dotCX = x + 8, dotCY = rowY + 8;
            gfx.fill(dotCX - dotHalfW, dotCY - dotHalfH, dotCX + dotHalfW, dotCY + dotHalfH, dotClr);

            // Module name — brightness lerps from 0xAA (dim) to 0xFF (bright)
            int nameGrey = (int) (0xAA + anim * 0x55);
            int nameColor = 0xFF000000 | (nameGrey << 16) | (nameGrey << 8) | nameGrey;
            boolean fav = ModuleFavorites.isFavorite(m);
            int textX = x + 14;
            if (fav) {
                gfx.drawString(font, "\u2605", textX, rowY + 4, 0xFFFFD27F, false);
                textX += 10;
            }
            gfx.drawString(font, m.name(), textX, rowY + 4, nameColor, false);

            // Description (if space allows)
            if (w > 250) {
                String desc = m.description();
                if (desc != null && !desc.isEmpty()) {
                    int descX = x + w / 2;
                    int maxDescW = w / 2 - 30;
                    if (font.width(desc) > maxDescW) {
                        desc = font.plainSubstrByWidth(desc, maxDescW - font.width("...")) + "...";
                    }
                    gfx.drawString(font, desc, descX, rowY + 4, 0xFF666666, false);
                }
            }

            // Settings expand arrow
            boolean isExpanded = m.name().equals(expandedModule);
            if (!m.settings().isEmpty()) {
                String arrow = isExpanded ? "\u25bc" : "\u25b6";
                gfx.drawString(font, arrow, x + w - 14, rowY + 4, 0xFF888888, false);
            }

            rowY += ROW_HEIGHT;

            // Expanded settings
            if (isExpanded && !m.settings().isEmpty()) {
                for (Setting<?> s : m.settings()) {
                    renderSetting(gfx, font, s, rowY, w, mouseX, mouseY, bodyTop, bodyBottom);
                    rowY += SETTING_ROW_HEIGHT;
                }
            }
        }
        } finally {
            gfx.disableScissor();
        }

        // Start the hover timer the moment we land on a new tooltip target
        // (module row or setting). Stays unchanged while the same target is
        // hovered so dwell accumulates across frames.
        if (hoverSetting != prevHoverSetting || hoverModule != prevHoverModule) {
            hoverStartMs = System.currentTimeMillis();
        }
    }

    /**
     * Tooltip for whatever's currently hovered (module row or setting), drawn
     * after ~400 ms of sustained hover. Module tooltips support multi-line
     * wrap so long descriptions that got "..."-truncated in the row are fully
     * readable on hover.
     */
    public void renderTooltip(GuiGraphics gfx, int mouseX, int mouseY) {
        long dwell = System.currentTimeMillis() - hoverStartMs;
        if (dwell < 400) return;

        Font font = Minecraft.getInstance().font;
        List<String> lines;
        if (hoverModule != null) {
            // Module hover → "<Name>" header + wrapped description.
            String desc = hoverModule.description();
            lines = new ArrayList<>();
            lines.add("\u00a7e" + hoverModule.name());
            if (desc != null && !desc.isEmpty()) {
                // Wrap at ~220 px so descriptions don't span the whole screen.
                lines.addAll(wrapText(font, desc, 220));
            }
        } else if (hoverSetting != null) {
            String text = tooltipFor(hoverSetting);
            if (text == null || text.isEmpty()) return;
            lines = List.of(text);
        } else {
            return;
        }

        int maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, font.width(line));
        int tw = maxW + 6;
        int th = lines.size() * 10 + 2;
        int tx = mouseX + 8;
        int ty = mouseY - th - 2;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            if (tx + tw > mc.screen.width) tx = mc.screen.width - tw - 2;
            if (ty < 2) ty = mouseY + 12;
        }
        // Bark-bordered dark tooltip
        gfx.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, 0xFF3A2410);
        gfx.fill(tx, ty, tx + tw, ty + th, 0xF0181210);
        for (int i = 0; i < lines.size(); i++) {
            gfx.drawString(font, lines.get(i), tx + 3, ty + 2 + i * 10, 0xFFEEEEEE, false);
        }
    }

    /**
     * Greedy word-wrap: break on spaces, fall back to per-char slicing if a
     * single token is wider than {@code maxPx}. Good enough for our short
     * one-paragraph module descriptions.
     */
    private static List<String> wrapText(Font font, String text, int maxPx) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String probe = line.length() == 0 ? word : line + " " + word;
            if (font.width(probe) <= maxPx) {
                line.setLength(0);
                line.append(probe);
            } else {
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                // Word alone doesn't fit: hard-slice it.
                while (font.width(word) > maxPx && word.length() > 1) {
                    int cut = word.length();
                    while (cut > 1 && font.width(word.substring(0, cut)) > maxPx) cut--;
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                line.append(word);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String tooltipFor(Setting<?> s) {
        if (s instanceof BooleanSetting) return "Click to toggle on/off";
        if (s instanceof SliderSetting)  return "Drag to adjust value";
        if (s instanceof ModeSetting)    return "Left-click: next  \u2022  Right-click: previous";
        if (s instanceof ColorSetting)   return "Click the swatch to open the color picker";
        if (s instanceof KeybindSetting) return "Left-click then press a key  \u2022  Right-click to clear";
        if (s instanceof StringSetting)  return "Click to edit  \u2022  Enter = save  \u2022  Esc = cancel";
        return s.name();
    }

    private void renderSetting(GuiGraphics gfx, Font font, Setting<?> s,
                               int rowY, int panelW, int mouseX, int mouseY,
                               int bodyTop, int bodyBottom) {
        int left = x + 14;
        int right = x + panelW - 8;
        boolean hovered = mouseX >= left && mouseX <= right
                && mouseY >= rowY && mouseY < rowY + SETTING_ROW_HEIGHT
                && mouseY >= bodyTop && mouseY < bodyBottom;

        // F4: track tooltip hover. Only set hoverSetting here — the timer is
        // managed in render() where we compare against prevHover, so it only
        // resets when the hovered setting actually changes (not every frame).
        if (hovered) {
            if (hoverSetting != s) {
                hoverSetting = s;
                // Do NOT reset hoverStartMs here — render() handles that.
            }
        }

        // Indent background
        gfx.fill(left - 2, rowY, right, rowY + SETTING_ROW_HEIGHT, 0x18FFFFFF);
        // Left accent
        gfx.fill(x + 10, rowY + 2, x + 12, rowY + SETTING_ROW_HEIGHT - 2, 0x40FF8830);
        if (hovered) {
            gfx.fill(left - 2, rowY, right, rowY + SETTING_ROW_HEIGHT, 0x18FFFFFF);
        }

        if (s instanceof BooleanSetting bs) {
            boolean val = bs.get();
            String label = s.name() + ": " + (val ? "\u00a7aON" : "\u00a7cOFF");
            gfx.drawString(font, label, left + 2, rowY + 3, 0xFFDDDDDD, false);

        } else if (s instanceof SliderSetting ss) {
            String valStr = formatSliderValue(ss);
            gfx.drawString(font, s.name(), left + 2, rowY + 1, 0xFFDDDDDD, false);
            int vw = font.width(valStr);
            gfx.drawString(font, valStr, right - vw - 2, rowY + 1, KitsuneTheme.ORANGE, false);

            // Bar
            int barX = left + 2;
            int barY = rowY + SETTING_ROW_HEIGHT - BAR_HEIGHT - 1;
            int barW = right - left - 4;
            gfx.fill(barX, barY, barX + barW, barY + BAR_HEIGHT, 0xFF3A3A3A);
            double t = (ss.get() - ss.min()) / Math.max(1e-9, ss.max() - ss.min());
            int fillW = (int) Math.round(Math.max(0, Math.min(1, t)) * barW);
            gfx.fill(barX, barY, barX + fillW, barY + BAR_HEIGHT, KitsuneTheme.ORANGE);
            // Thumb
            int thumbX = barX + fillW;
            gfx.fill(thumbX - 1, barY - 1, thumbX + 2, barY + BAR_HEIGHT + 1, 0xFFFFFFFF);

        } else if (s instanceof ModeSetting ms) {
            String label = s.name() + ": \u00a7f" + ms.get();
            gfx.drawString(font, label, left + 2, rowY + 3, 0xFFDDDDDD, false);
            gfx.drawString(font, "\u25b6", right - 10, rowY + 3, KitsuneTheme.ORANGE, false);

        } else if (s instanceof ColorSetting cs) {
            gfx.drawString(font, cs.name(), left + 2, rowY + 3, 0xFFDDDDDD, false);
            int swX = right - 22;
            int swY = rowY + 2;
            int swW = 18;
            int swH = SETTING_ROW_HEIGHT - 4;
            gfx.fill(swX - 1, swY - 1, swX + swW + 1, swY + swH + 1, 0xFF000000);
            gfx.fill(swX, swY, swX + swW, swY + swH, cs.get());

        } else if (s instanceof KeybindSetting ks) {
            String keyName;
            if (capturingKeybind == ks) {
                // P6: pulse the row background while armed
                long ms = System.currentTimeMillis();
                int pulseAlpha = (int) (40 + 40 * Math.abs(Math.sin(ms / 150.0)));
                gfx.fill(left - 2, rowY, right, rowY + SETTING_ROW_HEIGHT,
                        (pulseAlpha << 24) | (KitsuneTheme.ORANGE & 0x00FFFFFF));
                keyName = "\u00a7e[press any key\u2026 Esc=clear]";
            } else {
                int key = ks.get();
                keyName = key < 0 ? "\u00a78None" : "\u00a7a" + KeybindManager.getKeyName(key);
            }
            gfx.drawString(font, s.name() + ": " + keyName, left + 2, rowY + 3, 0xFFDDDDDD, false);

        } else if (s instanceof StringSetting str) {
            // Label on the left
            gfx.drawString(font, str.name(), left + 2, rowY + 1, 0xFFDDDDDD, false);
            // Edit field fills the right half
            int fieldX = left + Math.min(80, (right - left) / 2);
            int fieldY = rowY + SETTING_ROW_HEIGHT - BAR_HEIGHT - 6;
            int fieldW = right - fieldX - 2;
            boolean editing = editingString == str;
            int border = editing ? KitsuneTheme.ORANGE : 0xFF3A3A3A;
            gfx.fill(fieldX - 1, fieldY - 1, fieldX + fieldW + 1, fieldY + 11, border);
            gfx.fill(fieldX, fieldY, fieldX + fieldW, fieldY + 10, 0xFF1A1410);
            String shown = editing ? editingBuffer : str.get();
            String drawable = shown;
            // Right-trim if overflowing; show the tail so the caret stays visible when typing
            while (drawable.length() > 0 && font.width(drawable) > fieldW - 6) {
                drawable = drawable.substring(1);
            }
            gfx.drawString(font, drawable, fieldX + 3, fieldY + 1, 0xFFFFFFFF, false);
            if (editing && (System.currentTimeMillis() / 500) % 2 == 0) {
                int caretX = fieldX + 3 + font.width(drawable);
                gfx.fill(caretX, fieldY + 1, caretX + 1, fieldY + 9, 0xFFFFFFFF);
            }

        } else {
            gfx.drawString(font, s.name() + ": " + s.get(), left + 2, rowY + 3, 0xFF999999, false);
        }
    }

    private String formatSliderValue(SliderSetting ss) {
        double v = ss.get();
        if (ss.step() >= 1.0 && Math.abs(v - Math.round(v)) < 1e-6) {
            return Integer.toString((int) Math.round(v));
        }
        return String.format("%.2f", v);
    }

    // ---- Input handling ----

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int w = getWidth();

        if (colorPopup != null) {
            if (colorPopup.mouseClicked(mouseX, mouseY, button)) return true;
            colorPopup = null;
        }

        if (!withinPanel(mouseX, mouseY)) return false;

        List<Module> mods = getFilteredModules();
        int bodyTop = y + HEADER_HEIGHT + PADDING;
        int bodyBottom = y + height - PADDING;
        int rowY = bodyTop - scrollOffset;

        for (Module m : mods) {
            boolean isExpanded = m.name().equals(expandedModule);

            // Module row click
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseY >= bodyTop && mouseY < bodyBottom
                    && mouseX >= x + 4 && mouseX <= x + w - 4) {
                if (button == 0) {
                    m.toggle();
                    if (!(m instanceof LegacyFeatureModule)) {
                        NotificationManager.show(
                                m.name() + (m.isEnabled() ? " enabled" : " disabled"),
                                m.isEnabled() ? NotificationManager.Type.SUCCESS : NotificationManager.Type.INFO);
                    }
                    persist();
                    return true;
                } else if (button == 1) {
                    if (!m.settings().isEmpty()) {
                        expandedModule = isExpanded ? null : m.name();
                    }
                    return true;
                } else if (button == 2) {
                    ModuleFavorites.toggle(m);
                    NotificationManager.show(
                            (ModuleFavorites.isFavorite(m) ? "\u2605 Favorited " : "Unfavorited ") + m.name(),
                            NotificationManager.Type.INFO);
                    return true;
                }
            }
            rowY += ROW_HEIGHT;

            // Settings clicks
            if (isExpanded && !m.settings().isEmpty()) {
                for (Setting<?> s : m.settings()) {
                    int left = x + 14;
                    int right = x + w - 8;
                    if (mouseY >= rowY && mouseY < rowY + SETTING_ROW_HEIGHT
                            && mouseY >= bodyTop && mouseY < bodyBottom
                            && mouseX >= left && mouseX <= right) {

                        if (s instanceof BooleanSetting bs && button == 0) {
                            bs.set(!bs.get());
                            persist();
                            return true;
                        } else if (s instanceof SliderSetting ss && button == 0) {
                            int barX = left + 2;
                            int barW = right - left - 4;
                            draggingSlider = ss;
                            draggingSliderBarX = barX;
                            draggingSliderBarW = barW;
                            applySliderDrag(mouseX);
                            // Save happens on mouseReleased — avoids a write every pixel of drag.
                            return true;
                        } else if (s instanceof ModeSetting ms) {
                            if (button == 0) ms.cycle();
                            else if (button == 1) {
                                int idx = ms.options().indexOf(ms.get());
                                int prev = (idx - 1 + ms.options().size()) % ms.options().size();
                                ms.set(ms.options().get(prev));
                            }
                            persist();
                            return true;
                        } else if (s instanceof ColorSetting cs && button == 0) {
                            int px = x + w + 4;
                            int py = Math.max(2, rowY - 20);
                            colorPopup = new ColorPickerPopup(cs, px, py);
                            // Color saves happen on popup close / drag release.
                            return true;
                        } else if (s instanceof KeybindSetting ks) {
                            if (button == 0) capturingKeybind = ks;
                            else if (button == 1) { ks.set(-1); persist(); }
                            return true;
                        } else if (s instanceof StringSetting str && button == 0) {
                            // Start inline edit; commit any previously-armed edit first
                            commitStringEdit();
                            editingString = str;
                            editingBuffer = str.get();
                            return true;
                        }
                    }
                    rowY += SETTING_ROW_HEIGHT;
                }
            }
        }

        return withinPanel(mouseX, mouseY);
    }

    private void applySliderDrag(double mouseX) {
        if (draggingSlider == null) return;
        double t = (mouseX - draggingSliderBarX) / Math.max(1, draggingSliderBarW);
        t = Math.max(0, Math.min(1, t));
        double raw = draggingSlider.min() + t * (draggingSlider.max() - draggingSlider.min());
        double step = draggingSlider.step();
        if (step > 0) raw = Math.round(raw / step) * step;
        draggingSlider.set(raw);
    }

    /**
     * Persist the active profile to disk after a user mutation.
     * ConfigManager.saveProfile is cheap (hash-skip de-dupes no-ops) and atomic
     * (temp + rename) so calling it after every click is safe.
     */
    private static void persist() {
        try {
            ConfigManager.saveProfile(ConfigManager.getActiveProfile());
        } catch (Throwable ignored) {
            // Never let a save error propagate into the UI loop.
        }
    }

    public void mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            boolean wasDragging = draggingSlider != null;
            draggingSlider = null;
            if (colorPopup != null) colorPopup.mouseReleased();
            // End of slider drag / color drag: persist the final value.
            if (wasDragging || colorPopup != null) persist();
        }
    }

    public void mouseDragged(double mouseX, double mouseY) {
        if (draggingSlider != null) applySliderDrag(mouseX);
        if (colorPopup != null) colorPopup.mouseDragged(mouseX, mouseY);
    }

    public void renderOverlay(GuiGraphics gfx) {
        if (colorPopup != null) colorPopup.render(gfx);
    }

    public boolean onKeyPress(int glfwKey) {
        // Inline string editor consumes keys first
        if (editingString != null) {
            if (glfwKey == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                cancelStringEdit();
                return true;
            }
            if (glfwKey == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || glfwKey == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                commitStringEdit();
                return true;
            }
            if (glfwKey == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                if (!editingBuffer.isEmpty()) {
                    editingBuffer = editingBuffer.substring(0, editingBuffer.length() - 1);
                }
                return true;
            }
            return false;
        }
        if (capturingKeybind == null) return false;
        if (glfwKey == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            capturingKeybind = null;
            return true;
        }
        capturingKeybind.set(glfwKey);
        capturingKeybind = null;
        persist();
        return true;
    }

    /** Feed a typed character from the parent screen's charTyped hook. */
    public boolean onCharTyped(char ch) {
        if (editingString == null) return false;
        if (ch < 32 || ch == 127) return false; // skip control chars
        if (editingBuffer.length() >= StringSetting.MAX_LENGTH) return true;
        editingBuffer += ch;
        return true;
    }

    private void commitStringEdit() {
        if (editingString == null) return;
        editingString.set(editingBuffer);
        editingString = null;
        editingBuffer = "";
        persist();
    }

    private void cancelStringEdit() {
        editingString = null;
        editingBuffer = "";
    }

    public boolean isCapturingKey() { return capturingKeybind != null; }
    public boolean isEditingString() { return editingString != null; }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!withinPanel(mouseX, mouseY)) return false;
        int max = maxScroll();
        if (max <= 0) return true;
        scrollOffset -= (int) (amount * 20);
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > max) scrollOffset = max;
        return true;
    }

    public boolean withinPanel(double mouseX, double mouseY) {
        int w = getWidth();
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + height;
    }
}
