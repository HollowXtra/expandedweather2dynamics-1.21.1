package com.imbet.instruments;

import java.util.Locale;

/**
 * The three handheld instruments, as a model rather than as three items that happen to print numbers.
 *
 * <p>Everything the mod measures is already measured - wind, pressure, temperature, rainfall - and all of it is
 * read at a station or a block. That is the wrong shape for the one thing a chaser does, which is walk out into
 * a field and hold something up. So these are the same readings in the hand: the same wind field, the same
 * pressure column, the same air, with the difference that they are asked from wherever the player is standing.
 *
 * <p>The model is here so that what each instrument is <em>for</em> is checkable without a client. An
 * anemometer reports wind, and what makes a handheld one worth carrying is that it reports the gust and the
 * bearing separately rather than a single number, because on a storm's inflow that is the whole question. A
 * thermometer reports the air, and it has to invent the scale: vanilla's biome temperature is an abstract
 * number, so the conversion is stated here rather than left implicit, and every band the readout names comes
 * from it. A barometer reports pressure and its tendency, which is the only instrument of the three that
 * measures something about the future rather than the present.
 *
 * <p>The three ladders are what the readout calls things. They are deliberately the same ladders the block
 * instruments and the storm readouts use, so a handheld barometer and a wall barometer next to each other
 * cannot describe the same pressure differently.
 *
 * <p>Arithmetic only, with no Minecraft types at all, so all of it is measured outside the game.
 */
public final class HandheldInstrument {

    private HandheldInstrument() {
    }

    /** Which instrument it is, what it reads, and what a player calls it. */
    public enum Kind {
        ANEMOMETER("Anemometer", "mph"),
        THERMOMETER("Thermometer", "C"),
        BAROMETER("Barometer", "hPa");

        private final String label;
        private final String unit;

        Kind(String label, String unit) {
            this.label = label;
            this.unit = unit;
        }

        public String label() {
            return label;
        }

        public String unit() {
            return unit;
        }
    }

    /**
     * Vanilla's biome temperature is not a physical scale - it is a number the game uses for snow and rain -
     * so the thermometer's own scale is defined here and stated once. Five degrees below freezing at zero, and
     * twenty-five degrees per unit above it: plains country at 0.8 comes out at 15 degrees, a taiga at 1, and a
     * desert at 2.0 at 45, which is the range a player can check against the world around them.
     */
    public static final double CELSIUS_AT_ZERO = -5.0D;
    public static final double CELSIUS_PER_UNIT = 25.0D;

    /**
     * How fast the air cools with height: the standard atmosphere's own 6.5 degrees per kilometre.
     *
     * <p>The biome's number is a property of the ground, so a thermometer that only read the biome would say a
     * mountain summit in plains country is a mild afternoon. The lapse is the second input, and it is the real
     * one rather than the game's: vanilla's own altitude term is a fifth of a degree per block, which on this
     * scale would put a hundred blocks of height nearly four degrees apart and make every hill its own climate.
     */
    public static final double CELSIUS_PER_BLOCK_ALTITUDE = 0.0065D;

    /** The height the lapse is measured from: sea level, the same one the barometer's column uses. */
    public static final double SEA_LEVEL_Y = 63.0D;

    /** The air's temperature in degrees, from the biome's own number and the height it was read at. */
    public static double celsius(double baseTemperature, double y) {
        double atGround = CELSIUS_AT_ZERO + Math.max(0.0D, baseTemperature) * CELSIUS_PER_UNIT;
        return atGround - Math.max(0.0D, y - SEA_LEVEL_Y) * CELSIUS_PER_BLOCK_ALTITUDE;
    }

    /** What a player calls that temperature. */
    public static String temperatureBand(double celsius) {
        if (celsius <= -15.0D) {
            return "severe cold";
        }
        if (celsius <= 0.0D) {
            return "freezing";
        }
        if (celsius <= 10.0D) {
            return "cold";
        }
        if (celsius <= 22.0D) {
            return "mild";
        }
        if (celsius <= 32.0D) {
            return "warm";
        }
        return "extreme heat";
    }

    /** The wind ladder a handheld anemometer reports on, in miles per hour, exactly as the station does. */
    public static final double LIGHT_MPH = 12.0D;
    public static final double MODERATE_MPH = 25.0D;
    public static final double STRONG_MPH = 40.0D;
    public static final double GALE_MPH = 58.0D;
    public static final double DAMAGING_MPH = 75.0D;
    public static final double VIOLENT_MPH = 110.0D;

