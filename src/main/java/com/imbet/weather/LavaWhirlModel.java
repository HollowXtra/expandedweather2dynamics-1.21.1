package com.imbet.weather;

/**
 * Two vortices that are not weather: one made of fire over molten rock, and one made of steam where that rock
 * meets water.
 *
 * <p>Neither belongs to the storm system and neither should. A fire whirl needs a fire, a dust devil needs a
 * hot dry surface, and a lava whirl needs something much simpler and much rarer: a large enough surface of
 * molten rock to spin up over. That is why this class is anchored rather than seeded - its input is a volcano's
 * own phase, intensity and scale, and its output belongs to the vent it was made at. The 1.12.2 mod put lava
 * in a volcano's throat and left it there; this is what that throat can do.
 *
 * <p>The steam devil is the phreatic cousin and the more interesting of the two, because it is not a fire
 * phenomenon at all. Lava meeting water flashes it, and the plume of steam that comes off expands violently
 * enough to organise into a rotating column - the same eddy a dust devil is, but driven by a phase change
 * instead of by insolation, which is why it can appear in seconds and why it can do it in the rain. And
 * because steam is hot but has no mass, it is the one vortex in this mod that cannot move a single block: it
 * scalds anything standing in it and throws nothing, and that asymmetry is deliberate, not an omission.
 *
 * <p>This class is arithmetic only - no Minecraft types - so both ladders can be checked outside the game. See
 * {@code .freebuff/scratch/Phase3Sim.java}.
 */
public final class LavaWhirlModel {

    private LavaWhirlModel() {
    }

    /** What came off this vent, if anything. */
    public enum Kind {
        NONE,
        LAVA_WHIRL,
        STEAM_DEVIL
    }

    /** An eruption has to be at least this intense to have lava welling at the vent to spin up. */
    public static final double MIN_INTENSITY = 0.35D;

    /** How far from the vent a whirl may stand, as a multiple of the volcano's own crater radius. */
    public static final double VENT_REACH_FRACTION = 1.35D;

    /** The whirl's wind at the least intense qualifying eruption and at the most violent. */
    public static final double MIN_WIND_MPH = 42.0D;
    public static final double MAX_WIND_MPH = 96.0D;

    /**
     * Its width, against the vent's own crater. A whirl is thrown up by the vent and is sized by it: a small
     * crater spins a narrow one and a large caldera a wide one. The span below is the crater radius a whirl's
     * width is read across, in blocks.
     */
    public static final double MIN_RADIUS_BLOCKS = 6.0D;
    public static final double MAX_RADIUS_BLOCKS = 26.0D;
    public static final double CRATER_RADIUS_FLOOR_BLOCKS = 4.0D;
    public static final double CRATER_RADIUS_CEILING_BLOCKS = 30.0D;

    /** How long it spins, in ticks: a few seconds at the least and a couple of minutes at the most. */
    public static final int MIN_LIFE_TICKS = 200;
    public static final int MAX_LIFE_TICKS = 3000;

    /** The odds a pass lights the flammables it is standing among, and the odds it spills lava. */
    public static final double IGNITE_AT_MIN_INTENSITY = 0.10D;
    public static final double IGNITE_AT_FULL_INTENSITY = 0.45D;
    public static final double SPILL_AT_MIN_INTENSITY = 0.02D;
    public static final double SPILL_AT_FULL_INTENSITY = 0.12D;

    /**
     * What the whirl does to whatever is caught in it, in half-hearts, at its least and at its most. It is
     * read off the whirl's own wind rather than off the eruption, because the wind is what is touching the
     * thing being burned and it is the one figure both the entity and the client can see.
     */
    public static final float MIN_BURN_DAMAGE = 1.0F;
    public static final float MAX_BURN_DAMAGE = 4.0F;

