package dev.kitsune.client.module.movement;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.KeybindSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Free Look — hold a key to look around freely without turning the player's
 * body. Implemented by two mixins:
 * <ul>
 *   <li>{@code MouseHandlerMixin} — redirects {@code LocalPlayer.turn(DD)V}
 *       calls into {@link #applyMouseDelta(double, double)} when active. The
 *       deltas arriving here are POST-sensitivity (vanilla applies the
 *       sensitivity-cubed factor before calling {@code turn}), so we only
 *       need to apply the user's per-module sensitivity multiplier.</li>
 *   <li>{@code CameraMixin} — overrides the camera's final yaw/pitch with
 *       our stored values at the tail of {@code Camera.setup}.</li>
 * </ul>
 *
 * <p>The player's body and movement vector are completely unaffected. On
 * release, the camera snaps back to the body's yaw/pitch automatically.
 */
public class FreeLookModule extends Module {

    private final KeybindSetting holdKey      = addSetting(
            new KeybindSetting("Hold Key", GLFW.GLFW_KEY_LEFT_ALT));
    private final SliderSetting  sensitivity  = addSetting(
            new SliderSetting("Sensitivity", 1.0, 0.1, 3.0, 0.1));
    private final BooleanSetting thirdPerson  = addSetting(
            new BooleanSetting("Third Person", false));
    private final BooleanSetting invertPitch  = addSetting(
            new BooleanSetting("Invert Pitch", false));

    private CameraType previousPerspective = null;
    private boolean freeLookActive = false;

    private float freeLookYaw;
    private float freeLookPitch;

    public FreeLookModule() {
        super("Free Look", "Hold to look around without moving", Category.MOVEMENT);
    }

    public boolean isFreeLookActive() { return freeLookActive; }
    public float getFreeLookYaw()    { return freeLookYaw; }
    public float getFreeLookPitch()  { return freeLookPitch; }

    /**
     * Called from MouseHandlerMixin with deltas that vanilla has already
     * sensitivity-scaled. We only apply the per-module sensitivity multiplier
     * (1.0 = matches vanilla turn speed exactly).
     */
    public void applyMouseDelta(double yRotDelta, double xRotDelta) {
        double s = sensitivity.get();
        freeLookYaw   = (float) (freeLookYaw   + yRotDelta * s);
        float pitchDelta = (float) (xRotDelta * s);
        if (invertPitch.get()) pitchDelta = -pitchDelta;
        freeLookPitch = freeLookPitch + pitchDelta;
        if (freeLookPitch >  90.0f) freeLookPitch =  90.0f;
        if (freeLookPitch < -90.0f) freeLookPitch = -90.0f;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long window = mc.getWindow().handle();
        int key = holdKey.get();
        boolean keyHeld = key > 0 && GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;

        if (keyHeld && !freeLookActive) {
            freeLookActive = true;
            freeLookYaw   = mc.player.getYRot();
            freeLookPitch = mc.player.getXRot();
            if (thirdPerson.get()) {
                previousPerspective = mc.options.getCameraType();
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        } else if (!keyHeld && freeLookActive) {
            freeLookActive = false;
            if (previousPerspective != null) {
                mc.options.setCameraType(previousPerspective);
                previousPerspective = null;
            }
        }
    }

    @Override
    protected void onDisable() {
        if (freeLookActive) {
            freeLookActive = false;
            Minecraft mc = Minecraft.getInstance();
            if (previousPerspective != null && mc != null) {
                mc.options.setCameraType(previousPerspective);
                previousPerspective = null;
            }
        }
    }
}
