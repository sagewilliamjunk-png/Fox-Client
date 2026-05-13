package dev.kitsune.client.module.movement;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Toggle Sprint — auto-holds sprint so you don't have to.
 * Also shows a small on-screen HUD indicator with sprint / sneak state.
 *
 * <p>Three sprint modes:
 * <ul>
 *   <li><b>Always</b>: sprint whenever possible (not hungry, not blocking)</li>
 *   <li><b>Forward</b>: sprint only when walking forward</li>
 *   <li><b>Omni</b>: sprint in any direction</li>
 * </ul>
 */
public class ToggleSprintModule extends Module implements HudWidget {

    private final ModeSetting    sprintMode   = addSetting(new ModeSetting("Sprint Mode", "Forward",
            List.of("Always", "Forward", "Omni")));
    private final BooleanSetting sprintToggle = addSetting(new BooleanSetting("Toggle Sprint", true));
    private final BooleanSetting sneakToggle  = addSetting(new BooleanSetting("Toggle Sneak",  false));
    private final BooleanSetting requireFood  = addSetting(new BooleanSetting("Require Food",  true));
    private final BooleanSetting showHud      = addSetting(new BooleanSetting("Show HUD",      true));

    public ToggleSprintModule() {
        super("Toggle Sprint", "Auto-holds sprint and/or sneak with HUD indicator", Category.MOVEMENT);
        HudManager.register(this);
    }

    // ---- HudWidget ----

    @Override public String widgetId()    { return "toggle_sprint"; }
    @Override public String displayName() { return "Sprint"; }
    @Override public int widgetWidth()    { return 66; }
    @Override public int widgetHeight()   { return sneakToggle.get() ? 22 : 14; }
    @Override public boolean isWidgetVisible() { return isEnabled() && showHud.get(); }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        Font font = mc.font;

        boolean sprinting = player.isSprinting();
        boolean sneaking  = player.isCrouching();
        int w = widgetWidth();

        gfx.fill(x - 2, y - 2, x + w + 2, y + widgetHeight() + 2, 0x88000000);

        // Sprint indicator
        String sprintLabel = "\u00bb Sprint";
        int sprintColor = sprinting ? 0xFF55FF55 : 0xFF666666;
        gfx.fill(x - 2, y - 2, x - 2 + (sprinting ? 3 : 1), y + (sneakToggle.get() ? 10 : widgetHeight() + 2), sprinting ? 0xFF55FF55 : 0xFF333333);
        gfx.text(font, sprintLabel, x + 2, y + 2, sprintColor);

        // Sneak indicator
        if (sneakToggle.get()) {
            String sneakLabel = "\u25bc Sneak";
            int sneakColor = sneaking ? 0xFFFFCC44 : 0xFF666666;
            gfx.text(font, sneakLabel, x + 2, y + 12, sneakColor);
        }
    }

    // ---- Module logic ----

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (sprintToggle.get()) {
            boolean hungry = requireFood.get() && player.getFoodData().getFoodLevel() <= 6;
            if (!hungry && !player.isUsingItem() && !player.isCrouching()) {
                boolean shouldSprint = switch (sprintMode.get()) {
                    case "Always" -> true;
                    case "Omni"   -> hasAnyMovementInput(player);
                    default       -> player.input.hasForwardImpulse(); // Forward
                };
                if (shouldSprint) player.setSprinting(true);
            }
        }

        if (sneakToggle.get() && !player.isCrouching()) {
            player.setShiftKeyDown(true);
        }
    }

    /**
     * Omni-sprint check: does the player have ANY movement input held?
     * The previous implementation only checked {@code getDeltaMovement().x},
     * which is world-axis X velocity — not input. So Omni-sprint only
     * triggered when the player happened to be moving along world X.
     *
     * <p>Uses the {@code Input.getMovementVector()} Vec2 if available
     * (1.21.x). Falls back to {@code hasForwardImpulse()} only.
     */
    private static boolean hasAnyMovementInput(LocalPlayer player) {
        try {
            var v = player.input.getMoveVector();
            if (v != null && (Math.abs(v.x) > 1.0E-5f || Math.abs(v.y) > 1.0E-5f)) {
                return true;
            }
        } catch (Throwable ignored) {
            // API unavailable on this MC version
        }
        return player.input.hasForwardImpulse();
    }
}
