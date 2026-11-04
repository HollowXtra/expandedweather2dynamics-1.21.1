package com.imbet.weather;

/**
 * What a storm's deck does to the sky, as arithmetic.
 *
 * <p>Two things happen to the sky over a storm, and until now this mod did neither of them itself. It asked the game
 * to do the first - the sky and the world were darkened by writing a rain level into the level, which is vanilla's
 * own way of saying it is raining, and vanilla then derived the sky's colour, the sun's drawn alpha and the ambient
 * light from it. That works, and it has a ceiling that is the whole reason to stop doing it: the darkening can never
 * be deeper than the game's own rain, so the sky went the grey-blue vanilla paints rain rather than the sky of a
 * storm, and the sun's hiding belonged to the game's weather rather than to the storm in front of a player. So the
 * darkening is this class's own now, and it is one number applied in one place per surface - the sky's colour, the
 * fog's, the cloud deck's and the world's own ambient light - rather than a value written into a field that four
 * other systems then read.
 *
 * <p>The second is the green, and the reason it is here rather than a tint somebody picked is that a green sky is a
 * <em>hail</em> signal and this mod already knows exactly where its hail is. A green sky is light coming through a
 * storm's hail core, and a player under that core sees it as a green cast over everything and calls it what the
 * weather books call it: hail weather. So the green is gated the way the hail itself is gated, on
 * {@link HailModel} - the same structure and core strengths, the same size ladder, the same footprint - and drawn
 * only where stones are actually falling around the player. A storm with a rain core and no hail never goes green,
 * however dark it is, because the sky is not telling the player anything they cannot already see.
 *
 * <p>Nothing here reads the time of day, and that is deliberate rather than an omission. Every colour on this side
 * is a <em>multiplier</em> on the colour the game has already computed - which carries the sun's angle on the day -
 * so the deck darkens a noon sky to a dim version of noon and a dusk sky to a dim version of dusk, and the green is
 * mixed at the same weight whatever the hour, which at midnight means a green so dark it is barely there. That last
 * part is right: a green sky at midnight is not a thing, and it falls out of the arithmetic instead of being a
 * condition somebody had to remember.
 *
 * <p>Arithmetic only, with no Minecraft types in it beyond the size ladder {@link HailModel} already owns, so every
 * number here is checkable without a game running.
 */
public final class StormSky {

    /**
     * The darkest the sky is ever allowed to go, and it is vanilla's own midnight.
     *
     * <p>Vanilla's sky-darken value runs from 1 with a clear noon overhead to 0.2 at midnight, and every consumer -
     * the lightmap, a fog, the sky's own colour - is built for that range. Sitting this mod's floor on the game's own
     * floor means the darkest a storm can make the sky is the dark the game already knows how to render, so a hailing
     * supercell at noon reads as night and never as something the renderer has no case for.
     */
    public static final double DARKEST_SKY_DARKEN = 0.20D;

    /** How much of the light a storm's deck takes at full cover, before the hailing core is counted. */
    public static final double DECK_DARKEN = 0.62D;

    /** And how much a hailing core takes on top of the deck, at the middle of its own shaft. */
    public static final double HAIL_DARKEN = 0.18D;

    /** The two shares together are exactly what separates a clear sky from the floor, so scale 1 can reach it. */
    public static final double FULL_DARKEN = DECK_DARKEN + HAIL_DARKEN;

