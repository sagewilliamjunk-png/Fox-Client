package dev.kitsune.client.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Minimal synchronous pub/sub. Not a full reflective bus — just a type-keyed
 * map of subscriber lists. Modules subscribe in {@code onEnable()} and
 * unsubscribe in {@code onDisable()}.
 *
 * <p>Fabric's own callbacks are wired into this bus by {@link EventBusBridge}
 * so module code never directly imports Fabric events.
 */
public final class EventBus {
    private static final Map<Class<?>, List<Consumer<?>>> SUBS = new HashMap<>();

    private EventBus() {}

    @SuppressWarnings("unchecked")
    public static <E> void subscribe(Class<E> type, Consumer<E> handler) {
        SUBS.computeIfAbsent(type, k -> new ArrayList<>()).add(handler);
    }

    @SuppressWarnings("unchecked")
    public static <E> void unsubscribe(Class<E> type, Consumer<E> handler) {
        List<Consumer<?>> list = SUBS.get(type);
        if (list != null) list.remove(handler);
    }

    @SuppressWarnings("unchecked")
    public static <E> void post(E event) {
        List<Consumer<?>> list = SUBS.get(event.getClass());
        if (list == null || list.isEmpty()) return;
        // Copy-on-iterate to tolerate subscribe/unsubscribe during dispatch
        List<Consumer<?>> snapshot = new ArrayList<>(list);
        for (Consumer<?> c : snapshot) {
            try {
                ((Consumer<E>) c).accept(event);
            } catch (Throwable t) {
                System.err.println("[Fox] EventBus handler threw: " + t);
            }
        }
    }
}
