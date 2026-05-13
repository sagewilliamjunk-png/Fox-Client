package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * On-screen overlay of WASD + mouse-button key states. Popular for content
 * creators — lights up each key while it's held.
 *
 * <p>Layout: three rows. Top row is W. Middle row is A S D. Bottom row is
 * LMB and RMB with per-second click counters.
 */
public class KeystrokesHudModule extends Module implements HudWidget {

    private final BooleanSetting showMouse   = addSetting(new BooleanSetting("Show Mouse",   true));
    private final BooleanSetting showCps     = addSetting(new BooleanSetting("Show CPS",     true));
    private final BooleanSetting showSpace   = addSetting(new BooleanSetting("Show Space",   false));
    private final SliderSetting  keySize     = addSetting(new SliderSetting("Key Size", 18, 12, 28, 1));
    private final SliderSetting  bgOpacity   = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   idleColor   = addSetting(new ColorSetting("Idle Color",   0x66222222));
    private final ColorSetting   pressColor  = addSetting(new ColorSetting("Press Color",  0xFF44CCCC));
    private final ColorSetting   textColor   = addSetting(new ColorSetting("Text Color",   0xFFFFFFFF));
    private final ColorSetting   pressedText = addSetting(new ColorSetting("Pressed Text", 0xFF000000));

    // Click tracking for CPS display (populated from edge detection in onTick)
    private static final int WINDOW_MS = 1000;
    private final long[] lmbClicks = new long[32];
    private final long[] rmbClicks = new long[32];
    private int lmbHead = 0, rmbHead = 0;
    private int lmbCount = 0, rmbCount = 0;
    private boolean prevAttack = false;
    private boolean prevUse    = false;

    public KeystrokesHudModule() {
        super("Keystrokes", "Shows WASD and mouse button presses", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "keystrokes"; }
    @Override public String displayName() { return "Keystrokes"; }

    private int size()   { return keySize.get().intValue(); }
    private int gap()    { return 2; }

    @Override
    public int widgetWidth() {
        return size() * 3 + gap() * 2;
    }

    @Override
    public int widgetHeight() {
        int rows = 2; // W + ASD
        if (showMouse.get())  rows++;
        if (showSpace.get())  rows++;
        return rows * size() + gap() * (rows - 1);
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;

        long now = System.currentTimeMillis();
        boolean attack = mc.options.keyAttack.isDown();
        boolean use    = mc.options.keyUse.isDown();

        if (attack && !prevAttack) addClick(lmbClicks, lmbHead++, now);
        if (use    && !prevUse)    addClick(rmbClicks, rmbHead++, now);
        prevAttack = attack;
        prevUse = use;

        lmbCount = countWithin(lmbClicks, now, WINDOW_MS);
        rmbCount = countWithin(rmbClicks, now, WINDOW_MS);
    }

    private void addClick(long[] buf, int head, long ts) {
        buf[Math.floorMod(head, buf.length)] = ts;
    }

    private int countWithin(long[] buf, long now, int windowMs) {
        int c = 0;
        long cutoff = now - windowMs;
        for (long t : buf) if (t >= cutoff) c++;
        return c;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Options opts = mc.options;
        if (opts == null) return;
        Font font = mc.font;

        int s = size();
        int g = gap();
        int w = widgetWidth();
        int h = widgetHeight();

        int bgAlpha = (int)(bgOpacity.get() * 255) << 24;
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bgAlpha | 0x000000);

        // Row 1: centered W
        int row1y = y;
        drawKey(gfx, font, x + s + g, row1y, s, keyLabel(opts.keyUp, "W"), opts.keyUp.isDown());

        // Row 2: A S D
        int row2y = row1y + s + g;
        drawKey(gfx, font, x,             row2y, s, keyLabel(opts.keyLeft,  "A"), opts.keyLeft.isDown());
        drawKey(gfx, font, x + s + g,     row2y, s, keyLabel(opts.keyDown,  "S"), opts.keyDown.isDown());
        drawKey(gfx, font, x + 2*(s+g),   row2y, s, keyLabel(opts.keyRight, "D"), opts.keyRight.isDown());

        int cursorY = row2y + s + g;

        // Row 3: LMB + RMB (wide, centered)
        if (showMouse.get()) {
            int halfW = (w - g) / 2;
            String lmb = showCps.get() ? (lmbCount + " CPS") : "LMB";
            String rmb = showCps.get() ? (rmbCount + " CPS") : "RMB";
            drawWideKey(gfx, font, x,                  cursorY, halfW, s, lmb, opts.keyAttack.isDown());
            drawWideKey(gfx, font, x + halfW + g,      cursorY, halfW, s, rmb, opts.keyUse.isDown());
            cursorY += s + g;
        }

        // Row 4: full-width space
        if (showSpace.get()) {
            drawWideKey(gfx, font, x, cursorY, w, s, "\u2423", opts.keyJump.isDown());
        }
    }

    private static String keyLabel(KeyMapping km, String fallback) {
        try {
            String s = km.getTranslatedKeyMessage().getString();
            if (s == null || s.isEmpty() || s.length() > 3) return fallback;
            return s.toUpperCase();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void drawKey(GuiGraphicsExtractor gfx, Font font, int x, int y, int size, String label, boolean pressed) {
        int bg = pressed ? pressColor.get() : idleColor.get();
        int fg = pressed ? pressedText.get() : textColor.get();
        gfx.fill(x, y, x + size, y + size, bg);
        int tw = font.width(label);
        int tx = x + (size - tw) / 2;
        int ty = y + (size - 8) / 2;
        gfx.text(font, label, tx, ty, fg);
    }

    private void drawWideKey(GuiGraphicsExtractor gfx, Font font, int x, int y, int w, int h, String label, boolean pressed) {
        int bg = pressed ? pressColor.get() : idleColor.get();
        int fg = pressed ? pressedText.get() : textColor.get();
        gfx.fill(x, y, x + w, y + h, bg);
        int tw = font.width(label);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - 8) / 2;
        gfx.text(font, label, tx, ty, fg);
    }
}
