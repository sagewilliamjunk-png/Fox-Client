package dev.kitsune.client.module.render;

import dev.kitsune.client.event.EventBus;
import dev.kitsune.client.event.RenderHudEvent;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.SliderSetting;
import dev.kitsune.client.waypoint.Waypoint;
import dev.kitsune.client.waypoint.WaypointManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Draws waypoints in 3D world space as billboarded markers — what Xaero's
 * minimap shows: a small colored square with the waypoint's initials, the
 * name floating just below it, and the distance underneath that.
 *
 * <h2>Approach</h2>
 * Instead of hooking into {@code LevelRenderer.renderLevel} (a 9-arg method
 * that drifts between MC subversions and conflicts with Sodium/Iris vertex
 * pipelines), this renderer runs as a regular HUD overlay subscriber to
 * {@link RenderHudEvent}. We manually project each waypoint's world position
 * to screen coords using the active {@link Camera}'s position and rotation,
 * then draw 2D markers at those projected coordinates.
 *
 * <p>Trade-off: the marker is always-on-top (never occluded by terrain),
 * which 99% of Xaero users actually want. The 1% who want occlusion can
 * disable this and the marker shows on the minimap only.
 *
 * <h2>Distance-based sizing</h2>
 * Closer waypoints render bigger; far waypoints shrink to keep the HUD
 * readable when you have many waypoints in view.
 */
public class WaypointWorldOverlayModule extends Module {

    private final BooleanSetting showName     = addSetting(new BooleanSetting("Show Name",     true));
    private final BooleanSetting showDistance = addSetting(new BooleanSetting("Show Distance", true));
    private final BooleanSetting showAtEdge   = addSetting(new BooleanSetting("Show At Edge",  true));
    private final SliderSetting  maxDistance  = addSetting(new SliderSetting("Max Distance (blocks)", 1000, 32, 5000, 32));
    private final SliderSetting  iconScale    = addSetting(new SliderSetting("Icon Scale", 1.0, 0.5, 3.0, 0.1));

    private final Consumer<RenderHudEvent> renderHandler = this::onRender;

    public WaypointWorldOverlayModule() {
        super("Waypoint Beams",
              "Draws colored waypoint markers in 3D world space (Xaeros-style).",
              Category.RENDER);
    }

    @Override protected void onEnable()  { EventBus.subscribe(RenderHudEvent.class, renderHandler); }
    @Override protected void onDisable() { EventBus.unsubscribe(RenderHudEvent.class, renderHandler); }

    private void onRender(RenderHudEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return;

        Font font = mc.font;
        GuiGraphicsExtractor gfx = event.graphics;
        int guiWidth  = mc.getWindow().getGuiScaledWidth();
        int guiHeight = mc.getWindow().getGuiScaledHeight();

        var waypoints = WaypointManager.current();
        if (waypoints.isEmpty()) return;

        // Camera basis. rotation() is the camera-to-world quaternion;
        // inverse rotates world points INTO camera space.
        Vec3 camPos = camera.position();
        Quaternionf camRotInv = new Quaternionf(camera.rotation()).conjugate();

        // FOV in radians for perspective math. Camera.getFov() returns
        // degrees and respects our ZoomModule injection.
        float fovRad = (float) Math.toRadians(camera.getFov());
        float aspect = guiWidth / (float) Math.max(1, guiHeight);
        float halfH  = (float) Math.tan(fovRad / 2.0);

        double maxDist = maxDistance.get();
        double maxDist2 = maxDist * maxDist;
        float scale = iconScale.get().floatValue();

        for (Waypoint w : waypoints) {
            double dx = w.x() + 0.5 - camPos.x;
            double dy = w.y() + 0.5 - camPos.y;
            double dz = w.z() + 0.5 - camPos.z;
            double dist2 = dx * dx + dy * dy + dz * dz;
            if (dist2 > maxDist2) continue;
            double dist = Math.sqrt(dist2);

            // Rotate offset into camera space (-Z forward, +X right, +Y up).
            Vector3f camSpace = new Vector3f((float) dx, (float) dy, (float) dz);
            camSpace.rotate(camRotInv);

            // Behind camera → skip (will be edge-clamped below if showAtEdge).
            boolean behindOrOffscreen = camSpace.z >= -0.05f;

            float ndcX, ndcY;
            if (behindOrOffscreen) {
                if (!showAtEdge.get()) continue;
                // Hint direction via the projected X axis when behind / out of view.
                ndcX = camSpace.x >= 0 ? 1.2f : -1.2f;
                ndcY = -camSpace.y * 0.3f;
            } else {
                ndcX = (camSpace.x / -camSpace.z) / (halfH * aspect);
                ndcY = (camSpace.y / -camSpace.z) / halfH;
            }

            // Off-screen handling — clamp to the edge so the waypoint stays
            // visible as a directional hint instead of vanishing entirely.
            boolean clamped = Math.abs(ndcX) > 1.0f || Math.abs(ndcY) > 1.0f || behindOrOffscreen;
            if (clamped && !showAtEdge.get()) continue;
            ndcX = Math.max(-1.0f, Math.min(1.0f, ndcX));
            ndcY = Math.max(-1.0f, Math.min(1.0f, ndcY));

            int screenX = (int) ((ndcX * 0.5f + 0.5f) * guiWidth);
            int screenY = (int) ((1.0f - (ndcY * 0.5f + 0.5f)) * guiHeight);

            // Distance falloff so far waypoints don't dominate the HUD.
            float sizeMul = (float) Math.max(0.35, Math.min(1.0, 32.0 / Math.max(8.0, dist)));
            int boxSize = Math.round(16 * scale * sizeMul);
            int symbolSize = Math.round(12 * scale * sizeMul);

            // Marker: colored square + 1px dark border.
            int half = boxSize / 2;
            gfx.fill(screenX - half - 1, screenY - half - 1, screenX + half + 1, screenY + half + 1, 0xCC000000);
            gfx.fill(screenX - half,     screenY - half,     screenX + half,     screenY + half,     w.color());

            // Symbol on top — first letter or skull for deathpoints.
            String sym = w.deathpoint() ? "☠"
                       : (w.symbol() == null || w.symbol().isEmpty() ? "•" : w.symbol().substring(0, 1));
            int symW = font.width(sym);
            // Contrasting text color: dark for light backgrounds, light for dark.
            int textColor = isLightColor(w.color()) ? 0xFF000000 : 0xFFFFFFFF;
            gfx.text(font, sym, screenX - symW / 2, screenY - 4, textColor);

            // Name + distance below the marker. Skip when zoomed away.
            int below = screenY + half + 3;
            if (showName.get() && sizeMul > 0.55f) {
                String name = w.name();
                int nameW = font.width(name);
                // Backdrop pill so the text stays readable over busy terrain.
                gfx.fill(screenX - nameW / 2 - 2, below - 1, screenX + nameW / 2 + 2, below + 9, 0x88000000);
                gfx.text(font, name, screenX - nameW / 2, below, 0xFFFFFFFF);
                below += 11;
            }
            if (showDistance.get() && sizeMul > 0.55f) {
                String distStr = (int) dist + "m";
                int dW = font.width(distStr);
                gfx.fill(screenX - dW / 2 - 2, below - 1, screenX + dW / 2 + 2, below + 9, 0x88000000);
                gfx.text(font, distStr, screenX - dW / 2, below, 0xFFAAAAAA);
            }
        }
    }

    /** Naïve brightness check (sum of RGB / 3) — picks black-vs-white symbol
     *  text against the marker background so it's always readable. */
    private static boolean isLightColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        return (r + g + b) / 3 > 140;
    }
}
