package com.imbet.weather;

/**
 * The little spirals of leaves that lift off the ground before a storm reaches you.
 *
 * <p>It is the first thing anybody notices about an approaching outflow: not the gust front, which has no
 * visible edge at ground level, and not the shelf, which is a kilometre up, but a handful of leaves that
 * come off a lawn in a tight loop, swirl once or twice, and drop. They appear while the sky is still only
 * grey. What makes them is the inflow rather than the wind: ten to twenty mph of *converging* air, which
 * near the ground is a rotating eddy - the sheet of air rolling up the front of a slowing current - that
 * picks up whatever is loose enough to lift and holds it for a few seconds in a circle a couple of blocks
 * across.
 *
 * <p>So this is not the mod's debris system, which throws blocks and sticks around a tornado, and it is not
 * the foliage shader, which sways leaves on the tree. It is the small-scale thing, in the minutes before,
 * and it has three conditions and no others: there is wind, there is a storm within a few hundred blocks
 * whose inflow you are standing in, and there is something loose on the ground to pick up.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the model can be checked outside the game. See
 * {@code .freebuff/scratch/Phase2Sim.java}.
 */
public final class LeafEddy {

    private LeafEddy() {
    }

    /** Below this there is no eddy at all, whatever the storm is doing. */
    public static final double MIN_WIND_MPH = 11.0D;

    /** The wind at which the eddies are as strong as they get. */
    public static final double FULL_WIND_MPH = 34.0D;

    /** How far out a storm still counts: beyond this the air around you is not its inflow. */
    public static final double STORM_INFLUENCE_BLOCKS = 260.0D;

    /** How far from the player the trees and loose ground are looked for. */
    public static final int LOOK_RANGE_BLOCKS = 40;

    /** Below this much strength, nothing spawns at all. */
    public static final double MIN_STRENGTH = 0.12D;

    /** How many loose columns must be in range before a tick may lift anything. */
    public static final int MIN_TREES = 1;

    /** The eddy's own radius, from a tight little loop to a proper dust-devil-like circle. */
    public static final double MIN_RADIUS_BLOCKS = 1.1D;
    public static final double MAX_RADIUS_BLOCKS = 3.6D;

    /** How fast the leaves go round it, in radians per tick. */
    public static final double MIN_ORBIT_RADIANS = 0.10D;
    public static final double MAX_ORBIT_RADIANS = 0.42D;

    /** The lift, in mph of upward air, given to a leaf that the eddy has picked up. */
    public static final double MIN_LIFT_MPH = 3.0D;
    public static final double MAX_LIFT_MPH = 13.0D;

    /** How long an eddy holds together, in ticks. */
    public static final int MIN_LIFE_TICKS = 20;
    public static final int MAX_LIFE_TICKS = 64;

    /** How many leaves the eddy lifts on each tick of its life. */
    public static final int MIN_LEAVES = 1;
    public static final int MAX_LEAVES = 3;

    /**
     * How strong the air around the player reads, 0 to 1. Wind and proximity each on their own are not
     * enough: a still day under a supercell has nothing to lift, and a gale with no storm in it is just a
     * gale, which the foliage shader already draws.
     */
    public static double strength(double windMph, double stormDistanceBlocks) {
        if (stormDistanceBlocks > STORM_INFLUENCE_BLOCKS) {
            return 0.0D;
        }
        double wind = clamp((windMph - MIN_WIND_MPH) / (FULL_WIND_MPH - MIN_WIND_MPH), 0.0D, 1.0D);
        double proximity = clamp(1.0D - stormDistanceBlocks / STORM_INFLUENCE_BLOCKS, 0.0D, 1.0D);
        // Multiplied rather than averaged: both are needed, and one of them being zero means no eddies.
        return wind * (0.35D + 0.65D * proximity);
    }

    /**
     * How many eddies may be started on this tick, given how strong the air is and how many loose columns
     * are in range. Sparsity is the point - a lawn with forty leaves in the air is a tornado, not a gust.
     */
    public static int spawnAttempts(double strength, int looseColumnsInRange) {
        if (strength < MIN_STRENGTH || looseColumnsInRange < MIN_TREES) {
            return 0;
        }
        double expected = strength * 0.42D;
        return (int) Math.floor(expected) + (fraction(expected) > 0.0D ? 1 : 0);
    }

    public static double radiusBlocks(double strength) {
        return lerp(MIN_RADIUS_BLOCKS, MAX_RADIUS_BLOCKS, clamp(strength, 0.0D, 1.0D));
    }

    public static double orbitRadiansPerTick(double strength) {
        return lerp(MIN_ORBIT_RADIANS, MAX_ORBIT_RADIANS, clamp(strength, 0.0D, 1.0D));
    }

    public static double liftMph(double strength) {
        return lerp(MIN_LIFT_MPH, MAX_LIFT_MPH, clamp(strength, 0.0D, 1.0D));
    }

    public static int lifetimeTicks(double strength) {
        return (int) Math.round(lerp(MIN_LIFE_TICKS, MAX_LIFE_TICKS, clamp(strength, 0.0D, 1.0D)));
    }

    public static int leavesPerEddy(double strength) {
        return (int) Math.round(lerp(MIN_LEAVES, MAX_LEAVES, clamp(strength, 0.0D, 1.0D)));
    }

    /**
     * How much of the eddy's motion is a genuine lift rather than a slide across the ground: a weak eddy
     * only drags leaves along the grass, which is exactly what it looks like in life.
     */
    public static double liftShare(double strength) {
        return clamp(0.25D + 0.70D * clamp(strength, 0.0D, 1.0D), 0.0D, 1.0D);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double fraction(double value) {
        return value - Math.floor(value);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
