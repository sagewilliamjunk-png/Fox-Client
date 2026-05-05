package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draggable potion-timer HUD widget.
 * Shows active effects with time bars, sorted by duration or category.
 */
public class PotionTimersModule extends Module implements HudWidget {

    private final BooleanSetting showBars     = addSetting(new BooleanSetting("Show duration bars", true));
    private final BooleanSetting showNegative = addSetting(new BooleanSetting("Show negative effects", true));
    private final BooleanSetting compactMode  = addSetting(new BooleanSetting("Compact mode", false));
    private final BooleanSetting hideInfinite = addSetting(new BooleanSetting("Hide infinite effects", false));
    private final SliderSetting  maxEffects   = addSetting(new SliderSetting("Max effects shown", 8, 2, 16, 1));
    private final ModeSetting    sortMode     = addSetting(new ModeSetting("Sort by", "Duration",
            List.of("Duration", "Alphabetical", "Category")));

    public PotionTimersModule() {
        super("Potion Timers", "Shows active effect durations", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "potion_timers"; }
    @Override public String displayName() { return "Potions"; }
    @Override public int widgetWidth()    { return compactMode.get() ? 110 : 140; }
    @Override public int widgetHeight() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 16;
        int n = Math.min(visibleCount(mc.player), maxEffects.get().intValue());
        int rowH = compactMode.get() ? 10 : (showBars.get() ? 16 : 12);
        return Math.max(16, n * rowH + 6);
    }
    /**
     * Hide entirely when the player has no effects that pass the current
     * filters — an empty "No active effects" box pinned in the corner is just
     * visual noise. {@link HudEditorScreen} uses a ghost outline for invisible
     * widgets, so the user can still reposition this one even when it's empty.
     */
    @Override public boolean isWidgetVisible() {
        if (!isEnabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return visibleCount(mc.player) > 0;
    }

    private int visibleCount(LocalPlayer player) {
        int c = 0;
        for (MobEffectInstance e : player.getActiveEffects()) {
            if (!showNegative.get() && e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) continue;
            if (hideInfinite.get() && e.isInfiniteDuration()) continue;
            c++;
        }
        return c;
    }

    @Override
    public void renderWidget(GuiGraphics gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());

        // Filter
        effects.removeIf(e -> {
            if (!showNegative.get() && e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return true;
            if (hideInfinite.get() && e.isInfiniteDuration()) return true;
            return false;
        });

        // Sort
        switch (sortMode.get()) {
            case "Duration"     -> effects.sort(Comparator.comparingInt(e -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
            case "Alphabetical" -> effects.sort(Comparator.comparing(e -> effectName(e)));
            case "Category"     -> effects.sort(Comparator.comparing(e -> e.getEffect().value().getCategory().name()));
        }

        int max = maxEffects.get().intValue();
        if (effects.size() > max) effects = effects.subList(0, max);

        int w = widgetWidth();
        boolean bars  = showBars.get();
        boolean compact = compactMode.get();
        int rowH = compact ? 10 : (bars ? 16 : 12);
        int bgH  = Math.max(16, effects.size() * rowH + 6);

        // Background
        gfx.fill(x - 2, y - 2, x + w + 2, y + bgH + 2, 0x90000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFF8844CC); // purple accent

        // Defensive: isWidgetVisible() already short-circuits the empty case,
        // but HudEditorScreen may still call renderWidget directly. Draw a
        // faint placeholder so the editor's drag-box isn't empty.
        if (effects.isEmpty()) {
            gfx.drawString(font, "\u00a78(no active effects)", x + 2, y + 2, 0xFF666666, false);
            return;
        }

        int row = 0;
        for (MobEffectInstance effect : effects) {
            int ry = y + row * rowH + 2;

            String name = effectName(effect);
            int amp = effect.getAmplifier();
            if (amp > 0) name += " " + toRoman(amp + 1);

            String duration;
            float progress;
            if (effect.isInfiniteDuration()) {
                duration = "\u221e";
                progress = 1f;
            } else {
                int ticks = effect.getDuration();
                int secs  = ticks / 20;
                duration  = String.format("%d:%02d", secs / 60, secs % 60);
                progress  = Math.min(1f, ticks / 1200f); // 60s = full bar
            }

            MobEffectCategory cat = effect.getEffect().value().getCategory();
            int nameColor;
            if (cat == MobEffectCategory.BENEFICIAL)    nameColor = 0xFF88FFAA;
            else if (cat == MobEffectCategory.HARMFUL)  nameColor = 0xFFFF8888;
            else                                         nameColor = 0xFFCCCCCC;

            // Blinking when about to expire
            if (!effect.isInfiniteDuration() && effect.getDuration() < 200) {
                long ms = System.currentTimeMillis();
                if ((ms / 300) % 2 == 0) nameColor = 0xFFFF3333;
            }

            if (compact) {
                String line = name + " " + duration;
                gfx.drawString(font, line, x + 2, ry, nameColor, false);
            } else {
                gfx.drawString(font, name, x + 2, ry, nameColor, false);
                int dw = font.width(duration);
                gfx.drawString(font, duration, x + w - dw - 4, ry, 0xFFDDDDDD, false);

                if (bars) {
                    int barX  = x + 2;
                    int barW  = w - 6;
                    int barY  = ry + 9;
                    int barH  = 3;
                    int barBg = 0xFF333333;
                    int barFg = cat == MobEffectCategory.HARMFUL ? 0xFFCC3333 : 0xFF3399CC;
                    gfx.fill(barX, barY, barX + barW, barY + barH, barBg);
                    int fill  = Math.max(1, (int)(barW * progress));
                    gfx.fill(barX, barY, barX + fill, barY + barH, barFg);
                }
            }
            row++;
        }
    }

    private static String effectName(MobEffectInstance e) {
        String id = e.getEffect().value().getDescriptionId();
        if (id.contains(".")) id = id.substring(id.lastIndexOf('.') + 1);
        if (!id.isEmpty()) id = Character.toUpperCase(id.charAt(0)) + id.substring(1);
        return id;
    }

    private static String toRoman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III";
            case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n);
        };
    }
}
