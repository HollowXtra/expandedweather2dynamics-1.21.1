package com.imbet.client;

/**
 * How far a drawn funnel has to be raised so its lowest puffs stand on the ground under them instead
 * of hanging through it.
 *
 * <p>Every puff of a weather funnel is a camera-facing quad <em>centred</em> on the ring it belongs to.
 * {@code EntityRotFX.setScale(s)} writes {@code s} straight into {@code quadSize}, {@code getQuadSize}
 * returns that field unchanged, and the renderer builds the quad from corners at (±1, ±1, 0) scaled by
 * it - so a puff set to scale 4 reaches four blocks above <em>and four blocks below</em> its own
 * position. A ring is a flat circle drawn at one height, and the anchor height is the ground under the
 * middle of the vortex, so two things follow, and both of them bury the foot:</p>
 *
 * <ul>
 *   <li>the bottom of every puff hangs below its ring by the puff's whole width, which on a sub-vortex
 *       is the width of the ring itself, so the bottom two to four blocks of the foot are drawn inside
 *       the terrain even on flat ground;</li>
 *   <li>the ring's height comes from the ground under the vortex's centre column, so on any rise the
 *       whole uphill half of the foot - and of the dust skirt, which is drawn wider still - is inside
 *       the hill, and the smaller the vortex the larger the share of it that disappears.</li>
 * </ul>
 *
 * <p>The fix is to lift each layer so the lowest point of the puff it is about to place lands on the
 * ground <em>under that layer</em>: the highest ground sampled around the circle the layer's puffs are
 * drawn on, plus the puff's half width, plus a small gap. It is deliberately self-limiting - a ring
 * high enough that its puff already clears the surface asks for nothing - so only the foot moves and
 * the trunk above it keeps the shape the silhouette gives it.</p>
 *
 * <p>Pure arithmetic on numbers, with no game types of its own, so the behaviour can be checked rather
 * than eyeballed - see {@code .freebuff/scratch/FootHugSim.java}, which calls these methods directly
 * rather than copies of them.</p>
 */
public final class FunnelFootHug {

    /**
     * Half a puff's drawn height per unit of scale. This is the one number here that comes from the
     * shipped library rather than from this mod, so it is stated once: {@code setScale(s)} stores
     * {@code s} in {@code quadSize}, {@code getQuadSize} returns that field unchanged, and the quad is
     * built from corners at (±1, ±1, 0) multiplied by it. The half extent is therefore the scale
     * itself, not half of it, which is why a puff's width and the distance it reaches below its ring
     * are the same number.
     */
    public static final float QUAD_HALF_PER_SCALE = 1.0F;

    /** Clearance kept between the bottom of a puff and the ground under it. */
    public static final float GROUND_GAP = 0.15F;

    /**
     * Hard ceiling on the lift, in blocks. A foot may be raised clear of a rise, a bank or a roof, but
     * never so far that one bad sample - a cliff, a cave mouth, a chunk edge - can launch a whole
     * vortex into the air: past this the silhouette wins and the terrain is allowed to cut the foot.
     */
    public static final float MAX_LIFT = 8.0F;

    /**
     * How much of the way a lift falls back per tick once the ground drops away. Rising is immediate,
     * so a foot is never left buried waiting to catch up; falling eases, so a foot that steps off a
     * bank settles onto the lower ground instead of dropping by the height of the bank.
     */
    public static final double FALL_EASE = 0.25D;

    /** Bearings sampled around each of the foot's own circles. */
    public static final int BEARINGS = 8;

    /** Bearings re-sampled per funnel per tick; each circle is refreshed every four ticks. */
    public static final int SAMPLES_PER_TICK = 2;

    private FunnelFootHug() {
    }

    /**
     * Half the drawn height of a puff of this scale - which is also the distance its billboard reaches
     * below the ring it is centred on.
     */
    public static float halfWidth(float scale) {
        return scale * QUAD_HALF_PER_SCALE;
    }

    /**
     * The height a puff of this half width needs its ring at, given the highest ground sampled under
     * that ring, for the puff's bottom edge to clear the surface.
     */
    public static double requiredY(double highestGround, double halfWidth) {
        return highestGround + halfWidth + GROUND_GAP;
    }

    /**
     * How far a ring drawn at {@code ringY} has to be raised for its puff to clear the ground, or 0
     * when it already does. NaN ground means nothing around this foot has been sampled yet, and a foot
     * that has not been measured is left exactly where the silhouette put it rather than guessed at.
     */
    public static double lift(double ringY, double highestGround, double halfWidth) {
        if (Double.isNaN(highestGround)) {
            return 0.0D;
        }
        double wanted = requiredY(highestGround, halfWidth) - ringY;
        return Math.max(0.0D, Math.min(MAX_LIFT, wanted));
    }

    /** Applies a wanted lift to the one a layer already has - up at once, down eased. See {@link #FALL_EASE}. */
    public static double ease(double current, double wanted) {
        if (wanted >= current) {
            return wanted;
        }
        return current + (wanted - current) * FALL_EASE;
    }

    /**
     * Highest of the sampled bearings, or NaN when none of them has been sampled yet. The highest
     * rather than the mean is the point: a foot that must clear the ground it stands on has to clear
     * the highest ground under it, otherwise the uphill side is the part that stays buried.
     */
    public static double highest(double[] samples) {
        double highest = Double.NaN;
        for (double sample : samples) {
            if (!Double.isNaN(sample) && (Double.isNaN(highest) || sample > highest)) {
                highest = sample;
            }
        }
        return highest;
    }
}
