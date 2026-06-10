package dev.kitsune.client.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ServerRule}'s host glob matching — the gate that decides
 * which per-server rules apply on connect.
 */
class ServerRuleTest {

    @Test
    void exactHostMatches() {
        assertTrue(ServerRule.globMatch("play.example.net", "play.example.net"));
        assertFalse(ServerRule.globMatch("play.example.net", "play.example.org"));
    }

    @Test
    void leadingStarMatchesSubdomains() {
        assertTrue(ServerRule.globMatch("*.hypixel.net", "mc.hypixel.net"));
        assertTrue(ServerRule.globMatch("*.hypixel.net", "a.b.hypixel.net"));
        assertFalse(ServerRule.globMatch("*.hypixel.net", "hypixel.net"));   // needs the dot
        assertFalse(ServerRule.globMatch("*.hypixel.net", "nothypixel.org"));
    }

    @Test
    void starAloneMatchesEverything() {
        assertTrue(ServerRule.globMatch("*", "anything.example"));
        assertTrue(ServerRule.globMatch("*", ""));
    }

    @Test
    void innerAndTrailingStars() {
        assertTrue(ServerRule.globMatch("mc.*.net", "mc.someserver.net"));
        assertTrue(ServerRule.globMatch("2b2t.*", "2b2t.org"));
        assertFalse(ServerRule.globMatch("mc.*.net", "play.someserver.net"));
    }

    @Test
    void matchesIsCaseInsensitiveAndNullSafe() {
        ServerRule rule = new ServerRule("Hypixel", "*.HYPIXEL.net", ServerRule.Action.DISABLE);
        assertTrue(rule.matches("MC.hypixel.NET"));
        assertFalse(rule.matches(null));
        assertFalse(new ServerRule("empty", null, ServerRule.Action.WARN).matches("x"));
    }
}
