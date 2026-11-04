package com.imbet.weather;

/**
 * What a wildfire leaves behind it.
 *
 * <p>The mod has burned forests since it grew wildfire cores: a wildfire ignites, spreads small fires in a
 * disc around itself, drags a fire whirl out of its hottest part and puts a pyrocumulus column over the top
 * of it. What it did not do was leave a mark. Vanilla fire takes a leaf block and leaves nothing, and it
 * takes a trunk only after minutes of burning, so a forest that a wildfire passed through a game-day ago
 * looks exactly like a forest it has not reached yet. That is the gap: a wildfire that leaves no scar is a
 * light show, and it also means the mod's own builder and lumberjack villagers have nothing to do.
 *
 * <p>What a real burn leaves, in the order the ground shows it: the canopy is gone, completely - a crown
 * fire strips a tree in seconds and the leaves are the part that burns. Some trunks are left standing as
 * black spars, and the ones at the edge of the burn or in a wet hollow are merely scorched. The litter and
 * the top of the soil are consumed, which is what turns forest floor into bare earth for a season. So the
 * scar is not a new landform; it is the same forest with the parts that burn missing.
 *
 * <p>Heat is the one dial. It comes from how close the cell is to the wildfire's own core and how hard the
 * fire is burning - wind, a whirl, dry air - and it decides both the odds a trunk chars rather than
 * standing and the odds the ground beneath it scorches. A trunk that does not char keeps its block: fire in
 * this mod is allowed to have missed.
 *
 * <p>This class is arithmetic only - no Minecraft types - so the fates can be checked outside the game. See
 * {@code .freebuff/scratch/Phase2Sim.java}.
 */
public final class BurntPass {

    private BurntPass() {
    }

    /** What a pass does with one block. */
    public enum Fate {
        /** Left exactly as it was. */
        KEEP,
        /** Burned away: the block is gone. */
        BURN_AWAY,
        /** A trunk that burned where it stood: replaced with charred wood. */
        CHAR,
        /** The soil under the fire: scorched bare. */
        SCORCH
    }

    /** How much of the fire's own radius is burned hard enough to leave a scar. */
    public static final double SCAR_RADIUS_FRACTION = 0.85D;

    /** How far above the surface a column is read for canopy: a tall spruce, and a little slack. */
    public static final int COLUMN_HEIGHT_BLOCKS = 28;

    /** Cells read per pass, per block of the fire's radius. */
    public static final double CELLS_PER_RADIUS_BLOCK = 0.55D;
    public static final int MIN_CELLS_PER_PASS = 3;
    public static final int MAX_CELLS_PER_PASS = 26;

    /** The odds a trunk chars at no heat and at all the heat there is. */
    public static final double CHAR_AT_NO_HEAT = 0.22D;
    public static final double CHAR_AT_FULL_HEAT = 0.85D;

    /** The odds the ground under it scorches. */
    public static final double SCORCH_AT_NO_HEAT = 0.10D;
    public static final double SCORCH_AT_FULL_HEAT = 0.70D;

    /** Wind and a whirl each add this much heat on top of the cell's own position in the burn. */
    public static final double WIND_HEAT_AT_MPH = 55.0D;
    public static final double WHIRL_HEAT_BONUS = 0.25D;

    /**
     * How hot a cell burned, 0 to 1: its position in the disc, raised by the fire's own wind, plus a bonus
     * where a fire whirl is working. A cell at the fire's core in a strong wind is as hot as this gets.
     */
    public static double heat(double distanceFromCoreBlocks, double eventRadiusBlocks, double windMph, boolean whirl) {
        double radius = Math.max(1.0D, eventRadiusBlocks);
        double fromCore = clamp(1.0D - distanceFromCoreBlocks / radius, 0.0D, 1.0D);
        double wind = clamp(windMph / WIND_HEAT_AT_MPH, 0.0D, 1.0D);
        double heat = 0.55D * fromCore + 0.30D * wind + (whirl ? WHIRL_HEAT_BONUS : 0.0D);
        return clamp(heat, 0.0D, 1.0D);
    }

    public static double charChance(double heat) {
        return lerp(CHAR_AT_NO_HEAT, CHAR_AT_FULL_HEAT, clamp(heat, 0.0D, 1.0D));
    }

    public static double scorchChance(double heat) {
        return lerp(SCORCH_AT_NO_HEAT, SCORCH_AT_FULL_HEAT, clamp(heat, 0.0D, 1.0D));
    }

    /**
     * The verdict for one block. {@code roll} is one uniform draw per block, so the same cell gives the same
     * answer for a trunk and for the ground under it only if the two calls are given the same roll - which
     * is why the pass draws separately.
     */
    public static Fate fate(Foliage foliage, double heat, double roll) {
        double clampedHeat = clamp(heat, 0.0D, 1.0D);
        double draw = clamp(roll, 0.0D, 1.0D);
        return switch (foliage) {
            // A crown fire does not leave leaves. This is the one fate with no odds attached: it is what a
            // burn is, and a burned forest with its canopy intact would look like nothing happened.
            case LEAVES -> Fate.BURN_AWAY;
            case UNDERGROWTH -> Fate.BURN_AWAY;
            case TRUNK -> draw < charChance(clampedHeat) ? Fate.CHAR : Fate.KEEP;
            case SOIL -> draw < scorchChance(clampedHeat) ? Fate.SCORCH : Fate.KEEP;
            case OTHER -> Fate.KEEP;
        };
    }

    /** What a block is, as far as the burn is concerned. */
    public enum Foliage {
        TRUNK,
        LEAVES,
        UNDERGROWTH,
        SOIL,
        OTHER
    }

    /** How many cells one pass reads. */
    public static int cellsPerPass(double eventRadiusBlocks) {
        int cells = (int) Math.round(Math.max(0.0D, eventRadiusBlocks) * CELLS_PER_RADIUS_BLOCK);
        return clamp(cells, MIN_CELLS_PER_PASS, MAX_CELLS_PER_PASS);
    }

    /** The radius the scar is written into. */
    public static double scarRadiusBlocks(double eventRadiusBlocks) {
        return Math.max(1.0D, eventRadiusBlocks) * SCAR_RADIUS_FRACTION;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
