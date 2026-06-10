package dev.kitsune.client.module.combat;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.hud.BaseHudModule;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

/**
 * Combat Timer — counts down from the moment you take damage or land a hit
 * so you know when it's safe to log out / eat a gap / pearl away on servers
 * with a combat-log window.
 *
 * <p>Purely informational: damage taken is edge-detected from the local
 * player's {@code hurtTime}; hits landed are fed by the existing
 * {@code PlayerAttackMixin} hook. No packets, no input automation.
 */
public class CombatTimerModule extends BaseHudModule {

    private final SliderSetting  windowSec = addSetting(new SliderSetting("Combat Window (s)", 15, 5, 60, 1));
    private final BooleanSetting showBar   = addSetting(new BooleanSetting("Show Bar", true));
    private final BooleanSetting hitsCount = addSetting(new BooleanSetting("Trigger On Hit Dealt", true));

    /** Epoch ms when combat last (re)started; 0 = not in combat. */
    private long combatStartMs = 0;
    private int prevHurtTime = 0;

    public CombatTimerModule() {
        super("Combat Timer", "Countdown after taking or dealing damage", Category.COMBAT,
                "combat_timer", "Combat");
        useStandardPanel(0.50, Palette.ACCENT_RED);
        useTextColor();
    }

    @Override public int widgetWidth()  { return 86; }
    @Override public int widgetHeight() { return showBar.get() ? 18 : 14; }

    @Override
    public boolean isWidgetVisible() {
        return isEnabled() && remainingMs() > 0;
    }

    /** Called by PlayerAttackMixin when the local player swings at an entity. */
    public void onLocalAttack() {
        if (!isEffectivelyEnabled() || !hitsCount.get()) return;
        combatStartMs = System.currentTimeMillis();
    }

    private long remainingMs() {
        if (combatStartMs == 0) return 0;
        long end = combatStartMs + (long) (windowSec.get() * 1000);
        return Math.max(0, end - System.currentTimeMillis());
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            combatStartMs = 0;
            prevHurtTime = 0;
            return;
        }
        // Rising edge of hurtTime = we just took a hit.
        if (p.hurtTime > prevHurtTime) {
            combatStartMs = System.currentTimeMillis();
        }
        prevHurtTime = p.hurtTime;
    }

    @Override
    protected void onDisable() {
        combatStartMs = 0;
        prevHurtTime = 0;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        long rem = remainingMs();
        if (rem <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        drawPanel(gfx, x, y, w, h);

        double secs = rem / 1000.0;
        gfx.text(font, String.format("⚔ %.1fs", secs), x + 2, y + 3, textArgb());

        if (showBar.get()) {
            float pct = (float) (rem / (windowSec.get() * 1000.0));
            int barW = w - 4;
            gfx.fill(x + 2, y + h - 4, x + 2 + barW, y + h - 1, 0xFF222222);
            int fill = Math.max(1, (int) (barW * Math.min(1f, pct)));
            gfx.fill(x + 2, y + h - 4, x + 2 + fill, y + h - 1, accentArgb());
        }
    }
}
