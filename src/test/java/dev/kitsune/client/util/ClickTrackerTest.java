package dev.kitsune.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link ClickTracker} — the rolling-window CPS counter
 * shared by the CPS and Keystrokes HUD modules.
 */
class ClickTrackerTest {

    @Test
    void countsRisingEdgesOnly() {
        ClickTracker t = new ClickTracker();
        long now = 10_000;
        // Held down across three ticks = ONE click, not three.
        t.tick(true, now);
        t.tick(true, now + 50);
        t.tick(true, now + 100);
        assertEquals(1, t.cps(now + 100));
    }

    @Test
    void countsSeparatePresses() {
        ClickTracker t = new ClickTracker();
        long now = 10_000;
        for (int i = 0; i < 5; i++) {
            t.tick(true,  now + i * 100);
            t.tick(false, now + i * 100 + 50);
        }
        assertEquals(5, t.cps(now + 500));
    }

    @Test
    void clicksAgeOutOfTheOneSecondWindow() {
        ClickTracker t = new ClickTracker();
        long now = 10_000;
        t.tick(true,  now);
        t.tick(false, now + 50);
        assertEquals(1, t.cps(now + 999));
        assertEquals(0, t.cps(now + 1_001));
    }

    @Test
    void resetForgetsEverything() {
        ClickTracker t = new ClickTracker();
        long now = 10_000;
        t.tick(true, now);
        t.reset();
        assertEquals(0, t.cps(now + 10));
        // After reset, a held button is a fresh rising edge again.
        t.tick(true, now + 20);
        assertEquals(1, t.cps(now + 30));
    }

    @Test
    void emptyTrackerReportsZero() {
        assertEquals(0, new ClickTracker().cps(System.currentTimeMillis()));
    }
}
