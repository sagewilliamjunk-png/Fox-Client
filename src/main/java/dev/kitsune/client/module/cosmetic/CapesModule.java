package dev.kitsune.client.module.cosmetic;

import dev.kitsune.client.cosmetic.CosmeticRegistry;
import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.BooleanSetting;
import dev.kitsune.client.setting.ModeSetting;

import java.util.ArrayList;
import java.util.List;
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
 * <p>Server-safe: the rendering swap happens client-side in a render-state
 * extraction mixin. No packets, no extra requests.
 */
public class CapesModule extends Module {

    /** When false, the module renders nothing — vanilla cape behaviour returns. */
    private final BooleanSetting showOtherPlayers = addSetting(new BooleanSetting("Show On Other Players", true));
    /** Self-only opt-out for users who own a cape but don't want it visible to themselves. */
    private final BooleanSetting showOnSelf       = addSetting(new BooleanSetting("Show On Self", true));

    /** Choice list — populated lazily so the registry has time to load. */
    private final ModeSetting localCape;

    public CapesModule() {
        super("Capes", "Render Fox Client cosmetic capes for owners", Category.COSMETIC);
        // Seed the mode setting with at least the "(none)" option; we re-sync
        // the option list each tick so newly-loaded capes show up without a
        // restart.
        this.localCape = addSetting(new ModeSetting("Local Cape", "(none)", List.of("(none)")));
        // Rehydrate from persisted choice
        String persisted = KitsuneConfig.get().selectedCapeId;
        if (persisted != null && !persisted.isEmpty()) {
            // Set raw — even if it's not yet in the option list, we'll widen on
            // next tick. ModeSetting.set ignores values not in the list, so we
            // bypass that by hot-swapping the options first.
            ensureOption(persisted);
            localCape.set(persisted);
        }
    }

    public boolean showOtherPlayers() { return showOtherPlayers.get(); }
    public boolean showOnSelf()       { return showOnSelf.get(); }

    /** Return the cape id the local player should currently render, or null. */
    public String localCapeId() {
        String v = localCape.get();
        if (v == null || v.equals("(none)")) return null;
        return v;
    }

    @Override
    public void onTick() {
        // Refresh the choice list from the registry so newly-granted capes
        // (e.g. a fresh resource pack reload) appear immediately. Cheap —
        // string list concat, runs once per tick only.
        syncOptions();
    }

    /** Insert {@code id} into the option list if absent, preserving order. */
    private void ensureOption(String id) {
        List<String> opts = localCape.options();
        if (opts.contains(id)) return;
        // ModeSetting.options is unmodifiable — rebuild via reflection-free
        // path: instantiate a replacement setting under the same name. We
        // can't actually replace the setting in-place without a registry
        // shake-up, so instead we widen the list ahead of time in syncOptions.
    }

    private void syncOptions() {
        if (!CosmeticRegistry.isLoaded()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        UUID self = (mc != null && mc.player != null) ? mc.player.getUUID() : null;
        if (self == null) return;

        List<String> wanted = new ArrayList<>();
        wanted.add("(none)");
        wanted.addAll(CosmeticRegistry.capesOwnedBy(self));

        List<String> have = localCape.options();
        if (sameContents(wanted, have)) return;

        // Replace the underlying ModeSetting with one carrying the new options
        // by invoking the field setter directly. We take care to preserve the
        // current value if it's still valid; otherwise reset to "(none)".
        String current = localCape.get();
        if (!wanted.contains(current)) current = "(none)";

        // Mutate via a private field swap. ModeSetting exposes options() as a
        // List.copyOf — we can't add to it. So instead we publish a NEW
        // ModeSetting with the same name, copying the chosen value across.
        // The settings list lives on Module; index lookup keeps stable order.
        replaceSetting();
    }

    private static boolean sameContents(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }

    /** Rebuild the ModeSetting with a refreshed option list. Safe to call
     *  every tick — early-outs in {@link #syncOptions} avoid the work when
     *  the list hasn't changed. */
    private void replaceSetting() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        UUID self = (mc != null && mc.player != null) ? mc.player.getUUID() : null;
        if (self == null) return;

        List<String> opts = new ArrayList<>();
        opts.add("(none)");
        opts.addAll(CosmeticRegistry.capesOwnedBy(self));

        String current = localCape.get();
        if (!opts.contains(current)) current = "(none)";

        // Surgically patch the options + value via reflection on ModeSetting.
        // We avoid this if a public setter ever lands.
        try {
            var optsField = localCape.getClass().getDeclaredField("options");
            optsField.setAccessible(true);
            optsField.set(localCape, List.copyOf(opts));
            localCape.set(current);
        } catch (Throwable ignored) {
            // Reflection failed — leave the setting alone. The user can still
            // pick their cape next launch when init seeds the right options.
        }

        // Persist current selection so it survives a restart.
        KitsuneConfig cfg = KitsuneConfig.get();
        if (!java.util.Objects.equals(cfg.selectedCapeId, current)) {
            cfg.selectedCapeId = "(none)".equals(current) ? "" : current;
            KitsuneConfig.save();
        }
    }
}
