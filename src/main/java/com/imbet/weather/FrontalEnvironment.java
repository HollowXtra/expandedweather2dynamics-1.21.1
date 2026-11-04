package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Where a place sits relative to the nearest synoptic low's fronts, and how strongly that low is
 * influencing it.
 *
 * <p>The geometry is the same geometry the forecaster draws. A low's fronts are two arms out of its
 * centre at fixed angles from its own heading - the warm front at {@code +42} degrees, the cold front
 * at {@code +148}, both swinging back as the system occludes and pinching the warm sector shut - and
 * their lengths come from the live {@link SynopticLow}, not from a constant. So a place is:
 *
 * <ul>
 *   <li><b>on the warm front</b> if it is within the front's band of that arm,
 *   <li><b>on the cold front</b> if it is within the band of the other,
 *   <li>otherwise in whichever air mass the two arms divide the circle into: the <b>warm sector</b>
 *   between them, the <b>cool sector</b> on the far side of the warm front, and <b>post-frontal</b>
 *   behind the cold front.
 * </ul>
 *
 * <p>The band is a fraction of the low's own circulation, because a front is a system-scale feature: a
 * 560-block low and a 2600-block one are the same shape at different sizes, so a fixed block count
 * would make the front of a small low enormous and the front of a large one invisible.
 *
 * <p>Distance from the centre is deliberately <em>not</em> what weakens a front hit. A front is a line
 * rather than a point, so a place 900 blocks out along the warm front is on the warm front, and its
 * influence is set by how close to the line it is and by how deep the low is. For the air-mass
 * regimes, which have no line to be close to, the distance from the centre is what fades them out.
 *
 * <p>Only the server's own low store is read. The client's copy is for drawing; the day's hazards have
 * to come from the system the server is actually running.
 */
public final class FrontalEnvironment {
    /** How far past its own circulation a low is still treated as influencing the weather. */
    private static final double REACH_FACTOR = 1.35D;
    /** The band of a front that counts as being on it, at its narrowest and widest, in blocks. */
    private static final double MIN_BAND_BLOCKS = 48.0D;
    private static final double MAX_BAND_BLOCKS = 400.0D;
    /** The heading used when a low has no motion to speak of, matching how the chart draws it. */
    private static final double FALLBACK_HEADING_X = 1.0D;
    private static final double FALLBACK_HEADING_Z = -0.35D;

    private FrontalEnvironment() {
    }

    /**
     * The frontal influence at a place: which regime is over it, how strongly, and which low is
     * responsible.
     *
     * @param proximity 0 at the edge of any influence and 1 where the low is at its strongest here
     */
    public record Influence(FrontalRegime regime, double proximity, String lowLabel,
                            double lowIntensity, double distanceBlocks) {
        public static final Influence NONE = new Influence(FrontalRegime.NONE, 0.0D, "", 0.0D, 0.0D);

        public boolean present() {
            return regime != FrontalRegime.NONE && proximity > 1.0E-4D;
        }

        /** A regime factor, scaled by how much of the place this front actually governs. */
        public double scale(double factor) {
            return 1.0D + (factor - 1.0D) * clamp01(proximity);
        }

        /**
         * The conditional intensity group this place's fronts imply, from the one the environment
         * earned.
         *
         * <p>Two rules, and both matter. A front can only sharpen a hazard the environment already
         * supports - it sharpens upward only from CIG 1, so a quiet day cannot be talked up. And it
         * can only move the hazard within the published ladder, since the extension columns are what
         * extreme and hyper extreme are earned through and must stay the environment's own.
         */
        public int biasCig(SpcHazard hazard, int raw) {
            int publishedTop = Math.min(3, hazard.maxCig());
            if (raw > publishedTop) {
                return raw;
            }
            double bias = regime.cigBias(hazard) * clamp01(proximity);
            if (bias > 0.0D) {
                if (raw < 1) {
                    return raw;
                }
                return Math.min(publishedTop, raw + (int) Math.round(bias));
            }
            return Math.max(0, raw + (int) Math.round(bias));
        }

        /** One line, for the issuance log and the readout. */
        public String summary() {
            if (!present()) {
                return "no front in range";
            }
            return String.format(Locale.ROOT, "%s (%d%% influence, %s %d blocks away)",
                    regime.label().toLowerCase(Locale.ROOT),
                    Math.round(proximity * 100.0D),
                    lowLabel.toLowerCase(Locale.ROOT),
                    Math.round(distanceBlocks));
        }

        /**
         * Its own clamp rather than the game's, so the record stands alone: these are the rules the
         * outlook is read through, and they are checked outside the game.
         */
        private static double clamp01(double value) {
            if (Double.isNaN(value)) {
                return 0.0D;
            }
            return value < 0.0D ? 0.0D : Math.min(value, 1.0D);
        }
    }

