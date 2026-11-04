package com.imbet.client;

/**
 * How a whole lifted building moves, as pure arithmetic: where its centre is, how it is turned, and
 * when each of its blocks comes off it.
 *
 * <p>A building a tornado takes off its foundation is not a bag of loose blocks. It leaves the ground
 * as one body, is hauled into the circulation and carried around the funnel while it tumbles about all
 * three axes, and only then comes apart - and it comes apart from the outside in, the roof edges and
 * corners failing first and the core last, because that is the material furthest from the centre and
 * the least braced. At no point is the body allowed to be soft: {@link #rotate} is a rigid rotation, so
 * every block keeps its distance from every other block, and a structure that looks like a house in the
 * air still looks like one.</p>
 *
 * <p>Nothing here knows about storms, worlds or particles, so the path can be checked rather than
 * eyeballed - see {@code .freebuff/scratch/StructureFlightSim.java}.
 */
public final class StructureFlightMotion {

    /** Where the body is, and how it is turned, at one instant. Angles are in degrees. */
    public record Pose(double x, double y, double z, double yawDegrees, double pitchDegrees, double rollDegrees) {
    }

    /** Fraction of the flight spent being hauled into the circulation before it settles into orbit. */
    private static final double HAUL_SPAN = 0.34D;
    /** Degrees per tick the body orbits the funnel, before the tumble rate scales it. */
    private static final double ORBIT_DEGREES_PER_TICK = 1.15D;
    /** Degrees per tick the body yaws and rolls about its own axes, before the tumble rate scales it. */
    private static final double YAW_DEGREES_PER_TICK = 2.2D;
    private static final double ROLL_DEGREES_PER_TICK = 3.1D;
    /** Amplitude and rate of the nod about the third axis, so all three are turning. */
    private static final double PITCH_AMPLITUDE_DEGREES = 52.0D;
    private static final double PITCH_RADIANS_PER_TICK = 0.062D;
    /** Height a lifted building is carried to, as a multiple of the funnel's radius plus a floor. */
    private static final double LIFT_RADIUS_FACTOR = 2.35D;
    private static final double LIFT_FLOOR_BLOCKS = 24.0D;
    private static final double LIFT_CEILING_BLOCKS = 96.0D;
    /** Fraction of the flight it holds together before anything comes off it. */
    private static final double SHED_START = 0.42D;

    private StructureFlightMotion() {
    }

    /**
     * The body's pose at one instant of its flight.
     *
     * @param pluckX       where the building stood when it was taken, in blocks
     * @param pluckY       the ground it stood on
     * @param funnelX      the funnel's axis, which the body is carried around
     * @param funnelRadius the funnel's visible radius: how close the body is held, and how high it goes
     * @param rotationSign +1 or -1, the parent circulation's direction
     * @param ticks        ticks since the lift
     * @param duration     how long the whole flight lasts
     * @param tumbleRate   the config's tumble scale: 0 leaves it turning only slowly
     */
    public static Pose pose(double pluckX, double pluckY, double pluckZ,
                            double funnelX, double funnelZ, double funnelRadius,
                            double rotationSign, int ticks, int durationTicks, double tumbleRate) {
        double progress = progress(ticks, durationTicks);
        double rate = Math.max(0.0D, tumbleRate);
        double radius = Math.max(2.0D, funnelRadius);

        double dx = pluckX - funnelX;
        double dz = pluckZ - funnelZ;
        double pluckDistance = Math.sqrt(dx * dx + dz * dz);
        double pluckBearing = Math.atan2(dz, dx);
        // It is held just outside the condensation: a building is not drawn into the funnel, it is
        // carried around it, and being dragged across the radius is what tears it apart.
        double heldDistance = radius * 1.15D + 3.0D;
        double haul = smoothstep(clamp(progress / HAUL_SPAN, 0.0D, 1.0D));
        double distance = pluckDistance + (heldDistance - pluckDistance) * haul;
        double bearing = pluckBearing
                + Math.toRadians(ORBIT_DEGREES_PER_TICK * rate * rotationSign * ticks);

        double liftMax = Math.min(LIFT_CEILING_BLOCKS, radius * LIFT_RADIUS_FACTOR + LIFT_FLOOR_BLOCKS);
        // Hauled up fast at first and then climbing more slowly, the way anything does once it is in
        // the updraft - not rising on rails.
        double lifted = liftMax * (1.0D - Math.pow(1.0D - progress, 2.2D));

        double yaw = YAW_DEGREES_PER_TICK * rate * ticks * rotationSign;
        double roll = ROLL_DEGREES_PER_TICK * rate * ticks * rotationSign;
        double pitch = PITCH_AMPLITUDE_DEGREES * Math.sin(ticks * PITCH_RADIANS_PER_TICK * Math.max(0.25D, rate));
        return new Pose(
                funnelX + Math.cos(bearing) * distance,
                pluckY + lifted,
                funnelZ + Math.sin(bearing) * distance,
                yaw,
                pitch,
                roll);
    }