    /**
     * How dark the sky is under a deck, as the same value vanilla's own sky-darken uses.
     *
     * <p>One reading for everything that darkens, because a sky, a fog, a deck and an ambient light that disagree
     * about how dark it is are four things rather than a storm. {@code cover} is the share of the sky over the
     * player that is a storm's own mass - its deck, its wall cloud, its anvil and the precipitation falling on them -
     * and {@code hail} is how deep in a hailing core's shaft the player is, nought at the edge of the footprint and
     * one at its middle. The second term is what makes the inside of a hailing core measurably darker than the rain
     * beside it, which is the difference the clatter and the damage pass are drawn from as well.
     *
     * <p>{@code strength} is the config's dial on the whole thing: at 0 the sky is left exactly as the game drew it
     * however heavy the storm, at 1 a directly-overhead hailing core reaches the floor, and the shipped value is
     * slightly under 1 so that the darkest a storm gets still has somewhere to go.
     */
    public static double darkness(double cover, double hail, double strength) {
        double deck = clamp(cover, 0.0D, 1.0D);
        double core = clamp(hail, 0.0D, 1.0D);
        double depth = (DECK_DARKEN * deck + HAIL_DARKEN * core) * clamp(strength, 0.0D, 1.0D);
        return clamp(1.0D - depth, DARKEST_SKY_DARKEN, 1.0D);
    }

    /**
     * How much light an eclipse takes out of the sky, as the same value vanilla's sky-darken uses.
     *
     * <p>An eclipse is not a second kind of darkening with its own rules, it is the sun's own light being
     * taken away, so it composes with a storm's deck the same way a cloud does: a multiply on the light, at
     * the share of the sun the moon is covering. At full coverage it is the configured darkening and no
     * more, and it is held off the game's own floor - vanilla's midnight - so the darkest an eclipse can
     * leave the world is still a dark the renderer already knows how to draw. It is computed here rather
     * than in a renderer because it has to apply with or without a shader pack, and the only thing that
     * reaches a world under every renderer is the sky-darken value every consumer is built on.
     */
    public static double eclipseDarkness(double coverage, double darken) {
        double covered = clamp(coverage, 0.0D, 1.0D);
        double depth = clamp(darken, 0.0D, 1.0D);
        return clamp(1.0D - covered * depth, DARKEST_SKY_DARKEN, 1.0D);
    }

    /**
     * A storm's own darkening with an eclipse's folded in, as one reading.
     *
     * <p>Two things can darken the same sky and the player sees the darker of them, so this is a multiply
     * rather than whichever is deeper: a storm deck over an eclipse is darker than either alone, which is
     * what standing under a storm during one actually is. The result is held on the same floor, so the pair
     * cannot produce a value the renderer has no case for. With no eclipse the factor is exactly one and
     * this returns the storm's own reading unchanged.
     */
    public static double withEclipse(double dark, double eclipse) {
        double storm = clamp(dark, DARKEST_SKY_DARKEN, 1.0D);
        double event = clamp(eclipse, DARKEST_SKY_DARKEN, 1.0D);
        return clamp(storm * event, DARKEST_SKY_DARKEN, 1.0D);
    }

    /** The smallest stone whose sky goes green. A green sky is a large-hail signal, not a rain signal. */
    public static final HailModel.Size GREEN_FROM = HailModel.Size.QUARTER;

    /** How far into a shaft's footprint the player has to be before any green shows at all. */
    public static final double GREEN_AT_SHAFT_SHARE = 0.10D;

    /**
     * The colour of a hail sky: the yellow-green of light coming through a hail core.
     *
     * <p>Muted on purpose. A saturated green reads as a tint somebody applied; the real thing is a sickly
     * yellow-green that is only just recognisable as green, and it is mixed at a weight rather than painted, so the
     * sky keeps the hue the hour gave it and gains this one over the top of it.
     */
    public static final double TINT_RED = 0.35D;
    public static final double TINT_GREEN = 0.60D;
    public static final double TINT_BLUE = 0.38D;

    /** How much of the green the cloud deck takes, against the sky's own. The deck is where it is seen from. */
    public static final double DECK_GREEN_SHARE = 0.55D;

    /** And how much the fog takes. Less again: the air in front of you is not the thing that turned green. */
    public static final double FOG_GREEN_SHARE = 0.35D;

