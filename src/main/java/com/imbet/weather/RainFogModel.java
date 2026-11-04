package com.imbet.weather;

/**
 * The air a rain shaft puts around itself: the mist that stands in a downpour, and the wall of grey that
 * closes the distance down with it.
 *
 * <p>The mist in {@code MistField} is built as moisture - water nearby, the dew point, the time of day, the
 * wind - and that model is deliberately a calm-air one, because that is what ground mist is. Rain is not that
 * weather. A downpour fogs the air in seconds in the middle of the afternoon, in a place with no water in it
 * and with the wind screaming through it, and none of those three conditions is an accident: the fog of heavy
 * rain is the rain itself. The falling drops entrain and evaporate into the lowest few metres, the ground
 * underneath is soaked and feeds it back, and the downdraft spreads it out along the ground as a cold, wet
 * layer. It is thickest in the heaviest rain, it appears when the rain does, and it leaves when the rain
 * leaves - it is not something the sun burns off and it is not something a 50 mph gust front scours away. A
 * squall line's rain curtain is the clearest case of all: the windiest, blackest, most opaque air there is.
 *
 * <p>So this is that second system, kept separate on purpose, with its own two inputs:
 *
 * <ul>
 *   <li><b>How hard it is raining where you are standing.</b> Vanilla has one world-wide rain level, which
 *       is also what this mod drives while it is running its own weather, but rain is not equally thick
 *       everywhere under the same level - so the level is scaled by how much sky is actually over the
 *       player: under a storm's own deck it is the full amount, and out in the open under a plain vanilla
 *       shower it is a third of it. That is what keeps a rain fog attached to the rain rather than to the
 *       weather system's global mood.</li>
 *   <li><b>Whether you are inside the shaft.</b> Standing in the rain is thicker than standing beside it,
 *       which is what a squall line's curtain and a hurricane's band are: a body of falling water you can be
 *       in or out of. This is the term that sharpens the drizzle into a whiteout when you walk into it.</li>
 * </ul>
 *
 * <p>What it returns is a mass, 0 to 1, and the two ends of that scale are the two things a player sees:
 * {@link #farPlaneBlocks} is where the fog ends, and {@link #grey} is how much of the colour is washed out of
 * it. Both are read from the same mass as the east of it, so the fog and the mist it is made of cannot
 * disagree - the same rule {@code MistField} already keeps for its own haze.
 *
 * <p>Kept free of Minecraft on purpose: every number here is a curve that can be measured without a running
 * game. See {@code .freebuff/scratch/RainFogSim.java}.
 */
public final class RainFogModel {
    /**
     * Share of a plain vanilla shower's own level that reaches the air when nothing is overhead. A shower in
     * the open fogs the air a little; a shower under a storm's deck is that storm's own rain and fogs it by
     * the whole amount. See {@link #rainAmount}.
     */
    public static final double OPEN_SKY_RAIN_SHARE = 0.35D;
    /**
     * Mist mass at full rain with no shaft to stand in - the floor a downpour puts under the air on its own,
     * before the sharpening term below. Deliberately under 1: rain alone is heavy air, and it is being
     * <em>in</em> the fall that makes it a whiteout.
     */
    public static final double SHOWER_MASS = 0.68D;
    /**
     * How much more mass standing in the shaft adds, as a share of {@link #SHOWER_MASS}. Set so the two
     * together run a little past a full mass and the cap below is the thing that decides the worst rain: a
     * curtain is the thickest air there is, and it should be the same thickness however hard it is coming down.
     */
    public static final double SHAFT_SHARE = 0.60D;
    /** The mass is capped here: past this the fog is closed down and more rain cannot make it thicker. */
    public static final double MAX_MASS = 1.0D;
    /**
     * The curve from rain to mass. Below 1 because rain's first millimetres are all surface: a drizzle already
     * hazes the air visibly, and the difference between a downpour and a torrent is much smaller than the
     * difference between a drizzle and a downpour. A linear rain-to-fog map reads as no fog at all until it is
     * suddenly heavy.
     */
    public static final double RAIN_CURVE = 0.8D;
    /** Far fog plane in air with no rain in it, in blocks. */
    public static final double CLEAR_PLANE_BLOCKS = 240.0D;
    /** Far fog plane in the thickest rain there is, in blocks. */
    public static final double DOWNPOUR_PLANE_BLOCKS = 36.0D;
    /**
     * The curve from mass to fog distance, below 1 so the first of the fog arrives with the first of the rain:
     * visibility is what a player notices first, and a plane that only started moving at half mass would read
     * as the rain being late.
     */
    public static final double PLANE_CURVE = 0.7D;
    /** How grey the colour goes at full mass - not all the way, so rain fog is grey air rather than a wall. */
    public static final double GREY_AT_FULL_MASS = 0.9D;

    private RainFogModel() {
    }

    /**
     * How hard it is raining on the player: vanilla's own rain level, scaled by how much of the sky over them
     * is a storm's. 0 is dry air, 1 is a downpour directly under a deck.
     */
    public static double rainAmount(double vanillaRain, double cover) {
        double level = clamp(vanillaRain, 0.0D, 1.0D);
        double sky = clamp(cover, 0.0D, 1.0D);
        return clamp(level * (OPEN_SKY_RAIN_SHARE + (1.0D - OPEN_SKY_RAIN_SHARE) * sky), 0.0D, 1.0D);
    }

    /**
     * The mist mass the rain itself puts in the air, 0 (no rain, no fog) to {@link #MAX_MASS}.
     *
     * @param rain  how hard it is raining here, from {@link #rainAmount}
     * @param shaft 0 standing beside the rain, 1 standing in the middle of the shaft
     */
    public static double mistMass(double rain, double shaft) {
        double fall = clamp(shaft, 0.0D, 1.0D);
        double mass = Math.pow(clamp(rain, 0.0D, 1.0D), RAIN_CURVE) * SHOWER_MASS * (1.0D + SHAFT_SHARE * fall);
        return clamp(mass, 0.0D, MAX_MASS);
    }

    /** How far the fog reaches in air of this mass, in blocks. Never further than clear air's own plane. */
    public static double farPlaneBlocks(double mass) {
        double reach = Math.pow(clamp(mass, 0.0D, 1.0D), PLANE_CURVE);
        return CLEAR_PLANE_BLOCKS + (DOWNPOUR_PLANE_BLOCKS - CLEAR_PLANE_BLOCKS) * reach;
    }

    /** How much of the fog's colour is washed toward grey at this mass, 0 (untouched) to 0.9. */
    public static double grey(double mass) {
        return clamp(clamp(mass, 0.0D, 1.0D) * GREY_AT_FULL_MASS, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
