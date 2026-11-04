package com.imbet.weather;

/**
 * The wet season: when a world has one, how far in it is, and how hard it is raining.
 *
 * <p>A monsoon is not a storm. It is a season, and everything about it follows from that: it arrives on a
 * date, it lasts weeks, it rains every afternoon without a storm ever being responsible, and the ground goes
 * from dry to flooded over days rather than minutes. So this is a calendar first and a rain rate second, and
 * the only input that matters is what day the world is on.
 *
 * <p>Minecraft has no seasons, so one is defined here - a season of {@code seasonDays} inside a period of
 * {@code periodDays}, with the phase of the cycle offset by a property of the world so that two worlds do not
 * have their wet seasons on the same day. The offset is a parameter rather than a seed because it has to be
 * knowable on both sides: {@link MonsoonService} takes it from where the world's own spawn is, which the
 * client and the server both know without either telling the other.
 *
 * <p>The other half is where in the world it lands. This mod already knows which air it is standing in -
 * {@link BiomeClimate.Reading} names a family, a belt and a share - and a monsoon is a belt: the mod's own
 * monsoon belt air gets the season at full weight, air that is merely wet gets it at a discount, and dry air
 * gets almost nothing. That is what makes the season a regional event rather than a global one without any
 * new world data.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the calendar can be checked outside the game. See
 * {@code .freebuff/scratch/Phase3Sim.java}.
 */
public final class MonsoonModel {

    private MonsoonModel() {
    }

    /** A reading of the season at one moment. */
    public record Report(boolean active, double strength, int dayIntoSeason, int daysLeft) {
        public static final Report NONE = new Report(false, 0.0D, -1, 0);
    }

    /** How long the wet season runs, and how often one comes round, in game days. */
    public static final int DEFAULT_SEASON_DAYS = 8;
    public static final int DEFAULT_PERIOD_DAYS = 32;

    /**
     * How many days the season spends ramping up and ramping down. A monsoon does not switch on: the first
     * days are the arrival and the last days are the withdrawal, and both are part of what the season is.
     */
    public static final int RAMP_DAYS = 2;

    /** What the belt is worth: the mod's own monsoon-belt air, wet air, and everything else. */
    public static final double BELT_MONSOON = 1.35D;
    public static final double BELT_WET = 1.00D;
    public static final double BELT_DRY = 0.55D;

    /** The moisture an air needs before the season counts it as merely wet rather than dry. */
    public static final double WET_MOISTURE = 0.70D;
    /** Below this heat the air is frozen country, and a monsoon is a warm-season rain. */
    public static final double WET_HEAT_FLOOR = 0.45D;

    /** The rain the season delivers at full strength, on the same scale the floods are read against. */
    public static final double PEAK_RAIN_INTENSITY = 1.15D;

    /** How much the season multiplies the flood a given rain rate would otherwise cause. */
    public static final double FLOOD_MULTIPLIER_AT_PEAK = 1.4D;

    /** How dark the sky over a monsoon goes at full strength, as a share of the mod's storm tint. */
    public static final double SKY_SHARE_AT_PEAK = 0.78D;

    /** How far apart the season's rain cells sit, and how wide each one is, in blocks. */
    public static final double CELL_SPACING_BLOCKS = 320.0D;
    public static final double CELL_RADIUS_BLOCKS = 420.0D;

    /** How many cells a region carries, at no strength and at full strength. */
    public static final int MIN_CELLS = 1;
    public static final int MAX_CELLS = 3;

    /**
     * The three things the config dials that the season's own numbers are read against. Passing them in rather
     * than reading the config here is what keeps this class loadable with nothing but a JDK, which is how the
     * calendar is measured outside the game.
     */
    public record Settings(double peakRainIntensity, double peakFloodMultiplier, double peakSkyShare) {
        public static final Settings DEFAULT = new Settings(PEAK_RAIN_INTENSITY, FLOOD_MULTIPLIER_AT_PEAK,
                SKY_SHARE_AT_PEAK);
    }

    /**
     * The season at a moment. {@code day} is the world's own day counter, {@code offsetDays} the world's
     * phase in the cycle - so two worlds with different offsets have their wet seasons in different months,
     * and the same world always has them on the same date.
     */
    public static Report report(long day, int seasonDays, int periodDays, int offsetDays) {
        int season = Math.max(1, seasonDays);
        int period = Math.max(season, periodDays);
        int phase = (int) floorMod(day - offsetDays, period);
        if (phase >= season) {
            return Report.NONE;
        }
        double strength = strength(phase, season);
        return new Report(true, strength, phase, season - 1 - phase);
    }

    /**
     * How far into the season it is, 0 to 1. A trapezoid: {@link #RAMP_DAYS} up, a plateau, {@link #RAMP_DAYS}
     * down. A season too short to have a plateau at all is a triangle, because a two-day monsoon that is at
     * full strength for neither of its days is not a monsoon.
     */
    public static double strength(int dayIntoSeason, int seasonDays) {
        int season = Math.max(1, seasonDays);
        int day = Math.max(0, Math.min(season - 1, dayIntoSeason));
        double ramp = Math.min(RAMP_DAYS, season / 2.0D);
        if (ramp <= 0.0D) {
            return 1.0D;
        }
        double up = Math.min(1.0D, (day + 1) / ramp);
        double down = Math.min(1.0D, (season - day) / ramp);
        return clamp(Math.min(up, down), 0.0D, 1.0D);
    }

