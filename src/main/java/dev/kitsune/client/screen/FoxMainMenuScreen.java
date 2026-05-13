package dev.kitsune.client.screen;

import dev.kitsune.client.core.Profile;
import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.features.FeatureRegistry;
import dev.kitsune.client.features.FoxFeature;
import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.server.ServerRuleStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Fox Client root menu — opened with the Fox menu key (default {@code ]}).
 *
 * Sections:
 *   - Active profile (with quick switch)
 *   - Toggle features (scrollable)
 *   - Open server rules editor
 */
public class FoxMainMenuScreen extends Screen {

    private final Screen parent;
    private int featureScrollOffset = 0;

    // Layout constants, single source of truth — duplicating these as magic
    // numbers in init()/render()/mouseScrolled() left "Features (N)" label
    // sitting 2 px inside the bottom edge of the "Manage Profiles" button.
    private static final int MANAGE_PROFILES_BOTTOM = 118;  // button y=98 + height 20
    private static final int HEADER_Y               = 126;  // 8 px gap below button
    private static final int FEATURE_LIST_TOP       = 140;  // 14 px gap below header baseline
    private static final int BOTTOM_MARGIN          = 80;   // space reserved for bottom buttons

    public FoxMainMenuScreen(Screen parent) {
        super(Component.translatable("kitsune.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bw = Math.min(300, this.width - 40);

        // Top: profile cycle button
        Profile active = ProfileManager.getActiveProfile();
        String activeName = active != null ? FoxTheme.capitalize(active.getName()) : "(none)";
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, 50, bw, 20,
                Component.literal("Profile: " + activeName + " (click to cycle)"),
                btn -> {
                    cycleProfile();
                    this.rebuildWidgets();
                }));

        // Capture current settings into the active profile
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, 74, bw, 20,
                Component.literal("Capture current settings into this profile"),
                btn -> {
                    if (active != null && this.minecraft != null) {
                        ProfileManager.captureCurrentInto(active, this.minecraft);
                    }
                }));

        // Manage Profiles button
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, 98, bw, 20,
                Component.literal("Manage Profiles"),
                btn -> this.minecraft.setScreen(new ProfileManagementScreen(this))));

        // Feature toggles — scrollable area between FEATURE_LIST_TOP and bottom buttons
        int featureTop = FEATURE_LIST_TOP;
        int featureBottom = this.height - BOTTOM_MARGIN;
        int maxVisible = Math.max(1, (featureBottom - featureTop) / 22);
        List<FoxFeature> features = FeatureRegistry.all();

        // Clamp scroll
        int maxScroll = Math.max(0, features.size() - maxVisible);
        if (featureScrollOffset > maxScroll) featureScrollOffset = maxScroll;
        if (featureScrollOffset < 0) featureScrollOffset = 0;

        int y = featureTop;
        for (int i = featureScrollOffset; i < features.size() && y + 20 <= featureBottom; i++) {
            FoxFeature f = features.get(i);
            boolean on = FeatureRegistry.isEnabled(f.id());
            Component label = Component.literal((on ? "\u00a7a[ON]  " : "\u00a7c[off] ") + f.displayName());
            String fid = f.id();
            this.addRenderableWidget(FoxButton.of(cx - bw / 2, y, bw, 20,
                    label,
                    btn -> {
                        FeatureRegistry.toggleForActiveProfile(fid);
                        this.rebuildWidgets();
                    }));
            y += 22;
        }

        // Scroll indicators
        if (featureScrollOffset > 0) {
            this.addRenderableWidget(FoxButton.of(cx + bw / 2 + 4, featureTop, 18, 18,
                    Component.literal("\u25b2"),
                    btn -> { featureScrollOffset = Math.max(0, featureScrollOffset - 3); this.rebuildWidgets(); }));
        }
        if (featureScrollOffset < maxScroll) {
            this.addRenderableWidget(FoxButton.of(cx + bw / 2 + 4, featureBottom - 18, 18, 18,
                    Component.literal("\u25bc"),
                    btn -> { featureScrollOffset = Math.min(maxScroll, featureScrollOffset + 3); this.rebuildWidgets(); }));
        }

        // Bottom row: server rules + settings + done
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, this.height - 74, bw, 20,
                Component.literal("Server Rules (" + ServerRuleStore.all().size() + ")"),
                btn -> this.minecraft.setScreen(new ServerRulesScreen(this))));

        this.addRenderableWidget(FoxButton.of(cx - bw / 2, this.height - 50, bw / 2 - 2, 20,
                Component.literal("\u2699 Settings"),
                btn -> this.minecraft.setScreen(new FoxSettingsScreen(this))));

        this.addRenderableWidget(FoxButton.of(cx + 2, this.height - 50, bw / 2 - 2, 20,
                Component.literal("Done"),
                btn -> this.onClose()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaH, double deltaV) {
        int maxVisible = Math.max(1, (this.height - BOTTOM_MARGIN - FEATURE_LIST_TOP) / 22);
        int maxScroll = Math.max(0, FeatureRegistry.all().size() - maxVisible);
        int old = featureScrollOffset;
        featureScrollOffset -= (int) deltaV;
        featureScrollOffset = Math.max(0, Math.min(maxScroll, featureScrollOffset));
        if (featureScrollOffset != old) this.rebuildWidgets();
        return true;
    }

    private void cycleProfile() {
        var names = ProfileManager.getProfileNames();
        if (names.isEmpty()) return;
        int idx = names.indexOf(ProfileManager.getActiveName());
        idx = (idx + 1) % names.size();
        ProfileManager.switchTo(names.get(idx), this.minecraft);
        FeatureRegistry.syncEnabledStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        // Solid dark backdrop so text is always readable
        gfx.fill(0, 0, this.width, this.height, 0xE0101010);

        super.extractRenderState(gfx, mouseX, mouseY, delta);

        // Title
        gfx.centeredText(this.font, "\u00a76Fox \u00a7eClient",
                this.width / 2, 12, 0xFFFFFFFF);
        gfx.centeredText(this.font,
                Component.literal("\u00a77server-safe by design \u00b7 no blatant hacks"),
                this.width / 2, 26, FoxTheme.TEXT_MUTED);

        // Feature list header — sits in the gap between the Manage Profiles
        // button (ends at MANAGE_PROFILES_BOTTOM) and the list (starts at
        // FEATURE_LIST_TOP), so nothing clips.
        int featureCount = FeatureRegistry.all().size();
        gfx.text(this.font, "\u00a76Features (" + featureCount + ")",
                this.width / 2 - 145, HEADER_Y, 0xFFFFFFFF);

        // Scroll hint if applicable
        int maxVisible = Math.max(1, (this.height - BOTTOM_MARGIN - FEATURE_LIST_TOP) / 22);
        if (featureCount > maxVisible) {
            gfx.text(this.font,
                    "\u00a78scroll for more",
                    this.width / 2 + 60, HEADER_Y, 0xFFAAAAAA);
        }

        // Idle fox mascot in the bottom-right corner
        double t = System.currentTimeMillis() / 1000.0;
        FoxIdleMascot.draw(gfx, this.width - 56, this.height - 40, t);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
