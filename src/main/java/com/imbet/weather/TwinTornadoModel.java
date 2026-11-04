package com.imbet.weather;

/**
 * The arithmetic of twins: where the second tornado of a pair stands, which way it tracks, and how
 * strong it is allowed to be.
 *
 * <p>A twin is not a second funnel drawn on one storm. It is a second <em>storm</em>, parked abeam of
 * the first, held on a tornado rung, and left to run its own track - so Weather2 draws both funnels
 * out of its own storm state, and everything downstream of a tornado in this addon (the wind field,
 * the damage swath, the funnel's own build-in, the radar signature, the siren, the damage survey,
 * the spotter net) treats both of them as what they are. Nothing in this class knows about storms:
 * it is the geometry and the dice, and {@link TwinTornadoService} is what turns them into Weather2
 * objects.
 *
 * <p>The stage numbers are Weather2's own, repeated here as plain ints because this file has to stay
 * loadable without the Weather2 jar - {@code STATE_FORMING} is 4, the first rung at which a land storm
 * is a tornado at all, and {@code STATE_STAGE5} is 9, the top of the ladder. The service passes
 * Weather2's constants in wherever it can, so a future Weather2 that renumbers its rungs breaks the
 * constants it hands us rather than the arithmetic here.
 *
 * <p><b>The pair's geometry.</b> The twin is set down on the parent's beam - a quarter turn off the
 * parent's heading - and given a heading rotated a few degrees back toward the parent's own track.
 * That is what makes the two readable as twins rather than as two unrelated storms: they run
 * alongside each other the whole life of the pair, slowly closing, the way a pair of circulations
 * sharing one mesocyclone does. The closing is deliberately slow. A full 9 degrees of weave closes
 * the gap by about 16 blocks per hundred the pair travels, so a pair at the default spacing that
 * lives its whole eight minutes closes by a few tens of blocks and never reaches itself.
 */
public final class TwinTornadoModel {

    /** A ground-plane position. Only x and z: a twin's offsets are horizontal by definition. */
    public record Ground(double x, double z) {
    }

    /** Weather2's {@code STATE_FORMING}: the first rung where a land storm is a tornado. */
    public static final int TORNADO_STAGE = 4;
    /** Weather2's {@code STATE_STAGE5}: the top of the ladder, and the ceiling on a twin's rung. */
    public static final int STAGE_CEILING = 9;

    /**
     * The floor on the spacing. Below this the two funnels are inside each other's damage swath and
     * the pair reads as one wide tornado with a lump on it, which is the satellite look, not twins.
     */
    public static final double MIN_SPACING_BLOCKS = 120.0D;
    /**
     * The ceiling on the spacing, and the reason it is this and not something rounder: Weather2
     * anchors a storm's own cloud deck to the nearest player and stops spawning puffs for it past 512
     * blocks, so a second funnel further out than this is a storm whose sky is not even drawn from
     * where the first one stands. That is a second storm rather than half of a pair, whatever the
     * funnels look like from a hillside.
     */
    public static final double MAX_SPACING_BLOCKS = 512.0D;

    /**
     * How often the second tornado of a pair comes in a rung weaker than the first. Real twins are
     * rarely equals, and a pair that is one rung apart still reads as two tornadoes while making it
     * obvious which one the chase is about.
     */
    public static final double PEER_WEAKER_SHARE = 0.25D;

    /** The minimum weave: enough that the pair's tracks are visibly not the same line. */
    public static final double WEAVE_MIN_DEGREES = 4.0D;
    /** The span above the minimum. 4 to 13 degrees covers parallel-with-drift through to closing. */
    public static final double WEAVE_SPAN_DEGREES = 9.0D;

    /** A twin is at least this fraction of the parent's size, so it is a peer whatever the dice. */
    public static final double PEER_SIZE_FLOOR = 0.80D;
    /** The span above the floor: 0.80 to 1.00 of the parent's size. */
    public static final double PEER_SIZE_SPAN = 0.20D;

    /** Genesis progress at which the parent counts as a formed tornado rather than one still building. */
    public static final float FORMED_PROGRESS = 1.0F;

