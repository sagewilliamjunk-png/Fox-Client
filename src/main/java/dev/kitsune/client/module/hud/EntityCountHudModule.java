package dev.kitsune.client.module.hud;

import dev.kitsune.client.hud.HudManager;
import dev.kitsune.client.hud.HudWidget;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Lightweight entity-count read-out, useful as a perf indicator (a sudden
 * spike in mobs / item entities is often the cause of an FPS dip).
 *
 * <p>Counts are sampled per tick (not per frame) so the cost is constant
 * regardless of FPS. The world's entity iterator is read once and binned in
 * a single pass — total / living / items / players. No allocations on the
 * hot path beyond the count fields.
 */
public class EntityCountHudModule extends Module implements HudWidget {

    private final BooleanSetting showLiving = addSetting(new BooleanSetting("Living", true));
    private final BooleanSetting showItems  = addSetting(new BooleanSetting("Items",  true));
    private final BooleanSetting showPlayers = addSetting(new BooleanSetting("Players", true));
    private final SliderSetting  bgOpacity  = addSetting(new SliderSetting("BG Opacity", 0.50, 0.0, 1.0, 0.05));
    private final ColorSetting   accent     = addSetting(new ColorSetting("Accent", 0xFF8C8CFF));

    private int total, living, items, players;

    public EntityCountHudModule() {
        super("Entity Count", "Counts entities in the loaded world for a perf glance", Category.HUD);
        HudManager.register(this);
    }

    @Override public String widgetId()    { return "entity_count"; }
    @Override public String displayName() { return "Entities"; }
    @Override public int widgetWidth()    { return 100; }
    @Override public int widgetHeight() {
        int rows = 1; // total always
        if (showLiving.get())  rows++;
        if (showItems.get())   rows++;
        if (showPlayers.get()) rows++;
        return 4 + rows * 10;
    }
    @Override public boolean isWidgetVisible() { return isEnabled(); }

    @Override
    protected void onDisable() {
        total = living = items = players = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { onDisable(); return; }

        int t = 0, l = 0, p = 0, i = 0;
        // entitiesForRendering() is the cheapest accessor — same iterable used
        // by the renderer, no extra allocations or copies.
        for (Entity e : mc.level.entitiesForRendering()) {
            t++;
            if (e instanceof Player) p++;
            else if (e instanceof LivingEntity) l++;
            else if (e.getType().toString().contains("item")) i++;
        }
        total = t; living = l; players = p; items = i;
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        int h = widgetHeight();
        int bg = (int)(bgOpacity.get() * 255) << 24;

        gfx.fill(x - 2, y - 2, x + w + 2, y + h + 2, bg | 0x000000);
        gfx.fill(x - 2, y - 2, x + w + 2, y - 1, accent.get());

        int rowY = y + 2;
        gfx.text(font, "Total: " + total, x + 2, rowY, 0xFFFFFFFF); rowY += 10;
        if (showLiving.get())  { gfx.text(font, "Living: "  + living,  x + 2, rowY, 0xFFCCCCCC); rowY += 10; }
        if (showPlayers.get()) { gfx.text(font, "Players: " + players, x + 2, rowY, 0xFFCCCCCC); rowY += 10; }
        if (showItems.get())   { gfx.text(font, "Items: "   + items,   x + 2, rowY, 0xFFCCCCCC); }
    }
}
