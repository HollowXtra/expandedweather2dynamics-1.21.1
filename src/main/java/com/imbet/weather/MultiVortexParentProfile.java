package com.imbet.weather;

import net.minecraft.util.Mth;

/**
 * How the parent funnel is drawn under each multi-vortex arrangement, as pure functions of a layer's
 * height (0 = ground, 1 = cloud base).
 *
 * <p>All three arrangements have one, because all three draw the parent: a multi-vortex episode adds
 * circulations to a tornado, it does not take the tornado away.
 *
 * <ul>
 *   <li><b>Lifted base</b> - the parent's condensation has retracted upward off the surface. Below
 *       the retraction there is clear air with the sub-vortices turning in it; from the retraction up     *       the column is the parent it always was, at its own width and opacity. Only its foot is
     *       missing, and the foot comes and goes with the retraction, so the funnel is never erased.
     *       The fade back to full parent runs over {@link #LIFTED_BLEND_SPAN} of the height, so the
     *       foot thins out along the column instead of ending on a band.</li>
 *   <li><b>Ground swarm</b> - the parent is a thin, faint rope that gives up above the surface. The
 *       profile is deliberately not a slice: above the tip the parent is drawn narrow and translucent
 *       and thickens back to the funnel it really is as it climbs, while at the tip both width and
 *       opacity fall away to almost nothing, so the condensation appears to evaporate a few blocks up.
 *       Below the tip there is nothing at all, and the sub-vortices work in that gap.</li>
 *   <li><b>Embedded</b> - the parent is planted and stays the parent. Its lowest layers are drawn back
 *       a little in width and opacity, and only there, so a small vortex orbiting inside its base is
 *       visible through it instead of being hidden behind solid condensation.</li>
 * </ul>
 *
 * <p>Kept free of any storm or world reference so both profiles can be checked arithmetically - see
 * {@code .freebuff/scratch/GroundSwarmSim.java}.
 */
public final class MultiVortexParentProfile {

    /** Width the ground swarm's rope keeps just above its tip, as a fraction of its real width. */
    private static final float ROPE_WIDTH = 0.34F;
    /** Opacity the rope keeps just above its tip, as a fraction of a normal funnel layer's. */
    private static final float ROPE_ALPHA = 0.72F;
    /** Opacity at the very tip itself, where the condensation is giving up. */
    private static final float TIP_ALPHA = 0.10F;
    /** How far above the tip the rope has recovered to full width and opacity. */
    private static final float BLEND_SPAN = 0.16F;

    // ------------------------------------------------------------------ lifted base

    /** Width a lifted parent's condensation keeps where it gives up, as a fraction of its real width. */
    private static final float LIFTED_TIP_WIDTH = 0.42F;
    /** Opacity where a lifted parent's condensation gives up, before it thickens back upward. */
    private static final float LIFTED_TIP_ALPHA = 0.12F;
    /**
     * How far above the retraction the parent is back to its own width and opacity. A fifth of the
     * funnel's height, which is deliberately wider than the ground swarm's blend: this fade runs from
     * near-nothing to full opacity, so spreading it out keeps the steepest step between two adjacent
     * layers no worse than the fade the ground swarm already ships.
     */
    private static final float LIFTED_BLEND_SPAN = 0.20F;
    /**
     * Highest retraction a lifted base can reach, as a fraction of the funnel's height. Past this the
     * parent would be a stub hanging under the cloud base and the arrangement would read as a
     * different storm rather than as a tornado whose base has pulled up.
     */
    public static final float MAX_LIFTED_TIP = 0.62F;

    /** True below a lifted parent's retraction: that part of the column is clear air. */
    public static boolean liftedCulled(float tipFraction, float layerFraction) {
        return layerFraction < clampLiftedTip(tipFraction);
    }

    /** Lifted-base layer opacity: hazy where the condensation gives up, the parent's own above it. */
    public static float liftedAlpha(float tipFraction, float layerFraction) {
        return Mth.lerp(presence(tipFraction), 1.0F, Mth.lerp(liftedRecovery(tipFraction, layerFraction), LIFTED_TIP_ALPHA, 1.0F));
    }

    /** Lifted-base layer width: tapered where it gives up, the parent's real width above it. */
    public static float liftedWidthScale(float tipFraction, float layerFraction) {
        return Mth.lerp(presence(tipFraction), 1.0F, Mth.lerp(liftedRecovery(tipFraction, layerFraction), LIFTED_TIP_WIDTH, 1.0F));
    }

    /** The retraction height as it is actually used. 0 means the column reaches the ground as usual. */
    public static float clampLiftedTip(float tipFraction) {
        return Mth.clamp(tipFraction, 0.0F, MAX_LIFTED_TIP);
    }