    /**
     * How far behind the beam point the second tornado of a pair is set down. A twin forms under the
     * same mesocyclone as the first and trails it a little; dropping it exactly abeam would read as a
     * ruler line rather than as a pair, and one further back than this reads as two separate storms.
     */
    public static final double TWIN_TRAIL_BLOCKS = 70.0D;

    private TwinTornadoModel() {
    }

    /** The configured spacing, held inside the range a pair can actually be seen in. */
    public static double spacingBlocks(double configured) {
        if (!Double.isFinite(configured)) {
            return MIN_SPACING_BLOCKS;
        }
        return Math.max(MIN_SPACING_BLOCKS, Math.min(MAX_SPACING_BLOCKS, configured));
    }

    /**
     * Which side of the parent's track a twin belongs on, as -1 or +1, rolled once per parent from
     * its own id. A parent therefore always puts its twin on the same beam: a second attempt after a
     * failed seed does not flip the pair.
     */
    public static double sideOf(double roll) {
        return roll < 0.5D ? -1.0D : 1.0D;
    }

    /**
     * A deterministic 0 (inclusive) to 1 (exclusive) from an id, so a property of the pair - which
     * side, which rung, how wide - is the same on the server and on every client, and the same after
     * a reload, without storing anything.
     */
    public static double stableRoll(long id) {
        long mixed = id * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (mixed >>> 11) / (double) (1L << 53);
    }

    /**
     * The point abeam of the parent: a quarter turn off its heading, {@code side} to the right or the
     * left, {@code spacing} blocks out. A zero heading falls back to +x - the offset still has to be
     * somewhere, and a storm that is not moving yet has no beam of its own.
     */
    public static Ground abeam(double parentX, double parentZ, double headingX, double headingZ,
                               double side, double spacing) {
        double length = Math.hypot(headingX, headingZ);
        double fx = length < 1.0E-6D ? 1.0D : headingX / length;
        double fz = length < 1.0E-6D ? 0.0D : headingZ / length;
        double px = -fz;
        double pz = fx;
        double sign = side < 0.0D ? -1.0D : 1.0D;
        return new Ground(parentX + px * sign * spacing, parentZ + pz * sign * spacing);
    }

    /** The unsigned weave angle for a roll: {@link #WEAVE_MIN_DEGREES} to just under 13 degrees. */
    public static double weaveDegrees(double roll) {
        return WEAVE_MIN_DEGREES + clamp01(roll) * WEAVE_SPAN_DEGREES;
    }

    /** A heading turned by {@code degrees} counter-clockwise about +y, kept a unit vector. */
    public static Ground turn(double headingX, double headingZ, double degrees) {
        double length = Math.hypot(headingX, headingZ);
        double fx = length < 1.0E-6D ? 1.0D : headingX / length;
        double fz = length < 1.0E-6D ? 0.0D : headingZ / length;
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Ground(fx * cos - fz * sin, fx * sin + fz * cos);
    }

    /**
     * The twin's own heading: the parent's course turned back toward the parent by its weave, so the
     * pair runs side by side and closes slowly. The turn is toward the parent and not away from it -
     * a twin that diverges stops being half of a pair within a few hundred blocks.
     *
     * @param side which beam the twin is on, from {@link #sideOf}
     */
    public static Ground twinHeading(double parentHeadingX, double parentHeadingZ, double side, double degrees) {
        double sign = side < 0.0D ? -1.0D : 1.0D;
        return turn(parentHeadingX, parentHeadingZ, -sign * Math.abs(degrees));
    }

    /**
     * Which beam a twin is on, read back from where it actually is rather than remembered: +1 when it
     * sits to the right of the parent's heading, -1 when it sits to the left, and 0 when it is
     * exactly on the line.
     */
    public static double sideFromPosition(double parentX, double parentZ, double parentHeadingX, double parentHeadingZ,
                                          double twinX, double twinZ) {
        double cross = parentHeadingX * (twinZ - parentZ) - parentHeadingZ * (twinX - parentX);
        if (cross > 1.0E-6D) {
            return 1.0D;
        }
        if (cross < -1.0E-6D) {
            return -1.0D;
        }
        return 0.0D;
    }

