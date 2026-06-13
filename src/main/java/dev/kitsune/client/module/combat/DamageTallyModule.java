package dev.kitsune.client.module.combat;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.hud.BaseHudModule;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * Damage Tally — running session totals of damage dealt vs. taken, with an
 * optional ratio.
 *
 * <ul>
 *   <li><b>Dealt</b> is fed from {@code PlayerAttackMixin}'s measured pre/post
 *       HP delta (same value the Crosshair Damage Indicator shows). On remote
 *       servers where the clientside HP drop is deferred a frame, some hits
 *       land in the indicator's poll path and aren't counted here — so treat
 *       Dealt as a clientside-visible lower bound.</li>
 *   <li><b>Taken</b> is the sum of your own health decreases each tick — fully
 *       clientside and exact.</li>
 * </ul>
 *
 * <p>Display-only summary of information you already have. Fair-play.
 */
public class DamageTallyModule extends BaseHudModule {

    private final BooleanSetting showRatio = addSetting(new BooleanSetting("Show Ratio", true));

    private double dealt = 0;
    private double taken = 0;
    private float  lastHealth = -1f;

    public DamageTallyModule() {
        super("Damage Tally", "Session damage dealt vs. taken.", Category.COMBAT,
                "damage_tally", "Damage");
        useStandardPanel(0.50, Palette.ACCENT_RED);
        useTextColor();
    }

    /** Called by PlayerAttackMixin with the measured clientside damage delta. */
    public void onDamageDealt(double amount) {
        if (!isEffectivelyEnabled()) return;
        if (amount > 0) dealt += amount;
    }

    @Override
    protected void onEnable() {
        dealt = 0;
        taken = 0;
        lastHealth = -1f;
    }

    @Override
    protected void onDisable() {
        dealt = 0;
        taken = 0;
        lastHealth = -1f;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) { lastHealth = -1f; return; }
        float hp = p.getHealth();
        if (lastHealth >= 0f && hp < lastHealth) {
            taken += (lastHealth - hp); // any health decrease = damage taken
        }
        lastHealth = hp;
    }

    @Override public int widgetWidth()  { return 92; }
    @Override public int widgetHeight() { return showRatio.get() ? rowsHeight(3) : rowsHeight(2); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        drawPanel(gfx, x, y, widgetWidth(), widgetHeight());

        int curY = y + 3;
        gfx.text(font, String.format("Dealt %.1f", dealt), x + 2, curY, Palette.GOOD);
        curY += 10;
        gfx.text(font, String.format("Taken %.1f", taken), x + 2, curY, Palette.BAD);
        curY += 10;
        if (showRatio.get()) {
            String ratio = taken > 0 ? String.format("%.2f", dealt / taken) : (dealt > 0 ? "∞" : "—");
            gfx.text(font, "Ratio " + ratio, x + 2, curY, textArgb());
        }
    }
}
