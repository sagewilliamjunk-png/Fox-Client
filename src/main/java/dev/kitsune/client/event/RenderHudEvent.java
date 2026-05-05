package dev.kitsune.client.event;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Fired when a module-owned HUD layer wants to render. In Phase 2 this is
 * declared but not yet wired up — Fabric's 1.21.11 {@code HudElement} uses a
 * render-state extractor pattern rather than a callback, so the bridge for
 * this event lands in Phase 4 alongside the first HUD module.
 */
public final class RenderHudEvent {
    public final GuiGraphics graphics;

    public RenderHudEvent(GuiGraphics graphics) {
        this.graphics = graphics;
    }
}
