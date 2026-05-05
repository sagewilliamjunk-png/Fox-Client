package dev.kitsune.client.mixin;

import dev.kitsune.client.gui.chrome.FoxChrome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.LockIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla gray button chrome with the Fox dark-panel theme on every
 * {@code AbstractButton} instance in every screen — title, pause, options,
 * realms, multiplayer list, advancements, etc.
 *
 * <p>Explicit exclusions: {@code ImageButton}, {@code CycleButton}, and
 * {@code LockIconButton} keep vanilla rendering because they draw glyphs /
 * values on top of the same chrome path and would visually break without
 * their sprites.
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>{@code AbstractButton.renderWidget} is {@code final} at the Java level,
 *       but Mixin injects at the bytecode level so this works.</li>
 *   <li>Hover lerp is stored in a per-instance {@code @Unique} field so each
 *       button has its own independent fade — GC goes with the widget.</li>
 * </ul>
 */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonRenderMixin {

    @Unique private float kitsune$hoverLerp = 0f;
    @Unique private long  kitsune$lastRenderMs = 0L;

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void kitsune$paintFoxChrome(GuiGraphics gfx, int mouseX, int mouseY,
                                         float partialTick, CallbackInfo ci) {
        // Skin every AbstractButton EXCEPT the specialized subclasses that
        // render their own icons / state glyphs via the same renderWidget path
        // (they look wrong without their sprites).
        Object self = this;
        if (self instanceof ImageButton)    return; // icon tiles (mute, lang, accessibility)
        if (self instanceof LockIconButton) return; // world-lock glyph
        // Also let our own FoxButton render itself (it extends AbstractWidget, not
        // AbstractButton, so this branch is really belt-and-braces).
        String cn = self.getClass().getName();
        if (cn.startsWith("dev.kitsune")) return;
        // Fabric API widgets (creative-tab paging buttons, etc.) override
        // renderContents to draw their own custom iconography on top of the
        // vanilla chrome. If we paint over them AND cancel renderWidget, the
        // custom glyph never draws and the player sees a blank orange box with
        // only the button's fallback message ("...") showing — which is what
        // happens to FabricCreativeGuiComponents$ItemGroupButtonWidget. Let the
        // whole net.fabricmc.* namespace render itself.
        if (cn.startsWith("net.fabricmc.")) return;

        AbstractButton btn = (AbstractButton) self;
        int x = btn.getX();
        int y = btn.getY();
        int w = btn.getWidth();
        int h = btn.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean hovered = btn.isHovered() && btn.active;

        // Smooth hover fade, 150 ms full transition
        long now = System.currentTimeMillis();
        float dt = kitsune$lastRenderMs == 0L ? 16f : (now - kitsune$lastRenderMs);
        kitsune$lastRenderMs = now;
        kitsune$hoverLerp = FoxChrome.stepHover(
                kitsune$hoverLerp, hovered ? 1f : 0f, dt);

        FoxChrome.paintPanel(gfx, x, y, w, h, btn.active, kitsune$hoverLerp);
        FoxChrome.paintCenteredText(
                gfx, Minecraft.getInstance().font, btn.getMessage(),
                x, y, w, h, btn.active, kitsune$hoverLerp);

        ci.cancel();
    }
}
