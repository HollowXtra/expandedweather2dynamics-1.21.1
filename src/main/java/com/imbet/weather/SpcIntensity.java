package com.imbet.weather;

/**
 * Conditional intensity: how severe the weather is expected to be <em>if</em> it happens.
 *
 * <p>This is the CIG half of an SPC outlook. The same probability means different things depending on
 * it - a 10% tornado area is Slight on a marginal shear day and Enhanced when the environment
 * supports significant tornadoes - so the addon derives a CIG level from the same quantities the
 * storms themselves are built from: CAPE, deep-layer shear, storm mode index, and the actual wind
 * speeds the weather model is running.
 *
 * <p>The thresholds are the real significant-severe definitions: a CIG 1 tornado is EF2+ (111 mph),
 * CIG 2 is EF3+ (135 mph), CIG 3 is EF4+ (165 mph); a CIG 1 wind is a 75 mph thunderstorm gust, CIG 2
 * is 90, CIG 3 is 105; hail is 2 inch for CIG 1 and 3 inch for CIG 2. Environmental thresholds are
 * deliberately gated on shear as well as CAPE, because a huge-CAPE/no-shear day cannot produce
 * significant severe weather and must not earn hatch marks.
 *
 * <p>CIG 4 and CIG 5 are the addon's extension, and they are what makes the extreme and hyper extreme
 * categories reachable at all: the published ladder has no column for them. CIG 4 is an EF5 tornado
 * (200 mph) or a 130 mph derecho gust; CIG 5, the 260 mph / 165 mph cell, is the 1974 or 2011 class
 * event. Hail has no extension, because hail cannot carry a category that high on its own.
 *
 * <p>Both extension gates are placed strictly beyond the published ladder's own top rung. They used to
 * sit below it, which meant every high-risk environment already qualified for an extension and the
 * extreme categories swallowed high risk whole - the ladder jumped straight from moderate to extreme
 * and high risk could never be issued at all. Stepping the gates up past the high-risk rung is what
 * gives high risk a band of its own.
 */
public final class SpcIntensity {
    private SpcIntensity() {
    }

    /**
     * Forecast CIG for tornadoes from the day's environment.
     *
     * <p>Every level has to be a strict superset of the one below it - higher thresholds in at least
     * one quantity - or a lower level becomes unreachable and the ladder silently reports the level
     * above. The extension levels therefore step up from CIG 3's 4000 J/kg, 30 m/s and 0.78 mode
     * rather than sitting beside it, and beyond the published ladder's own top rung as well, so an
     * extension category can never swallow high risk. Their values are placed where this model can
     * actually reach: CAPE tops out near 5100 J/kg, so 4 is a once-a-season day and 5 needs CAPE and
     * shear together up against the ceiling of both.
     */
    public static int tornadoCig(double capeJkg, double shearMps, double stormModeIndex) {
        if (capeJkg >= 4750.0D && shearMps >= 38.0D && stormModeIndex >= 0.88D) {
            return 5;
        }
        if (capeJkg >= 4400.0D && shearMps >= 34.0D && stormModeIndex >= 0.84D) {
            return 4;
        }
        if (capeJkg >= 4000.0D && shearMps >= 30.0D && stormModeIndex >= 0.78D) {
            return 3;
        }
        if (capeJkg >= 3200.0D && shearMps >= 25.0D && stormModeIndex >= 0.68D) {
            return 2;
        }
        if (capeJkg >= 2200.0D && shearMps >= 20.0D && stormModeIndex >= 0.55D) {
            return 1;
        }
        return 0;
    }

    /**
     * Forecast CIG for damaging thunderstorm wind, stepped up from CIG 3's 3800 J/kg and 26 m/s. Its
     * extension trades CAPE for shear against the tornado one on purpose: a wind-driven extreme is a
     * derecho, and a derecho does not need supercell tornado thermodynamics - nor the instability a
     * tornado extreme does - to be off the published ladder. Trading the two apart also keeps each
     * hazard's extension able to be the sole reason a day escalates, rather than one swallowing the other.
     */
    public static int windCig(double capeJkg, double shearMps) {
        if (capeJkg >= 4600.0D && shearMps >= 44.0D) {
            return 5;
        }
        if (capeJkg >= 4200.0D && shearMps >= 38.0D) {
            return 4;
        }
        if (capeJkg >= 3800.0D && shearMps >= 26.0D) {
            return 3;
        }
        if (capeJkg >= 3000.0D && shearMps >= 18.0D) {
            return 2;
        }
        if (capeJkg >= 1800.0D && shearMps >= 14.0D) {
            return 1;
        }
        return 0;
    }

    /** Forecast CIG for large hail; hail only reaches CIG 2. */
    public static int hailCig(double capeJkg, double shearMps) {
        if (capeJkg >= 3200.0D && shearMps >= 20.0D) {
            return 2;
        }
        if (capeJkg >= 2000.0D && shearMps >= 12.0D) {
            return 1;
        }
        return 0;
    }

    /**
     * Observed tornado intensity, from the fastest wind a tornado-capable storm is carrying. CIG 4 is
     * EF5 and CIG 5 is past the top of the scale; the in-game wind model runs to roughly 350 mph at
     * stage 5, so both are reachable by a genuinely violent tornado instead of being decoration.
     *
     * <p>Each rung turns over where its EF class <em>starts</em>, not one mile per hour below it: CIG 1
     * is significant severe (EF2, 111), CIG 2 is EF3 (136), CIG 3 is EF4 (166) and CIG 4 is EF5 (201),
     * so a storm sitting exactly on a class floor is not read as one rung under it - which is what
     * 135/165/200 did. CIG 5 keeps its own 260, the mod's extension above the published scale.
     */
    public static int tornadoCigForWind(double maxWindMph) {
        if (maxWindMph >= 260.0D) {
            return 5;
        }
        if (maxWindMph >= 201.0D) {
            return 4;
        }
        if (maxWindMph >= 166.0D) {
            return 3;
        }
        if (maxWindMph >= 136.0D) {
            return 2;
        }
        if (maxWindMph >= 111.0D) {
            return 1;
        }
        return 0;
    }

    /** Observed wind intensity, against the 75/90/105 mph significant-gust thresholds. */
    public static int windCigForWind(double maxWindMph) {
        if (maxWindMph >= 165.0D) {
            return 5;
        }
        if (maxWindMph >= 130.0D) {
            return 4;
        }
        if (maxWindMph >= 105.0D) {
            return 3;
        }
        if (maxWindMph >= 90.0D) {
            return 2;
        }
        if (maxWindMph >= 75.0D) {
            return 1;
        }
        return 0;
    }

    /** Observed hail intensity; the biggest in-game hail sits in the strongest hail stages. */
    public static int hailCigForStage(int intensityStage) {
        if (intensityStage >= 7) {
            return 2;
        }
        if (intensityStage >= 4) {
            return 1;
        }
        return 0;
    }
}