    /** A steam devil needs this much lava, this much water, and the two of them touching. */
    public static final int MIN_LAVA_BLOCKS = 3;
    public static final int MIN_WATER_BLOCKS = 2;
    /** How many blocks of each saturate the reaction. */
    public static final int FULL_LAVA_BLOCKS = 24;
    public static final int FULL_WATER_BLOCKS = 12;

    public static final double MIN_STEAM_WIND_MPH = 18.0D;
    public static final double MAX_STEAM_WIND_MPH = 54.0D;
    public static final double MIN_STEAM_RADIUS_BLOCKS = 3.0D;
    public static final double MAX_STEAM_RADIUS_BLOCKS = 11.0D;
    public static final int MIN_STEAM_LIFE_TICKS = 120;
    public static final int MAX_STEAM_LIFE_TICKS = 900;
    /** How far the column rises, in blocks. */
    public static final double MIN_STEAM_RISE_BLOCKS = 14.0D;
    public static final double MAX_STEAM_RISE_BLOCKS = 52.0D;
    /** Steam scalds: it burns anything inside it and, unlike a fire whirl, never sets it alight. */
    public static final float MIN_SCALD_DAMAGE = 1.5F;
    public static final float MAX_SCALD_DAMAGE = 5.0F;

    /** Whether this volcano can put a whirl up at all. */
    public static boolean canFormLavaWhirl(boolean phaseHasLava, double intensity, double distanceToVentBlocks, double craterRadiusBlocks) {
        if (!phaseHasLava || intensity < MIN_INTENSITY) {
            return false;
        }
        double reach = Math.max(4.0D, craterRadiusBlocks) * VENT_REACH_FRACTION;
        return distanceToVentBlocks <= reach;
    }

    /** How hard it blows, from the eruption's own intensity. */
    public static double windMph(double intensity) {
        return lerp(MIN_WIND_MPH, MAX_WIND_MPH, shareOf(intensity));
    }

    /**
     * The eruption intensity a whirl's own wind was made from: the same ladder read backwards. It exists
     * because the wind is the one figure that travels with the whirl - the entity carries it, the client draws
     * it, and a world pass that wants the intensity behind it has nothing else to read. The round trip is a
     * property the harness measures rather than assumes.
     */
    public static double intensityFromWind(double windMph) {
        double span = Math.max(1.0E-6D, MAX_WIND_MPH - MIN_WIND_MPH);
        return MIN_INTENSITY + clamp((windMph - MIN_WIND_MPH) / span, 0.0D, 1.0D) * (1.0D - MIN_INTENSITY);
    }

    /** How wide it stands, from the radius of the crater it came off. */
    public static double radiusBlocks(double craterRadiusBlocks) {
        double span = Math.max(1.0E-6D, CRATER_RADIUS_CEILING_BLOCKS - CRATER_RADIUS_FLOOR_BLOCKS);
        double sizeShare = clamp((craterRadiusBlocks - CRATER_RADIUS_FLOOR_BLOCKS) / span, 0.0D, 1.0D);
        return lerp(MIN_RADIUS_BLOCKS, MAX_RADIUS_BLOCKS, sizeShare);
    }

    /** How long it lasts, from the eruption's intensity and one roll. */
    public static int lifeTicks(double intensity, double roll) {
        double share = clamp(0.5D * shareOf(intensity) + 0.5D * clamp(roll, 0.0D, 1.0D), 0.0D, 1.0D);
        return (int) Math.round(lerp(MIN_LIFE_TICKS, MAX_LIFE_TICKS, share));
    }

    public static double ignitingChance(double intensity) {
        return lerp(IGNITE_AT_MIN_INTENSITY, IGNITE_AT_FULL_INTENSITY, shareOf(intensity));
    }

    public static double lavaSpillChance(double intensity) {
        return lerp(SPILL_AT_MIN_INTENSITY, SPILL_AT_FULL_INTENSITY, shareOf(intensity));
    }

