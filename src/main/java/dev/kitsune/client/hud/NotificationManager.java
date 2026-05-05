package dev.kitsune.client.hud;

import dev.kitsune.client.screen.FoxTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Non-intrusive toast notification system for Fox Client.
 *
 * <p>Toasts stack toward the chosen anchor; a maximum of 4 are visible at once.
 * Each toast slides in from the nearest screen edge, holds, then fades out.
 *
 * <p>Anchor is configurable via {@link #setAnchor(HudManager.Anchor)} and is
 * persisted by {@link dev.kitsune.client.core.KitsuneConfig}. The default is
 * {@link HudManager.Anchor#BOTTOM_RIGHT} which matches the previous fixed
 * behaviour, so existing players see no visual change unless they opt in.
 */
public final class NotificationManager {
    private NotificationManager() {}

    private static final int MAX_VISIBLE = 4;
    private static final long DISPLAY_MS = 3000;
    private static final long FADE_MS = 500;
    private static final long SLIDE_MS = 200;
    private static final int TOAST_HEIGHT = 18;
    private static final int TOAST_MARGIN = 3;
    private static final int PADDING_X = 6;
    private static final int PADDING_Y = 4;
    private static final int EDGE_MARGIN = 6;
    private static final int BOTTOM_OFFSET = 40; // keep clear of the hotbar
    private static final int TOP_OFFSET = 6;

    /** Color presets for notification types. */
    public enum Type {
        INFO(0xFF2A2A3A, FoxTheme.FOX_ORANGE),
        SUCCESS(0xFF1A2E1A, 0xFF55FF55),
        WARNING(0xFF3A2A1A, 0xFFFFAA00),
        ERROR(0xFF3A1A1A, 0xFFFF5555);

        final int bgColor;
        final int textColor;

        Type(int bgColor, int textColor) {
            this.bgColor = bgColor;
            this.textColor = textColor;
        }
    }

    private static final Deque<Toast> toasts = new ArrayDeque<>();
    private static volatile HudManager.Anchor anchor = HudManager.Anchor.BOTTOM_RIGHT;

    public static HudManager.Anchor getAnchor() { return anchor; }

    public static void setAnchor(HudManager.Anchor a) {
        if (a == null) return;
        anchor = a;
    }

    /** Show an INFO toast with the given message. */
    public static void show(String message) {
        show(message, Type.INFO);
    }

    /** Show a typed toast with the given message. */
    public static void show(String message, Type type) {
        if (message == null || message.isEmpty()) return;
        synchronized (toasts) {
            toasts.addLast(new Toast(message, type, System.currentTimeMillis()));
            while (toasts.size() > MAX_VISIBLE + 2) {
                toasts.removeFirst();
            }
        }
    }

    /**
     * Render all active toasts. Called from GuiMixin at the end of HUD rendering.
     */
    public static void render(GuiGraphics gfx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        long now = System.currentTimeMillis();
        int slot = 0;

        synchronized (toasts) {
            Iterator<Toast> it = toasts.iterator();
            while (it.hasNext()) {
                Toast t = it.next();
                if (now - t.createdAt > DISPLAY_MS + FADE_MS) {
                    it.remove();
                }
            }

            HudManager.Anchor a = anchor;
            boolean fromTop = (a == HudManager.Anchor.TOP_LEFT
                    || a == HudManager.Anchor.TOP_RIGHT
                    || a == HudManager.Anchor.TOP_CENTER);
            boolean fromLeft = (a == HudManager.Anchor.TOP_LEFT
                    || a == HudManager.Anchor.BOTTOM_LEFT);
            boolean center   = (a == HudManager.Anchor.TOP_CENTER
                    || a == HudManager.Anchor.BOTTOM_CENTER);

            Toast[] arr = toasts.toArray(new Toast[0]);
            // Render newest closest to the anchor (matches the original feel).
            for (int i = arr.length - 1; i >= 0 && slot < MAX_VISIBLE; i--, slot++) {
                Toast t = arr[i];
                long age = now - t.createdAt;

                float alpha;
                float slideProgress;
                if (age < SLIDE_MS) {
                    slideProgress = age / (float) SLIDE_MS;
                    alpha = slideProgress;
                } else if (age > DISPLAY_MS) {
                    slideProgress = 1.0f;
                    alpha = 1.0f - ((age - DISPLAY_MS) / (float) FADE_MS);
                } else {
                    slideProgress = 1.0f;
                    alpha = 1.0f;
                }
                alpha = Math.max(0, Math.min(1, alpha));
                slideProgress = Math.max(0, Math.min(1, slideProgress));

                int textW = font.width(t.message);
                int toastW = textW + PADDING_X * 2;

                int targetX;
                if (center) {
                    targetX = (screenW - toastW) / 2;
                } else if (fromLeft) {
                    targetX = EDGE_MARGIN;
                } else {
                    targetX = screenW - toastW - EDGE_MARGIN;
                }
                int slideAxis = (int) ((1.0f - slideProgress) * (toastW + 10));
                int x;
                if (center) {
                    x = targetX;
                } else if (fromLeft) {
                    x = targetX - slideAxis;
                } else {
                    x = targetX + slideAxis;
                }

                int slotOffset = slot * (TOAST_HEIGHT + TOAST_MARGIN);
                int y = fromTop
                        ? TOP_OFFSET + slotOffset
                        : screenH - BOTTOM_OFFSET - slotOffset;

                int bgAlpha = (int) (alpha * 0xDD);
                int bg = (bgAlpha << 24) | (t.type.bgColor & 0x00FFFFFF);

                // Outer fox-orange wash
                gfx.fill(x - 1, y - 1, x + toastW + 1, y + TOAST_HEIGHT + 1,
                        (int)(alpha * 0x80) << 24 | (FoxTheme.FOX_ORANGE & 0x00FFFFFF));
                gfx.fill(x, y, x + toastW, y + TOAST_HEIGHT, bg);

                // Accent bar — flips to the right edge for left-anchored toasts so
                // it always sits on the side closest to the screen edge.
                int accentAlpha = (int) (alpha * 0xFF);
                int accentColor = (accentAlpha << 24) | (t.type.textColor & 0x00FFFFFF);
                if (fromLeft) {
                    gfx.fill(x + toastW - 2, y, x + toastW, y + TOAST_HEIGHT, accentColor);
                } else {
                    gfx.fill(x, y, x + 2, y + TOAST_HEIGHT, accentColor);
                }

                int textAlpha = (int) (alpha * 255);
                int textColor = (textAlpha << 24) | (t.type.textColor & 0x00FFFFFF);
                gfx.drawString(font, t.message, x + PADDING_X, y + PADDING_Y, textColor, false);
            }
        }
    }

    private static class Toast {
        final String message;
        final Type type;
        final long createdAt;

        Toast(String message, Type type, long createdAt) {
            this.message = message;
            this.type = type;
            this.createdAt = createdAt;
        }
    }
}
