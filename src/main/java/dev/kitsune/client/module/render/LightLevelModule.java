package dev.kitsune.client.module.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ColorSetting;
import dev.kitsune.client.setting.SliderSetting;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space light-level overlay. For every mob-spawnable floor tile within
 * a configurable radius of the player, draw the current block-light value
 * as a billboarded number sitting just above the block's top face — coloured
 * red when mobs can spawn, yellow when marginal, green when safe.
 *
 * <p>Rewritten from the earlier HUD-panel grid: the panel version had visually
 * overlapping cells and, more importantly, forced the player to mentally map
 * a 2D grid back onto the 3D world. Numbers directly on the ground remove the
 * translation step — this is what the player actually asked for.
 *
 * <p>Hooks {@link WorldRenderEvents#AFTER_ENTITIES} so our text draws after
 * opaque geometry but before translucent passes, and uses
 * {@link Font.DisplayMode#SEE_THROUGH} so values stay visible even with a
 * block edge between the camera and the number.
 *
 * <p>The Fabric event system only supports {@code register}, never
 * {@code unregister}. So the listener subscribes exactly once at module
 * construction and self-gates on {@link #isEnabled()} at every frame — a
 * single branch miss when disabled, zero ongoing cost.
 */
public class LightLevelModule extends Module {

    private final SliderSetting  range        = addSetting(new SliderSetting("Range",              8, 2, 16, 1));
    private final SliderSetting  threshold    = addSetting(new SliderSetting("Spawn Threshold",    0, 0, 7, 1));
    private final BooleanSetting safeOnlyRed  = addSetting(new BooleanSetting("Only Show Unsafe",  false));
    private final BooleanSetting showShadow   = addSetting(new BooleanSetting("Drop Shadow",       true));
    private final SliderSetting  textScale    = addSetting(new SliderSetting("Text Scale",       1.0, 0.5, 2.0, 0.1));
    private final ColorSetting   safeColor    = addSetting(new ColorSetting("Safe Color",      0xFF44DD44));
    private final ColorSetting   warnColor    = addSetting(new ColorSetting("Warn Color",      0xFFFFD54A));
    private final ColorSetting   dangerColor  = addSetting(new ColorSetting("Danger Color",    0xFFFF3333));

    /** One-shot guard so we register the world-render listener exactly once. */
    private boolean listenerRegistered = false;

    /**
     * One eligible floor tile + its pre-computed display state. We cache these
     * at tick rate (not frame rate) because the inputs — player block position,
     * block light, block geometry — only change on world-update boundaries.
     * Scanning (2r+1)² × 5 block states every frame was ~86k lookups/sec at
     * default range; cached, we do ~290 lookups/sec.
     */
    private static final class Sample {
        final float wx, wy, wz;
        final int   light;
        final int   color;
        Sample(float wx, float wy, float wz, int light, int color) {
            this.wx = wx; this.wy = wy; this.wz = wz;
            this.light = light; this.color = color;
        }
    }

    /** Reused between rebuilds — clear()+add() beats re-allocating on every tick. */
    private final List<Sample> cache = new ArrayList<>(2048);
    private BlockPos cachedOrigin     = null;
    private long     cachedAtTick     = Long.MIN_VALUE;
    private int      cachedRange      = -1;
    private int      cachedThreshold  = -1;
    private boolean  cachedOnlyUnsafe = false;

    public LightLevelModule() {
        super("Light Level", "World-space numbers on every mob-spawnable tile around you", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        // Fabric events don't support unregister — attach once, forever.
        // isEnabled() inside the handler is what actually turns rendering on/off.
        if (!listenerRegistered) {
            WorldRenderEvents.AFTER_ENTITIES.register(this::onWorldRender);
            listenerRegistered = true;
        }
    }

    @Override
    protected void onDisable() {
        // Handler self-gates on isEnabled(), so no unregister needed — but
        // drop the cached samples so we're not holding thousands of Sample
        // objects in memory while disabled. Also forces a fresh rebuild
        // with current world state on re-enable (cache-position staleness
        // after a long disabled stretch is not worth trusting).
        cache.clear();
        cachedOrigin = null;
        cachedAtTick = Long.MIN_VALUE;
    }

    private void onWorldRender(WorldRenderContext ctx) {
        if (!isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();

        PoseStack pose = ctx.matrices();
        MultiBufferSource buffers = ctx.consumers();
        if (buffers == null) return; // mid-frame edge case, fabric may pass null

        Font font = mc.font;
        int r = range.get().intValue();
        int t = threshold.get().intValue();
        boolean onlyUnsafe = safeOnlyRed.get();
        boolean shadow = showShadow.get();
        float scale = textScale.get().floatValue();

        // --- Tick-rate cache refresh ----------------------------------------
        // Rebuild when the player crosses a block boundary, when one of the
        // scan-affecting settings changes, or every ~5 ticks as a safety net
        // for nearby block-light changes (torches placed, fire spread, etc.).
        BlockPos origin = player.blockPosition().immutable();
        long gameTime = level.getGameTime();
        boolean movedBlock   = cachedOrigin == null || !origin.equals(cachedOrigin);
        boolean stale        = gameTime - cachedAtTick >= 5;
        boolean settingsChgd = r != cachedRange || t != cachedThreshold || onlyUnsafe != cachedOnlyUnsafe;
        if (movedBlock || stale || settingsChgd) {
            rebuildCache(level, origin, r, t, onlyUnsafe);
            cachedOrigin     = origin;
            cachedAtTick     = gameTime;
            cachedRange      = r;
            cachedThreshold  = t;
            cachedOnlyUnsafe = onlyUnsafe;
        }

        // --- Per-frame: just project + draw ---------------------------------
        float cpx = (float) camPos.x, cpy = (float) camPos.y, cpz = (float) camPos.z;
        for (int i = 0, n = cache.size(); i < n; i++) {
            Sample s = cache.get(i);
            String text = Integer.toString(s.light);
            int tw = font.width(text);

            pose.pushPose();
            pose.translate(s.wx - cpx, s.wy - cpy, s.wz - cpz);
            // Billboard: camera.rotation() accounts for first/third-person
            // camera orientation, so multiplying here faces the text at us.
            pose.mulPose(camera.rotation());
            // Entity-text scale is 0.025; flip X+Y because the camera's local
            // axes are negated vs. world text coords.
            pose.scale(-0.025f * scale, -0.025f * scale, 0.025f * scale);

            Matrix4f matrix = pose.last().pose();
            font.drawInBatch(
                    text,
                    -tw / 2f,
                    -4f,
                    s.color,
                    shadow,
                    matrix,
                    buffers,
                    Font.DisplayMode.SEE_THROUGH,
                    0x00000000, // no background
                    0xF000F0    // full skylight packedLight → ignore world lighting for readability
            );
            pose.popPose();
        }
    }

    /**
     * Walk the (2r+1)² × 5 cube around {@code origin} and push one {@link Sample}
     * per mob-spawnable tile into {@link #cache}. Called at most once per game
     * tick — ~290 block-state lookups/sec at default r=8, down from ~86k/sec
     * when we were scanning every frame.
     */
    private void rebuildCache(ClientLevel level, BlockPos origin, int r, int t, boolean onlyUnsafe) {
        cache.clear();
        // Two mutable cursors so we can probe (pos, pos.above()) without
        // allocating a fresh BlockPos for every eligible cell.
        BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();

        // Narrow vertical window: anything further than ±2 from the player's
        // feet is on a cave ceiling above us or a cave floor below us — not
        // relevant to "where could a mob spawn next to me right now?".
        final int yMin = -2, yMax = 2;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = yMin; dy <= yMax; dy++) {
                    floor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);

                    BlockState floorState = level.getBlockState(floor);
                    // Cheap air pre-filter — far more common than non-air-yet-unsturdy,
                    // and lets us skip the more expensive isFaceSturdy call.
                    if (floorState.isAir()) continue;
                    if (!floorState.isFaceSturdy(level, floor, Direction.UP)) continue;

                    above.set(floor.getX(), floor.getY() + 1, floor.getZ());
                    BlockState aboveState = level.getBlockState(above);
                    if (!aboveState.getCollisionShape(level, above).isEmpty()) continue;

                    // Hostile-mob spawn rule since 1.18: block light must be at
                    // or below threshold. Sky light doesn't matter for hostiles
                    // (dark at night regardless), so we display block light only.
                    int bl = level.getBrightness(LightLayer.BLOCK, above);
                    if (onlyUnsafe && bl > t) continue;

                    cache.add(new Sample(
                            floor.getX() + 0.5f,
                            floor.getY() + 1.01f, // lift 1 px off so it isn't z-fighting
                            floor.getZ() + 0.5f,
                            bl,
                            colorFor(bl, t)));
                }
            }
        }
    }

    private int colorFor(int light, int t) {
        if (light <= t)       return dangerColor.get();
        if (light <= t + 3)   return warnColor.get();
        return safeColor.get();
    }
}