    public static String windBand(double mph) {
        if (mph < LIGHT_MPH) {
            return "light air";
        }
        if (mph < MODERATE_MPH) {
            return "breeze";
        }
        if (mph < STRONG_MPH) {
            return "moderate wind";
        }
        if (mph < GALE_MPH) {
            return "strong wind";
        }
        if (mph < DAMAGING_MPH) {
            return "gale";
        }
        if (mph < VIOLENT_MPH) {
            return "damaging wind";
        }
        return "violent wind";
    }

    /**
     * How much of the wind is gust rather than steady air, as a share. A handheld reading is the wind where the
     * player is standing, and what a chaser is looking for is the ratio: 0.45 of the reading and above is air
     * that is being stirred rather than blown, which is what a gust front and a funnel's inflow both look like
     * on an anemometer.
     */
    public static final double GUSTY_SHARE = 0.45D;

    public static boolean gusty(double gustMph, double steadyMph) {
        return gustShare(gustMph, steadyMph) >= GUSTY_SHARE;
    }

    /** The gust's own share of a reading, or zero when there is nothing to compare it against. */
    public static double gustShare(double gustMph, double steadyMph) {
        if (steadyMph <= 1.0E-3D) {
            return gustMph > 0.0D ? 1.0D : 0.0D;
        }
        return Math.max(0.0D, (gustMph - steadyMph) / steadyMph);
    }

    /** The barometer's bands, in hectopascals: the same record low and record high the pressure model uses. */
    public static final double VERY_LOW_HPA = 975.0D;
    public static final double LOW_HPA = 1000.0D;
    public static final double HIGH_HPA = 1025.0D;

    public static String pressureBand(double hPa) {
        if (hPa < VERY_LOW_HPA) {
            return "very low";
        }
        if (hPa < LOW_HPA) {
            return "low";
        }
        if (hPa <= HIGH_HPA) {
            return "normal";
        }
        return "high";
    }

    /**
     * The one line an instrument prints, from the numbers the world gave it. It is built here rather than at
     * the item so that what the three instruments say can be read as text by a harness: a reading that is
     * printed twice with different wording in two places is how a mod ends up telling a player two things.
     */
    public static String reading(Kind kind, double first, double second) {
        return switch (kind) {
            case ANEMOMETER -> String.format(Locale.ROOT, "%.0f mph %s%s", first, windBand(first),
                    second > 0.0D ? String.format(Locale.ROOT, ", gusting %.0f", second) : "");
            case THERMOMETER -> String.format(Locale.ROOT, "%.1f C, %s", first, temperatureBand(first));
            case BAROMETER -> String.format(Locale.ROOT, "%.1f hPa, %s", first, pressureBand(first));
        };
    }

    /** Whether an instrument can read anything here: a thermometer reads anywhere, the other two need a sky. */
    public static boolean readsAt(Kind kind, boolean underRoof) {
        return kind == Kind.THERMOMETER || !underRoof;
    }

    /**
     * What a handheld anemometer remembers: the highest reading since it was last cleared.
     *
     * <p>A real handheld anemometer has this button and it is the whole reason a chaser carries one rather
     * than reading the number when they arrive - the gust that matters already went past. So the peak is kept
     * on the instrument, not worked out from the storm, and this is the arithmetic of keeping it: a reading
     * either beats the peak or it does not.
     */
    public static double peakSince(double previousPeak, double reading) {
        if (!Double.isFinite(previousPeak) || previousPeak < 0.0D) {
            return Math.max(0.0D, reading);
        }
        return Math.max(previousPeak, Math.max(0.0D, reading));
    }

    /**
     * The rate a barometer's own record gives, in hectopascals per in-game hour.
     *
     * <p>It is the difference between two readings over the time between them, and the time is the reason it
     * has to be arithmetic rather than a field: a rate is only ever a statement about two readings. No earlier
     * reading, no elapsed time, an absurd interval, or a non-finite pressure gives {@link Double#NaN}, which is
     * the device saying it does not know yet rather than a number that looks like a measurement.
     */
    public static final long ONE_IN_GAME_HOUR_TICKS = 1000L;

    /** How long a barometer's record is allowed to be old before it stops being a rate. A day of nothing. */
    public static final long MAX_MEMORY_TICKS = 24000L;

    public static double rateHpaPerHour(double previousHpa, double currentHpa, long ticksElapsed) {
        if (!Double.isFinite(previousHpa) || !Double.isFinite(currentHpa)) {
            return Double.NaN;
        }
        if (ticksElapsed <= 0L || ticksElapsed > MAX_MEMORY_TICKS) {
            return Double.NaN;
        }
        return (currentHpa - previousHpa) * (double) ONE_IN_GAME_HOUR_TICKS / (double) ticksElapsed;
    }

    /** What a player is told when the instrument has not got a record to compare against yet. */
    public static final String NO_RECORD = "no record yet - hold it again in a moment";
}
