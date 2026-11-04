package com.imbet.weather;

/**
 * What a spout is allowed to take, in the tier below a tornado.
 *
 * <p>A waterspout is the least destructive vortex in the mod and was the one with no effect on the world at
 * all. The vortex event handler returns early for anything under 62 mph, and a fair-weather waterspout is
 * 40 to 60 mph by definition - so the whole family was decoration: it had a wind field, a mesocyclone, a
 * column of spray and a place in the forecast, and it could not so much as move a carrot. That is also why
 * a spout that spins up in a village square did nothing while a tornado two biomes away took roofs off.
 *
 * <p>The rule this class exists to state is that a vortex which cannot break a wall should still take what a
 * strong gust takes: the loose stuff. A spout here blows carpets off the floor, knocks the heads off
 * flowers and crops, tears grass out of the ground, strips leaves off the trees it passes through, and
 * carries off torches and snow layers. It never touches soil, stone, planks, logs or anything a player
 * built to stand up, and it never digs. That is deliberate and it is the whole point of the tier: a spout
 * is the one severe-weather event a player can stand next to and watch, so it is the one that must stay
 * survivable rather than becoming a slow tornado.
 *
 * <p>The floors are set so that the two things a fair-weather spout actually is - a waterspout of 40 to
 * 60 mph and a landspout of 60 to 100 mph - both clear them, while a dust devil's 20-odd mph does not.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the table can be checked outside the game. See
 * {@code .freebuff/scratch/Phase2Sim.java}.
 */
public final class SpoutDamage {

    private SpoutDamage() {
    }

    /** What a block is, as far as a spout is concerned. */
    public enum BlockClass {
        /** Solid, or held down, or something a player would miss: never touched. */
        NONE,
        /** Flowers, crops, grass, saplings, anything replaceable. */
        PLANT,
        /** Loose ground cover: carpet, snow layers, torches, small non-solid things. */
        LOOSE,
        /** Canopy. */
        LEAVES
    }

    /** What the spout does to it. */
    public enum Kind {
        NONE,
        /** Taken off the ground and thrown downwind; the block is gone and the world is one block lighter. */
        BLOW_AWAY
    }

    /** How hard it is blowing, for anything that wants to name the tier. */
    public enum Tier {
        CALM,
        NUDGE,
        STRIP,
        RAVAGE
    }

    /** Loose ground cover goes at the bottom of a fair-weather spout's range. */
    public static final double LOOSE_AT_MPH = 30.0D;
    /** Plants are held a little better than a carpet but not by much. */
    public static final double PLANT_AT_MPH = 28.0D;
    /** Leaves are held by a branch until the wind is properly strong. */
    public static final double LEAVES_AT_MPH = 34.0D;

    /** Where this mod's destructive tier begins: ground is scoured, and the spout stops being gentle. */
    public static final double STRIP_AT_MPH = 62.0D;
    /** Where soil itself starts to move, which is a tornado's business rather than a spout's. */
    public static final double RAVAGE_AT_MPH = 75.0D;

    /** The wind a class of block starts moving at. */
    public static double floorMph(BlockClass blockClass) {
        return switch (blockClass) {
            case NONE -> Double.MAX_VALUE;
            case PLANT -> PLANT_AT_MPH;
            case LOOSE -> LOOSE_AT_MPH;
            case LEAVES -> LEAVES_AT_MPH;
        };
    }

    /** What this wind does to this kind of block. */
    public static Kind verdict(BlockClass blockClass, double windMph) {
        if (blockClass == BlockClass.NONE) {
            return Kind.NONE;
        }
        return windMph >= floorMph(blockClass) ? Kind.BLOW_AWAY : Kind.NONE;
    }

    public static Tier tierOf(double windMph) {
        if (windMph >= RAVAGE_AT_MPH) {
            return Tier.RAVAGE;
        }
        if (windMph >= STRIP_AT_MPH) {
            return Tier.STRIP;
        }
        if (windMph >= PLANT_AT_MPH) {
            return Tier.NUDGE;
        }
        return Tier.CALM;
    }

    /**
     * The share of the eligible blocks in reach a pass takes, by tier. A spout does not strip a hillside in
     * one tick: at the bottom of its range it is plucking, and by 62 mph it is taking most of what is loose.
     */
    public static double takeShare(double windMph) {
        Tier tier = tierOf(windMph);
        double above = Math.max(0.0D, windMph - PLANT_AT_MPH);
        double span = Math.max(1.0D, RAVAGE_AT_MPH - PLANT_AT_MPH);
        double ramp = Math.min(1.0D, above / span);
        return switch (tier) {
            case CALM -> 0.0D;
            case NUDGE -> 0.18D + 0.30D * ramp;
            case STRIP -> 0.52D + 0.26D * ramp;
            case RAVAGE -> 0.85D;
        };
    }
}
