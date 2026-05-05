package dev.kitsune.client.event;

import dev.kitsune.client.module.ModuleManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

/**
 * Wires Fabric's own callbacks into the Kitsune {@link EventBus}. Called once
 * from {@code KitsuneClient.onInitializeClient}. This is the only place Fabric
 * event classes are referenced directly — module code only sees Kitsune events.
 */
public final class EventBusBridge {
    private EventBusBridge() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            EventBus.post(TickEvent.INSTANCE);
            ModuleManager.tickAll();
        });

        // ---- Chat send (free-form messages) ----
        // ALLOW_CHAT can veto the send; MODIFY_CHAT can rewrite it. We run
        // the Kitsune bus in MODIFY so handlers see the message first and
        // can either rewrite (setMessage) or cancel (cancelled=true).
        ClientSendMessageEvents.MODIFY_CHAT.register(message -> {
            ChatSendEvent e = new ChatSendEvent(message, false);
            EventBus.post(e);
            return e.getMessage();
        });
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            ChatSendEvent e = new ChatSendEvent(message, false);
            EventBus.post(e);
            return !e.cancelled;
        });

        // ---- Command send (/slash commands) ----
        ClientSendMessageEvents.MODIFY_COMMAND.register(command -> {
            ChatSendEvent e = new ChatSendEvent(command, true);
            EventBus.post(e);
            return e.getMessage();
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            ChatSendEvent e = new ChatSendEvent(command, true);
            EventBus.post(e);
            return !e.cancelled;
        });
    }
}
