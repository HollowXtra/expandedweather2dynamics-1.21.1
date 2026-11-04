package com.imbet.weather;

/**
 * What a steam devil looks like, as numbers.
 *
 * <p>It is the whitest thing in the family and the narrowest, and both follow from what it is made of. Steam is
 * a phase change rather than a substance lifted off the ground, so the column is pale and bright instead of
 * taking the colour of the rock it is standing on, it has no dark heart because there is no debris in it to
 * darken, and it <em>widens as it rises</em> - a plume, not a funnel: narrow at the base, thicker through the
 * middle and spread at the top, which is the opposite of every condensation funnel in this mod and the reason
 * this class computes its own profile rather than borrowing the funnels' one.
 */
public final class SteamDevilVisual {

    /** Height of the column, in blocks. */
    public static final double BASE_HEIGHT = 18.0D;
    public static final double HEIGHT_PER_MPH = 0.90D;
    public static final double HEIGHT_PER_RADIUS = 2.40D;
    public static final double MIN_HEIGHT = 26.0D;
    public static final double MAX_HEIGHT = 84.0D;

    /** How the column is cut into horizontal layers, and how many of them. */
    public static final double LAYERS_PER_BLOCK = 1.25D;
    public static final int MIN_LAYERS = 18;
    public static final int MAX_LAYERS = 56;

    /**
     * Width: narrow at the base and wider at the top, which is what a rising plume does. The shares are of the
     * ground radius, and the top's is above one on purpose.
     */
    public static final double WIDTH_PER_RADIUS = 0.40D;
    public static final double WIDTH_PER_MPH = 0.007D;
    public static final double MIN_WIDTH = 0.60D;
    public static final double MAX_WIDTH = 2.60D;
    public static final double COLUMN_WIDTH_SHARE = 0.85D;
    public static final double TOP_WIDTH_SHARE = 1.35D;
    public static final double FLARE_SHARE = 0.35D;

    /** Spin, in degrees a tick: a column of vapour turns lazily next to a fire whirl. */
    public static final float SPIN_BASE = 12.0F;
    public static final float SPIN_PER_MPH = 0.16F;
    public static final float MAX_SPIN_STEP = 3.6F;
    public static final float SPIN_RADIUS_DAMPING = 0.90F;

    /** How thick it reads: thin at the base, thickening upward the way a plume does. */
    public static final float ALPHA_BASE = 0.66F;
    public static final float ALPHA_PER_FRACTION = 0.72F;
    public static final float ALPHA_FLOOR = 0.45F;
    public static final float MAX_ALPHA = 0.82F;

    /** The size of one billboard, and the ceiling on it. */
    public static final float SCALE_BASE = 1.90F;
    public static final float SCALE_PER_RADIUS = 0.14F;
    public static final float SCALE_PER_FRACTION = 3.60F;
    public static final float MAX_SCALE = 8.0F;

    /** Scalding water vapour: very nearly white, with a hint of cold in the blue. */
    public static final float TONE_RED = 0.94F;
    public static final float TONE_GREEN = 0.96F;
    public static final float TONE_BLUE = 1.00F;

    /** No dark heart: there is nothing in a steam devil to make one. */
    public static final boolean DARK_CORE = false;

    /** How many billboards one layer's ring is cut into at most, and how coarse the ring is. */
    public static final double RING_SPACING = 3.30D;
    public static final int MAX_RING = 14;
    public static final int MIN_RING = 2;

    /** How long the drawing lingers after the devil has gone, in ticks. */
    public static final long FADE_TICKS = 60L;

    /** How fast what is left of it turns while it is coming apart, in degrees a tick. */
    public static final float FADE_SPIN_PER_TICK = 2.8F;

    /** How far above the ground the base is drawn: it comes up out of the water, not out of the ground. */
    public static final double BASE_OFFSET_BLOCKS = 0.25D;

    private SteamDevilVisual() {
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

    /** The half-width at one fraction of its height: a narrow base opening out to a spread top. */
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

    /** How thick it reads at one fraction of the column: thinnest at the water, thickening upward. */
    public static float alphaFor(double fraction) {
        double share = clamp(0.50D + clamp(fraction, 0.0D, 1.0D) * ALPHA_PER_FRACTION, ALPHA_FLOOR, 1.0D);
        return (float) (ALPHA_BASE * share);
    }

    /** The size of one billboard at this radius and height. */
    public static float scaleFor(double radius, double fraction) {
        double raw = SCALE_BASE + Math.max(0.0D, radius) * SCALE_PER_RADIUS
                + clamp(fraction, 0.0D, 1.0D) * SCALE_PER_FRACTION;
        return (float) clamp(raw, 0.85D, MAX_SCALE);
    }

    /** How many billboards a layer's ring holds. Sparse: a plume is mostly air with a shine on it. */
    public static int targetRing(double radius, double fraction, float particleRate) {
        double circumference = Math.max(1.0D, Math.max(0.0D, radius) * Math.PI * 2.0D);
        int count = (int) Math.ceil(circumference / RING_SPACING * Math.max(0.05F, particleRate));
        return (int) clamp(count, MIN_RING, MAX_RING);
    }

    /** How far it leans. Like a funnel cloud, it leans back over its own tip as it climbs. */
    public static double leanFor(double height, double fraction) {
        return Math.pow(clamp(fraction, 0.0D, 1.0D), 1.30D)
                * clamp(Math.max(0.0D, height) * 0.040D, 0.40D, 3.00D);
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
