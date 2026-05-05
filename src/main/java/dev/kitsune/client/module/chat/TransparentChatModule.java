package dev.kitsune.client.module.chat;

import dev.kitsune.client.module.Category;
import dev.kitsune.client.module.Module;
import dev.kitsune.client.setting.SliderSetting;

/**
 * Makes the chat background more transparent. The actual rendering
 * modification is done via {@code ChatComponentMixin} reading
 * {@link #getBackgroundAlpha()} every frame.
 *
 * <p>The user picks an exact alpha multiplier from 0.0 (fully transparent)
 * to 1.0 (vanilla opaque). Default 0.4 — the previous "reduced" mode.
 */
public class TransparentChatModule extends Module {

    private final SliderSetting backgroundAlpha = addSetting(
            new SliderSetting("Background Alpha", 0.4, 0.0, 1.0, 0.05));

    public TransparentChatModule() {
        super("Transparent Chat", "Adjusts chat background opacity", Category.CHAT);
    }

    /**
     * Multiplier applied to vanilla's text background opacity.
     * 0.0 = fully transparent, 1.0 = vanilla.
     */
    public float getBackgroundAlpha() {
        return backgroundAlpha.get().floatValue();
    }
}
