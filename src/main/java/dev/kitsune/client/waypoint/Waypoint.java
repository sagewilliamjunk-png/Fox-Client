package dev.kitsune.client.waypoint;

/**
 * One pinned point in the world. Persisted by {@link WaypointManager}.
 *
 * @param id           stable identifier within the sub-world (UUID-ish string)
 * @param name         user-visible name; defaults to "WP" + sequential number
 * @param x            world block X
 * @param y            world block Y
 * @param z            world block Z
 * @param color        ARGB tint used both on the minimap and in the world
 * @param symbol       1-2 character label drawn over the marker on the map
 * @param global       global waypoints render past the max-draw-distance
 *                     cap; local waypoints respect the cap
 * @param deathpoint   true for auto-created death markers (renders with a
 *                     skull symbol and a different default color)
 */
public record Waypoint(
        String id,
        String name,
        int x, int y, int z,
        int color,
        String symbol,
        boolean global,
        boolean deathpoint,
        String set) {
    public static final int DEFAULT_COLOR     = 0xFFCC8833;
    public static final int DEATHPOINT_COLOR  = 0xFFFF3333;
    /** The implicit default set every new waypoint lands in. */
    public static final String DEFAULT_SET    = "Default";

    /** Back-compat ctor used by callers from v1.3.x that pre-dated the set field. */
    public Waypoint(String id, String name, int x, int y, int z, int color, String symbol,
                    boolean global, boolean deathpoint) {
        this(id, name, x, y, z, color, symbol, global, deathpoint, DEFAULT_SET);
    }

    public Waypoint withName(String newName) {
        return new Waypoint(id, newName, x, y, z, color, symbol, global, deathpoint, set);
    }

    public Waypoint withColor(int newColor) {
        return new Waypoint(id, name, x, y, z, newColor, symbol, global, deathpoint, set);
    }

    public Waypoint withGlobal(boolean isGlobal) {
        return new Waypoint(id, name, x, y, z, color, symbol, isGlobal, deathpoint, set);
    }

    public Waypoint withSet(String newSet) {
        return new Waypoint(id, name, x, y, z, color, symbol, global, deathpoint,
                            newSet == null || newSet.isEmpty() ? DEFAULT_SET : newSet);
    }
}
