package com.imbet.weather;

/**
 * Where an animal goes when a funnel is coming.
 *
 * <p>Cattle, sheep and pigs do one thing before weather, and it is the opposite of what a player does: they
 * bunch. A herd in a field compresses into the tightest group it can hold, and it drifts as a body, which is
 * why a storm that is chasing fifty sheep moves fifty sheep at once. That is the behaviour this models -
 * cohesion first, and the funnel as a thing to get away from second - and it is deliberately not the same
 * shape as the villager drill, because a villager runs *to* something and an animal runs *from* something.
 *
 * <p>Three rules, and each earns its place:
 *
 * <ul>
 *   <li><b>Bunch.</b> An animal drifts toward the centre of the animals around it, and stops moving once it
 *       is inside the herd spacing. This is what makes a scattered field collect into a cluster rather than
 *       a line of animals all running the same way.</li>
 *   <li><b>Leave, away from where the funnel is going.</b> Not away from where it is: a herd that runs
 *       directly away from a moving funnel is run down by it, and this mod's funnels are fast. The target
 *       is the funnel's own projected position, so the herd leaves across the track rather than down it.</li>
 *   <li><b>Never aim inside the core.</b> An animal standing in the funnel cannot avoid it by walking, so
 *       the aim point is pushed out of the core even if that means aiming into the funnel's own path - which
 *       is the honest answer: at that range, sideways is the only direction that is not the same as staying
 *       put.</li>
 * </ul>
 *
 * <p>This class is arithmetic only - no Minecraft types - so the behaviour can be checked outside the game.
 * See {@code .freebuff/scratch/Phase2Sim.java}.
 */
public final class HerdFlock {

    private HerdFlock() {
    }

    /** Where the animal should walk to, and how fast. {@code speed} of zero means hold position. */
    public record Target(double x, double z, double speed, boolean fleeing) {
    }

    /** Animals closer than this to the herd centre stop drifting and simply stand. */
    public static final double HERD_SPACING_BLOCKS = 6.0D;

    /** How much of the aim is the herd rather than the funnel. Cohesion never outweighs leaving. */
    public static final double COHESION_WEIGHT = 0.35D;

    /** How far ahead of the funnel's own motion the herd aims, in ticks. */
    public static final int LEAD_TICKS = 80;

    /** The multiple of the core radius an animal will not aim inside. */
    public static final double CORE_CLEARANCE = 1.25D;

    /** Walk and run speeds handed to the animal's navigation. */
    public static final double HOLD_SPEED = 0.0D;
    public static final double DRIFT_SPEED = 0.55D;
    public static final double FLEE_SPEED = 1.25D;

    /** The alarm at which an animal is running flat out rather than walking away. */
    public static final double FULL_ALARM = 0.75D;

    /**
     * The aim point for one animal.
     *
     * @param x                the animal's own position
     * @param z                the animal's own position
     * @param herdX            the centre of the animals around it, or the animal's own position when alone
     * @param z                as above
     * @param herdSize         how many animals were found, itself included
     * @param threatX          the funnel's centre
     * @param threatZ          as above
     * @param coreRadius       the funnel's core radius in blocks
     * @param driftX           the funnel's motion per tick
     * @param driftZ           as above
     * @param alarm            0 when there is no threat, 1 when it is on top of them
     */
    public static Target target(double x, double z, double herdX, double herdZ, int herdSize,
                                double threatX, double threatZ, double coreRadius,
                                double driftX, double driftZ, double alarm) {
        double worked = clamp(alarm, 0.0D, 1.0D);
        double toHerdX = herdX - x;
        double toHerdZ = herdZ - z;
        double herdDistance = Math.sqrt(toHerdX * toHerdX + toHerdZ * toHerdZ);

        if (worked <= 0.0D) {
            if (herdSize < 2 || herdDistance <= HERD_SPACING_BLOCKS) {
                return new Target(x, z, HOLD_SPEED, false);
            }
            return new Target(herdX, herdZ, DRIFT_SPEED, false);
        }

        double projectedX = threatX + driftX * LEAD_TICKS;
        double projectedZ = threatZ + driftZ * LEAD_TICKS;
        double awayX = x - projectedX;
        double awayZ = z - projectedZ;
        double awayDistance = Math.sqrt(awayX * awayX + awayZ * awayZ);
        double stepX;
        double stepZ;
        if (awayDistance < 1.0E-4D) {
            // Dead centre: there is no "away", so pick the funnel's own drift and step against it.
            double length = Math.max(1.0E-4D, Math.sqrt(driftX * driftX + driftZ * driftZ));
            stepX = -driftX / length;
            stepZ = -driftZ / length;
        } else {
            stepX = awayX / awayDistance;
            stepZ = awayZ / awayDistance;
        }
        // Inside the core, sideways is the only direction that is not "stand still in it": an animal that
        // keeps backing away from the centre of the vortex stays in it until the vortex leaves.
        double coreDistance = Math.sqrt((x - threatX) * (x - threatX) + (z - threatZ) * (z - threatZ));
        if (coreDistance < coreRadius) {
            double radialX = (x - threatX) / Math.max(1.0E-4D, coreDistance);
            double radialZ = (z - threatZ) / Math.max(1.0E-4D, coreDistance);
            stepX = -radialZ;
            stepZ = radialX;
        }

        double reach = Math.max(4.0D, coreRadius) * 1.6D;
        double aimX = x + stepX * reach;
        double aimZ = z + stepZ * reach;

        if (herdSize >= 2 && herdDistance > HERD_SPACING_BLOCKS) {
            aimX += toHerdX * COHESION_WEIGHT;
            aimZ += toHerdZ * COHESION_WEIGHT;
        }

        double clearance = Math.max(1.0D, coreRadius) * CORE_CLEARANCE;
        double fromCoreX = aimX - threatX;
        double fromCoreZ = aimZ - threatZ;
        double fromCore = Math.sqrt(fromCoreX * fromCoreX + fromCoreZ * fromCoreZ);
        if (fromCore < clearance) {
            if (fromCore < 1.0E-4D) {
                aimX = threatX + clearance;
                aimZ = threatZ;
            } else {
                aimX = threatX + fromCoreX / fromCore * clearance;
                aimZ = threatZ + fromCoreZ / fromCore * clearance;
            }
        }

        double speed = DRIFT_SPEED + (FLEE_SPEED - DRIFT_SPEED) * clamp(worked / FULL_ALARM, 0.0D, 1.0D);
        return new Target(aimX, aimZ, speed, true);
    }

    /** The alarm a funnel produces at this distance. */
    public static double alarm(double threatDistanceBlocks, double threatRadiusBlocks) {
        double reach = Math.max(16.0D, threatRadiusBlocks);
        if (threatDistanceBlocks >= reach) {
            return 0.0D;
        }
        return clamp(1.0D - threatDistanceBlocks / reach, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
