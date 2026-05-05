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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * Dynamic crosshair that changes colour and style based on what the player
 * is looking at and their combat state.
 *
 * <p>Styles: Cross (default), Dot, Circle, Plus (no gap), Brackets</p>
 * <p>Colours: white default, red hostile, green friendly, yellow block</p>
 * <p>Expands when attack cooldown is ready.</p>
 */
public class DynamicCrosshairModule extends Module {

    private final ModeSetting    style        = addSetting(new ModeSetting("Style", "Cross",
            List.of("Cross", "Dot", "Circle", "Plus", "Brackets")));
    private final SliderSetting  size         = addSetting(new SliderSetting("Size", 4, 2, 10, 1));
    private final SliderSetting  thickness    = addSetting(new SliderSetting("Thickness", 1, 1, 3, 1));
    private final SliderSetting  gap          = addSetting(new SliderSetting("Gap", 1, 0, 4, 1));
    private final BooleanSetting showCooldown = addSetting(new BooleanSetting("Cooldown Expand",  true));
    private final BooleanSetting colorTarget  = addSetting(new BooleanSetting("Color by Target",  true));
    private final BooleanSetting showOutline  = addSetting(new BooleanSetting("Show Outline",     false));
    private final BooleanSetting showDot      = addSetting(new BooleanSetting("Center Dot",       true));
    private final ColorSetting   defaultColor = addSetting(new ColorSetting("Default Color",  0xFFFFFFFF));
    private final ColorSetting   hostileColor = addSetting(new ColorSetting("Hostile Color",  0xFFFF4444));
    private final ColorSetting   friendColor  = addSetting(new ColorSetting("Friendly Color", 0xFF44FF44));
    private final ColorSetting   blockColor   = addSetting(new ColorSetting("Block Color",    0xFFFFFF44));

    private final Consumer<RenderHudEvent> renderHandler = this::onRender;

    public DynamicCrosshairModule() {
        super("Dynamic Crosshair", "Custom crosshair with target colour and cooldown feedback", Category.RENDER);
    }

    @Override
    protected void onEnable()  { EventBus.subscribe(RenderHudEvent.class, renderHandler); }

    @Override
    protected void onDisable() { EventBus.unsubscribe(RenderHudEvent.class, renderHandler); }

    private void onRender(RenderHudEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;

        GuiGraphics gfx = event.graphics;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;

        // ---- pick colour ----
        int color = defaultColor.get();

        if (colorTarget.get()) {
            HitResult hit = mc.hitResult;
            if (hit != null) {
                if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult ehr) {
                    Entity target = ehr.getEntity();
                    color = (target instanceof Monster) ? hostileColor.get() : friendColor.get();
                } else if (hit.getType() == HitResult.Type.BLOCK) {
                    color = blockColor.get();
                }
            }
        }

        // ---- cooldown expand ----
        float cd = player.getAttackStrengthScale(0f);
        int sz = size.get().intValue();
        int g  = gap.get().intValue();
        int t  = thickness.get().intValue();

        if (showCooldown.get() && cd < 1.0f) {
            // Shrink the arms while charging, grow back when ready
            sz = Math.max(1, (int)(sz * 0.6f + sz * 0.4f * cd));
        }

        // Outline colour (semi-transparent black under)
        int outlineColor = 0x80000000;

        // ---- draw by style ----
        switch (style.get()) {
            case "Dot" -> {
                int r = Math.max(1, sz / 2);
                if (showOutline.get()) drawFilledCircle(gfx, cx, cy, r + 1, outlineColor);
                drawFilledCircle(gfx, cx, cy, r, color);
            }
            case "Circle" -> {
                int r = sz + 2;
                drawCircleOutline(gfx, cx, cy, r, t, color, outlineColor);
                if (showDot.get()) gfx.fill(cx, cy, cx + 1, cy + 1, color);
            }
            case "Plus" -> {
                // No gap, lines cross in centre
                drawCross(gfx, cx, cy, sz, t, 0, color, showOutline.get() ? outlineColor : 0);
                if (showDot.get()) gfx.fill(cx, cy, cx + 1, cy + 1, color);
            }
            case "Brackets" -> {
                drawBrackets(gfx, cx, cy, sz, t, g, color, showOutline.get() ? outlineColor : 0);
            }
            default -> { // Cross
                drawCross(gfx, cx, cy, sz, t, g, color, showOutline.get() ? outlineColor : 0);
                if (showDot.get()) gfx.fill(cx, cy, cx + 1, cy + 1, color);
            }
        }

