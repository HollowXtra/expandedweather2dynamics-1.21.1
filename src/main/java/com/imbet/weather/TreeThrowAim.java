package com.imbet.weather;

/**
 * Where a thrown tree is aimed, as pure arithmetic.
 *
 * <p>A tornado does not throw debris straight downwind: it slings it across its forward flank, so a
 * thrown tree reaches further to one side of the storm's track than the other and lands somewhere in a
 * fan rather than on a line. This class is that fan - the candidate landing points, in the order a
 * landing is preferred - with no world, no blocks and no storm, so the aim can be checked rather than
 * eyeballed (see {@code .freebuff/scratch/TreeLiftSim.java}).
 *
 * <p>{@link #candidate} is deliberately what the world code iterates: {@code TreeLift} does the column
 * lookups and the "is something built there" test, and everything about where the points are lives
 * here. Two properties matter and both are checked. Every candidate is within
 * {@link #FAN_HALF_ANGLE_DEGREES} of the storm's own heading, so the aim only ever picks between places
 * the throw already reached - it never invents a target the storm could not have hit. And candidate zero
 * is the straight-downwind point at the nominal distance, so the search's fallback is exactly the honest
 * answer rather than a compromise.
 */
public final class TreeThrowAim {
    /** Half-angle of the fan the throw is aimed across, in degrees, either side of the storm's motion. */
    public static final double FAN_HALF_ANGLE_DEGREES = 60.0D;
    /** Fan offsets, in degrees, straight downwind first so the first candidate is the honest one. */
    public static final double[] FAN_OFFSETS = {0.0D, 20.0D, -20.0D, 40.0D, -40.0D, 60.0D, -60.0D};
    /** Distance multipliers tried, in the order a landing is preferred. */
    public static final double[] FAN_SCALES = {1.0D, 0.72D, 1.28D};

    private TreeThrowAim() {
    }

    /** How many candidate landing points the search visits. */
    public static int candidateCount() {
        return FAN_OFFSETS.length * FAN_SCALES.length;
    }

    /**
     * One candidate landing point, as the world position it is centred on.
     *
     * @param index   candidate index, 0 to {@link #candidateCount()} - 1
     * @param baseX   where the tree stood
     * @param heading direction the storm is travelling, in radians
     * @param distance nominal throw distance in blocks
     * @return {x, z} of the candidate, or null when the index is out of range
     */
    public static double[] candidate(int index, double baseX, double baseZ, double heading, double distance) {
        if (index < 0 || index >= candidateCount()) {
            return null;
        }
        int offsetIndex = index / FAN_SCALES.length;
        int scaleIndex = index % FAN_SCALES.length;
        double angle = heading + Math.toRadians(FAN_OFFSETS[offsetIndex]);
        double reach = distance * FAN_SCALES[scaleIndex];
        return new double[]{baseX + Math.cos(angle) * reach, baseZ + Math.sin(angle) * reach};
    }

    /** How far from where the tree stood a candidate lands, in blocks. */
    public static double candidateDistance(double baseX, double baseZ, double x, double z) {
        double dx = x - baseX;
        double dz = z - baseZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The angle of a candidate off the storm's heading, in degrees - how far round the fan it sits. */
    public static double candidateOffsetDegrees(double baseX, double baseZ, double heading, double x, double z) {
        double bearing = Math.atan2(z - baseZ, x - baseX);
        return Math.toDegrees(wrapRadians(bearing - heading));
    }

    /** Radians wrapped to -pi..pi, so an offset reads as a signed gap rather than a lap. */
    public static double wrapRadians(double radians) {
        double wrapped = radians % (Math.PI * 2.0D);
        if (wrapped > Math.PI) {
            wrapped -= Math.PI * 2.0D;
        } else if (wrapped < -Math.PI) {
            wrapped += Math.PI * 2.0D;
        }
        return wrapped;
    }

    /* -------------------------------------------------------------------------------------------------
     * The felled trunk's axis
     * ------------------------------------------------------------------------------------------------*/

    /** A felled trunk lies along a world axis; which one the throw decides. */
    public enum Axis {
        /** Lying along the world's east-west line, blocks rotated to axis X. */
        X,
        /** Lying along the world's north-south line, blocks rotated to axis Z. */
        Z
    }

    /**
     * Which world axis a trunk felled along the given throw heading lies along.
     *
     * <p>A tree falls in the direction the throw carries it, but Minecraft logs only lie along the
     * world's axes, so the trunk is laid along whichever axis is closer to the heading. Both sides of
     * the event need the same answer - the server places the real trunk, the client settles the flying
     * body to meet it - and both read it from here, so a trunk drawn one way and placed another is not
     * possible.
     *
     * @param heading direction the throw travels, in radians
     */
    public static Axis felledAxis(double heading) {
        double facing = wrapRadians(heading);
        return Math.abs(Math.cos(facing)) >= Math.abs(Math.sin(facing)) ? Axis.X : Axis.Z;
    }
}
