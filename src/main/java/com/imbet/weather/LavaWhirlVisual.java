package com.imbet.weather;

/**
 * What a lava whirl looks like, as numbers.
 *
 * <p>The same arrangement the snow devil uses: the client reads its colour, its thickness, its height and its
 * rotation out of here rather than deciding them inline, so the whole look can be measured outside the game and
 * the only thing the renderer has to know about a lava whirl is that it is one.
 *
 * <p>What the numbers say is what the phenomenon is. A fire whirl is <em>drawn in its own light</em> - it is
 * not the colour of the rock under it, because it is a flame - it is <em>taller and narrower</em> than a dust
 * devil of the same wind, because a fire whirl is a thin column rather than a broad skirt, it <em>spins
 * harder</em> than anything else in the family because its rotation is the flame's own, and it is
 * <em>bottom-heavy</em>: the burning is at the base where the fuel is and there is less of it the higher it
 * gets, which is the opposite of the funnels, whose density comes from condensation and rises with height.
 */
public final class LavaWhirlVisual {

    /** Height of the column, in blocks: taller than a dust devil's, because a fire whirl is a column. */
    public static final double BASE_HEIGHT = 30.0D;
    public static final double HEIGHT_PER_MPH = 0.45D;
    public static final double HEIGHT_PER_RADIUS = 2.10D;
    public static final double MIN_HEIGHT = 42.0D;
    public static final double MAX_HEIGHT = 100.0D;

    /** How the column is cut into horizontal layers, and how many of them. */
    public static final double LAYERS_PER_BLOCK = 1.60D;
    public static final int MIN_LAYERS = 24;
    public static final int MAX_LAYERS = 62;

    /** Width: a tight base that flares quickly, then a narrow column all the way up. */
    public static final double WIDTH_PER_RADIUS = 1.05D;
    public static final double WIDTH_PER_MPH = 0.013D;
    public static final double MIN_WIDTH = 1.30D;
    public static final double MAX_WIDTH = 5.20D;
    public static final double COLUMN_WIDTH_SHARE = 0.66D;
    public static final double TOP_WIDTH_SHARE = 0.30D;
    public static final double FLARE_SHARE = 0.22D;

    /** Spin, in degrees a tick: the hardest-turning whirl in the family, and damped least by width. */
    public static final float SPIN_BASE = 34.0F;
    public static final float SPIN_PER_MPH = 0.34F;
    public static final float MAX_SPIN_STEP = 7.6F;
    public static final float SPIN_RADIUS_DAMPING = 0.95F;

    /** How thick it reads, and how far it is allowed to fade. */
    public static final float ALPHA_BASE = 0.74F;
    public static final float ALPHA_PER_FRACTION = 0.44F;
    public static final float ALPHA_FLOOR = 0.55F;
    public static final float MAX_ALPHA = 0.88F;

    /** The size of one billboard, and the ceiling on it. */
    public static final float SCALE_BASE = 4.0F;
    public static final float SCALE_PER_RADIUS = 0.55F;
    public static final float SCALE_PER_FLARE = 3.40F;
    public static final float SCALE_PER_FRACTION = 1.10F;
    public static final float MAX_SCALE = 11.0F;

    /** The glowing body: orange, lit from inside rather than tinted by what it is crossing. */
    public static final float TONE_RED = 0.88F;
    public static final float TONE_GREEN = 0.42F;
    public static final float TONE_BLUE = 0.17F;

    /** Its heart is smoke rather than flame, so the core is darker than the shell around it. */
    public static final float CORE_RED = 0.24F;
    public static final float CORE_GREEN = 0.10F;
    public static final float CORE_BLUE = 0.06F;
    public static final boolean DARK_CORE = true;

    /** How many billboards one layer's ring is cut into at most, and how coarse the ring is. */
    public static final double RING_SPACING = 2.35D;
    public static final int MAX_RING = 18;
    public static final int MIN_RING = 1;
    /** Above this share of the column the ring thins by one, the way every whirl's top frays. */
    public static final double FRAY_FROM = 0.72D;
    /** Below this share the base is dense: that is the burning end. */
    public static final double DENSE_TO = 0.80D;

    /** How long the drawing lingers after the whirl has gone, in ticks. */
    public static final long FADE_TICKS = 90L;

