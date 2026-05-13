package dev.kitsune.client.screen;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.core.ProfileIO;
import dev.kitsune.client.gui.widget.FoxButton;
import dev.kitsune.client.hud.NotificationManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Fox Client global settings screen. Controls config options like
 * fox title screen, auto-reconnect, watermark, adaptive FPS, etc.
 * Opened from the Fox Menu, pause screen, or ClickGUI gear icon.
 */
public class FoxSettingsScreen extends Screen {

    private final Screen parent;

    public FoxSettingsScreen(Screen parent) {
        super(Component.literal("Fox Client Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 48;
        int bw = Math.min(300, this.width - 40);

        KitsuneConfig cfg = KitsuneConfig.get();

        // Fox Title Screen toggle
        addToggle(cx, y, bw, "Fox Title Screen", cfg.foxTitleScreen, val -> {
            cfg.foxTitleScreen = val;
            KitsuneConfig.save();
        });
        y += 24;

        // Auto-check server rules
        addToggle(cx, y, bw, "Auto-Check Server Rules", cfg.autoCheckServerRules, val -> {
            cfg.autoCheckServerRules = val;
            KitsuneConfig.save();
        });
        y += 24;

        // Auto-reconnect after restart
        addToggle(cx, y, bw, "Auto-Reconnect After Restart", cfg.autoReconnectAfterRestart, val -> {
            cfg.autoReconnectAfterRestart = val;
            KitsuneConfig.save();
        });
        y += 24;

        // Adaptive FPS limit
        addToggle(cx, y, bw, "Adaptive FPS Limit (on unfocus)", cfg.adaptiveFpsLimit, val -> {
            cfg.adaptiveFpsLimit = val;
            KitsuneConfig.save();
        });
        y += 24;

        // Show watermark
        addToggle(cx, y, bw, "Show HUD Watermark", cfg.showWatermark, val -> {
            cfg.showWatermark = val;
            KitsuneConfig.save();
        });
        y += 24;

        // Background FPS limit
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, y, bw, 20,
                Component.literal("Background FPS Limit: " + cfg.backgroundFpsLimit),
                btn -> {
                    int[] options = {5, 10, 15, 20, 30, 60};
                    int current = cfg.backgroundFpsLimit;
                    int nextIdx = 0;
                    for (int i = 0; i < options.length; i++) {
                        if (options[i] == current) {
                            nextIdx = (i + 1) % options.length;
                            break;
                        }
                    }
                    cfg.backgroundFpsLimit = options[nextIdx];
                    KitsuneConfig.save();
                    this.rebuildWidgets();
                }));
        y += 30;

        // Keybind conflict screen
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, y, bw, 20,
                Component.literal("\u26a0 Keybind Conflicts"),
                btn -> this.minecraft.setScreen(new KeybindConflictScreen(this))));
        y += 24;

        // Profile export / import (two side-by-side buttons)
        int halfW = (bw - 6) / 2;
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, y, halfW, 20,
                Component.literal("\u21ea Export Profile"),
                btn -> {
                    ProfileIO.Result r = ProfileIO.exportProfile(null);
                    NotificationManager.show(r.message(),
                            r.ok() ? NotificationManager.Type.SUCCESS : NotificationManager.Type.WARNING);
                }));
        this.addRenderableWidget(FoxButton.of(cx - bw / 2 + halfW + 6, y, halfW, 20,
                Component.literal("\u21e9 Import Profile"),
                btn -> {
                    ProfileIO.Result r = ProfileIO.importProfile();
                    NotificationManager.show(r.message(),
                            r.ok() ? NotificationManager.Type.SUCCESS : NotificationManager.Type.WARNING);
                    if (r.ok()) this.rebuildWidgets();
                }));
        y += 24;

        // Done button
        this.addRenderableWidget(FoxButton.of(cx - 75, this.height - 28, 150, 20,
                Component.literal("Done"),
                btn -> this.onClose()));
    }

    private void addToggle(int cx, int y, int bw, String label, boolean currentValue,
                            java.util.function.Consumer<Boolean> setter) {
        String stateStr = currentValue ? "\u00a7aON" : "\u00a7cOFF";
        this.addRenderableWidget(FoxButton.of(cx - bw / 2, y, bw, 20,
                Component.literal(label + ": " + stateStr),
                btn -> {
                    setter.accept(!currentValue);
                    this.rebuildWidgets();
                }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta) {
        // Dark backdrop
        gfx.fill(0, 0, this.width, this.height, 0xE0101010);

        super.extractRenderState(gfx, mouseX, mouseY, delta);

        gfx.centeredText(this.font, "\u00a76Fox \u00a7eClient Settings",
                this.width / 2, 14, 0xFFFFFFFF);
        gfx.centeredText(this.font,
                Component.literal("\u00a77Global settings"),
                this.width / 2, 28, FoxTheme.TEXT_MUTED);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
