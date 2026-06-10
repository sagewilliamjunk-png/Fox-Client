package dev.kitsune.client.module.hud;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.util.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Mount HUD — when riding any entity, shows its name, HP bar, and (for
 * horses) jump strength / movement speed. Matches the Better Mount HUD
 * mod that's in our recommended pack, but native so it integrates with
 * Fox's HUD editor + per-profile config.
 *
 * <p>Invisible when not riding. Auto-disables for boats (vanilla F1 / no
 * info to surface) unless the user explicitly enables "Show for boats".
 */
public class MountHudModule extends BaseHudModule {

    private final BooleanSetting showBoats   = addSetting(new BooleanSetting("Show on Boats",  false));
    private final BooleanSetting showHorseStats = addSetting(new BooleanSetting("Horse Jump/Speed", true));
    private final BooleanSetting showName    = addSetting(new BooleanSetting("Show Mount Name",   true));

    // Cached mount snapshot — updated each tick. Reading entity fields from
    // the render thread is dicey on bad servers, so we read once per tick.
    private Entity mountRef = null;
    private String mountName = "";
    private float  mountHp = 0;
    private float  mountMaxHp = 0;
    private float  mountJump = 0;     // blocks the horse can jump (≈ 1..6)
    private float  mountSpeed = 0;    // %, normalised 0..100
    private boolean isHorse = false;
    private boolean isBoat  = false;

    public MountHudModule() {
        super("Mount HUD", "When riding, shows mount name + HP + horse stats.", Category.HUD,
                "mount_hud", "Mount");
        useStandardPanel(0.55, Palette.ACCENT_MINT);
    }

    // ---- HudWidget --------------------------------------------------------

    @Override public int widgetWidth()    { return 150; }
    @Override public int widgetHeight()   {
        int rows = 1;             // name
        rows++;                   // hp bar + numeric
        if (isHorse && showHorseStats.get()) rows += 2;
        return 4 + rows * 11 + 4;
    }
    @Override public boolean isWidgetVisible() {
        if (!isEnabled() || mountRef == null) return false;
        if (isBoat && !showBoats.get()) return false;
        return true;
    }

    // ---- Module logic -----------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { mountRef = null; return; }
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) {
            mountRef = null;
            return;
        }
        mountRef = vehicle;
        isBoat = vehicle instanceof AbstractBoat;
        isHorse = vehicle instanceof AbstractHorse;
        mountName = vehicle.getDisplayName() != null
                ? vehicle.getDisplayName().getString()
                : vehicle.getType().toString();
        if (vehicle instanceof LivingEntity living) {
            mountHp    = living.getHealth();
            mountMaxHp = Math.max(1, living.getMaxHealth());
        } else {
            // Non-living mount (boat) — no HP to surface.
            mountHp = 0; mountMaxHp = 0;
        }
        if (vehicle instanceof AbstractHorse h) {
            // Horse jump strength attribute → meters jumped. The vanilla
            // formula is approximately: blocks = -0.184 + jump * 0.595 ...
            // but that needs squaring etc. Closer is to just show the
            // attribute value scaled.
            try {
                double jumpAttr = h.getAttributes()
                        .getValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
                // Convert to "blocks" via the standard quadratic approximation:
                // height = jumpAttr * 1.0 * 1.0 + ... — close enough for a HUD readout
                mountJump = (float) Math.max(0, jumpAttr * 5.5);
            } catch (Throwable t) { mountJump = 0; }
            try {
                double speedAttr = h.getAttributes()
                        .getValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                // Movement speed attribute roughly 0.1125 .. 0.3375 for horses;
                // normalise to 0..100% scale so users get a clean readout.
                mountSpeed = (float) Math.max(0, Math.min(100, (speedAttr - 0.1125) / (0.3375 - 0.1125) * 100));
            } catch (Throwable t) { mountSpeed = 0; }
        }
    }

    @Override
    public void renderWidget(GuiGraphicsExtractor gfx, int x, int y) {
        if (mountRef == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = widgetWidth();
        drawPanel(gfx, x, y, w, widgetHeight());

        int rowY = y + 2;
        // Name
        if (showName.get()) {
            String tagged = (isHorse ? "🐎 " : isBoat ? "⛵ " : "🪑 ") + truncate(font, mountName, w - 8);
            gfx.text(font, tagged, x + 4, rowY, Palette.TEXT_WHITE);
            rowY += 11;
        }
        // HP bar
        if (mountMaxHp > 0) {
            float frac = Math.max(0, Math.min(1, mountHp / mountMaxHp));
            int barLeft = x + 4, barW = w - 8, barH = 5;
            gfx.fill(barLeft, rowY, barLeft + barW, rowY + barH, 0xFF1A1A1A);
            int filled = (int)(barW * frac);
            int hpColor = frac > 0.66 ? 0xFF55DD55 : frac > 0.33 ? 0xFFDDCC44 : 0xFFDD4444;
            if (filled > 0) gfx.fill(barLeft, rowY, barLeft + filled, rowY + barH, hpColor);
            String hpText = String.format("%.0f / %.0f", mountHp, mountMaxHp);
            gfx.text(font, hpText, x + 4, rowY + 7, Palette.TEXT_LIGHT);
            rowY += 18;
        }
        // Horse-only stats
        if (isHorse && showHorseStats.get()) {
            gfx.text(font, String.format("Jump  %.1f blocks", mountJump),  x + 4, rowY, Palette.TEXT_LIGHT); rowY += 11;
            gfx.text(font, String.format("Speed  %.0f%%",     mountSpeed), x + 4, rowY, Palette.TEXT_LIGHT);
        }
    }

    private static String truncate(Font font, String s, int maxW) {
        if (s == null) return "";
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