    public static float burnDamage(double windMph) {
        return (float) lerp(MIN_BURN_DAMAGE, MAX_BURN_DAMAGE, windShare(windMph, MIN_WIND_MPH, MAX_WIND_MPH));
    }

    /**
     * Whether a whirl can still stand where it is. A lava whirl needs molten rock under it: one that drifts
     * off the flow and onto cold ground is a fire whirl, which is a different phenomenon and not this one. In
     * a nether-like world the ground is its own heat source, so the test is passed by it rather than by lava.
     */
    public static boolean survivesOn(boolean moltenGround, boolean netherLike) {
        return moltenGround || netherLike;
    }

    /** Whether the reaction that makes a steam devil is still there: both halves of it have to be. */
    public static boolean steamSurvives(boolean lavaNearby, boolean waterNearby) {
        return lavaNearby && waterNearby;
    }

    /**
     * Whether this kind of whirl moves blocks at all. The lava whirl does - it throws fire and can spill lava -
     * and the steam devil cannot, because steam is hot but has no mass. That asymmetry is the whole of the
     * steam devil's block-damage model, so it is written as its own answer rather than left to a caller's
     * judgement.
     */
    public static boolean movesBlocks(Kind kind) {
        return kind == Kind.LAVA_WHIRL;
    }

    /** Whether lava meeting water makes a steam devil. The rain cannot stop one, which is the point. */
    public static boolean formsSteamDevil(int lavaBlocks, int waterBlocks) {
        return lavaBlocks >= MIN_LAVA_BLOCKS && waterBlocks >= MIN_WATER_BLOCKS;
    }

    /** How strong the reaction is, 0 to 1: both halves have to be there. */
    public static double steamStrength(int lavaBlocks, int waterBlocks) {
        if (!formsSteamDevil(lavaBlocks, waterBlocks)) {
            return 0.0D;
        }
        double lava = clamp(lavaBlocks / (double) FULL_LAVA_BLOCKS, 0.0D, 1.0D);
        double water = clamp(waterBlocks / (double) FULL_WATER_BLOCKS, 0.0D, 1.0D);
        return Math.sqrt(lava * water);
    }

    public static double steamWindMph(double strength) {
        return lerp(MIN_STEAM_WIND_MPH, MAX_STEAM_WIND_MPH, clamp(strength, 0.0D, 1.0D));
    }

    public static double steamRadiusBlocks(double strength) {
        return lerp(MIN_STEAM_RADIUS_BLOCKS, MAX_STEAM_RADIUS_BLOCKS, clamp(strength, 0.0D, 1.0D));
    }

    public static double steamRiseBlocks(double strength) {
        return lerp(MIN_STEAM_RISE_BLOCKS, MAX_STEAM_RISE_BLOCKS, clamp(strength, 0.0D, 1.0D));
    }

    public static int steamLifeTicks(double strength, double roll) {
        double share = clamp(0.5D * clamp(strength, 0.0D, 1.0D) + 0.5D * clamp(roll, 0.0D, 1.0D), 0.0D, 1.0D);
        return (int) Math.round(lerp(MIN_STEAM_LIFE_TICKS, MAX_STEAM_LIFE_TICKS, share));
    }

    public static float steamScaldDamage(double windMph) {
        return (float) lerp(MIN_SCALD_DAMAGE, MAX_SCALD_DAMAGE,
                windShare(windMph, MIN_STEAM_WIND_MPH, MAX_STEAM_WIND_MPH));
    }

    /** How far up a wind ladder a wind sits, 0 at the floor and 1 at the ceiling. */
    private static double windShare(double windMph, double min, double max) {
        double span = Math.max(1.0E-6D, max - min);
        return clamp((windMph - min) / span, 0.0D, 1.0D);
    }

    private static double shareOf(double intensity) {
        double span = Math.max(1.0E-6D, 1.0D - MIN_INTENSITY);
        return clamp((intensity - MIN_INTENSITY) / span, 0.0D, 1.0D);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