    /** How fast what is left of it turns while it is coming apart, in degrees a tick. */
    public static final float FADE_SPIN_PER_TICK = 3.4F;

    /** How far above the ground the base of the column is drawn. It stands on the flow it came off. */
    public static final double BASE_OFFSET_BLOCKS = 0.35D;

    private LavaWhirlVisual() {
    }

    /** How tall the column is, from its own radius and the wind it is carrying. */
    public static double heightFor(double radius, double wind) {
        double raw = BASE_HEIGHT + Math.max(0.0D, wind) * HEIGHT_PER_MPH
                + Math.max(0.0D, radius) * HEIGHT_PER_RADIUS;
        return clamp(raw, MIN_HEIGHT, MAX_HEIGHT);
    }

    /** How many layers that height is cut into. */
    public static int layerCountFor(double height) {
        return (int) clamp(Math.floor(Math.max(0.0D, height) / LAYERS_PER_BLOCK), MIN_LAYERS, MAX_LAYERS);
    }

    /** The half-width of the column at one fraction of its height: a flare at the base, then a thin column. */
    public static double radiusFor(double baseRadius, double wind, double fraction) {
        double ground = clamp(Math.max(0.0D, baseRadius) * WIDTH_PER_RADIUS + Math.max(0.0D, wind) * WIDTH_PER_MPH,
                MIN_WIDTH, MAX_WIDTH);
        double column = Math.max(MIN_WIDTH * COLUMN_WIDTH_SHARE, ground * COLUMN_WIDTH_SHARE);
        double top = Math.max(MIN_WIDTH * TOP_WIDTH_SHARE, ground * TOP_WIDTH_SHARE);
        double f = clamp(fraction, 0.0D, 1.0D);
        if (f < FLARE_SHARE) {
            return lerp(smoothstep(f / FLARE_SHARE), ground, column);
        }
        return lerp(smoothstep((f - FLARE_SHARE) / (1.0D - FLARE_SHARE)), column, top);
    }

    /** Spin in degrees a tick at this wind. */
    public static float spinBaseFor(double wind) {
        return (float) (SPIN_BASE + Math.max(0.0D, wind) * SPIN_PER_MPH);
    }

    /** How thick it reads at one fraction of the column: densest at the burning base, thinning upward. */
    public static float alphaFor(double fraction) {
        double share = clamp(1.16D - clamp(fraction, 0.0D, 1.0D) * ALPHA_PER_FRACTION, ALPHA_FLOOR, 1.0D);
        return (float) (ALPHA_BASE * share);
    }

    /** The size of one billboard at this radius and height. */
    public static float scaleFor(double radius, double fraction) {
        double raw = SCALE_BASE + Math.max(0.0D, radius) * SCALE_PER_RADIUS
                + (1.0D - clamp(fraction, 0.0D, 1.0D)) * SCALE_PER_FLARE
                + clamp(fraction, 0.0D, 1.0D) * SCALE_PER_FRACTION;
        return (float) clamp(raw, 0.85D, MAX_SCALE);
    }

    /** How many billboards a layer's ring holds, before the particle setting scales it. */
    public static int targetRing(double radius, double fraction, float particleRate) {
        double circumference = Math.max(1.0D, Math.max(0.0D, radius) * Math.PI * 2.0D);
        int count = (int) Math.ceil(circumference / RING_SPACING * Math.max(0.05F, particleRate));
        if (clamp(fraction, 0.0D, 1.0D) > FRAY_FROM) {
            count -= 1;
        }
        return (int) clamp(count, MIN_RING, MAX_RING);
    }

    /**
     * How far the column leans downwind at one fraction of its height. Less than a dust devil's: a lava whirl
     * is standing on the vent that made it rather than drifting across open country, so less of it is in the
     * shear.
     */
    public static double leanFor(double height, double fraction) {
        return Math.sin(clamp(fraction, 0.0D, 1.0D) * Math.PI)
                * clamp(Math.max(0.0D, height) * 0.030D, 0.15D, 1.50D);
    }

    private static double lerp(double delta, double start, double end) {
        return start + (end - start) * delta;
    }

    private static double smoothstep(double value) {
        double clamped = clamp(value, 0.0D, 1.0D);
        return clamped * clamped * (3.0D - 2.0D * clamped);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
