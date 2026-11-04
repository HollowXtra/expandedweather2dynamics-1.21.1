package com.imbet.weather;

/**
 * How big a tornado is allowed to get, and how much of that it is using at any moment of its life.
 *
 * <p>The old model rolled a fresh width every retarget out of nothing but the storm's stage ladder: a
 * memoryless draw. A storm could be a rope at one update and a wedge at the next, and a storm that had
 * been enormous had no reason to be enormous again - size was a property of the moment rather than of
 * the storm. This is the same ladder, driven by three things that ARE properties of the storm:
 *
 * <ul>
 *   <li>its TEMPERAMENT: how big-boned the storm is, hashed once from its id. A thin-temperament tornado
 *       is thin for its whole life and a monster is wide from the start, which is what makes two storms of
 *       the same stage recognisably different. It is derived rather than stored, so the server and every
 *       client agree about it with nothing to sync and a world reload cannot change it.</li>
 *   <li>its LIFE ARC: a storm widens as it organizes, keeps its width through its climax, and gives it
 *       back as it ropes out. The old model had no arc at all - a storm could do its widest an hour after
 *       it had already peaked.</li>
 *   <li>its ENVIRONMENT: the existing warmth, moisture and terrain reading, which changes as the storm
 *       moves rather than only when the dice say so.</li>
 * </ul>
 *
 * <p>The ladder is GEOMETRIC rather than linear, because the widths in this mod span three orders of
 * magnitude. On a linear reach, almost the whole range would be spent on the top few percent of storms
 * and every ordinary tornado would come out the same size; on a log ladder a storm halfway up its reach
 * is halfway up the range in the sense a player reads it, and the size classes below are evenly spaced.
 */
public final class TornadoSizeModel {

    /** The fraction of its ladder even the thinnest-tempered storm can reach at full stage. */
    private static final double TEMPERAMENT_FLOOR = 0.35D;

    /** Curve of the stage ladder: a forming storm is not yet using its width, a stage 5 one is. */
    private static final double STAGE_CURVE = 1.1D;

    /** The share of its potential a storm carries before it has organized, and at its climax. */
    private static final double ARC_BUILD_FLOOR = 0.72D;

    /** What rain, sitting over water and a warm boundary layer each add to the moisture reading. */
    private static final double RAIN_SHARE = 0.62D;
    private static final double WATER_SHARE = 0.24D;
    private static final double WARMTH_SHARE = 0.14D;

    /** Where the size classes fall on the ladder, as a fraction of reach. */
    private static final double ROPE_LIMIT = 0.30D;
    private static final double CONE_LIMIT = 0.45D;
    private static final double BARREL_LIMIT = 0.62D;
    private static final double WEDGE_LIMIT = 0.82D;

    private static final long ODD_CONSTANT = 0x9E3779B97F4A7C15L;

    private TornadoSizeModel() {
    }

    /**
     * How big-boned this storm is, in [0, 1): the one number that makes a rope a rope and a monster a
     * monster. Hashed from the storm's id alone, so it is the same on the server and on every client, it
     * never needs saving, and it cannot drift when a world is reloaded.
     */
    public static double bone(long stormId) {
        long hashed = mix(stormId * ODD_CONSTANT);
        return (hashed >>> 11) * 0x1.0p-53;
    }

    /**
     * The share of the ladder this storm's temperament lets it reach, before its life arc or its
     * environment are taken into account.
     *
     * <p>{@code skew} is the population dial: 1 puts the median storm at the median size, below 1 makes
     * big storms commoner, and 3 or 4 makes the ladder top nearly unreachable, which is what a world of
     * ropes and cones wants.
     */
    public static double temperament(double bone, double skew) {
        return TEMPERAMENT_FLOOR + (1.0D - TEMPERAMENT_FLOOR) * Math.pow(clamp(bone, 0.0D, 1.0D), Math.max(0.05D, skew));
    }

    /**
     * The storm's life arc as a fraction of its potential: below 1 while it is still organizing, exactly 1
     * at its climax, and falling toward {@code 1 - ropeOut} as it decays. {@code intensity} is the storm's
     * own strength, which is what a rope-out actually tracks - the width goes as the circulation goes.
     */
    public static double lifeArc(double stage, double intensity, boolean decaying, double ropeOut) {
        if (decaying) {
            return 1.0D - clamp(ropeOut, 0.0D, 0.95D) * clamp(1.0D - intensity, 0.0D, 1.0D);
        }
        return ARC_BUILD_FLOOR + (1.0D - ARC_BUILD_FLOOR) * smoothstep(clamp(stage, 0.0D, 1.0D));
    }

