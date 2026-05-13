package dev.kitsune.client.mixin;

import dev.kitsune.client.core.KitsuneConfig;
import dev.kitsune.client.core.ProfileManager;
import dev.kitsune.client.screen.FoxBranding;
import dev.kitsune.client.screen.FoxMainMenuScreen;
import dev.kitsune.client.screen.FoxTheme;
import dev.kitsune.client.screen.StarrySkyRenderer;
import dev.kitsune.client.server.AutoReconnectHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimal Lunar/Feather-style title screen makeover:
 *   - Vanilla logo + splash hidden by sibling mixins
 *   - Dark wash over the panorama
 *   - Single big "FOX CLIENT" wordmark in the upper third
 *   - Small "Profile: fox" chip top-right
 *   - Vanilla buttons (Singleplayer / Multiplayer / Options / Quit) untouched
 *   - "Fox Menu" accent button appended to the bottom of the button stack
 *   - Tiny version stamp bottom-left
 * No top/bottom bars, no overlapping fox glyph, no subtitle clutter.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private boolean kitsune$autoReconnectChecked = false;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void kitsune$onInit(CallbackInfo ci) {
        if (!KitsuneConfig.get().foxTitleScreen) return;

        // Drop vanilla's corner icon buttons (Language globe + Accessibility).
        // Our AbstractButtonRenderMixin re-skins them as tiny text buttons
        // showing just "L..." / "A..." because SpriteIconButton paints its
        // sprite on top of our chrome, and the vanilla bounds are too narrow
        // for any label. Both pages are already reachable from Options →
        // Language / Accessibility, so remove them from the title entirely.
        java.util.List<GuiEventListener> kids =
                new java.util.ArrayList<>(this.children());
        for (GuiEventListener child : kids) {
            if (child instanceof SpriteIconButton) {
                this.removeWidget(child);
            }
        }

        // Profile chip top-right
        this.addRenderableWidget(Button.builder(
                Component.literal("\u00a76Profile: \u00a7e" + FoxTheme.capitalize(ProfileManager.getActiveName())),
                btn -> cycleProfile()
        ).bounds(this.width - 110, 6, 104, 18).build());

        // "Fox Menu" accent pill — top-right, directly below the profile chip.
        // Previously this button was placed at (cx - 75, height - 30) which sat
        // directly on top of the vanilla Multiplayer button and intercepted its
        // clicks. Moved out of the vanilla button column entirely.
        this.addRenderableWidget(Button.builder(
                Component.literal("\u00a76Fox Menu"),
                btn -> Minecraft.getInstance().setScreen(new FoxMainMenuScreen(this))
        ).bounds(this.width - 80, 28, 74, 18).build());
    }

    private void cycleProfile() {
        var names = ProfileManager.getProfileNames();
        if (names.isEmpty()) return;
        int idx = names.indexOf(ProfileManager.getActiveName());
        idx = (idx + 1) % names.size();
        // ProfileManager.switchTo authoritatively syncs both modules (ModuleManager.applyProfileState)
        // and legacy features; calling FeatureRegistry.syncEnabledStates again here is redundant.
        ProfileManager.switchTo(names.get(idx), Minecraft.getInstance());
        this.rebuildWidgets();
    }

    private float kitsune$tickAccumulator = 0;

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void kitsune$preRender(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!KitsuneConfig.get().foxTitleScreen) return;
        kitsune$tickAccumulator += delta;
        // Starry sky replaces the vanilla panorama — draws over it
        StarrySkyRenderer.render(gfx, this.width, this.height, kitsune$tickAccumulator);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kitsune$postRender(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!KitsuneConfig.get().foxTitleScreen) return;

        // Bottom vignette: fades from transparent to near-opaque over the last
        // 30 px of the screen, masking vanilla's "Minecraft <version>" and
        // "Copyright Mojang AB. Do not distribute!" strings without having to
        // mixin-redirect them. Matches the Lunar / Feather title screen look.
        int vignetteH = 30;
        int vignetteTop = this.height - vignetteH;
        for (int i = 0; i < vignetteH; i++) {
            float t = (float) i / (float) vignetteH;
            int alpha = (int) (t * t * 235);  // ease-in for a softer start
            int color = (alpha << 24);        // black with ramped alpha
            gfx.fill(0, vignetteTop + i, this.width, vignetteTop + i + 1, color);
        }

        // Brand: fox face above the wordmark, both pinned to the top
        int cx = this.width / 2;
        int glyphSize = 64;
        int glyphY = 2;
        FoxBranding.drawFoxGlyph(gfx, cx - glyphSize / 2, glyphY, glyphSize);
        FoxBranding.drawWordmark(gfx, this.font, cx, glyphY + glyphSize + 4);

        if (!kitsune$autoReconnectChecked) {
            kitsune$autoReconnectChecked = true;
            try {
                AutoReconnectHandler.tryReconnect((TitleScreen) (Object) this);
            } catch (Throwable t) {
                System.err.println("[Fox] auto-reconnect threw: " + t);
            }
        }
    }
}
