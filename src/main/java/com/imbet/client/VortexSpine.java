package com.imbet.client;

import net.minecraft.util.Mth;

/**
 * The centreline of a vortex: where its axis actually is at each height.
 *
 * <p>The funnels used to be bent by a formula: a lean and a sine wave, written straight onto each layer every tick. A
 * formula has no history, so a funnel could not trail behind anything, and when a storm turned, its whole column swung
 * round at once like a stick. A real funnel is a line of air being dragged along by its foot at one end and by a
 * storm at the other, and the shape of it is mostly a record of where those two have recently been.
 *
 * <p>So each node of the spine has a position in the world of its own, and it chases where it would like to be at a
 * rate that gets slower with height. The foot jinks over a hill and the kink climbs the column a moment later. The
 * storm turns and the top comes round last, so the funnel curves through the turn. A storm moving at speed trails its
 * top behind it without anyone having to ask for that. Stretching is limited, because a funnel is only so long.
 *
 * <p>This is the look and nothing else: the wind field, the damage path and everything else that matters to the world
 * read the storm's own position, and a client that has just arrived simply settles into the same shape within a few
 * seconds.
 */
public final class VortexSpine {
    /** Where the nodes sit, as a fraction of the column's height. The foot itself is a fifth, at nought. */
    static final float[] HEIGHTS = {0.25F, 0.50F, 0.75F, 1.00F};

    /** How long the lowest node takes to follow, as a share of the top's. */
    private static final float LOW_NODE_LAG_SHARE = 0.22F;

    private final double[] worldX = new double[HEIGHTS.length];
    private final double[] worldZ = new double[HEIGHTS.length];
    private boolean placed;

    /** Each node's offset from the foot. */
    final float[] offsetX = new float[HEIGHTS.length];
    final float[] offsetZ = new float[HEIGHTS.length];

    /** The result of the last {@link #sample}, so a funnel with sixty layers can ask sixty times without allocating. */
    public float sampleX;
    public float sampleZ;

    /**
     * @param lagTicks how long the top of the column takes to follow, in ticks
     * @param dt ticks since the last update
     * @param height the column's height in blocks
     * @param leanX the lean of the top against the foot, in blocks: the steady tilt the shear gives it
     * @param sway how far it may wander either side of that, in blocks
     * @param phase a slowly advancing clock, seeded per vortex, so no two columns move together
     * @param maxLeanShare the longest the axis may stretch sideways at any height, as a share of that height
     */
    public void advance(double footX, double footZ, float lagTicks, float dt, float height,
                 float leanX, float leanZ, float sway, double phase, float maxLeanShare) {
        float leanLength = Mth.sqrt(leanX * leanX + leanZ * leanZ);
        float dirX = leanLength > 1.0E-3F ? leanX / leanLength : (float) Math.cos(phase * 0.05D);
        float dirZ = leanLength > 1.0E-3F ? leanZ / leanLength : (float) Math.sin(phase * 0.05D);
        for (int i = 0; i < HEIGHTS.length; i++) {
            float h = HEIGHTS[i];
            float belly = Mth.sin(h * Mth.PI);
            // The steady tilt, gathering with height, plus travelling waves. Two across the lean with periods that
            // never line up, and one along it so the column bunches and stretches rather than only swinging sideways.
            float tilt = (float) Math.pow(h, 1.6D);
            float across = (float) (Math.sin(phase * 0.55D + h * Math.PI * 2.3D) + 0.45D * Math.sin(phase * 0.31D + 1.7D + h * Math.PI * 4.1D)) * belly * sway;
            float along = (float) Math.sin(phase * 0.43D + 0.9D + h * Math.PI * 1.7D) * belly * sway * 0.5F;
            double targetX = footX + leanX * tilt + dirX * along - dirZ * across;
            double targetZ = footZ + leanZ * tilt + dirZ * along + dirX * across;

            if (!placed) {
                worldX[i] = targetX;
                worldZ[i] = targetZ;
            } else {
                float tau = Math.max(0.5F, lagTicks * Mth.lerp(h, LOW_NODE_LAG_SHARE, 1.0F));
                float follow = 1.0F - (float) Math.exp(-dt / tau);
                worldX[i] += (targetX - worldX[i]) * follow;
                worldZ[i] += (targetZ - worldZ[i]) * follow;
            }

            double dx = worldX[i] - footX;
            double dz = worldZ[i] - footZ;
            double length = Math.sqrt(dx * dx + dz * dz);
            double limit = Math.max(0.5D, maxLeanShare * h * height);
            if (length > limit) {
                dx *= limit / length;
                dz *= limit / length;
                worldX[i] = footX + dx;
                worldZ[i] = footZ + dz;
            }
            offsetX[i] = (float) dx;
            offsetZ[i] = (float) dz;
        }
        placed = true;
    }

    /**
     * The axis's offset from the foot at a height, between the nodes, left in {@link #sampleX} and {@link #sampleZ}.
     * A curve through the nodes rather than straight lines between them, because the layers are close enough together
     * for a corner to show as a kink in the column.
     */
    public void sample(float height) {
        float h = Mth.clamp(height, 0.0F, 1.0F) * HEIGHTS.length;
        int segment = Math.min(HEIGHTS.length - 1, (int) h);
        float t = h - segment;
        sampleX = catmullRom(node(offsetX, segment - 1), node(offsetX, segment), node(offsetX, segment + 1), node(offsetX, segment + 2), t);
        sampleZ = catmullRom(node(offsetZ, segment - 1), node(offsetZ, segment), node(offsetZ, segment + 1), node(offsetZ, segment + 2), t);
    }

    /** Node {@code index} counted from the foot, which is node nought and always at no offset. */
    private static float node(float[] offsets, int index) {
        return index <= 0 ? 0.0F : offsets[Math.min(index - 1, offsets.length - 1)];
    }

    private static float catmullRom(float a, float b, float c, float d, float t) {
        return 0.5F * (2.0F * b + (c - a) * t + (2.0F * a - 5.0F * b + 4.0F * c - d) * t * t + (3.0F * b - a - 3.0F * c + d) * t * t * t);
    }
}
