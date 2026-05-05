package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Draggable widget showing attack-cooldown and reach distance.
 *
 * <p>The cooldown section can be displayed as a bar, segmented pip row, or
 * circular arc (pixel-approximated). The reach section is optional and
 * shows real-time distance to the crosshair hit-result.
 */
public class ReachCooldownHudModule extends Module implements HudWidget {

    private final BooleanSetting showReach      = addSetting(new BooleanSetting("Show Reach",       true));
    private final BooleanSetting showNumericCd  = addSetting(new BooleanSetting("Show Cooldown %",  true));
    private final BooleanSetting showNumericMs  = addSetting(new BooleanSetting("Show CD in ms",    false));
    private final BooleanSetting showReadyFlash = addSetting(new BooleanSetting("Flash when Ready", true));
    private final ModeSetting    cdStyle        = addSetting(new ModeSetting("Cooldown Style", "Bar",
            List.of("Bar", "Pips", "Segments")));
    private final ModeSetting    reachUnit      = addSetting(new ModeSetting("Reach Unit", "Blocks",
            List.of("Blocks", "m")));
    private final ColorSetting   readyColor     = addSetting(new ColorSetting("Ready Color",  0xFF55FF55));
    private final ColorSetting   chargingColor  = addSetting(new ColorSetting("Charging Color", 0xFFFFAA00));
    private final ColorSetting   emptyColor     = addSetting(new ColorSetting("Empty Color",  0xFFFF5555));
    private final SliderSetting  barWidth       = addSetting(new SliderSetting("Bar Width", 80, 40, 160, 4));

    public ReachCooldownHudModule() {
        super("Reach/Cooldown", "Attack cooldown bar + reach distance", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "reach_cooldown"; }
    @Override public String displayName() { return "Reach/CD"; }

    @Override
    public int widgetWidth() { return barWidth.get().intValue() + 12; }

    @Override
    public int widgetHeight() {
        int h = 14; // cooldown row always
        if (showReach.get())     h += 12;
        if (showNumericMs.get()) h += 10;
        return h;
    }

    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        float cd  = player.getAttackStrengthScale(0f); // 0..1
        int   bw  = barWidth.get().intValue();
        int   w   = widgetWidth();
        int   h   = widgetHeight();

        // Colour
        int col = cd >= 1.0f ? readyColor.get()
                : cd >= 0.5f ? chargingColor.get()
                : emptyColor.get();

        // Flash when ready
        if (showReadyFlash.get() && cd >= 1.0f) {
            long ms = System.currentTimeMillis();
            if ((ms / 200) % 2 == 0) col = 0xFFFFFFFF;
        }

        // Background
        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x90000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, col);

        int curY = y + 2;

        // --- Cooldown label + numeric ---
        String cdLabel = "CD";
        if (showNumericCd.get()) cdLabel += "  " + (int)(cd * 100) + "%";
        gfx.drawString(font, cdLabel, x + 2, curY, col, false);
        curY += 10;

        // --- Cooldown visualisation ---
        switch (cdStyle.get()) {
            case "Bar" -> {
                gfx.fill(x + 2, curY, x + 2 + bw, curY + 4, 0xFF222222);
                int fill = Math.max(2, (int)(bw * cd));
                gfx.fill(x + 2, curY, x + 2 + fill, curY + 4, col);
                curY += 6;
            }
            case "Pips" -> {
                // 10 small square pips
                int pips = 10;
                int pw   = (bw - pips + 1) / pips;
                int filled = Math.round(cd * pips);
                for (int i = 0; i < pips; i++) {
                    int px2 = x + 2 + i * (pw + 1);
                    int c2 = i < filled ? col : 0xFF333333;
                    gfx.fill(px2, curY, px2 + pw, curY + 4, c2);
                }
                curY += 6;
            }
            case "Segments" -> {
                // 4 thick segments separated by gaps
                int segs = 4;
                int sw   = (bw - (segs - 1) * 2) / segs;
                int filled = (int) Math.ceil(cd * segs);
                for (int i = 0; i < segs; i++) {
                    int px2 = x + 2 + i * (sw + 2);
                    // Partially fill the last active segment proportionally
                    int c2;
                    float segFrac = (cd * segs) - i;
                    if (i < filled - 1) {
                        c2 = col;
                    } else if (i == filled - 1) {
                        c2 = col; // last segment fully coloured
                    } else {
                        c2 = 0xFF333333;
                    }
                    gfx.fill(px2, curY, px2 + sw, curY + 5, c2);
                }
                curY += 7;
            }
        }

        // --- Numeric ms ---
        if (showNumericMs.get()) {
            // Full cooldown is ~12 ticks = ~600 ms in vanilla
            int ms = (int)((1f - cd) * 600);
            gfx.drawString(font, ms + " ms", x + 2, curY, 0xFFAAAAAA, false);
            curY += 10;
        }

        // --- Reach ---
        if (showReach.get()) {
            double reach = -1;
            if (mc.hitResult != null && mc.hitResult.getLocation() != null) {
                reach = mc.hitResult.getLocation().distanceTo(player.getEyePosition());
            }
            String unit = reachUnit.get().equals("m") ? "m" : " blk";
            String reachStr = reach < 0
                    ? "Reach  --"
                    : String.format("Reach  %.2f%s", reach, unit);
            int reachCol = reach < 0 ? 0xFF666666
                    : reach < 3.5 ? 0xFF55FF55
                    : reach < 5.0 ? 0xFFFFFF55
                    : 0xFFFF9955;
            gfx.drawString(font, reachStr, x + 2, curY, reachCol, false);
        }
    }
}
