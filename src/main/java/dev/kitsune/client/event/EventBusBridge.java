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
        // Fabric fires MODIFY first then ALLOW for every send, both on the
        // main thread. We post the Kitsune ChatSendEvent ONCE during MODIFY
        // and reuse the same instance's cancellation flag during ALLOW so
        // listeners don't fire twice (which would double-execute .fox commands
        // and alias expansions). Stash the per-send event in a single-slot
        // array — safe because sends are serialised on the client tick.
        final ChatSendEvent[] pendingChat = { null };
        ClientSendMessageEvents.MODIFY_CHAT.register(message -> {
            ChatSendEvent e = new ChatSendEvent(message, false);
            EventBus.post(e);
            pendingChat[0] = e;
            return e.getMessage();
        });
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            ChatSendEvent e = pendingChat[0];
            pendingChat[0] = null;
            return e == null || !e.cancelled;
        });

        // ---- Command send (/slash commands) ----
        final ChatSendEvent[] pendingCmd = { null };
        ClientSendMessageEvents.MODIFY_COMMAND.register(command -> {
            ChatSendEvent e = new ChatSendEvent(command, true);
            EventBus.post(e);
            pendingCmd[0] = e;
            return e.getMessage();
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            ChatSendEvent e = pendingCmd[0];
            pendingCmd[0] = null;
            return e == null || !e.cancelled;
        });
    }
}
