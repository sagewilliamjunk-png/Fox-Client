package dev.kitsune.client.gui.clickgui;

import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.hud.HudEditorScreen;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.screen.FoxSettingsScreen;
import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Kitsune ClickGUI — single-panel tabbed layout.
 *
 * <p>Category tabs run down the left side. The selected category's modules
 * are shown in one scrollable panel on the right. No overlap, works at any
 * window size.
 *
 * <p>Open with Right Shift; close with Right Shift or ESC.
 */
public class ClickGuiScreen extends Screen {

    /** The single active panel (one category at a time). */
    private Panel activePanel;
    private Category activeCategory;

    private String searchText = "";
    private boolean searchFocused = false;
    private long lastFrameNanos = 0;
    private float fadeIn = 0f;

    // Layout constants
    private static final int TAB_WIDTH = 80;
    private static final int TOP_BAR_H = 26;
    private static final int TAB_HEIGHT = 18;
    private static final int TAB_GAP = 2;
    private static final int MARGIN = 8;
    /** Pushes the HUD module panel down by this many px to make room for the
     *  "Open HUD Editor" button. Zero for every other category. */
    private static final int HUD_ACTION_BAR_H = 22;

    // Cached layout positions
    private int panelX, panelY, panelW, panelH;
    private int searchX, searchY, searchW;
    private int profileX, profileW;
    private int gearX, gearY;
    private int hudEditorBtnX, hudEditorBtnY, hudEditorBtnW, hudEditorBtnH;

    public ClickGuiScreen() {
        super(Component.literal("Fox Client"));
        this.activeCategory = Category.values()[0];
    }

    @Override
    protected void init() {
        super.init();
        rebuildPanel();
    }

    private void rebuildPanel() {
        // Panel fills the space to the right of the tabs. When the HUD
        // category is active we reserve a slim row above for the editor button.
        int actionBar = (activeCategory == Category.HUD) ? HUD_ACTION_BAR_H : 0;
        panelX = MARGIN + TAB_WIDTH + MARGIN;
        panelY = TOP_BAR_H + MARGIN + actionBar;
        panelW = this.width - panelX - MARGIN;
        panelH = this.height - panelY - MARGIN;

        activePanel = new Panel(activeCategory, panelX, panelY);
        activePanel.setMaxBodyHeight(panelH - Panel.HEADER_HEIGHT - Panel.PADDING * 2);
        activePanel.setSearchFilter(searchText);
    }

    private void selectCategory(Category cat) {
        this.activeCategory = cat;
        rebuildPanel();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // Frame delta
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        if (dt > 0.1f) dt = 0.1f;
        fadeIn = Math.min(1f, fadeIn + dt * 6f);
        activePanel.tickAnimations(dt);

        // Backdrop
        double extraDarkness = dev.kitsune.client.module.render.MenuBlurModule.darkness();
        int baseAlpha = (int) (0xD0 * fadeIn);
        int blurAlpha = (int) Math.min(255, baseAlpha + 255 * extraDarkness * fadeIn);
        gfx.fill(0, 0, this.width, this.height, (blurAlpha << 24));

        Font font = this.font;

        // ---- Top bar ----
        gfx.fill(0, 0, this.width, TOP_BAR_H, 0xE0181210);
        gfx.fill(0, TOP_BAR_H - 1, this.width, TOP_BAR_H, KitsuneTheme.ORANGE);
        gfx.drawString(font, "\u00a76Fox \u00a7eClient", MARGIN, 9, 0xFFFFFFFF, true);

        // Profile pill (right side)
        String profileText = FoxTheme.capitalize(ProfileManager.getActiveName());
        profileW = font.width(profileText) + 8;
        profileX = this.width - profileW - 30;
        gfx.fill(profileX - 1, 5, profileX + profileW + 1, 21, KitsuneTheme.BARK_SOFT);
        gfx.fill(profileX, 6, profileX + profileW, 20, 0xE0201810);
        gfx.drawString(font, profileText, profileX + 4, 9, 0xFFDDDDDD, false);

        // Gear icon
        gearX = this.width - 22;
        gearY = 6;
        gfx.fill(gearX - 1, gearY - 1, gearX + 17, gearY + 15, KitsuneTheme.BARK_SOFT);
        gfx.fill(gearX, gearY, gearX + 16, gearY + 14, 0xE0201810);
        gfx.drawCenteredString(font, "\u2699", gearX + 8, gearY + 3, KitsuneTheme.ORANGE);

        // ---- Search bar (below top bar, spans the tab area) ----
        searchX = MARGIN;
        searchY = TOP_BAR_H + MARGIN;
        searchW = TAB_WIDTH;
        int searchBorder = searchFocused ? KitsuneTheme.ORANGE : KitsuneTheme.BARK_SOFT;
        gfx.fill(searchX - 1, searchY - 1, searchX + searchW + 1, searchY + 15, searchBorder);
        gfx.fill(searchX, searchY, searchX + searchW, searchY + 14, 0xE0201810);
        // Magnifier icon — a tiny circle + handle drawn with fills so it renders
        // at any font size (emoji fallback is unreliable on some platforms).
        int iconX = searchX + 3;
        int iconY = searchY + 3;
        gfx.fill(iconX,     iconY,     iconX + 5, iconY + 1, 0xFFCCCCCC);
        gfx.fill(iconX,     iconY + 4, iconX + 5, iconY + 5, 0xFFCCCCCC);
        gfx.fill(iconX,     iconY + 1, iconX + 1, iconY + 4, 0xFFCCCCCC);
        gfx.fill(iconX + 4, iconY + 1, iconX + 5, iconY + 4, 0xFFCCCCCC);
        gfx.fill(iconX + 4, iconY + 4, iconX + 7, iconY + 7, 0xFFCCCCCC);
        int textStartX = searchX + 12;
        String placeholder = searchText.isEmpty() ? "\u00a78Search..." : searchText;
        gfx.drawString(font, placeholder, textStartX, searchY + 3, 0xFFCCCCCC, false);
        if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cursorX = textStartX + font.width(searchText);
            gfx.fill(cursorX, searchY + 2, cursorX + 1, searchY + 12, 0xFFFFFFFF);
        }

