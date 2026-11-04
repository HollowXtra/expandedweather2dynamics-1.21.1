package com.imbet.village;

/**
 * The arithmetic of a felled tree: what counts as one, how much of it is taken, and how the load is split
 * between logs and planks.
 *
 * <p>Three rules, each small enough to argue with. A felled trunk is a <em>run</em> of at least two logs
 * lying on their side - one log on the ground is a log somebody dropped, and a village that takes it apart
 * is a village stealing from a woodpile. What the village needs as logs is reserved before anything is
 * sawed, because a wall frame wants a log and a wall wants planks, and a mill that sawed the last log the
 * builder was waiting for would be busy and useless at once. And a log is four planks, which is what the
 * game's own crafting says, so the conversion is the game's own number rather than a second opinion about
 * it.
 *
 * <p>Minecraft-free on purpose: what a village takes out of a fallen tree and what it turns that into are
 * the two things a player can watch it do, so both are measured rather than trusted. The world reads - is
 * this block a log, is it lying down, is it on storm ground - belong to {@link com.imbet.village.LumberjackGoal},
 * which is the half that needs a world to ask.
 */
public final class TimberStand {
    /** A log saws into four planks; the same ratio the game's own planks recipes use. */
    public static final int PLANKS_PER_LOG = 4;

    /**
     * How many logs in a row make a felled trunk rather than a dropped log. Two, because the storm lays a
     * trunk down along its own throw direction and a trunk is longer than it is wide - so a run is what a
     * felled tree looks like from above, and a single lying log is something else.
     */
    public static final int MIN_RUN = 2;

    /**
     * One trip's worth of work: what is carried back as logs, what is sawed on the way, and what the sawing
     * yields. {@code logs + sawed} is what came out of the tree, which is why it is derived rather than
     * stored - two numbers that have to agree are two numbers that can disagree.
     */
    public record Load(int logs, int sawed, int planks) {
        public Load {
            if (logs < 0 || sawed < 0) {
                throw new IllegalArgumentException("a load is not a negative number of logs");
            }
            if (planks != sawed * PLANKS_PER_LOG) {
                throw new IllegalArgumentException("a log is " + PLANKS_PER_LOG + " planks");
            }
        }

        /** How many logs the trip takes off the trunk. */
        public int harvested() {
            return logs + sawed;
        }

        public boolean isEmpty() {
            return harvested() <= 0;
        }
    }

    private TimberStand() {
    }

    /** Whether this many lying logs in a row are a felled trunk. */
    public static boolean isFelledRun(int length) {
        return length >= MIN_RUN;
    }

    /** How many logs a trip takes off a trunk: what is there, up to what it can carry. */
    public static int harvest(int available, int carryLimit) {
        if (available <= 0 || carryLimit <= 0) {
            return 0;
        }
        return Math.min(available, carryLimit);
    }

    /**
     * How many of the harvested logs are sawed rather than kept.
     *
     * <p>The village's own need decides, in the order the need is felt: logs it wants as logs are reserved
     * first, and only what is left over is sawed - up to the logs that the plank shortfall is worth. A
     * village short of nothing saws nothing, because a log is the more useful of the two to a village that
     * is not short of planks, and it can always be sawed later.
     */
    public static int sawCount(int harvested, int plankBlocksWanted, int logBlocksWanted) {
        int carried = Math.max(0, harvested);
        if (carried == 0 || plankBlocksWanted <= 0) {
            return 0;
        }
        int reserve = Math.min(carried, Math.max(0, logBlocksWanted));
        int spare = carried - reserve;
        int worthSawing = ceilDiv(plankBlocksWanted, PLANKS_PER_LOG);
        return Math.min(spare, worthSawing);
    }

    /** What sawing this many logs yields. */
    public static int planksFrom(int logs) {
        return Math.max(0, logs) * PLANKS_PER_LOG;
    }

    /**
     * The whole trip, decided before the villager sets out: what it takes off the trunk, what it saws at the
     * mill, and what it puts in the chest. Deciding here rather than at the trunk is what makes the store's
     * own room check possible before anything is lifted.
     */
    public static Load load(int available, int carryLimit, int plankBlocksWanted, int logBlocksWanted) {
        int harvested = harvest(available, carryLimit);
        int sawed = sawCount(harvested, plankBlocksWanted, logBlocksWanted);
        return new Load(harvested - sawed, sawed, planksFrom(sawed));
    }

    /**
     * The planks a log of this kind saws into, as a name - or null when the name is not a log's.
     *
     * <p>Read from the name rather than from a table so every wood works, including one added by another
     * mod: {@code stripped_oak_log}, {@code oak_wood} and {@code oak_log} are the same oak, and
     * {@code crimson_stem} and {@code crimson_hyphae} are the same crimson. Null is a real answer: a block
     * whose name says nothing about a wood is not sawed into planks that do not exist.
     */
    public static String planksName(String logPath) {
        if (logPath == null || logPath.isEmpty()) {
            return null;
        }
        String path = logPath.startsWith("stripped_") ? logPath.substring("stripped_".length()) : logPath;
        for (String suffix : new String[] {"_log", "_wood", "_stem", "_hyphae"}) {
            if (path.endsWith(suffix) && path.length() > suffix.length()) {
                return path.substring(0, path.length() - suffix.length()) + "_planks";
            }
        }
        return null;
    }

    /** The wording a workstation uses when it says what one trip is worth. */
    public static String describe(Load load) {
        if (load.isEmpty()) {
            return "nothing to fell";
        }
        if (load.sawed() == 0) {
            return load.logs() + " logs";
        }
        if (load.logs() == 0) {
            return load.harvested() + " logs, sawed into " + load.planks() + " planks";
        }
        return load.logs() + " logs and " + load.planks() + " planks from " + load.sawed() + " sawed";
    }

    /** Division that rounds up, for a non-negative divisor. */
    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
