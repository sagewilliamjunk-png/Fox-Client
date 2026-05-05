package dev.kitsune.client.event;

/** Fired once per client tick (after vanilla END_CLIENT_TICK). */
public final class TickEvent {
    public static final TickEvent INSTANCE = new TickEvent();
    private TickEvent() {}
}