        // ---- Category tabs (left side, below search) ----
        int tabX = MARGIN;
        int tabY = searchY + 20;
        Category[] cats = Category.values();
        for (Category cat : cats) {
            boolean selected = cat == activeCategory;
            boolean hasModules = !ModuleManager.getByCategory(cat).isEmpty();
            boolean tabHovered = mouseX >= tabX && mouseX < tabX + TAB_WIDTH
                    && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT;

            // Tab background
            int bg = selected ? 0xE0302820 : (tabHovered ? 0xC0251D15 : 0xA0181210);
            gfx.fill(tabX, tabY, tabX + TAB_WIDTH, tabY + TAB_HEIGHT, bg);

            // Left accent bar when selected
            if (selected) {
                gfx.fill(tabX, tabY, tabX + 2, tabY + TAB_HEIGHT, KitsuneTheme.ORANGE);
            }

            // Tab text
            int textColor;
            if (!hasModules) {
                textColor = 0xFF555555; // dim if no modules
            } else if (selected) {
                textColor = KitsuneTheme.ORANGE;
            } else if (tabHovered) {
                textColor = 0xFFDDDDDD;
            } else {
                textColor = 0xFFAAAAAA;
            }
            String tabLabel = cat.icon() + " " + cat.displayName();
            gfx.drawString(font, tabLabel, tabX + 6, tabY + 5, textColor, false);

            // Module count badge — FAVORITES shows the favorited-module count
            // because no modules are actually registered under that category.
            int count = (cat == Category.FAVORITES)
                    ? dev.kitsune.client.module.ModuleFavorites.all().size()
                    : ModuleManager.getByCategory(cat).size();
            if (count > 0) {
                String countStr = String.valueOf(count);
                int cw = font.width(countStr);
                gfx.drawString(font, countStr, tabX + TAB_WIDTH - cw - 3, tabY + 5,
                        selected ? 0xFFFFFFFF : 0xFF777777, false);
            }

            tabY += TAB_HEIGHT + TAB_GAP;
        }

        // ---- HUD action bar (only when HUD category is active) ----
        if (activeCategory == Category.HUD) {
            hudEditorBtnX = panelX;
            hudEditorBtnY = TOP_BAR_H + MARGIN;
            hudEditorBtnW = Math.min(180, panelW);
            hudEditorBtnH = HUD_ACTION_BAR_H - 4;
            boolean hov = mouseX >= hudEditorBtnX && mouseX < hudEditorBtnX + hudEditorBtnW
                    && mouseY >= hudEditorBtnY && mouseY < hudEditorBtnY + hudEditorBtnH;
            int border = hov ? KitsuneTheme.ORANGE : KitsuneTheme.BARK_SOFT;
            int bg = hov ? 0xE0302820 : 0xE0201810;
            int fg = hov ? 0xFFFFFFFF : KitsuneTheme.ORANGE;
            gfx.fill(hudEditorBtnX - 1, hudEditorBtnY - 1,
                    hudEditorBtnX + hudEditorBtnW + 1, hudEditorBtnY + hudEditorBtnH + 1, border);
            gfx.fill(hudEditorBtnX, hudEditorBtnY,
                    hudEditorBtnX + hudEditorBtnW, hudEditorBtnY + hudEditorBtnH, bg);
            String label = "✎ Open HUD Editor";
            int lw = font.width(label);
            gfx.drawString(font, label,
                    hudEditorBtnX + (hudEditorBtnW - lw) / 2,
                    hudEditorBtnY + (hudEditorBtnH - 7) / 2,
                    fg, false);
        } else {
            hudEditorBtnW = 0;
            hudEditorBtnH = 0;
        }