        // ---- cooldown pips (small dots in a ring) ----
        if (showCooldown.get() && cd < 1.0f) {
            int pips   = 8;
            int filled = (int)(cd * pips);
            int radius = sz + g + 5;
            for (int i = 0; i < pips; i++) {
                double angle = (i / (double) pips) * Math.PI * 2 - Math.PI / 2;
                int dx = (int)(Math.cos(angle) * radius);
                int dy = (int)(Math.sin(angle) * radius);
                int pipCol = i < filled ? (color & 0x00FFFFFF) | 0xC0000000 : 0x40FFFFFF;
                gfx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, pipCol);
            }
        }
    }

    // ---- drawing helpers ----

    private static void drawCross(GuiGraphics gfx, int cx, int cy,
                                  int sz, int t, int g, int color, int outline) {
        // Top arm
        if (outline != 0) {
            gfx.fill(cx - 1,    cy - g - sz - 1, cx + t + 1,    cy - g + 1,   outline);
            gfx.fill(cx - 1,    cy + g,           cx + t + 1,    cy + g + sz + 1, outline);
            gfx.fill(cx - g - sz - 1, cy - 1,     cx - g + 1,    cy + t + 1,   outline);
            gfx.fill(cx + g,    cy - 1,            cx + g + sz + 1, cy + t + 1, outline);
        }
        gfx.fill(cx, cy - g - sz, cx + t, cy - g,    color); // top
        gfx.fill(cx, cy + g + 1,  cx + t, cy + g + 1 + sz, color); // bottom
        gfx.fill(cx - g - sz, cy, cx - g, cy + t,    color); // left
        gfx.fill(cx + g + 1,  cy, cx + g + 1 + sz, cy + t, color); // right
    }

    private static void drawBrackets(GuiGraphics gfx, int cx, int cy,
                                     int sz, int t, int g, int color, int outline) {
        // Four L-shaped corners
        int r = sz + g;
        int arm = sz / 2 + 1;
        // TL
        gfx.fill(cx - r, cy - r, cx - r + arm, cy - r + t, color); // horizontal
        gfx.fill(cx - r, cy - r, cx - r + t, cy - r + arm, color); // vertical
        // TR
        gfx.fill(cx + r - arm + 1, cy - r, cx + r + 1, cy - r + t, color);
        gfx.fill(cx + r + 1 - t, cy - r, cx + r + 1, cy - r + arm, color);
        // BL
        gfx.fill(cx - r, cy + r - t + 1, cx - r + arm, cy + r + 1, color);
        gfx.fill(cx - r, cy + r - arm + 1, cx - r + t, cy + r + 1, color);
        // BR
        gfx.fill(cx + r - arm + 1, cy + r - t + 1, cx + r + 1, cy + r + 1, color);
        gfx.fill(cx + r + 1 - t, cy + r - arm + 1, cx + r + 1, cy + r + 1, color);
    }

    private static void drawFilledCircle(GuiGraphics gfx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= r * r) {
                    gfx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }
    }

    private static void drawCircleOutline(GuiGraphics gfx, int cx, int cy,
                                          int r, int t, int color, int outline) {
        for (int dy = -r - 1; dy <= r + 1; dy++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist <= r + t + 1 && dist >= r - 1) {
                    int c = (dist <= r + t && dist >= r) ? color : outline;
                    if (c != 0) gfx.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, c);
                }
            }
        }
    }
}
