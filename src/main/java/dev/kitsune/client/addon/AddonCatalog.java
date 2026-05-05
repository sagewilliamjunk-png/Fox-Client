package dev.kitsune.client.addon;

/**
 * Constants for every Fox Client addon flag the launcher writes and the
 * client reads. Keeping them in one place makes the cross-process contract
 * explicit — if you add a new gray-zone module, add its id here, register
 * the toggle in the launcher's addon list, and gate the module
 * registration with {@link AddonFlags#isAddonEnabled}.
 */
public final class AddonCatalog {
    private AddonCatalog() {}

    // ---- Group: grayzone ----
    // Visually-suspicious or input-touching modules that some servers ban
    // even though Fox Client implements them server-safely. Disable the
    // whole group (or specific items) for strict competitive servers.

    /** Tiny periodic view nudge. Input automation — explicit risk flag. */
    public static final String GRAYZONE_ANTI_AFK     = "grayzone.anti_afk";
    /** Toggles vanilla F3+B hitboxes. Server-safe but the toggle pattern
     *  is a known anti-cheat heuristic. */
    public static final String GRAYZONE_HITBOXES     = "grayzone.hitboxes";
    /** HUD readout of crosshair-target distance. Purely informational; the
     *  word "reach" alone trips some anti-cheat name filters. */
    public static final String GRAYZONE_REACH_HUD    = "grayzone.reach_display";
    /** Decoupled camera (Lunar/Optifine FreeLook). Universally banned on
     *  competitive PvP — keep off when joining those servers. */
    public static final String GRAYZONE_FREE_LOOK    = "grayzone.free_look";
}
