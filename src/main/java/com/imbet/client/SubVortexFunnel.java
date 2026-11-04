package com.imbet.client;

import net.minecraft.util.Mth;

/**
 * The silhouette of a sub-vortex's own condensation funnel, as pure functions of height
 * (0 = the vortex's lowest drawn layer, 1 = cloud base).
 *
 * <p>A sub-vortex used to be drawn as a stack of rings at a nearly constant radius, each ring filled
 * with a handful of puffs as wide as the ring itself - which is a plume, not a funnel. A real
 * tornado's funnel is a <em>surface</em>: the condensation is a thin tapering trunk that is narrowest
 * at its lowest reach and widens toward the cloud base with a concave flare, carrying a broader dust
 * swirl around its base where the surface wind is working. Three things make a drawn funnel read that
 * way, and this class is all three:</p>
 *
 * <ul>
 *   <li><b>a taper</b> - {@link #wallRadius} runs from a tip a quarter of the top's width up to the top
 *       along a curve below linear, so the trunk stays slender well up its height and only flares
 *       near the cloud base;</li>
 *   <li><b>a dust skirt</b> - {@link #dustRadius} is wider than the condensation for the lowest tenth
 *       or so, so debris rings around a narrow tip instead of hugging it;</li>
 *   <li><b>puffs sized to their own ring</b> - {@link #puffScale} and {@link #puffCount} keep each puff
 *       a fraction of the ring it sits on and enough of them to close the ring, so the surface is
 *       continuous instead of lumpy.</li>
 * </ul>
 *
 * <p>Pure arithmetic on numbers, with no storm, world or renderer reference, so the silhouette can be
 * checked rather than eyeballed - see {@code .freebuff/scratch/SubFunnelSim.java}.
 */
public final class SubVortexFunnel {

    /** Radius at the funnel's lowest drawn layer, as a fraction of its radius at the cloud base. */
    public static final float TIP_FRACTION = 0.26F;
    /**
     * Taper exponent. A real funnel's radius grows faster than linearly just above its tip - the trunk
     * looks concave, narrow for most of its height and flaring only near the cloud base - and this is
     * what produces that. 1.0 would be a straight cone; 0.72 is the classic elephant-trunk curve.
     */
    private static final float TAPER_EXPONENT = 0.72F;
    /** How much wider the ground dust swirl is than the condensation tip above it. */
    private static final float SKIRT_FRACTION = 1.85F;
    /** Fraction of the funnel's height the ground dust swirl reaches up. */
    private static final float SKIRT_HEIGHT = 0.12F;
    /** Floor on the tip radius, so a tiny vortex still has a visible contact point. */
    private static final float MIN_TIP = 0.60F;
    /** Puff size (across) as a fraction of its own ring's radius. */
    private static final float PUFF_FRACTION = 0.62F;
    private static final float MIN_PUFF = 0.55F;
    private static final float MAX_PUFF = 5.0F;
    /**
     * Rings of puffs are spaced no wider than this fraction of a puff, or the funnel surface shows gaps
     * between its puffs and reads as a ring of blobs rather than as condensation.
     */
    public static final float RING_OVERLAP = 0.86F;
    /** Amplitude of the slow rope-wave wobble along the trunk, as a fraction of the local radius. */
    private static final float ROPE_WAVE_AMPLITUDE = 0.09F;
    /** Share of a funnel's puff count a dust column gets: coarser, because haze has no edge to close. */
    private static final float HAZE_PUFF_FRACTION = 0.75F;
    /** How much stronger a dust column's surface ripple is than a condensation trunk's. */
    private static final float HAZE_WOBBLE_SCALE = 1.6F;
    /** How much wider the top of the funnel is on a violent vortex than on a weak one. */
    private static final float INTENSITY_FLARE = 0.18F;

    private SubVortexFunnel() {
    }

    /** Radius of the funnel where it meets the cloud base - its widest point. */
    public static float topRadius(float visibleRadius, float intensity) {
        float base = Math.max(0.9F, visibleRadius);
        return base * (1.0F + Mth.clamp(intensity, 0.0F, 1.0F) * INTENSITY_FLARE);
    }

    /** Radius of the condensation at the funnel's lowest reach - its narrowest point. */
    public static float tipRadius(float topRadius) {
        return Math.max(MIN_TIP, topRadius * TIP_FRACTION);
    }

    /**
     * Condensation radius at a height. Narrow at the lowest layer, widening concavely toward the cloud
     * base, with the slow rope-wave wobble a real funnel has along its length.
     */
    public static float wallRadius(float topRadius, float fraction, float ropeWave) {
        float top = Math.max(tipRadius(topRadius), topRadius);
        float curve = (float) Math.pow(Mth.clamp(fraction, 0.0F, 1.0F), TAPER_EXPONENT);
        return Mth.lerp(curve, tipRadius(top), top) * (1.0F + ropeWave);
    }