    /* -------------------------------------------------------------------------------------------------
     * Thrown bodies: a tree pulled out of the ground
     * ------------------------------------------------------------------------------------------------*/

    /** Apex of a thrown body's arc, as a fraction of how far it is thrown. */
    private static final double THROW_APEX_FRACTION = 0.42D;
    private static final double THROW_APEX_FLOOR_BLOCKS = 9.0D;
    private static final double THROW_APEX_CEILING_BLOCKS = 44.0D;
    /** How far the path bows sideways off the straight line to the landing point, as a fraction of the throw. */
    private static final double THROW_BULGE_FRACTION = 0.26D;
    private static final double THROW_BULGE_CEILING_BLOCKS = 16.0D;
    /** Degrees per tick a thrown body turns about its own axes, before the tumble rate scales it. */
    private static final double THROWN_YAW_DEGREES_PER_TICK = 3.6D;
    private static final double THROWN_ROLL_DEGREES_PER_TICK = 4.4D;
    private static final double THROWN_PITCH_AMPLITUDE_DEGREES = 74.0D;
    private static final double THROWN_PITCH_RADIANS_PER_TICK = 0.085D;

    /**
     * The pose of a THROWN body - a tree torn out of the ground - at one instant of its flight.
     *
     * <p>Unlike a building, this flight is a single ballistic throw: the body is slung up and out and
     * comes down on a landing point that was chosen before it was lifted. That is the whole reason the
     * landing point is passed in rather than derived: the server has to know where the impact happens
     * before the client draws it, and a body that is drawn in one place and hits in another reads as a
     * bug. So both ends are exact - at progress 0 the body is at the point it stood, at progress 1 it is
     * on the landing point - and everything in between is arithmetic over the payload.
     *
     * <p>Three details make it read as slung rather than lerped. The path bows sideways, perpendicular to
     * the throw, on the side the circulation turns, so it swings out of the vortex instead of sliding
     * along a straight line. The vertical profile is one sine arc over the line joining the two ends, so
     * the body leaves the ground quickly, hangs at the top and accelerates into the landing rather than
     * rising on rails. And it turns on all three axes throughout, which is what a tree in a violent
     * updraft actually does.
     *
     * @param pluckX  where the tree stood, in blocks
     * @param pluckY  the ground it stood on
     * @param landX   where the server has aimed the impact
     * @param landY   what it lands on there
     * @param spinSign +1 or -1, the parent circulation's direction: which way the path bows and turns
     */
    public static Pose thrownPose(double pluckX, double pluckY, double pluckZ,
                                  double landX, double landY, double landZ,
                                  double spinSign, int ticks, int durationTicks, double tumbleRate) {
        double progress = progress(ticks, durationTicks);
        double rate = Math.max(0.0D, tumbleRate);
        double sign = spinSign >= 0.0D ? 1.0D : -1.0D;

        double dx = landX - pluckX;
        double dz = landZ - pluckZ;
        double throwDistance = Math.sqrt(dx * dx + dz * dz);
        double bow = Math.sin(Math.PI * progress);
        double bulge = Math.min(THROW_BULGE_CEILING_BLOCKS, throwDistance * THROW_BULGE_FRACTION) * sign;
        double sideX = throwDistance < 0.001D ? 1.0D : -dz / throwDistance;
        double sideZ = throwDistance < 0.001D ? 0.0D : dx / throwDistance;
        double x = pluckX + dx * progress + sideX * bulge * bow;
        double z = pluckZ + dz * progress + sideZ * bulge * bow;

        // One arc over the line joining the two ends: exactly the stood-on ground at 0, exactly the
        // landing point at 1, and the apex measured above whichever end is higher.
        double apexRise = Math.min(THROW_APEX_CEILING_BLOCKS,
                Math.max(THROW_APEX_FLOOR_BLOCKS, throwDistance * THROW_APEX_FRACTION));
        double overlap = Math.abs(pluckY - landY) * 0.5D;
        double arcHeight = apexRise + overlap;
        double y = pluckY + (landY - pluckY) * progress + arcHeight * bow;

        double yaw = THROWN_YAW_DEGREES_PER_TICK * rate * ticks * sign;
        double roll = THROWN_ROLL_DEGREES_PER_TICK * rate * ticks * sign;
        double pitch = THROWN_PITCH_AMPLITUDE_DEGREES
                * Math.sin(ticks * THROWN_PITCH_RADIANS_PER_TICK * Math.max(0.25D, rate));
        return new Pose(x, y, z, yaw, pitch, roll);
    }

    /* -------------------------------------------------------------------------------------------------
     * The felled settle: from the landing pose to a trunk at rest
     * ------------------------------------------------------------------------------------------------*/

