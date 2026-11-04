package com.imbet.chase;

/**
 * The published Enhanced Fujita bands, in one place, with no game types in it.
 *
 * <p>These numbers were already the mod's shared ladder - the damage survey, the survey packet and the
 * damage tint all rate a wind with them. They lived on {@code StormStageLabel}, which is a class that reads
 * storm objects, and that made them unusable from the parts of the mod that have to be checkable without a
 * running game: the chase log's arithmetic and the survey's own accuracy scoring. Compiling a class against
 * {@code StormObject} to ask what a wind is called is how a band boundary ends up restated in a second place
 * and then drifts. So the ladder moved here and {@code StormStageLabel.efClass} now delegates to it.
 *
 * <p>The bands are EF0 65-85, EF1 86-110, EF2 111-135, EF3 136-165, EF4 166-200 and EF5 over 200 mph,
 * measured as 3-second gusts. Below 65 there is no rating to give: {@code EFU} is the published scale's own
 * unknown class, which is what a wind under the bottom rung genuinely is.
 */
public final class ChaseScale {
    /** EF0 through EF5, then the past-the-top class, which is index 6 in {@link #efTier}. */
    public static final int TIERS = 7;
    /** Index of the "no rating earned" class, below EF0. */
    public static final int TIER_UNKNOWN = -1;
    /** Index of the class above EF5, which the wind model can reach when configured past 300 mph. */
    public static final int TIER_ABOVE = 6;
    /** The lower bound of each band, in mph, EF0 first. */
    public static final double[] BAND_FLOOR_MPH = {65.0D, 86.0D, 111.0D, 136.0D, 166.0D, 201.0D};

    private ChaseScale() {
    }

    /** The EF class the given wind earns, as the published label reads. */
    public static String efClass(double mph) {
        if (mph < 65.0D) {
            return "EFU";
        }
        if (mph < 86.0D) {
            return "EF0";
        }
        if (mph < 111.0D) {
            return "EF1";
        }
        if (mph < 136.0D) {
            return "EF2";
        }
        if (mph < 166.0D) {
            return "EF3";
        }
        if (mph < 201.0D) {
            return "EF4";
        }
        if (mph < 250.0D) {
            return "EF5";
        }
        return "EF5+";
    }

    /**
     * The same answer as a number, for counting: 0 to 6 on the ladder, or {@link #TIER_UNKNOWN} when the
     * wind has not earned a rating. EF5 and EF5+ are deliberately the same tier - a chase log counting the
     * tornadoes it has stood beside should not have a class that only exists above the published scale.
     */
    public static int efTier(double mph) {
        if (mph < 65.0D) {
            return TIER_UNKNOWN;
        }
        if (mph < 86.0D) {
            return 0;
        }
        if (mph < 111.0D) {
            return 1;
        }
        if (mph < 136.0D) {
            return 2;
        }
        if (mph < 166.0D) {
            return 3;
        }
        if (mph < 201.0D) {
            return 4;
        }
        return 5;
    }

    /** The ladder's own name for a tier index, for a counter that has to say what it counted. */
    public static String labelForTier(int tier) {
        return tier < 0 ? "EFU" : tier >= 5 ? "EF5" : "EF" + tier;
    }
}
