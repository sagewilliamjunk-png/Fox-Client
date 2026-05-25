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

    /** Return the cape id the local player should currently render, or null. */
    public String localCapeId() {
        String v = localCape.get();
        if (v == null || NONE.equals(v)) return null;
        return v;
    }

    @Override
    public void onTick() {
        syncOptions();
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