        // ---- Module panel (right side) ----
        // Dark background behind the panel area
        gfx.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, 0xC0181210);

        // Override panel width for rendering
        activePanel.setSearchFilter(searchText);
        activePanel.setMaxBodyHeight(panelH - Panel.HEADER_HEIGHT - Panel.PADDING * 2);
        activePanel.mouseDragged(mouseX, mouseY);
        activePanel.render(gfx, mouseX, mouseY);
        activePanel.renderOverlay(gfx);
        // F4 tooltip last so it sits on top of everything else
        activePanel.renderTooltip(gfx, mouseX, mouseY);

        super.render(gfx, mouseX, mouseY, delta);
    }

    private void cycleProfile() {
        var names = ProfileManager.getProfileNames();
        if (names.isEmpty()) return;
        int idx = names.indexOf(ProfileManager.getActiveName());
        idx = (idx + 1) % names.size();
        // ProfileManager.switchTo authoritatively syncs modules + legacy features.
        ProfileManager.switchTo(names.get(idx), Minecraft.getInstance());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        // Gear icon → Fox Settings
        if (button == 0 && mouseX >= gearX && mouseX < gearX + 16
                && mouseY >= gearY && mouseY < gearY + 14) {
            Minecraft.getInstance().setScreen(new FoxSettingsScreen(this));
            return true;
        }

        // HUD Editor action button (only present when HUD category is active)
        if (button == 0 && activeCategory == Category.HUD && hudEditorBtnW > 0
                && mouseX >= hudEditorBtnX && mouseX < hudEditorBtnX + hudEditorBtnW
                && mouseY >= hudEditorBtnY && mouseY < hudEditorBtnY + hudEditorBtnH) {
            Minecraft.getInstance().setScreen(new HudEditorScreen());
            return true;
        }

        // Profile pill → cycle
        if (button == 0 && mouseX >= profileX - 1 && mouseX < profileX + profileW + 1
                && mouseY >= 5 && mouseY < 21) {
            cycleProfile();
            return true;
        }

        // Search bar
        if (mouseX >= searchX && mouseX < searchX + searchW
                && mouseY >= searchY && mouseY < searchY + 14) {
            searchFocused = true;
            return true;
        } else {
            searchFocused = false;
        }

        // Category tab clicks
        int tabY = searchY + 20;
        for (Category cat : Category.values()) {
            if (mouseX >= MARGIN && mouseX < MARGIN + TAB_WIDTH
                    && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT) {
                if (button == 0) {
                    selectCategory(cat);
                    return true;
                }
            }
            tabY += TAB_HEIGHT + TAB_GAP;
        }

        // Panel interaction
        if (activePanel.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        activePanel.mouseReleased(event.x(), event.y(), event.button());
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        activePanel.mouseDragged(event.x(), event.y());
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaH, double deltaV) {
        if (activePanel.mouseScrolled(mouseX, mouseY, deltaV)) return true;
        // Also allow scrolling anywhere on screen to scroll the panel
        activePanel.mouseScrolled(panelX + 10, panelY + 10, deltaV);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();

        // String-edit capture priority (highest — consumes Esc/Enter/Backspace)
        if (activePanel.isEditingString()) {
            if (activePanel.onKeyPress(keyCode)) return true;
        }

        // Keybind capture priority
        if (activePanel.isCapturingKey()) {
            activePanel.onKeyPress(keyCode);
            return true;
        }

        // ESC: unfocus the search bar first if it's focused; only close on
        // a second ESC. Right Shift always closes.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && searchFocused) {
            searchFocused = false;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }

        // Ctrl+F or Ctrl+K focuses the search bar from anywhere.
        // Avoid bare "/" or "k" because charTyped would append them to the search.
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (!searchFocused && ctrl
                && (keyCode == GLFW.GLFW_KEY_F || keyCode == GLFW.GLFW_KEY_K)) {
            searchFocused = true;
            return true;
        }

        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !searchText.isEmpty()) {
                searchText = searchText.substring(0, searchText.length() - 1);
                return true;
            }
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // String edit field takes char input first
        if (activePanel.isEditingString() && event.isAllowedChatCharacter()) {
            String s = event.codepointAsString();
            for (int i = 0; i < s.length(); i++) {
                activePanel.onCharTyped(s.charAt(i));
            }
            return true;
        }
        if (searchFocused && event.isAllowedChatCharacter()) {
            searchText += event.codepointAsString();
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