    /**
     * Where on the ladder the storm sits right now, in [0, 1]. The four inputs multiply because the ladder
     * it feeds is logarithmic: multiplying reaches adds up to the sum of their logarithms, so a better
     * environment and a bigger temperament both move the storm up the ladder by their own share, and none
     * of them can on its own put a rope at a mile wide.
     */
    public static double reach(double stage, double temperament, double arc, double environment) {
        double stageShare = Math.pow(clamp(stage, 0.0D, 1.0D), STAGE_CURVE);
        return clamp(stageShare * clamp(temperament, 0.0D, 1.0D) * clamp(arc, 0.0D, 1.0D)
                * Math.max(0.0D, environment), 0.0D, 1.0D);
    }

    /**
     * The width a reach is worth, in blocks: the geometric ladder between the configured minimum and
     * maximum, so equal steps of reach are equal ratios of width.
     */
    public static double ladder(double reach, double minBlocks, double maxBlocks) {
        double min = Math.max(0.01D, minBlocks);
        double max = Math.max(min, maxBlocks);
        return min * Math.pow(max / min, clamp(reach, 0.0D, 1.0D));
    }

    /** The inverse of {@link #ladder}: how far up the ladder a width of this many blocks sits. */
    public static double reachOf(double widthBlocks, double minBlocks, double maxBlocks) {
        double min = Math.max(0.01D, minBlocks);
        double max = Math.max(min, maxBlocks);
        if (max <= min) {
            return 0.0D;
        }
        return clamp(Math.log(Math.max(min, widthBlocks) / min) / Math.log(max / min), 0.0D, 1.0D);
    }

    /**
     * How much of the damage path the condensation funnel itself covers, in [0, 1]: the dry-air end of the
     * ladder for a storm over cold dry ground with no rain, the wet end for one raining into a saturated
     * boundary layer over water.
     *
     * <p>This is the one place the visible funnel stops being a second roll of the dice. A dryline tornado
     * and a rain-wrapped one can scour the same swath, and what separates them is the air, not luck: this is
     * why a thin condensation funnel over a wide damage path is a real thing a player can now see.
     */
    public static double visibleShare(double hydration, double dryShare, double wetShare) {
        double dry = clamp(dryShare, 0.01D, 1.0D);
        double wet = clamp(wetShare, 0.01D, 1.0D);
        return dry + (wet - dry) * clamp(hydration, 0.0D, 1.0D);
    }

    /**
     * Low-level moisture as a 0..1 reading, from the air the funnel is standing in: how warm the biome is,
     * whether it is raining, and whether the storm is over water. Warm wet air condenses wide, cold dry air
     * over dry ground does not.
     *
     * <p>Every term is a share of the whole and there is no constant floor, so the reading spans the full
     * 0..1: a cold funnel over dry land really does sit at the bottom of the range, which is where the
     * thinnest condensation funnel the mod can draw lives. That matters because this is the number that
     * decides how much of a swath is drawn - a reading that could never reach its own floor would leave
     * half of the moisture response - and half of the player's `visibleFunnelFraction` pair - unreachable.
     */
    public static double hydration(double temperature, boolean raining, boolean overWater) {
        double warmth = clamp((temperature + 0.15D) / 1.45D, 0.0D, 1.0D);
        double rain = raining ? RAIN_SHARE : 0.0D;
        double water = overWater ? WATER_SHARE : 0.0D;
        return clamp(rain + water + warmth * WARMTH_SHARE, 0.0D, 1.0D);
    }

    /**
     * The size class a damage path belongs in, for a label a player can read.
     *
     * <p>NONE is for a storm with no damage path at all rather than for one sitting at the bottom of the
     * ladder: a rope at the configured minimum width is still a rope, and a storm list that printed no tag
     * beside it would be saying the circulation did not exist.
     */
    public static SizeClass classify(double damageWidthBlocks, double minBlocks, double maxBlocks) {
        if (damageWidthBlocks <= 0.0D) {
            return SizeClass.NONE;
        }
        double reach = reachOf(damageWidthBlocks, minBlocks, maxBlocks);
        if (reach < ROPE_LIMIT) {
            return SizeClass.ROPE;
        }
        if (reach < CONE_LIMIT) {
            return SizeClass.CONE;
        }
        if (reach < BARREL_LIMIT) {
            return SizeClass.BARREL;
        }
        if (reach < WEDGE_LIMIT) {
            return SizeClass.WEDGE;
        }
        return SizeClass.MONSTER;
    }

    /** The bands of the ladder, in the order the ladder climbs them. */
    public enum SizeClass {
        NONE(""),
        ROPE("ROPE"),
        CONE("CONE"),
        BARREL("BARREL"),
        WEDGE("WEDGE"),
        MONSTER("MONSTER");

        private final String label;

        SizeClass(String label) {
            this.label = label;
        }

        /** The short tag a readout prints, or an empty string for a storm with no circulation. */
        public String label() {
            return label;
        }
    }

    private static double smoothstep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Murmur3's finalizer, so two ids that differ in one bit get unrelated temperaments. */
    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }
}