    /** The frontal influence at a place, or {@link Influence#NONE} when nothing is near enough. */
    public static Influence at(ServerLevel level, double x, double z) {
        if (level == null || !Config.LOW_PRESSURE_ENABLED.get() || !Config.LOW_PRESSURE_FRONTAL_RISK.get()) {
            return Influence.NONE;
        }
        List<SynopticLow> lows = SynopticLowStore.get(level).lows();
        if (lows.isEmpty()) {
            return Influence.NONE;
        }
        double bandFraction = Mth.clamp(Config.LOW_PRESSURE_FRONT_BAND_FRACTION.get(), 0.01D, 0.30D);
        Influence best = Influence.NONE;
        double bestScore = 0.0D;
        for (SynopticLow low : lows) {
            double dx = x - low.x();
            double dz = z - low.z();
            double distance = Math.sqrt(dx * dx + dz * dz);
            double radius = Math.max(1.0F, low.radius());
            double reach = radius * REACH_FACTOR;
            if (distance > reach) {
                continue;
            }
            double heading = headingDegrees(low);
            double band = Mth.clamp(radius * bandFraction, MIN_BAND_BLOCKS, MAX_BAND_BLOCKS);

            double warmPerp = perpendicularDistance(dx, dz, heading + low.warmFrontAngleDegrees(), low.warmFrontLengthBlocks());
            boolean onWarm = warmPerp >= 0.0D && warmPerp <= band;
            double coldPerp = perpendicularDistance(dx, dz, heading + low.coldFrontAngleDegrees(), low.coldFrontLengthBlocks());
            boolean onCold = coldPerp >= 0.0D && coldPerp <= band;

            FrontalRegime regime;
            double adjacency;
            if (onWarm) {
                regime = FrontalRegime.WARM_FRONT;
                adjacency = 1.0D - warmPerp / band;
            } else if (onCold) {
                regime = FrontalRegime.COLD_FRONT;
                adjacency = 1.0D - coldPerp / band;
            } else {
                double relative = normalizeDegrees(Math.toDegrees(Math.atan2(dz, dx)) - heading);
                double warmAngle = normalizeDegrees(low.warmFrontAngleDegrees());
                double coldAngle = normalizeDegrees(low.coldFrontAngleDegrees());
                regime = airMass(relative, warmAngle, coldAngle);
                adjacency = Mth.clamp(1.0D - distance / reach, 0.0D, 1.0D);
            }

            // A deep low organises its air masses harder than a shallow one, so the same geometry means
            // more when the system is at its peak than when it is filling.
            double proximity = Mth.clamp(adjacency * (0.55D + 0.75D * low.intensity()), 0.0D, 1.0D);
            double score = proximity * (0.35D + 0.65D * low.intensity()) * ((onWarm || onCold) ? 1.4D : 1.0D);
            if (score > bestScore) {
                bestScore = score;
                best = new Influence(regime, proximity, low.label(), low.intensity(), distance);
            }
        }
        return best;
    }

    /** The frontal influence at a block position. */
    public static Influence at(ServerLevel level, BlockPos pos) {
        if (pos == null) {
            return Influence.NONE;
        }
        return at(level, pos.getX() + 0.5D, pos.getZ() + 0.5D);
    }

    /**
     * Which air mass a bearing relative to the low's heading falls in.
     *
     * <p>The wedge between the two arms is the warm sector. The rest of the circle is the cold air, and
     * it is split down <em>its own</em> middle rather than at the low's heading: ahead of the system is
     * the cool air the warm front is advancing into, behind it the cold air the cold front has already
     * swept. Splitting at the heading instead would put the boundary exactly on the track, where an
     * angle folding between 360 and 0 would flip a place from one side to the other on rounding alone.
     * Down the middle of the cold arc, every boundary is far from the fold.
     */
    private static FrontalRegime airMass(double relative, double warmAngle, double coldAngle) {
        if (relative >= warmAngle && relative <= coldAngle) {
            return FrontalRegime.WARM_SECTOR;
        }
        double coldMidpoint = normalizeDegrees((coldAngle + warmAngle + 360.0D) * 0.5D);
        boolean aheadOfTheWarmFront = relative >= coldMidpoint || relative < warmAngle;
        return aheadOfTheWarmFront ? FrontalRegime.COOL_SECTOR : FrontalRegime.POST_FRONTAL;
    }

    /**
     * How far the place is from one of a low's front arms, or a negative number when it is not beside
     * the arm at all.
     *
     * <p>The arm is the segment from the centre out along the front's own angle, so a place behind the
     * centre or past the end of the arm is not on that front - it is in the air mass the arm divides.
     */
    private static double perpendicularDistance(double dx, double dz, double angleDegrees, double length) {
        double radians = Math.toRadians(angleDegrees);
        double ux = Math.cos(radians);
        double uz = Math.sin(radians);
        double along = dx * ux + dz * uz;
        if (along < 0.0D || along > length) {
            return -1.0D;
        }
        return Math.abs(-dx * uz + dz * ux);
    }

    /** The low's heading in degrees, in the same x-to-z sense the front angles are measured in. */
    private static double headingDegrees(SynopticLow low) {
        Vec3 motion = low.motion();
        double mx = motion.x;
        double mz = motion.z;
        if (Math.sqrt(mx * mx + mz * mz) < 1.0E-4D) {
            mx = FALLBACK_HEADING_X;
            mz = FALLBACK_HEADING_Z;
        }
        return Math.toDegrees(Math.atan2(mz, mx));
    }

    /** An angle folded into [0, 360). */
    private static double normalizeDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped < 0.0D ? wrapped + 360.0D : wrapped;
    }
}