    /**
     * How far through the settle a felled body is, 0 to 1.
     *
     * <p>The settle starts at the landing tick, not at the takeoff, and runs for
     * {@code settleTicks}. Before the landing it is exactly 0 (the flight pose stands on its own);
     * after it ends it is exactly 1 (the body is at rest). The ease is a smoothstep, so the body
     * decelerates into rest rather than stopping dead.
     */
    public static double settleProgress(int ticks, int durationTicks, int settleTicks) {
        int into = ticks - durationTicks;
        if (into <= 0 || settleTicks <= 0) {
            return 0.0D;
        }
        return smoothstep(clamp(into / (double) settleTicks, 0.0D, 1.0D));
    }

    /**
     * Where the body's centre ends up once the settle is done, relative to the landing pose.
     *
     * <p>A fallen tree does not stop where its centre was when it touched: it drops the last little
     * way to the ground and, because it is lying along the throw, its centre sits lower than the
     * upright tumbling pose had it. The offset is downward only, and the caller supplies the drop -
     * measured against the real ground at the landing - because how far a trunk settles is a question
     * about the world, not arithmetic.
     *
     * @param settleProgress 0 at the landing tick, 1 at rest
     * @param dropBlocks     how far the centre descends from the landing pose to its resting place
     * @return the offset to add to the landing pose's centre
     */
    public static double[] settledOffset(double settleProgress, double dropBlocks) {
        double eased = clamp(settleProgress, 0.0D, 1.0D);
        return new double[]{0.0D, -Math.max(0.0D, dropBlocks) * eased, 0.0D};
    }

    /**
     * The body's resting orientation, in degrees, once it has settled as a felled trunk.
     *
     * <p>A trunk lying along a world axis is the throw direction rounded to that axis, rolled flat:
     * the pitch is the tilt that turns the standing trunk over onto its side (a quarter turn past
     * horizontal, raised by the rolling as it came down), and the yaw is squared to the axis so the
     * drawn trunk points the same way as the placed one. The roll eases to flat - a tree at rest does
     * not keep turning. All three are reached by the caller blending from the landing pose with
     * {@link #settleProgress}, so the motion is continuous by construction.
     *
     * @param throwHeading direction the throw travelled, in degrees, for squaring the yaw
     */
    public static double settledYaw(double throwHeadingDegrees) {
        double facing = ((throwHeadingDegrees % 360.0D) + 360.0D) % 360.0D;
        // Square to the nearest world axis: 0 or 180 for east-west, 90 or 270 for north-south.
        return Math.round(facing / 90.0D) * 90.0D;
    }

    /**
     * The resting pitch of a felled trunk: lying on its side, which for the drawn pose is just past a
     * quarter turn from vertical.
     */
    public static double settledPitchDegrees() {
        return 98.0D;
    }

    /**
     * Blend of one angle between its landing value and its resting value by the settle progress.
     * Continuous at both ends: 0 returns the landing angle, 1 the resting one.
     */
    public static double settleBlend(double landingDegrees, double restingDegrees, double settleProgress) {
        double eased = clamp(settleProgress, 0.0D, 1.0D);
        return landingDegrees + (restingDegrees - landingDegrees) * eased;
    }

    /**
     * Rotates a block's offset from the body's centre by the body's tumble. A rigid rotation: no
     * length is changed, so the building keeps its shape while it turns.
     */
    public static double[] rotate(double x, double y, double z, double yawDegrees, double pitchDegrees, double rollDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double roll = Math.toRadians(rollDegrees);
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);

        double yawX = x * cosYaw + z * sinYaw;
        double yawZ = -x * sinYaw + z * cosYaw;
        double pitchY = y * cosPitch - yawZ * sinPitch;
        double pitchZ = y * sinPitch + yawZ * cosPitch;
        double rollX = yawX * cosRoll - pitchY * sinRoll;
        double rollY = yawX * sinRoll + pitchY * cosRoll;
        return new double[]{rollX, rollY, pitchZ};
    }

    /**
     * How far into its own shedding a block is, from its distance out from the body's centre: the
     * outermost blocks leave first, the core last. Returned as the point in the flight's second half at
     * which that block comes off, so a caller can hold each block until then.
     */
    public static double shedProgress(double radiusFraction, double jitter) {
        double outer = 1.0D - clamp(radiusFraction, 0.0D, 1.0D);
        double curved = Math.pow(outer, 0.85D);
        double ordered = SHED_START + (1.0D - SHED_START) * curved * 0.86D;
        // A little disorder, so the body does not come apart in neat shells.
        return clamp(ordered + (clamp(jitter, 0.0D, 1.0D) - 0.5D) * 0.16D, 0.0D, 1.0D);
    }

    /** True once this block's turn has come. */
    public static boolean hasShed(double shedProgress, double progress) {
        return progress >= shedProgress;
    }

    /** Progress through a flight, 0 to 1, clamped so a caller can step past the end without surprises. */
    public static double progress(int ticks, int durationTicks) {
        if (durationTicks <= 0) {
            return 1.0D;
        }
        return clamp(ticks / (double) durationTicks, 0.0D, 1.0D);
    }

    private static double smoothstep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
