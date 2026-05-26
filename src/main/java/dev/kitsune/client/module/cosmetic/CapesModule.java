package dev.kitsune.client.module.cosmetic;

import dev.kitsune.client.KitsuneClient;
import dev.kitsune.client.cosmetic.CosmeticRegistry;
import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Toggleable cape rendering for Fox Client cosmetic owners.
 *
 * <p>Two effects when enabled:
 * <ul>
 *   <li>For other players in the cosmetic registry: their first owned cape
 *       renders automatically (so the local user sees other Fox users' capes).</li>
 *   <li>For the local player: the {@link #localCape} setting picks which of
 *       the user's owned capes is displayed. The choice persists in
 *       {@link KitsuneConfig#selectedCapeId}.</li>
 * </ul>
 *
 * <p>v1.2: dropped the {@code Field.setAccessible} reflection hack that silently
 * failed against {@code ModeSetting}'s previously-final options list. The setting
 * now exposes a public {@link ModeSetting#setOptions(List)} method and we call
 * it directly. The "owned cape never appears in the dropdown" bug is gone with
 * the reflection.
 *
 * <p>Server-safe: the rendering swap happens client-side in a render-state
 * extraction mixin. No packets, no extra requests.
 */
public class CapesModule extends Module {

    private static final String NONE = "(none)";

    /** When false, the module renders nothing — vanilla cape behaviour returns. */
    private final BooleanSetting showOtherPlayers = addSetting(new BooleanSetting("Show On Other Players", true));
    /** Self-only opt-out for users who own a cape but don't want it visible to themselves. */
    private final BooleanSetting showOnSelf       = addSetting(new BooleanSetting("Show On Self", true));
    /** Velocity-driven cape sway. When on, the cape leans forward / back / sideways
     *  proportional to the player's horizontal velocity, smoothed each tick. */
    private final BooleanSetting physicsEnabled   = addSetting(new BooleanSetting("Physics Sway", true));
    /** Multiplier on the velocity → degrees mapping. 1.0 = "natural" (Lunar-ish).
     *  Lower = stiffer cape; higher = exaggerated cartoony sway. */
    private final dev.kitsune.client.setting.SliderSetting physicsStrength =
            addSetting(new dev.kitsune.client.setting.SliderSetting("Physics Strength", 1.0, 0.2, 2.5, 0.1));

    // Per-player smoothed sway state. Read by AvatarRendererCapeMixin each
    // frame to drive capeLean / capeFlap. Cleared on disable.
    private final java.util.Map<java.util.UUID, float[]> capeSway = new java.util.concurrent.ConcurrentHashMap<>();

    /** Choice list — re-synced from {@link CosmeticRegistry} each tick. */
    private final ModeSetting localCape;

    /** Cached for sameContents() early-out — avoids touching the setting when
     *  nothing has changed (which would no-op anyway but is wasted work). */
    private List<String> lastSyncedOptions = List.of(NONE);

    public CapesModule() {
        super("Capes", "Render Fox Client cosmetic capes for owners", Category.COSMETIC);
        this.localCape = addSetting(new ModeSetting("Local Cape", NONE, List.of(NONE)));

        // Rehydrate from persisted choice. We don't have the registry loaded
        // yet at construction time, so we just remember the desired value and
        // syncOptions() below will apply it the moment the option list grows
        // to include it.
        String persisted = KitsuneConfig.get().selectedCapeId;
        if (persisted != null && !persisted.isEmpty()) {
            localCape.setOptions(List.of(NONE, persisted));
            localCape.set(persisted);
            lastSyncedOptions = List.of(NONE, persisted);
        }
    }

    public boolean showOtherPlayers() { return showOtherPlayers.get(); }
    public boolean showOnSelf()       { return showOnSelf.get(); }
    public boolean physicsEnabled()   { return physicsEnabled.get(); }

    /** Cape sway tuple: [lean (forward/back, deg), lean2 (sideways, deg), flap].
     *  Read by AvatarRendererCapeMixin to override the vanilla cape angles. */
    public float[] swayFor(java.util.UUID uuid) {
        return capeSway.get(uuid);
    }

    @Override
    protected void onDisable() {
        capeSway.clear();
    }

    /** Return the cape id the local player should currently render, or null. */
    public String localCapeId() {
        String v = localCape.get();
        if (v == null || NONE.equals(v)) return null;
        return v;
    }

    @Override
    public void onTick() {
        syncOptions();
        tickPhysics();
    }

    /** Smooth per-player velocity into a lean/lean2/flap tuple. Skips quickly
     *  when physics is off; iterates only the players the registry knows about
     *  + ourselves, which is at most the current server roster (cheap). */
    private void tickPhysics() {
        if (!physicsEnabled.get()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        float strength = physicsStrength.get().floatValue();
        java.util.HashSet<java.util.UUID> seen = new java.util.HashSet<>();
        for (var p : mc.level.players()) {
            seen.add(p.getUUID());
            // Skip elytra glide — vanilla's wing logic owns the cape pose there.
            if (p.isFallFlying()) {
                capeSway.put(p.getUUID(), new float[]{0f, 0f, 0f});
                continue;
            }
            var v = p.getDeltaMovement();
            double horizSpeed = Math.sqrt(v.x * v.x + v.z * v.z);
            // Project velocity onto the player's facing so forward motion =
            // positive lean and lateral motion drives lean2.
            float yawRad = (float) Math.toRadians(p.getYRot());
            double forwardDot  = v.x * -Math.sin(yawRad) + v.z * Math.cos(yawRad);
            double sidewaysDot = v.x *  Math.cos(yawRad) + v.z * Math.sin(yawRad);
            float targetLean  = (float)(forwardDot  * 30f * strength);
            float targetLean2 = (float)(sidewaysDot * 30f * strength);
            float targetFlap  = (float) Math.min(15f, horizSpeed * 10f * strength);

            // Cap so even a Speed II sprint doesn't fold the cape over.
            targetLean  = clamp(targetLean,  -45f, 45f);
            targetLean2 = clamp(targetLean2, -45f, 45f);

            float[] state = capeSway.computeIfAbsent(p.getUUID(), u -> new float[3]);
            // Linear smoothing — 25% per tick is a fast-but-natural curve.
            state[0] += (targetLean  - state[0]) * 0.25f;
            state[1] += (targetLean2 - state[1]) * 0.25f;
            state[2] += (targetFlap  - state[2]) * 0.25f;
        }
        // Garbage-collect entries for players that left.
        if (capeSway.size() > seen.size()) {
            capeSway.keySet().retainAll(seen);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void syncOptions() {
        if (!CosmeticRegistry.isLoaded()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        UUID self = (mc != null && mc.player != null) ? mc.player.getUUID() : null;
        if (self == null) return;

        List<String> wanted = new ArrayList<>();
        wanted.add(NONE);
        wanted.addAll(CosmeticRegistry.capesOwnedBy(self));

        if (sameContents(wanted, lastSyncedOptions)) return;
        lastSyncedOptions = List.copyOf(wanted);

        // Preserve the user's current choice if still valid; otherwise revert
        // to "(none)" via setOptions' built-in fallback.
        String current = localCape.get();
        localCape.setOptions(wanted);
        if (wanted.contains(current)) localCape.set(current);

        // Persist current selection so it survives a restart.
        KitsuneConfig cfg = KitsuneConfig.get();
        String now = localCape.get();
        String toStore = NONE.equals(now) ? "" : now;
        if (!Objects.equals(cfg.selectedCapeId, toStore)) {
            cfg.selectedCapeId = toStore;
            KitsuneConfig.save();
        }
    }

    private static boolean sameContents(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }
}
