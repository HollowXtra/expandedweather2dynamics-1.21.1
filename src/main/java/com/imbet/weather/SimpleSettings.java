package com.imbet.weather;

/**
 * The arithmetic behind the simple settings: one dial per thing a player wants to change, scaling what the
 * detailed knobs already decided.
 *
 * <p>This mod's configuration is a few hundred numbers, and almost none of them are a question a player
 * should have to answer - they are the tuning of systems, and they are right as they ship. What a player
 * does want to say is short: how often storms come, how many there are at once, how big and how violent
 * they get, how hard it rains, and whether they may break anything. So each of those is one dial that
 * <em>scales</em> the detail rather than replacing any of it: at the default of one, everything below is
 * exactly the mod as it was tuned, and every other value is a multiplier on that.
 *
 * <p>Written here rather than inside the config so that the arithmetic can be measured - the identity at
 * one, the ends of each dial, and the fact that zero really is zero on the two dials where it means the
 * feature is off. The alternative, a dial that is read in fifteen places, is a dial that means something
 * slightly different in each of them.
 */
public final class SimpleSettings {
    private SimpleSettings() {
    }

    /** The value every dial returns to the mod's own tuning at, and the default of each. */
    public static final double NEUTRAL = 1.0D;

    /** The chance a storm spawns at, with the frequency dial on it. */
    public static double spawnChance(double chance, double frequency) {
        return Math.max(0.0D, chance) * clampDial(frequency, 0.0D, 8.0D);
    }

    /** A wind speed, with the strength dial on it. */
    public static double windMph(double mph, double strength) {
        return Math.max(0.0D, mph) * clampDial(strength, 0.0D, 4.0D);
    }

    /** A width, with the size dial on it. */
    public static double width(double blocks, double size) {
        return Math.max(0.0D, blocks) * clampDial(size, 0.0D, 4.0D);
    }

    /**
     * How thick the rain is, with the rain dial on it.
     *
     * <p>Floored a little above zero rather than allowed to reach it: rain that is drawn at all has to be
     * drawn with <em>some</em> light taken out of it, and a dial that faded a storm to a completely
     * invisible curtain would leave a thunderstorm overhead with nothing falling out of it. The frequency
     * and strength dials have no such floor, because zero storms and no wind at all are both coherent
     * things to ask for.
     */
    public static double rainOpacity(double opacity, double rain) {
        return Math.max(0.0D, opacity) * Math.max(0.04D, clampDial(rain, 0.0D, 4.0D));
    }

    /**
     * Whether anything may be broken: the destruction dial and the damage system's own switch, together.
     *
     * <p>The dial can only ever take destruction away, never add it. A master switch that could override
     * the detailed one would make the detailed one a lie, and this mod's damage settings are read by two
     * dozen systems that each have their own good reason to stand down - a keep-inventory world, a
     * compatibility hook, a block another mod owns.
     */
    public static boolean destructionEnabled(boolean destruction, boolean damageEnabled) {
        return destruction && damageEnabled;
    }

    /**
     * How strongly the world's own calendar applies, with the seasons dial on it.
     *
     * <p>The dial scales the whole year rather than any one season: at one the season is exactly what the
     * calendar's own strengths say, at zero the year turns and the sky never notices, and above one every season
     * is that many times as far from the neutral day. The detailed per-season strengths still decide the shape -
     * this only says how much of it is in force - so the two dials cannot disagree about which season it is.
     */
    public static double seasonStrength(double strength, double dial) {
        return Math.max(0.0D, strength) * clampDial(dial, 0.0D, 2.0D);
    }

    /**
     * Whether the plains-only rule is in force: the simple switch and the detailed one, together.
     *
     * <p>It can only ever take the rule away, never add it. A world owner who has switched the plains rule off
     * in the tornado genesis section is saying a tornado may form anywhere, and a dial that could override that
     * would make the detailed setting a lie; the other way round is what the dial is for, since a player who wants
     * the funnels over their own forest can say so in one place.
     */
    public static boolean plainsOnly(boolean simple, boolean detailed) {
        return simple && detailed;
    }

    /**
     * Whether the storm limit has been reached: the cap, and how many are already inside the ring.
     *
     * <p>A cap of zero or less means no cap at all, which is the way back to the mod's own behaviour
     * without turning the dial to something arbitrary. Counts at or above the cap are at the limit, so the
     * check reads the way the setting does: two storms with a cap of three is room for another.
     */
    public static boolean atStormLimit(int active, int cap) {
        return cap > 0 && active >= cap;
    }

    /** A dial clamped to its own range, so a config file edited by hand cannot ask for something absurd. */
    private static double clampDial(double dial, double minimum, double maximum) {
        if (Double.isNaN(dial)) {
            return NEUTRAL;
        }
        return Math.max(minimum, Math.min(maximum, dial));
    }
}