    /**
     * How far this layer has recovered from the retraction: 0 exactly at it, 1 once the column is the
     * parent again {@link #LIFTED_BLEND_SPAN} of its height further up.
     */
    private static float liftedRecovery(float tipFraction, float layerFraction) {
        return recoverySpan(clampLiftedTip(tipFraction), layerFraction, LIFTED_BLEND_SPAN);
    }

    /**
     * How far up an embedded parent's funnel the vortices thin its condensation. Deliberately short:
     * a suction vortex turns in the lowest tenth or so of the funnel, so drawing back much more than
     * the base would read as a parent with a missing bottom rather than a planted one.
     */
    private static final float EMBEDDED_THINNED_FRACTION = 0.22F;
    /** Width an embedded parent keeps at its base, where the vortices orbit inside it. */
    private static final float EMBEDDED_WIDTH = 0.82F;
    /**
     * Opacity an embedded parent keeps at its base. Measured against what it has to hide: a sub-vortex
     * column is drawn inside the parent's own silhouette, so at full opacity the vortex is simply not
     * visible through the condensation - which is exactly the state this arrangement exists to fix.
     * The base is hazy instead, and the funnel is back to solid condensation by
     * {@link #EMBEDDED_THINNED_FRACTION} of its height up.
     */
    private static final float EMBEDDED_ALPHA = 0.70F;

    private MultiVortexParentProfile() {
    }

    // ------------------------------------------------------------------ ground swarm

    /** True below the tip: that part of the funnel is clear air with the swirls turning in it. */
    public static boolean groundSwarmCulled(float tipFraction, float layerFraction) {
        return layerFraction < clampTip(tipFraction);
    }

    /** Ground swarm layer opacity, 0 at the tip rising to {@link #ROPE_ALPHA} and then back to full. */
    public static float groundSwarmAlpha(float tipFraction, float layerFraction) {
        return Mth.lerp(presence(tipFraction), 1.0F, Mth.lerp(recovery(tipFraction, layerFraction), TIP_ALPHA, ROPE_ALPHA));
    }

    /** Ground swarm layer width, a thin rope at the tip widening to the parent's real width above it. */
    public static float groundSwarmWidthScale(float tipFraction, float layerFraction) {
        return Mth.lerp(presence(tipFraction), 1.0F, Mth.lerp(recovery(tipFraction, layerFraction), ROPE_WIDTH, 1.0F));
    }

    /** The tip height as it is actually used, clamped into the range the config allows. */
    public static float clampTip(float tipFraction) {
        return Mth.clamp(tipFraction, 0.0F, 0.45F);
    }

    /** How far up the foot has to have come before a retraction's whole shape is in force. */
    private static final float PRESENCE_SPAN = 0.08F;

    /**
     * How much of a retraction's shape is in force: none while the foot is still on the ground, all of it once it has
     * risen a little way. The tip rides the episode's envelope up from the ground and back down to it, but the shape hung
     * off it did not: the foot thinned to a haze the tick an episode began, before it had lifted at all, and filled back
     * in the tick it ended.
     */
    private static float presence(float tipFraction) {
        float t = Mth.clamp(tipFraction / PRESENCE_SPAN, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    // ------------------------------------------------------------------ embedded

    /** Layer width for a planted parent: drawn back at the base, its real width above. */
    public static float embeddedWidthScale(float layerFraction) {
        return Mth.lerp(embeddedWeight(layerFraction), 1.0F, EMBEDDED_WIDTH);
    }

    /** Layer opacity for a planted parent: drawn back at the base, full above. */
    public static float embeddedAlpha(float layerFraction) {
        return Mth.lerp(embeddedWeight(layerFraction), 1.0F, EMBEDDED_ALPHA);
    }

    /**
     * How much the base is drawn back: 1 at the ground, 0 by the time the funnel is back to normal
     * {@link #EMBEDDED_THINNED_FRACTION} of its height up.
     */
    private static float embeddedWeight(float layerFraction) {
        float t = Mth.clamp(layerFraction / Math.max(0.0001F, EMBEDDED_THINNED_FRACTION), 0.0F, 1.0F);
        return 1.0F - t * t * (3.0F - 2.0F * t);
    }

    /**
     * How far this layer has recovered from the tip: 0 exactly at the tip, 1 once the funnel is back
     * to its normal self {@link #BLEND_SPAN} of its height further up.
     */
    private static float recovery(float tipFraction, float layerFraction) {
        return recoverySpan(clampTip(tipFraction), layerFraction, BLEND_SPAN);
    }

    /** Smooth approach from a boundary: 0 at the boundary, 1 once the layer is `span` above it. */
    private static float recoverySpan(float tipFraction, float layerFraction, float span) {
        float safeSpan = Math.max(0.0001F, span);
        float t = Mth.clamp((layerFraction - tipFraction) / safeSpan, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
