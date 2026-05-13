package dev.kitsune.client.module.render;

import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.event.RenderHudEvent;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.ModeSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;
import java.util.function.Consumer;

/**
 * Flashes a coloured vignette around the screen edge when the player takes damage.
 *
 * <p>Intensity is driven by vanilla {@code hurtTime} so it naturally decays
 * with the same rhythm as the vanilla damage tilt effect.
 *
 * <p>Three flash styles:
 * <ul>
 *   <li>Vignette — four border bars (classic)</li>
 *   <li>Full Screen — translucent overlay over the entire screen</li>
 *   <li>Pulse — alternating alpha for a strobe effect</li>
 * </ul>
 */
public class HitColorFlashModule extends Module {

    private final ColorSetting   flashColor   = addSetting(new ColorSetting("Flash Color",   0x66FF3322));
    private final SliderSetting  strength     = addSetting(new SliderSetting("Strength",     0.7, 0.1, 1.0, 0.05));
    private final SliderSetting  duration     = addSetting(new SliderSetting("Duration",     10,  5,  30,  1));
    private final SliderSetting  borderSize   = addSetting(new SliderSetting("Border px",    24,  4,  64,  2));
    private final BooleanSetting showOnKill   = addSetting(new BooleanSetting("Green on Kill",   false));
    private final ColorSetting   killColor    = addSetting(new ColorSetting("Kill Color",    0x5544FF44));
    private final ModeSetting    style        = addSetting(new ModeSetting("Style", "Vignette",
            List.of("Vignette", "Full Screen", "Pulse")));

    /** Last sampled MOB_KILLS stat. -1 = not yet sampled (skip first-tick spurious flash). */
    private int lastKillStat = -1;
    private int killFlash    = 0; // ticks of kill-green to show

    private final Consumer<RenderHudEvent> renderHandler = this::onRender;

    public HitColorFlashModule() {
        super("Hit Flash", "Screen-edge vignette flash on damage or kill", Category.RENDER);
    }

    @Override
    protected void onEnable()  { EventBus.subscribe(RenderHudEvent.class, renderHandler); }

    @Override
    protected void onDisable() { EventBus.unsubscribe(RenderHudEvent.class, renderHandler); }

    @Override
    public void onTick() {
        if (!showOnKill.get()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        // Detect a kill by watching MOB_KILLS stat increment. First sample is
        // taken silently — only diffs trigger the flash, so a player who joins
        // with kills already on their stat doesn't see a spurious green pulse.
        try {
            int ks = p.getStats() != null
                    ? p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM,
                            net.minecraft.stats.Stats.MOB_KILLS)
                    : 0;
            if (lastKillStat < 0) {
                lastKillStat = ks; // initial sample, no flash
            } else if (ks > lastKillStat) {
                killFlash = 8;
                lastKillStat = ks;
            }
        } catch (Throwable ignored) {}

        if (killFlash > 0) killFlash--;
    }

    private void onRender(RenderHudEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        GuiGraphicsExtractor gfx = event.graphics;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        // Kill flash (green, brief)
        if (killFlash > 0 && showOnKill.get()) {
            float kt = killFlash / 8f;
            int kc = killColor.get();
            int ka = (int)(((kc >>> 24) & 0xFF) * kt);
            drawFlash(gfx, (ka << 24) | (kc & 0x00FFFFFF), w, h);
        }

        // Hurt flash
        if (player.hurtTime <= 0) return;
        float t = player.hurtTime / duration.get().floatValue();
        if (t <= 0) return;
        t = Math.min(1f, t * strength.get().floatValue());

        // Pulse style modifies alpha by a sine wave
        if (style.get().equals("Pulse")) {
            long ms = System.currentTimeMillis();
            t *= (float) Math.abs(Math.sin(ms / 100.0));
        }

        int baseColor  = flashColor.get();
        int baseAlpha  = (baseColor >>> 24) & 0xFF;
        int alpha      = (int)(baseAlpha * t);
        int color      = (alpha << 24) | (baseColor & 0x00FFFFFF);

        if (style.get().equals("Full Screen")) {
            gfx.fill(0, 0, w, h, color);
        } else {
            drawFlash(gfx, color, w, h);
        }
    }

    private void drawFlash(GuiGraphicsExtractor gfx, int color, int w, int h) {
        int b = borderSize.get().intValue();
        gfx.fill(0,     0,     w, b,         color); // top
        gfx.fill(0,     h - b, w, h,         color); // bottom
        gfx.fill(0,     b,     b, h - b,     color); // left
        gfx.fill(w - b, b,     w, h - b,     color); // right
    }
}
