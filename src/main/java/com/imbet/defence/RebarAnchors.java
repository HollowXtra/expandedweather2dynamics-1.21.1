package com.imbet.defence;

/**
 * What a structural rebar holds on to, as arithmetic.
 *
 * <p>Every other defence in this mod is about what a storm cannot do. This one is about what a player can do
 * about it, and it is one idea: a vortex pulls things out of the ground, so put something in the ground. A rebar
 * is driven in at the bottom of whatever it is meant to hold, and what it holds is the block it stands in, the
 * eight blocks beside it and the column above them - a wall three blocks tall around one rod is anchored, which is
 * the difference between a storm taking a roof off and a storm taking the whole building.
 *
 * <p>Two rules make it a mechanic rather than a blanket:
 *
 * <ul>
 *   <li><b>It has to be planted.</b> A rebar with nothing under it - waving in the air, or stuck to the side of a
 *       wall - holds nothing at all, itself included. That is what makes anchoring something a player builds from
 *       the ground up, and it is also the one rule that makes a rebar useful: a rod hanging in a doorway is
 *       scenery.</li>
 *   <li><b>It reaches up, not sideways.</b> The reach is a column, not a sphere. A rebar anchors the wall around
 *       it to a height (configurable, three by default) and the block below it, because the load a vortex puts on a
 *       wall is vertical: it is trying to lift the top of it, and a foundation that grips the bottom three blocks
 *       is what resists that. A sphere would anchor a cellar and leave the roof it was put there to hold.</li>
 * </ul>
 *
 * <p>Arithmetic only, with no Minecraft types, so the reach, the planting rule and the count of what one rod holds
 * are all checkable without a world - which matters, because a footprint that is off by one at the top is a wall
 * that comes off one block above the thing that was supposed to hold it.
 */
public final class RebarAnchors {

    private RebarAnchors() {
    }

    /** How far sideways a rebar holds: one block, so a 3x3 footprint of columns. */
    public static final int REACH_HORIZONTAL = 1;

    /** How far below a rebar it holds. One, so the block it was driven into is held too. */
    public static final int REACH_DOWN = 1;

    /** The default height a rebar reaches above itself, which is the config's own default. */
    public static final int DEFAULT_REACH_UP = 3;

    /** How many blocks above itself the top of the reach is, clamped to what a rebar can sensibly hold. */
    public static int reachUp(int configured) {
        return Math.max(1, Math.min(8, configured));
    }

    /**
     * Whether a rebar holds the block at an offset from itself.
     *
     * <p>The offsets are relative to the rebar: {@code dy} is positive above it. A block at the rebar's own
     * position is covered, which is the rod itself - a rebar is at least as well anchored as what it holds.
     */
    public static boolean covers(int dx, int dy, int dz, int reachUp) {
        if (Math.abs(dx) > REACH_HORIZONTAL || Math.abs(dz) > REACH_HORIZONTAL) {
            return false;
        }
        return dy >= -REACH_DOWN && dy <= reachUp(reachUp);
    }

    /**
     * Whether a rebar is planted: it has to have something solid under it.
     *
     * <p>{@code supportedBelow} is that block's own answer, so this rule does not care what the support is made of
     * - stone, a slab, another rebar driven into the rock below it, or a player's foundation. What it refuses is
     * air, which is exactly the case that makes a rod useless.
     */
    public static boolean planted(boolean supportedBelow) {
        return supportedBelow;
    }

    /**
     * How many cells one planted rebar holds: its own block, the eight beside it, the ring below those and a
     * column above them. It is stated as a count rather than described in prose because it is the number a player is
     * buying when they place one, and because it is the number a harness can check the reach against.
     */
    public static int heldCells(int reachUp) {
        int columns = (REACH_HORIZONTAL * 2 + 1) * (REACH_HORIZONTAL * 2 + 1);
        return columns * (reachUp(reachUp) + 1 + REACH_DOWN);
    }
}
