package dev.kitsune.client.mixin;

import dev.kitsune.client.module.ModuleManager;
import dev.kitsune.client.module.chat.ChatHighlightsModule;
import dev.kitsune.client.module.chat.ChatLoggerModule;
import dev.kitsune.client.module.chat.TransparentChatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Two unrelated hooks into ChatComponent:
 * <ul>
 *   <li>{@link #kitsune$highlightChat} — prepends a gold star to messages
 *       containing the player's name when {@link ChatHighlightsModule} is on.</li>
 *   <li>{@link #kitsune$transparentChatBg} — scales the text background
 *       opacity float in the private {@code render} method when
 *       {@link TransparentChatModule} is on.</li>
 * </ul>
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component kitsune$highlightChat(Component message) {
        try {
            // Chat logger — sink the raw message text regardless of highlight state
            ChatLoggerModule logger = ModuleManager.getModule(ChatLoggerModule.class);
            if (logger != null && logger.isEnabled() && message != null) {
                logger.logMessage(message.getString());
            }

            ChatHighlightsModule module = ModuleManager.getModule(ChatHighlightsModule.class);
            if (module == null || !module.isEnabled()) return message;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return message;

            String text = message.getString();
            String playerName = mc.player.getName().getString();

            if (module.shouldHighlightName() && !text.isEmpty() && playerName != null
                    && text.toLowerCase().contains(playerName.toLowerCase())) {
                if (module.shouldPlaySound()) {
                    mc.player.playSound(
                            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
                            0.5f, 1.5f
                    );
                }
                MutableComponent highlighted = Component.literal("\u00a76\u2b50 ")
                        .append(message);
                return highlighted;
            }
        } catch (Throwable t) {
            // Never crash the chat system
        }
        return message;
    }

    /**
     * Scales the float captured from {@code Options.textBackgroundOpacity().get()}
     * inside the private {@code render} method. This is the second float STORE
     * in that method (ordinal=1) — the first float is the scaled chat opacity.
     */
    @ModifyVariable(
            method = "extractRenderState(Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;IILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;)V",
            at = @At("STORE"),
            ordinal = 1,
            require = 0)
    private float kitsune$transparentChatBg(float textBackgroundOpacity) {
        try {
            TransparentChatModule mod = ModuleManager.getModule(TransparentChatModule.class);
            if (mod != null && mod.isEnabled()) {
                return textBackgroundOpacity * mod.getBackgroundAlpha();
            }
        } catch (Throwable t) {
            // Ignore
        }
        return textBackgroundOpacity;
    }
}