    /**
     * How many blocks the pair travels before their tracks meet, at this weave. The guard rail on the
     * spacing: a twin's weave can close the gap but cannot cross the parent's track within a pair's
     * lifetime.
     */
    public static double blocksUntilTracksMeet(double separationBlocks, double degrees) {
        double tangent = Math.tan(Math.toRadians(Math.abs(degrees)));
        if (tangent < 1.0E-6D) {
            return Double.POSITIVE_INFINITY;
        }
        return separationBlocks / tangent;
    }

    /** How far off its line a heading carries a track over this much travel. */
    public static double lateralDriftBlocks(double degrees, double distanceBlocks) {
        return distanceBlocks * Math.tan(Math.toRadians(Math.abs(degrees)));
    }

    /** A point pulled back along a heading, so a twin trails the tornado it formed beside. */
    public static Ground trailing(Ground point, double headingX, double headingZ, double blocks) {
        double length = Math.hypot(headingX, headingZ);
        double fx = length < 1.0E-6D ? 1.0D : headingX / length;
        double fz = length < 1.0E-6D ? 0.0D : headingZ / length;
        return new Ground(point.x() - fx * blocks, point.z() - fz * blocks);
    }

    /** Straight-line distance between two ground positions. */
    public static double separation(Ground a, Ground b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** Whether a parent is tornadic enough to have a twin: at or above the configured rung. */
    public static boolean parentEligible(int parentStage, int minParentStage) {
        return parentStage >= Math.max(minParentStage, TORNADO_STAGE);
    }

    /** Whether the parent has finished forming. A twin arrives beside a tornado, not beside a funnel. */
    public static boolean parentFormed(float genesisProgress) {
        return genesisProgress >= FORMED_PROGRESS;
    }

    /**
     * The rung a twin is held on: the parent's own, or one below it for the quarter of pairs that are
     * not equals. Never below the rung where a storm is a tornado, never above the top of the ladder.
     */
    public static int peerStage(int parentStage, int minParentStage, double roll) {
        int rung = roll < PEER_WEAKER_SHARE ? parentStage - 1 : parentStage;
        int floor = Math.max(minParentStage, TORNADO_STAGE);
        return Math.max(floor, Math.min(STAGE_CEILING, rung));
    }

    /** A twin's size as a fraction of its parent's: 0.80 to 1.00. */
    public static double peerSizeFraction(double roll) {
        return PEER_SIZE_FLOOR + clamp01(roll) * PEER_SIZE_SPAN;
    }

    /** A twin storm's size, never smaller than a storm that can carry a funnel at all. */
    public static int peerSize(int parentSize, double roll) {
        int floor = Math.max(160, parentSize);
        return Math.max(1, (int) Math.round(floor * peerSizeFraction(roll)));
    }

    /** Whether the dice gave a twin: one roll against the configured chance. */
    public static boolean rollSeeds(double roll, double chance) {
        return clamp01(roll) < clamp01(chance);
    }

    /** Whether this tick is an attempt tick. Stateless, so a reload cannot make a storm twin twice. */
    public static boolean attemptDue(long gameTime, int intervalTicks) {
        return intervalTicks > 0 && Math.floorMod(gameTime, (long) intervalTicks) == 0L;
    }

    /** Whether a twin has lived its configured life and should be left to Weather2 to wind down. */
    public static boolean lifetimeSpent(int ageTicks, int lifetimeTicks) {
        return lifetimeTicks > 0 && ageTicks >= lifetimeTicks;
    }

    /**
     * The speed to give a twin: the parent's own, held to the range a Weather2 storm travels at. A
     * pair with different speeds stops being a pair, so the twin copies the parent rather than
     * rolling for itself.
     */
    public static double peerSpeed(double parentSpeed, double fallbackSpeed) {
        double speed = parentSpeed > 1.0E-5D ? parentSpeed : fallbackSpeed;
        if (!Double.isFinite(speed)) {
            return 0.03D;
        }
        return Math.max(0.012D, Math.min(0.12D, speed));
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
