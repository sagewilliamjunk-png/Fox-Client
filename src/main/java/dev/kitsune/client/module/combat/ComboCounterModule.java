package dev.kitsune.client.module.combat;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.hud.BaseHudModule;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Combo Counter — counts consecutive hits landed within a reset window, with a
 * session best. Fed by {@code PlayerAttackMixin} on every landed attack on a
 * living target (the same hook that drives Combat Timer), so it counts the
 * connect regardless of whether a clientside damage delta is visible.
 *
 * <p>Display-only: it observes your own attacks, exactly the information you
 * already have from swinging. No advantage beyond a tidy readout — fair-play.
 */
public class ComboCounterModule extends BaseHudModule {

    private final SliderSetting  window  = addSetting(new SliderSetting("Reset Window (s)", 2.0, 0.5, 5.0, 0.5));
    private final BooleanSetting showBest = addSetting(new BooleanSetting("Show Best", true));

    private int  combo = 0;
    private int  best  = 0;
    private long lastHitMs = 0;

    public ComboCounterModule() {
        super("Combo Counter", "Counts consecutive hits within a window, with a session best.",
                Category.COMBAT, "combo_counter", "Combo");
        useStandardPanel(0.50, Palette.ACCENT_ORANGE);
        useTextColor();
    }

    /** Called by PlayerAttackMixin on a landed attack against a living target. */
    public void onLocalHit() {
        if (!isEffectivelyEnabled()) return;
        long now = System.currentTimeMillis();
        long windowMs = (long) (window.get() * 1000);
        combo = (combo > 0 && now - lastHitMs <= windowMs) ? combo + 1 : 1;
        lastHitMs = now;
        if (combo > best) best = combo;
    }

    @Override
    public void onTick() {
        if (combo > 0) {
            long windowMs = (long) (window.get() * 1000);
            if (System.currentTimeMillis() - lastHitMs > windowMs) combo = 0; // window expired
        }
    }

    @Override
    protected void onDisable() {
        combo = 0;
        best = 0;
        lastHitMs = 0;
    }

    @Override
    public boolean isWidgetVisible() {
        return isEnabled() && (combo > 0 || (showBest.get() && best > 0));
    }

    @Override public int widgetWidth()  { return 70; }
    @Override public int widgetHeight() { return showBest.get() ? rowsHeight(2) : rowsHeight(1); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        // Colour ramp by combo size: white → gold → orange → red as it climbs.
        int color = combo >= 10 ? Palette.BAD
                  : combo >= 6  ? Palette.ACCENT_ORANGE
                  : combo >= 3  ? Palette.ACCENT_GOLD
                  : textArgb();
        gfx.text(font, "Combo x" + combo, x + 2, y + 3, color);
        if (showBest.get()) {
            gfx.text(font, "Best " + best, x + 2, y + 13, Palette.TEXT_MUTED);
        }
    }
}