    /**
     * The season's own belt reading, taken from the air over a place: the mod already names a monsoon belt and
     * already knows how wet and how warm a place's air is, so the season is a reading of that rather than a
     * list of biome names.
     */
    public record Belt(boolean monsoonBelt, boolean wetAir) {
        public static final Belt NEITHER = new Belt(false, false);
    }

    /** Which belt a reading of the air puts a place in. */
    public static Belt beltOf(BiomeClimate.Reading air) {
        if (air == null) {
            return Belt.NEITHER;
        }
        boolean monsoon = air.alley() == BiomeClimate.Alley.MONSOON;
        boolean wet = !monsoon && air.moisture() >= WET_MOISTURE && air.heat() >= WET_HEAT_FLOOR;
        return new Belt(monsoon, wet);
    }

    /** The belt's own weight: the air you are standing in decides how much of the season you get. */
    public static double beltMultiplier(Belt belt) {
        Belt air = belt == null ? Belt.NEITHER : belt;
        if (air.monsoonBelt()) {
            return BELT_MONSOON;
        }
        return air.wetAir() ? BELT_WET : BELT_DRY;
    }

    /** The rain the season is delivering, on the scale the mod's own rain and floods are read on. */
    public static double rainIntensity(double strength, double beltMultiplier, Settings settings) {
        Settings dials = settings == null ? Settings.DEFAULT : settings;
        return Math.max(0.0D, dials.peakRainIntensity() * clamp(strength, 0.0D, 1.0D)
                * clamp(beltMultiplier / BELT_MONSOON, 0.0D, 1.0D));
    }

    /** How much harder a given rain floods during the season: the ground is already wet. */
    public static double floodMultiplier(double strength, double beltMultiplier, Settings settings) {
        Settings dials = settings == null ? Settings.DEFAULT : settings;
        double share = clamp(beltMultiplier / BELT_MONSOON, 0.0D, 1.0D);
        return 1.0D + Math.max(0.0D, dials.peakFloodMultiplier() - 1.0D)
                * clamp(strength, 0.0D, 1.0D) * share;
    }

    /** How much of the sky the season takes, as a share of the mod's own storm tint. */
    public static double skyShare(double strength, Settings settings) {
        Settings dials = settings == null ? Settings.DEFAULT : settings;
        return Math.max(0.0D, dials.peakSkyShare()) * clamp(strength, 0.0D, 1.0D);
    }

    /** How many rain cells a region carries. The season is one system made of several cores, not one core. */
    public static int cells(double strength, double beltMultiplier) {
        double share = clamp(strength * clamp(beltMultiplier / BELT_MONSOON, 0.0D, 1.0D), 0.0D, 1.0D);
        return MIN_CELLS + (int) Math.round((MAX_CELLS - MIN_CELLS) * share);
    }

    /**
     * The bias a cell coordinate is stored with, so that a cell west or north of the origin survives the
     * round trip. Masking a negative coordinate to 24 bits and reading it back unsigned puts a band meant for
     * -400 blocks somewhere near 1.3 billion, which is half the world away and silent. 23 bits of bias keeps
     * every cell inside about two and a half billion blocks of the origin in both directions, and the decode is
     * exact everywhere in that range.
     */
    public static final long CELL_COORDINATE_BIAS = 1L << 23;
    private static final long CELL_COORDINATE_MASK = 0xFFFFFFL;

    /** Which cell of the grid a position belongs to, for a system made of a few fixed cores. */
    public static long cellKey(double x, double z) {
        return cellKeyFor((int) Math.floor(x / CELL_SPACING_BLOCKS), (int) Math.floor(z / CELL_SPACING_BLOCKS));
    }

    /**
     * The same key from a cell's own grid coordinates. The bands are placed on the grid, so this is how one is
     * addressed from its neighbour rather than from a position inside it.
     */
    public static long cellKeyFor(int cellX, int cellZ) {
        long x = ((long) cellX + CELL_COORDINATE_BIAS) & CELL_COORDINATE_MASK;
        long z = ((long) cellZ + CELL_COORDINATE_BIAS) & CELL_COORDINATE_MASK;
        return (x << 24) | z;
    }

    /** The centre of a cell, so a core sits still instead of following the player it was made for. */
    public static double[] cellCentre(long cellKey) {
        long z = (cellKey & CELL_COORDINATE_MASK) - CELL_COORDINATE_BIAS;
        long x = ((cellKey >>> 24) & CELL_COORDINATE_MASK) - CELL_COORDINATE_BIAS;
        return new double[]{x * CELL_SPACING_BLOCKS + CELL_SPACING_BLOCKS * 0.5D,
                z * CELL_SPACING_BLOCKS + CELL_SPACING_BLOCKS * 0.5D};
    }

    /** A season's own name, for a warning: "Monsoon (day 3 of 8)". */
    public static String label(Report report) {
        if (report == null || !report.active()) {
            return "no monsoon";
        }
        return "monsoon, day " + (report.dayIntoSeason() + 1) + " of " + (report.dayIntoSeason() + report.daysLeft() + 1);
    }

    private static long floorMod(long value, long modulus) {
        long result = value % modulus;
        return result < 0L ? result + modulus : result;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