    /**
     * How green the sky is, from how deep in a hailing core the player is and how big its stones are.
     *
     * <p>Two gates and a size, in that order, because that is the order the claim is true in. The size gate is the
     * strong one: below a quarter there is no green at all, however hard it is hailing, because a sky going green
     * over pea-sized stones would be the mod telling a player about hail they can already hear on the roof and
     * telling them something the weather does not do. Above it the green rises with the stone - a quarter is a third
     * of it, a golf ball two thirds, a baseball all of it - and with how far into the shaft the player is standing,
     * so walking out of a hail core takes the green with it rather than leaving the sky tinted over the rain beside
     * it. The whole thing is nought the moment the stones stop, because {@code size} is what the hail pass itself is
     * running on.
     */
    public static double greenness(double hail, HailModel.Size size) {
        if (size == null || size.tier() < GREEN_FROM.tier()) {
            return 0.0D;
        }
        double inside = clamp(hail, 0.0D, 1.0D);
        if (inside < GREEN_AT_SHAFT_SHARE) {
            return 0.0D;
        }
        double depth = (inside - GREEN_AT_SHAFT_SHARE) / (1.0D - GREEN_AT_SHAFT_SHARE);
        double stone = (size.tier() - (GREEN_FROM.tier() - 1))
                / (double) (HailModel.Size.BASEBALL.tier() - (GREEN_FROM.tier() - 1));
        return clamp(depth * stone, 0.0D, 1.0D);
    }

    /**
     * A colour under the deck, given how dark it is and how green.
     *
     * <p>The darkening is a multiply rather than a blend toward grey, and that is the one place this deliberately
     * parts company with vanilla's own rain darkening. Vanilla mixes the sky toward its own luminance, which is what
     * turns a rain sky a flat blue-grey whatever the hour; multiplying instead keeps the hue the day gave it, so a
     * storm at sunset is a dim orange and a storm at noon is dim white. The green is mixed in after, at the full
     * weight, so it is the last thing done to the colour and cannot be washed out by the darkening in front of it.
     *
     * <p>The tint is itself scaled by the darkening, which is what stops a green sky being a bright green one. At a
     * third dark the mix lands on a dark green, and the brightest this can ever return is the tint at full daylight -
     * so a green sky is only ever visible when there is a deck over it making it dark enough to be believed.
     */
    public static double[] shade(double red, double green, double blue, double dark, double greenness, double greenShare) {
        double d = clamp(dark, DARKEST_SKY_DARKEN, 1.0D);
        double g = clamp(greenness, 0.0D, 1.0D) * clamp(greenShare, 0.0D, 1.0D);
        return new double[] {
                mix(red * d, TINT_RED * d, g),
                mix(green * d, TINT_GREEN * d, g),
                mix(blue * d, TINT_BLUE * d, g)
        };
    }

    /** The sky and the fog: the deck's own colour, with the whole of the green over it. */
    public static double[] skyShade(double red, double green, double blue, double dark, double greenness) {
        return shade(red, green, blue, dark, greenness, 1.0D);
    }

    /** The deck itself. */
    public static double[] deckShade(double red, double green, double blue, double dark, double greenness) {
        return shade(red, green, blue, dark, greenness, DECK_GREEN_SHARE);
    }

    /**
     * How much of the sun, the moon and the stars is hidden.
     *
 * <p>Vanilla draws all three with one alpha, {@code 1 - rainLevel}, which is why this mod used to have to write
 * a rain level into the level to get the sun out of the way - a number that belonged to the game's weather and
 * could only ever be as strong as the game's own rain. The number is this mod's own now, derived from the same
 * darkness that darkens everything else, so the sun is gone exactly when the sky is dark and there is no second
 * system to disagree with the first.
     */
    public static double sunHiding(double cover, double hail, double strength) {
        double left = (darkness(cover, hail, strength) - DARKEST_SKY_DARKEN) / (1.0D - DARKEST_SKY_DARKEN);
        return clamp(1.0D - left, 0.0D, 1.0D);
    }

