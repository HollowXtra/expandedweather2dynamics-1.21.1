package com.imbet.weather;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The shape of a tornado's wind field on the ground.
 *
 * <p>The field itself is a Rankine vortex in {@link TornadoWindField}, and a Rankine vortex is a circle:
 * every isotach is a ring around the core, so the dust the field lifts, the swath it scours and the
 * profile a probe reads all came out rotationally symmetric - the tornado's footprint was drawn with a
 * compass. A real one is not round. Its inflow is not even, and the debris it holds is dragged along its
 * own track, so the wind reaches furthest on one side of the axis, the dust is thickest there, and the
 * outline is ragged rather than elliptical.
 *
 * <p>This is that shape written once, as arithmetic, in the same spirit as {@code TornadoGustModel}: a
 * pure deterministic function of the storm's id and the world clock, so the server's wind field and the
 * client's particles agree about where the field is long without anything being synced between them. Two
 * terms make it up. The first is a harmonic along the storm's own track - the field trails the vortex, so
 * its reach is longest astern and shortest ahead - and the second is a pair of slow angular lobes whose
 * phases are hashed per storm and drift at different rates, which is what stops the outline settling into
 * an oval. {@link #reachShare} is how far the isotachs are pushed out on a bearing and {@link #densityShare}
 * is how much of the field's dust and scour sits there, so the drawn cloud cannot disagree with the wind
 * that scoured it.
 *
 * <p>Nothing here caps anything: the shares multiply radii the rest of the mod has already worked out, and
 * they are held to a band that leaves the vortex recognisably a vortex. A caller that ignores this class
 * gets the old circular field back.
 */
public final class TornadoFootprint {

    /** How much longer the reach is astern of the vortex than ahead of it, before per-storm jitter. */
    private static final double ALONG_TRACK = 0.22D;

    /** How deep the per-bearing lobes bite, which is what makes the outline ragged rather than oval. */
    private static final double LOBE_DEPTH = 0.18D;

    /** The band every reach share is held to, so no bearing invents or deletes a wind field. */
    private static final double MIN_REACH = 0.55D;
    private static final double MAX_REACH = 1.60D;

    /** The band a density share is held to, and the heaviest one the bearing picker scales its draws by. */
    private static final double MIN_DENSITY = 0.15D;
    private static final double MAX_DENSITY = 2.20D;

    /** How fast the lobes drift, in radians per tick before per-storm jitter - a turn in some minutes. */
    private static final double LOBE_DRIFT_PER_TICK = 0.0026D;

    /** How many draws the bearing picker takes before it settles for a uniform one. */
    private static final int PICK_ATTEMPTS = 8;

    private TornadoFootprint() {
    }

    /**
     * How far this bearing's isotachs stand from the core, as a share of the circular radius the profile
     * was worked out with: over 1 is the long side of the field, under 1 the short one.
     *
     * <p>This is the term that makes the wind field itself not round. Every radius the profile is built
     * from - the radius of maximum wind and the radius it has faded to nothing at - is multiplied by it,
     * so a bearing that reads 1.2 here has its whole wind profile twenty per cent further out, and the
     * swath scoured under the same tornado is a lobed shape rather than a ribbon of constant width.
     */
    public static double reachShare(long stormId, double bearingRadians, double motionBearingRadians, long gameTime) {
        double along = alongTrackShare(stormId, bearingRadians, motionBearingRadians);
        double lobes = lobes(stormId, bearingRadians, gameTime);
        return clamp(1.0D + along + lobes, MIN_REACH, MAX_REACH);
    }

    /**
     * How much of the field's own dust and scour belongs on this bearing, as a share of an even spread:
     * over 1 is the side the dust piles up on, under 1 the clear side.
     *
     * <p>Deliberately the same two terms as {@link #reachShare}, weighted harder, so where the field
     * reaches furthest is also where it is thickest - which is what a debris cloud actually looks like,
     * and what stops a heavy dust cloud hanging in the thin air beside a long isotach.
     */
    public static double densityShare(long stormId, double bearingRadians, double motionBearingRadians, long gameTime) {
        double along = alongTrackShare(stormId, bearingRadians, motionBearingRadians);
        double lobes = lobes(stormId, bearingRadians, gameTime);
        return clamp(1.0D + 0.65D * along + 0.75D * lobes, MIN_DENSITY, MAX_DENSITY);
    }

    /**
     * A bearing drawn from that density: the spawners' replacement for a uniform {@code random * 2pi},
     * which is the single line that used to make every dust cloud a ring.
     *
     * <p>Rejection sampling rather than inverse arithmetic, because both shares are sums of harmonics and
     * inverting them would be a second copy of the shape written the hard way. Bounded at
     * {@link #PICK_ATTEMPTS} draws and falling back to a uniform bearing, so the thinnest side of the
     * field is still reached now and then and no caller can be starved of particles.
     */
    public static double pickBearing(long stormId, double motionBearingRadians, long gameTime, RandomSource random) {
        double bearing = uniform(random);
        for (int attempt = 0; attempt < PICK_ATTEMPTS; attempt++) {
            if (random.nextDouble() * MAX_DENSITY <= densityShare(stormId, bearing, motionBearingRadians, gameTime)) {
                return bearing;
            }
            bearing = uniform(random);
        }
        return bearing;
    }

    /**
     * The bearing a storm is travelling on, in radians from +X towards +Z, or 0 for one that is parked.
     *
     * <p>A parked storm has no astern, and the field of one is the old circle plus its lobes: the terms
     * above are harmonics of the bearing, so a zero track bearing is still a shape rather than a
     * degenerate one.
     */
    public static double motionBearing(Vec3 motion) {
        if (motion == null || motion.horizontalDistanceSqr() < 1.0E-5D) {
            return 0.0D;
        }
        return Math.atan2(motion.z, motion.x);
    }

    /** The first harmonic: longest astern of the vortex, shortest on the heading it is running. */
    private static double alongTrackShare(long stormId, double bearingRadians, double motionBearingRadians) {
        double relative = wrapToPi(bearingRadians - motionBearingRadians);
        double depth = ALONG_TRACK * (0.75D + 0.5D * unitHash(stormId, 11L));
        return -depth * Math.cos(relative);
    }

    /** The second and third harmonics, hashed per storm and drifting at different rates. */
    private static double lobes(long stormId, double bearingRadians, long gameTime) {
        double drift = gameTime * LOBE_DRIFT_PER_TICK * (0.7D + 0.6D * unitHash(stormId, 29L));
        double firstPhase = unitHash(stormId, 101L) * Math.PI * 2.0D;
        double secondPhase = unitHash(stormId, 211L) * Math.PI * 2.0D;
        double first = Math.sin(2.0D * bearingRadians + firstPhase + drift);
        double second = Math.sin(3.0D * bearingRadians - secondPhase - drift * 0.6D);
        double depth = LOBE_DEPTH * (0.85D + 0.30D * unitHash(stormId, 307L));
        return depth * (0.62D * first + 0.38D * second);
    }

    private static double uniform(RandomSource random) {
        return random.nextDouble() * Math.PI * 2.0D;
    }

    private static double wrapToPi(double radians) {
        double wrapped = radians % (Math.PI * 2.0D);
        if (wrapped > Math.PI) {
            wrapped -= Math.PI * 2.0D;
        } else if (wrapped < -Math.PI) {
            wrapped += Math.PI * 2.0D;
        }
        return wrapped;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** A hash of (storm, salt) in [0, 1): the same storm keeps the same shape for its whole life. */
    private static double unitHash(long stormId, long salt) {
        long h = stormId * 0x9E3779B97F4A7C15L ^ salt * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 34;
        return (h >>> 11) * 0x1.0p-53;
    }
}