    /**
     * Radius of the vortex's dust at a height: its debris swirl is wider than the condensation near
     * the ground, where the strongest surface wind is, and settles onto the funnel's own surface above
     * the skirt. Never narrower than {@link #wallRadius}, so debris rings can't cut inside the funnel.
     */
    public static float dustRadius(float topRadius, float fraction) {
        float skirt = tipRadius(topRadius) * SKIRT_FRACTION;
        float blend = smoothstep(Mth.clamp(fraction / SKIRT_HEIGHT, 0.0F, 1.0F));
        return Math.max(wallRadius(topRadius, fraction, 0.0F), Mth.lerp(blend, skirt, wallRadius(topRadius, fraction, 0.0F)));
    }

    /** Width of one puff on a ring of this radius: a fraction of the ring, floored and capped. */
    public static float puffScale(float ringRadius) {
        return Mth.clamp(ringRadius * PUFF_FRACTION, MIN_PUFF, MAX_PUFF);
    }

    /**
     * Width for one puff on a ring whose puff count is already decided. Never narrower than the gap it
     * has to span: the puffs are placed evenly around the circumference, so a ring is continuous only
     * while the spacing stays under {@link #RING_OVERLAP} of a puff's width, and a low particle setting
     * with a fixed puff size is exactly how a drawn funnel ends up as a ring of separate blobs.
     */
    public static float puffScale(float ringRadius, int puffCount) {
        float spacing = ringRadius * 2.0F * Mth.PI / Math.max(1, puffCount);
        return Math.max(puffScale(ringRadius), spacing / RING_OVERLAP);
    }

    /**
     * How many puffs a ring of this radius gets. The particle setting scales the count, and whatever
     * count comes out, {@link #puffScale(float, int)} widens the puffs to match - so a low particle
     * budget draws a chunkier funnel rather than one with holes in it.
     */
    public static int puffCount(float ringRadius, float particleRate) {
        float rate = Mth.clamp(particleRate, 0.5F, 2.0F);
        float spacing = puffScale(ringRadius) * RING_OVERLAP / rate;
        return Mth.clamp((int) Math.ceil(ringRadius * 2.0F * Mth.PI / spacing), 4, 26);
    }

    // ------------------------------------------------------------------ dust columns

    /**
     * Radius of a dust column: the pre-touchdown whirl rather than a condensation funnel.
     *
     * <p>A whirl is nothing <em>but</em> the dust it lifts, so unlike a funnel the whole column follows
     * the dust silhouette: a flared swirl right at the ground where the surface wind is strongest, a
     * waist just above it, and then a steady flare up to {@code topRadius} where the gathering
     * circulation is drawing the haze up under the cloud that is about to become a funnel. That is a
     * column - the thing the whirl is, and the thing a squat stack of oversized puffs never read as.
     */
    public static float dustColumnRadius(float topRadius, float fraction, float wobble) {
        return dustRadius(topRadius, fraction) * (1.0F + wobble);
    }

    /**
     * Puffs for one ring of a dust column. Haze has no edge to close, so a whirl needs fewer puffs than
     * a condensation funnel of the same width - but the width still comes from
     * {@link #puffScale(float, int)} with this count, so the reduced ring is covered rather than gappy.
     */
    public static int dustColumnPuffCount(float ringRadius, float particleRate) {
        return Math.max(3, (int) (puffCount(ringRadius, particleRate) * HAZE_PUFF_FRACTION));
    }

    /**
     * Opacity of the condensation. Lower than a parent tornado's: these are small vortices whose
     * condensation is thin, and drawing them as solid as the main funnel reads as a second parent.
     */
    public static float condensationAlpha(float reveal) {
        return Mth.clamp(0.42F + 0.44F * Mth.clamp(reveal, 0.0F, 1.0F), 0.16F, 0.86F);
    }

    /** Slow wobble of the trunk along its length, as a fraction of the local radius. */
    public static float ropeWave(long tick, long seed, float fraction) {
        return (float) (ROPE_WAVE_AMPLITUDE * Math.sin(tick * 0.10D + fraction * Math.PI * 5.4D + seed * 0.09D));
    }

    /** The same ripple for a dust column, which churns visibly rather than swelling smoothly. */
    public static float dustWobble(long tick, long seed, float fraction) {
        return (float) (ROPE_WAVE_AMPLITUDE * HAZE_WOBBLE_SCALE
                * Math.sin(tick * 0.058D + fraction * Math.PI * 5.2D + seed * 0.11D));
    }

    private static float smoothstep(float value) {
        return value * value * (3.0F - 2.0F * value);
    }
}
