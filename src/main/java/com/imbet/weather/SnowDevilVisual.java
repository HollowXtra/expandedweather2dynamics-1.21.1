package com.imbet.weather;

/**
 * What a snow devil looks like, as numbers.
 *
 * <p>It is the dust devil's cold-country twin and it is drawn by the same machinery, so the two are
 * separated here rather than inside the renderer: a snow devil is <em>thinner</em>, <em>shorter</em> and
 * <em>slower</em> than a dust devil of the same wind, and it is white-cold rather than earth-coloured,
 * because it is made of loose snow and not of the ground. Keeping the shape and the colour in one
 * arithmetic class means the client's own arms can be one line each and the whole look can be measured
 * without a client - which matters here, because a snow devil that is drawn but invisible is the bug
 * this exists to prevent.
 *
 * <p>Nothing in here reads the world. A snow devil's own wind and radius are the two things it is drawn
 * from, and both of those come from the storm that made it.
 */
public final class SnowDevilVisual {

    /** Height of the column, in blocks: shorter than a dust devil's, because cold air is shallow. */
    public static final double BASE_HEIGHT = 15.0D;
    public static final double HEIGHT_PER_MPH = 0.30D;
    public static final double HEIGHT_PER_RADIUS = 1.60D;
    public static final double MIN_HEIGHT = 22.0D;
    public static final double MAX_HEIGHT = 58.0D;

    /** How the column is cut into horizontal layers, and how many of them. */
    public static final double LAYERS_PER_BLOCK = 1.55D;
    public static final int MIN_LAYERS = 14;
    public static final int MAX_LAYERS = 34;

    /** Width: a tight base, a column about two thirds of it, and a narrow top. */
    public static final double WIDTH_PER_RADIUS = 1.10D;
    public static final double WIDTH_PER_MPH = 0.014D;
    public static final double MIN_WIDTH = 1.10D;
    public static final double MAX_WIDTH = 4.60D;
    public static final double COLUMN_WIDTH_SHARE = 0.62D;
    public static final double TOP_WIDTH_SHARE = 0.30D;
    /** The share of the column spent flaring from the base out to the column's own width. */
    public static final double FLARE_SHARE = 0.30D;

    /** Spin, in degrees a tick: slower than a dust devil's, and slower still as it widens. */
    public static final float SPIN_BASE = 16.0F;
    public static final float SPIN_PER_MPH = 0.20F;
    public static final float MAX_SPIN_STEP = 4.8F;
    public static final float SPIN_RADIUS_DAMPING = 0.85F;

    /** How thick the cloud reads, and how far it is allowed to fade. */
    public static final float ALPHA_BASE = 0.54F;
    public static final float ALPHA_PER_FRACTION = 0.30F;
    public static final float ALPHA_FLOOR = 0.80F;
    public static final float MAX_ALPHA = 0.74F;

    /** The size of one billboard, and the ceiling on it. */
    public static final float SCALE_BASE = 2.4F;
    public static final float SCALE_PER_RADIUS = 0.62F;
    public static final float SCALE_PER_FLARE = 1.40F;
    public static final float MAX_SCALE = 7.0F;

    /** The colour of loose snow lit by a low sun: cold, pale, and never brown. */
    public static final float TONE_RED = 0.90F;
    public static final float TONE_GREEN = 0.93F;
    public static final float TONE_BLUE = 1.00F;
    public static final float CORE_RED = 0.76F;
    public static final float CORE_GREEN = 0.80F;
    public static final float CORE_BLUE = 0.89F;
    /** Snow has no dark heart: the middle of a snow devil is the brightest part of it. */
    public static final boolean DARK_CORE = false;

    /** How many billboards one layer's ring is cut into at most, and how coarse the ring is. */
    public static final double RING_SPACING = 2.05D;
    public static final int MAX_RING = 10;
    public static final int MIN_RING = 2;
    /** Above this share of the column the ring thins by one, the way a whirl's top frays. */
    public static final double FRAY_FROM = 0.72D;

    /** How long the drawing lingers after the devil has gone, in ticks. */
    public static final long FADE_TICKS = 70L;

    private SnowDevilVisual() {
    }

    /** How tall the column is, from its own radius and the wind it is carrying. */
    public static double heightFor(double radius, double wind) {
        double raw = BASE_HEIGHT + Math.max(0.0D, wind) * HEIGHT_PER_MPH + Math.max(0.0D, radius) * HEIGHT_PER_RADIUS;
        return clamp(raw, MIN_HEIGHT, MAX_HEIGHT);
    }

    /** How many layers that height is cut into. */
    public static int layerCountFor(double height) {
        return (int) clamp(Math.floor(Math.max(0.0D, height) / LAYERS_PER_BLOCK), MIN_LAYERS, MAX_LAYERS);
    }

    /**
     * The half-width of the column at one fraction of its height: a tight base that flares quickly,
     * then a column that tapers to a narrow top.
     */
    public static double radiusFor(double baseRadius, double wind, double fraction) {
        double ground = clamp(Math.max(0.0D, baseRadius) * WIDTH_PER_RADIUS + Math.max(0.0D, wind) * WIDTH_PER_MPH,
                MIN_WIDTH, MAX_WIDTH);
        double column = Math.max(MIN_WIDTH, ground * COLUMN_WIDTH_SHARE);
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

    /** How thick the cloud reads at one fraction of the column: thickest at the ground, thinning upward. */
    public static float alphaFor(double fraction) {
        double share = clamp(1.0D - clamp(fraction, 0.0D, 1.0D) * ALPHA_PER_FRACTION, ALPHA_FLOOR, 1.0D);
        return (float) (ALPHA_BASE * share);
    }

    /** The size of one billboard at this radius and height. */
    public static float scaleFor(double radius, double fraction) {
        double raw = SCALE_BASE + Math.max(0.0D, radius) * SCALE_PER_RADIUS
                + (1.0D - clamp(fraction, 0.0D, 1.0D)) * SCALE_PER_FLARE;
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

    /** How far the column leans downwind at one fraction of its height. */
    public static double leanFor(double height, double fraction) {
        return Math.sin(clamp(fraction, 0.0D, 1.0D) * Math.PI) * clamp(Math.max(0.0D, height) * 0.035D, 0.20D, 1.60D);
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
