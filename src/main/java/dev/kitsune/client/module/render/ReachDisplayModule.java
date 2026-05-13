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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * HUD readout of the distance to whatever the crosshair is over.
 * Does NOT extend reach — purely informational, fully server-safe.
 *
 * <p>Can be positioned near the crosshair, top-left, or top-right.
 * Colour codes the distance: green = close, yellow = mid, red = far.
 */
public class ReachDisplayModule extends Module {

    private final ModeSetting    unit         = addSetting(new ModeSetting("Unit", "Blocks",
            List.of("Blocks", "Meters")));
    private final ModeSetting    position     = addSetting(new ModeSetting("Position", "Crosshair",
            List.of("Crosshair", "Top-left", "Top-right")));
    private final BooleanSetting hideIfNoHit  = addSetting(new BooleanSetting("Hide if No Target", true));
    private final BooleanSetting showTargetType = addSetting(new BooleanSetting("Show Target Type", true));
    private final BooleanSetting colorByDist  = addSetting(new BooleanSetting("Color by Distance", true));
    private final SliderSetting  closeRange   = addSetting(new SliderSetting("Close Range",  3.5, 1.0, 5.0, 0.5));
    private final SliderSetting  farRange     = addSetting(new SliderSetting("Far Range",    5.0, 3.0, 10.0, 0.5));
    private final ColorSetting   closeColor   = addSetting(new ColorSetting("Close Color",  0xFF55FF55));
    private final ColorSetting   midColor     = addSetting(new ColorSetting("Mid Color",    0xFFFFFF55));
    private final ColorSetting   farColor     = addSetting(new ColorSetting("Far Color",    0xFFFF9955));
    private final ColorSetting   noHitColor   = addSetting(new ColorSetting("No Hit Color", 0xFF888888));

    private final Consumer<RenderHudEvent> renderHandler = this::onRender;

    public ReachDisplayModule() {
        super("Reach Display", "Shows distance to crosshair target", Category.RENDER);
    }

    @Override protected void onEnable()  { EventBus.subscribe(RenderHudEvent.class, renderHandler); }
    @Override protected void onDisable() { EventBus.unsubscribe(RenderHudEvent.class, renderHandler); }

    private void onRender(RenderHudEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Snapshot hitResult once — it's mutated by the game thread between ticks,
        // so reading mc.hitResult repeatedly risks a null between the check and the use.
        HitResult hit = mc.hitResult;
        boolean hasHit = hit != null && hit.getLocation() != null;
        if (!hasHit && hideIfNoHit.get()) return;

        double dist = hasHit
                ? hit.getLocation().distanceTo(player.getEyePosition())
                : 0;

        GuiGraphicsExtractor gfx = event.graphics;
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        String unitStr = unit.get().equals("Meters") ? "m" : " blk";
        String distStr = hasHit ? String.format("%.2f%s", dist, unitStr) : "--";

        // Target type label
        String typeStr = "";
        if (showTargetType.get() && hasHit) {
            typeStr = switch (hit.getType()) {
                case BLOCK  -> "\u25a0 Block";
                case ENTITY -> "\u25cf Entity";
                default     -> "";
            };
        }

        String text = typeStr.isEmpty() ? distStr : typeStr + "  " + distStr;

        // Colour
        int color;
        if (!hasHit) {
            color = noHitColor.get();
        } else if (colorByDist.get()) {
            double close = closeRange.get();
            double far   = farRange.get();
            color = dist < close ? closeColor.get()
                    : dist < far ? midColor.get()
                    : farColor.get();
        } else {
            color = closeColor.get();
        }

        // Position
        int tx, ty;
        switch (position.get()) {
            case "Top-left"  -> { tx = 4;              ty = 4; }
            case "Top-right" -> { tx = w - font.width(text) - 4; ty = 4; }
            default          -> { // Crosshair
                tx = w / 2 + 8;
                ty = h / 2 + 8;
            }
        }

        // Small background pill
        int tw = font.width(text);
        gfx.fill(tx - 2, ty - 1, tx + tw + 2, ty + 9, 0x70000000);
        gfx.text(font, text, tx, ty, color);
    }
}