    /**
     * The world's own ambient light under a deck, as vanilla's sky-darken value.
     *
     * <p>It is a floor rather than a replacement: whichever of the game's own darkening and this mod's is lower is
     * the one that wins, so a night storm is not twice as dark as a night and an actual thunderstorm settles the
     * question itself. Everything that reads the game's sky-darken - the lightmap, this mod's own quads, anything
     * drawing under the deck - then agrees about how dark it is without being told twice.
     */
    public static double worldDarken(double vanillaSkyDarken, double dark) {
        return Math.min(clamp(vanillaSkyDarken, 0.0D, 1.0D), clamp(dark, DARKEST_SKY_DARKEN, 1.0D));
    }

    /** How fast the deck's own reading rises, per sky update: fast, because a storm arriving is not gradual. */
    public static final double DECK_RISE_RATE = 0.30D;

    /** And how fast it falls, which is slower: a deck that has passed still has its light behind you. */
    public static final double DECK_FALL_RATE = 0.12D;

    /** The green rises faster than the deck, because it is the last thing to appear and the first to matter. */
    public static final double GREEN_RISE_RATE = 0.28D;

    /** And falls more slowly than the deck, because a sky that has been green stays in the eye for a moment. */
    public static final double GREEN_FALL_RATE = 0.10D;

    /**
     * One step of a smoothed reading toward its target, on its own rate for each direction.
     *
     * <p>Two rates rather than one because the two directions are different events: a deck arriving is a storm
     * arriving, and a deck leaving is weather that has already gone. A single rate has to be the faster of the two
     * to be usable, which makes the sky snap back to clear the moment the last stones pass, or the slower of the
     * two, which makes a storm arrive as a slow fade out of nowhere.
     */
    public static double approach(double current, double target, double riseRate, double fallRate) {
        double to = clamp(target, 0.0D, 1.0D);
        double from = clamp(current, 0.0D, 1.0D);
        double rate = to > from ? riseRate : fallRate;
        return from + (to - from) * clamp(rate, 0.0D, 1.0D);
    }

    /** How far from a player a storm's hail is looked for, in blocks: the same range the sky already tracks storms in. */
    public static final double HAIL_SCAN_RANGE_BLOCKS = 700.0D;

    /**
     * How deep in a hailing core's own footprint a position is, nought to one.
     *
     * <p>It is the footprint the damage and the clatter both run on, read as a position rather than as a box: one at
     * the middle of the shaft and falling to nought at its edge, so a player watching a hail core go past forty
     * blocks away sees the sky go green over there and fade as it comes, and the sky they are standing under is
     * always the sky over them. A footprint of no size is depth nought rather than a division by nothing.
     */
    public static double shaftDepth(double distanceBlocks, double footprintRadiusBlocks) {
        if (!(footprintRadiusBlocks > 0.0D)) {
            return 0.0D;
        }
        return clamp(1.0D - Math.max(0.0D, distanceBlocks) / footprintRadiusBlocks, 0.0D, 1.0D);
    }

    /**
     * The rain level the sun, the moon and the stars should be drawn against.
     *
     * <p>Vanilla consumes a rain level and computes {@code 1 - rainLevel} itself, so what has to be answered here is
     * the level rather than the alpha, and it is a floor again: the game's own rain hides the sun whenever the world
     * is really raining, and this mod's deck hides it when the mod's own sky has gone dark, and whichever is greater
     * wins. A world that is actually raining and has a storm over it hides the sun exactly as much as the darker of
     * the two says, not twice as much.
     */
    public static float sunHidingLevel(double vanillaRainLevel, double cover, double hail, double strength) {
        double rain = clamp(vanillaRainLevel, 0.0D, 1.0D);
        return (float) Math.max(rain, sunHiding(cover, hail, strength));
    }

    private static double clamp(double value, double low, double high) {
        if (!Double.isFinite(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }

    private static double mix(double from, double to, double share) {
        return from + (to - from) * share;
    }

    private StormSky() {
    }
}
