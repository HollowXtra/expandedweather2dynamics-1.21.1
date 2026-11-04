package com.imbet.client;

import java.util.Random;

/**
 * The geometry of one lightning strike, built from its seed.
 *
 * <p>Vanilla and Weather2 both draw a bolt as a chain of fixed-width, hard-edged ribbons in four
 * greedy passes, which is why a strike reads as a grey stick: there is no taper, no halo and no
 * cross-section for a fragment shader to work with. This builds something a shader can shade
 * properly instead - a channel that is thickest where it meets the ground and narrows as it climbs,
 * drawn twice (a wide dim halo under a narrow hot core) with the ribbon's cross-coordinate in U and
 * the distance up the channel in V, plus a few forks and a glow where it meets the ground.
 *
 * <p>The whole class is deliberately free of Minecraft types: it is pure arithmetic over a seed, so
 * the geometry can be measured directly rather than eyeballed. Everything a renderer needs is in
 * {@link Mesh#data}: x, y, z relative to the strike point, then u, v, then r, g, b, a.
 *
 * <p>Three properties are load-bearing and are checked rather than assumed. Each path <em>point</em>
 * carries one cross axis and one width, and every quad that touches that point uses them, so
 * consecutive ribbons share their corners exactly and the channel cannot tear open where it bends;
 * each axis is perpendicular to the line of sight <em>to the point it belongs to</em>, so the ribbon
 * presents its full width to the eye rather than being foreshortened away; and no channel vertex is
 * left below the ground plane, so a strike cannot be seen cutting into the block it hit.
 */
public final class LightningBoltShape {
    /** Floats per vertex: three of position, two of coordinate, four of colour. */
    public static final int STRIDE = 9;
    /** Vertical segments in the trunk. Vanilla uses eight 16-block steps over the same 112 blocks. */
    public static final int TRUNK_SEGMENTS = 8;
    /** How far a step may wander from the last one before the walk is smoothed back. */
    private static final float MAX_STEP_WANDER = 3.4F;
    /** The heading changes by at most this much per step, which is what keeps the channel a channel. */
    private static final float MAX_TURN_DEGREES = 26.0F;
    private static final int BRANCH_SEGMENTS = 4;
    /** Tendrils an anvil crawler throws out across the cloud base from the top of its channel. */
    private static final int CRAWLER_TENDRILS = 5;
    /** Tendrils a cloud-to-cloud discharge is drawn as, radiating in the cloud plane. */
    private static final int CLOUD_CHANNEL_TENDRILS = 3;
    /** How far beyond the last point a crawl tendril may wander before the walk is smoothed back. */
    private static final float CRAWL_STEP_SPAN = 8.0F;
    /** The turn a crawl tendril may make per step. Wider than the trunk: these race, they do not lean. */
    private static final float CRAWL_TURN_DEGREES = 34.0F;
    /** How far an anvil crawler's tendrils droop as they race outward, per step, in blocks. */
    private static final float CRAWL_DROOP = 0.55F;
    /**
     * The cross-product magnitude below which a point is treated as seen along its own length.
     *
     * <p>The magnitude is the sine of the angle between the channel and the sight line, so this is
     * about one degree: above it the axis is well conditioned, and below it the point carries the
     * previous point's axis instead of swinging on noise.
     */
    private static final float CROSS_AXIS_FLOOR = 0.02F;
    /**
     * The V written for the patch of lit ground.
     *
     * <p>The channel's own V runs from 0 at the ground to 1 at the top, so the fragment program tells
     * the two passes apart by asking for a V above 1 - it uses 1.2. This is deliberately well clear
     * of that threshold, because a glow written at V exactly 1 would be shaded as channel: it would
     * wear the filaments and the travelling return stroke, which is a light on the ground flickering
     * with the threads of a bolt of air.
     */
    public static final float GROUND_GLOW_V = 1.6F;
    /** Sectors around the strike point for the ground glow. */
    private static final int GLOW_SECTORS = 10;
    private static final int GLOW_RINGS = 2;

    private LightningBoltShape() {
    }

    /** A finished bolt: direct vertex data, ready to be written to a quad buffer. */
    public static final class Mesh {
        public final float[] data;
        public final int vertexCount;

        Mesh(float[] data, int vertexCount) {
            this.data = data;
            this.vertexCount = vertexCount;
        }

        public int quadCount() {
            return vertexCount / 4;
        }
    }

    /**
     * Builds one strike.
     *
     * @param seed      the strike's own seed, so a bolt keeps its shape for its whole life
     * @param height    how far up the channel climbs, in blocks
     * @param coreWidth half-width of the hot core at its thickest, in blocks
     * @param haloWidth half-width of the glow pass at its thickest, in blocks
     * @param branches  how many forks to hang off the trunk
     * @param glowRadius radius of the ground glow, or zero for none
     * @param eyeX      camera position relative to the strike point, used to face the ribbons
     */
    public static Mesh build(long seed, float height, float coreWidth, float haloWidth, int branches,
                             float glowRadius, double eyeX, double eyeY, double eyeZ) {
        return build(LightningDischarge.GROUND, seed, height, coreWidth, haloWidth, branches, glowRadius,
                height, eyeX, eyeY, eyeZ);
    }

    /**
     * Builds one strike of a given discharge type.
     *
     * <p>A ground strike is the channel described above. An anvil crawler keeps that channel but at
     * the anvil throws several long tendrils out sideways, so the strike is a bolt that becomes a
     * spider of light across the cloud base. A cloud-to-cloud flash has no channel to the ground at
     * all: it is the tendrils alone, radiating in the cloud plane, and the caller offsets the whole
     * shape up into the cloud because a discharge that never reaches the ground has no ground point to
     * be built from.</p>
     *
     * @param crawlLength how far a crawler or cloud channel reaches, in blocks
     */
    public static Mesh build(LightningDischarge discharge, long seed, float height, float coreWidth,
                             float haloWidth, int branches, float glowRadius, float crawlLength,
                             double eyeX, double eyeY, double eyeZ) {
        if (discharge == null || discharge == LightningDischarge.GROUND) {
            return buildGround(seed, height, coreWidth, haloWidth, branches, glowRadius, eyeX, eyeY, eyeZ);
        }
        Random random = new Random(seed);
        Colour core = new Colour(1.00F, 0.97F, 0.92F, 1.00F);
        Colour halo = new Colour(0.46F, 0.56F, 1.00F, 0.34F);
        float length = Math.max(24.0F, crawlLength);
        int tendrils = discharge == LightningDischarge.ANVIL_CRAWLER ? CRAWLER_TENDRILS : CLOUD_CHANNEL_TENDRILS;
        int steps = crawlSteps(length);
        int crawlQuads = tendrils * steps * 2;

        if (discharge == LightningDischarge.ANVIL_CRAWLER) {
            // The channel is still there - a crawler is a ground strike - so the trunk is drawn as
            // usual and the tendrils leave it from the top third, where the anvil is.
            float[] px = new float[TRUNK_SEGMENTS + 1];
            float[] py = new float[TRUNK_SEGMENTS + 1];
            float[] pz = new float[TRUNK_SEGMENTS + 1];
            walk(random, height, px, py, pz);
            Writer writer = new Writer((TRUNK_SEGMENTS * 2 + crawlQuads) * 4);
            ribbon(writer, px, py, pz, 0, TRUNK_SEGMENTS, coreWidth, core, eyeX, eyeY, eyeZ, 0.0F, 1.0F, false);
            ribbon(writer, px, py, pz, 0, TRUNK_SEGMENTS, haloWidth, halo, eyeX, eyeY, eyeZ, 0.0F, 1.0F, false);
            int firstFork = TRUNK_SEGMENTS * 3 / 5;
            int forkSpan = Math.max(1, TRUNK_SEGMENTS - firstFork);
            for (int index = 0; index < tendrils; index++) {
                int fork = firstFork + random.nextInt(forkSpan);
                float from = fork / (float) TRUNK_SEGMENTS;
                float heading = random.nextFloat() * (float) (Math.PI * 2.0D);
                crawlTendril(writer, random, px[fork], py[fork], pz[fork], heading, length, steps,
                        coreWidth * 0.46F, haloWidth * 0.42F, core, halo, eyeX, eyeY, eyeZ, from, 1.0F, true);
            }
            return new Mesh(java.util.Arrays.copyOf(writer.data, writer.vertices * STRIDE), writer.vertices);
        }

        // CLOUD_TO_CLOUD: no trunk and no ground glow, just tendrils radiating in the cloud plane. The
        // caller has already lifted the shape into the cloud, so they are built around the origin.
        Writer writer = new Writer(crawlQuads * 4);
        for (int index = 0; index < tendrils; index++) {
            float heading = (float) (Math.PI * 2.0D * index / tendrils) + (random.nextFloat() - 0.5F) * 0.9F;
            crawlTendril(writer, random, 0.0F, 0.0F, 0.0F, heading, length, steps, coreWidth, haloWidth,
                    core, halo, eyeX, eyeY, eyeZ, 0.0F, 1.0F, false);
        }
        return new Mesh(java.util.Arrays.copyOf(writer.data, writer.vertices * STRIDE), writer.vertices);
    }

    /** The ground-reaching channel: the trunk, its forks, and the glow where it meets the ground. */
    private static Mesh buildGround(long seed, float height, float coreWidth, float haloWidth, int branches,
                                    float glowRadius, double eyeX, double eyeY, double eyeZ) {
        Random random = new Random(seed);
        float[] px = new float[TRUNK_SEGMENTS + 1];
        float[] py = new float[TRUNK_SEGMENTS + 1];
        float[] pz = new float[TRUNK_SEGMENTS + 1];
        walk(random, height, px, py, pz);

        // Trunk core and halo, then a fork per branch, then the glow: sized exactly, so the buffer is
        // written once with no growth and no garbage.
        int trunkQuads = TRUNK_SEGMENTS * 2;
        int branchQuads = branches * BRANCH_SEGMENTS * 2;
        int glowQuads = glowRadius > 0.0F ? GLOW_SECTORS * GLOW_RINGS : 0;
        Writer writer = new Writer((trunkQuads + branchQuads + glowQuads) * 4);

        Colour core = new Colour(1.00F, 0.97F, 0.92F, 1.00F);
        Colour halo = new Colour(0.46F, 0.56F, 1.00F, 0.34F);
        ribbon(writer, px, py, pz, 0, TRUNK_SEGMENTS, coreWidth, core, eyeX, eyeY, eyeZ, 0.0F, 1.0F, false);
        ribbon(writer, px, py, pz, 0, TRUNK_SEGMENTS, haloWidth, halo, eyeX, eyeY, eyeZ, 0.0F, 1.0F, false);

        for (int index = 0; index < branches; index++) {
            // Forks start high on the trunk and run downward-outward, which is the way a real branch
            // leaves its parent: the parent's tip is where the current is densest.
            int fork = 3 + random.nextInt(Math.max(1, TRUNK_SEGMENTS - 3));
            float from = fork / (float) TRUNK_SEGMENTS;
            float[] bx = new float[BRANCH_SEGMENTS + 1];
            float[] by = new float[BRANCH_SEGMENTS + 1];
            float[] bz = new float[BRANCH_SEGMENTS + 1];
            bx[0] = px[fork];
            by[0] = py[fork];
            bz[0] = pz[fork];
            float heading = random.nextFloat() * (float) (Math.PI * 2.0D);
            for (int step = 1; step <= BRANCH_SEGMENTS; step++) {
                heading += (random.nextFloat() - 0.5F) * 0.9F;
                // Branches fall away faster than they wander sideways, so they read as branches of the
                // channel rather than as a second bolt.
                bx[step] = bx[step - 1] + (float) Math.cos(heading) * (1.6F + random.nextFloat() * 1.8F);
                bz[step] = bz[step - 1] + (float) Math.sin(heading) * (1.6F + random.nextFloat() * 1.8F);
                by[step] = Math.max(0.0F, by[step - 1] - height / TRUNK_SEGMENTS * (0.55F + random.nextFloat() * 0.55F));
            }
            // A branch is thinner than its parent and runs out to a point.
            float branchCore = coreWidth * 0.52F;
            Colour branchColour = new Colour(core.red, core.green, core.blue, 0.82F);
            ribbon(writer, bx, by, bz, 0, BRANCH_SEGMENTS, branchCore, branchColour, eyeX, eyeY, eyeZ, from, 1.0F, true);
            ribbon(writer, bx, by, bz, 0, BRANCH_SEGMENTS, haloWidth * 0.46F, halo, eyeX, eyeY, eyeZ, from, 1.0F, true);
        }

        if (glowQuads > 0) {
            groundGlow(writer, glowRadius);
        }
        // Trimmed to exactly what was written, so the mesh's own length is the contract.
        return new Mesh(java.util.Arrays.copyOf(writer.data, writer.vertices * STRIDE), writer.vertices);
    }

    /** Steps one crawl tendril is drawn in: finer for a longer reach, capped so the buffer stays small. */
    private static int crawlSteps(float length) {
        return Math.max(4, Math.min(20, Math.round(length / CRAWL_STEP_SPAN)));
    }

    /**
     * Writes one crawl tendril: a mostly-horizontal walk that races out from its start, turning a few
     * tens of degrees a step and, for a crawler, drooping as it goes the way an anvil tendril does.
     * Built from the same ribbon primitive as the trunk, so it shares its taper, its per-point frames
     * and its continuity through a bend - a tendril is a channel too.
     */
    private static void crawlTendril(Writer writer, Random random, float x, float y, float z, float heading,
                                     float length, int steps, float coreWidth, float haloWidth,
                                     Colour core, Colour halo, double eyeX, double eyeY, double eyeZ,
                                     float vStart, float vEnd, boolean droop) {
        float[] px = new float[steps + 1];
        float[] py = new float[steps + 1];
        float[] pz = new float[steps + 1];
        px[0] = x;
        py[0] = y;
        pz[0] = z;
        float direction = heading;
        float reach = length / Math.max(1, steps);
        for (int step = 1; step <= steps; step++) {
            direction += (random.nextFloat() - 0.5F) * (float) Math.toRadians(CRAWL_TURN_DEGREES);
            float distance = reach * (0.55F + random.nextFloat() * 0.75F);
            px[step] = px[step - 1] + (float) Math.cos(direction) * distance;
            pz[step] = pz[step - 1] + (float) Math.sin(direction) * distance;
            float fall = droop
                    ? CRAWL_DROOP * (0.35F + random.nextFloat() * 0.65F)
                    : (random.nextFloat() - 0.5F) * 0.22F;
            py[step] = Math.max(0.0F, py[step - 1] + fall);
        }
        ribbon(writer, px, py, pz, 0, steps, coreWidth, core, eyeX, eyeY, eyeZ, vStart, vEnd, true);
        ribbon(writer, px, py, pz, 0, steps, haloWidth, halo, eyeX, eyeY, eyeZ, vStart, vEnd, true);
    }

    /**
     * The trunk's path: a random walk up the channel with a bounded turn per step.
     *
     * <p>An unbounded walk - which is what a naive bolt generator gives you - folds the channel back
     * on itself within eight steps and reads as a scribble. Holding the heading to a few tens of
     * degrees per step is what makes it a channel that leans and wanders.
     */
    private static void walk(Random random, float height, float[] px, float[] py, float[] pz) {
        float heading = random.nextFloat() * (float) (Math.PI * 2.0D);
        float x = 0.0F;
        float z = 0.0F;
        px[0] = 0.0F;
        py[0] = 0.0F;
        pz[0] = 0.0F;
        for (int step = 1; step <= TRUNK_SEGMENTS; step++) {
            float turn = (random.nextFloat() * 2.0F - 1.0F) * (float) Math.toRadians(MAX_TURN_DEGREES);
            heading += turn;
            float distance = MAX_STEP_WANDER * (0.45F + random.nextFloat() * 0.55F);
            x += (float) Math.cos(heading) * distance;
            z += (float) Math.sin(heading) * distance;
            px[step] = x;
            pz[step] = z;
            py[step] = height * step / TRUNK_SEGMENTS;
        }
    }

    /**
     * Writes one tapered ribbon through a run of a path.
     *
     * <p>The frame is built per path point rather than per segment, and that is the whole trick. A
     * point's axis is the channel's own direction there - the bisector of the two segments meeting at
     * it - crossed with the line of sight to that point, so the axis is perpendicular to the sight
     * line (its full width faces the eye) and perpendicular to the channel (the width is across the
     * channel, not along it). The width comes from the taper at that point, scaled by the miter factor
     * that keeps a bend from thinning the ribbon.
     *
     * <p>Because one axis and one width belong to the point, both quads meeting there compute the same
     * two corners and the ribbon is continuous: built per segment, the axis would be derived from two
     * different segment directions at the same joint, and every bend would open a crack into the
     * channel.
     *
     * <p>That cross product collapses when a point is seen along the channel's length - a strike's top
     * seen from the foot of the channel, which is where a player stands - and near the collapse the
     * axis swings sharply for a change of a fraction of a degree, so the ribbon would pinch and then
     * snap round. Below {@link #CROSS_AXIS_FLOOR} the previous point's axis is carried over instead:
     * still perpendicular to that sight line, so the width stays visible, and continuous, so the ribbon
     * keeps its twist rather than jumping.
     */
    private static void ribbon(Writer writer, float[] px, float[] py, float[] pz, int from, int to,
                               float halfWidth, Colour colour, double eyeX, double eyeY, double eyeZ,
                               float vStart, float vEnd, boolean branch) {
        int points = to - from + 1;
        float[] axis = new float[points * 3];
        float[] width = new float[points];
        float carriedX = 0.0F;
        float carriedY = 1.0F;
        float carriedZ = 0.0F;
        boolean carrying = false;

        for (int point = 0; point < points; point++) {
            int index = from + point;
            float along = (to - from) == 0 ? 0.0F : point / (float) (to - from);

            // The channel's direction at this point: the bisector of the segments meeting at it, so
            // that a corner is shared by both of them.
            float inX = 0.0F;
            float inY = 0.0F;
            float inZ = 0.0F;
            boolean hasIn = point > 0;
            if (hasIn) {
                inX = px[index] - px[index - 1];
                inY = py[index] - py[index - 1];
                inZ = pz[index] - pz[index - 1];
            }
            float outX = 0.0F;
            float outY = 0.0F;
            float outZ = 0.0F;
            boolean hasOut = point < points - 1;
            if (hasOut) {
                outX = px[index + 1] - px[index];
                outY = py[index + 1] - py[index];
                outZ = pz[index + 1] - pz[index];
            }
            float directionX = (hasIn ? inX : 0.0F) + (hasOut ? outX : 0.0F);
            float directionY = (hasIn ? inY : 0.0F) + (hasOut ? outY : 0.0F);
            float directionZ = (hasIn ? inZ : 0.0F) + (hasOut ? outZ : 0.0F);
            float direction = (float) Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
            if (direction < 1.0E-5F) {
                directionX = 0.0F;
                directionY = 1.0F;
                directionZ = 0.0F;
                direction = 1.0F;
            }
            directionX /= direction;
            directionY /= direction;
            directionZ /= direction;

            // The sight line to this point, not to a segment's midpoint: this is what makes the width
            // perpendicular to the eye direction exactly where the corner is.
            float sightX = (float) (eyeX - px[index]);
            float sightY = (float) (eyeY - py[index]);
            float sightZ = (float) (eyeZ - pz[index]);
            float sight = (float) Math.sqrt(sightX * sightX + sightY * sightY + sightZ * sightZ);
            if (sight < 1.0E-3F) {
                sightX = 0.0F;
                sightY = 1.0F;
                sightZ = 0.0F;
                sight = 1.0F;
            }
            sightX /= sight;
            sightY /= sight;
            sightZ /= sight;

            float axisX = directionY * sightZ - directionZ * sightY;
            float axisY = directionZ * sightX - directionX * sightZ;
            float axisZ = directionX * sightY - directionY * sightX;
            float magnitude = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
            if (magnitude < CROSS_AXIS_FLOOR) {
                if (carrying) {
                    axisX = carriedX;
                    axisY = carriedY;
                    axisZ = carriedZ;
                } else {
                    // No point has been written yet, so there is nothing to carry: the axis is taken
                    // from world up, which is perpendicular to the sight line except when looking
                    // straight up, and in that one case any axis at all will do.
                    axisX = sightZ;
                    axisY = 0.0F;
                    axisZ = -sightX;
                    float fallback = (float) Math.sqrt(axisX * axisX + axisZ * axisZ);
                    if (fallback < 1.0E-4F) {
                        axisX = 1.0F;
                        axisZ = 0.0F;
                        fallback = 1.0F;
                    }
                    axisX /= fallback;
                    axisZ /= fallback;
                }
            } else {
                axisX /= magnitude;
                axisY /= magnitude;
                axisZ /= magnitude;
            }
            carriedX = axisX;
            carriedY = axisY;
            carriedZ = axisZ;
            carrying = true;

            // A bend is cut square by a bisector axis, so the ribbon would thin by the cosine of half
            // the bend; the miter factor puts the width back. It is clamped, because at a hairpin the
            // corners would otherwise shoot off to infinity.
            float miter = 1.0F;
            if (hasIn && hasOut) {
                float inLength = (float) Math.sqrt(inX * inX + inY * inY + inZ * inZ);
                float outLength = (float) Math.sqrt(outX * outX + outY * outY + outZ * outZ);
                if (inLength > 1.0E-5F && outLength > 1.0E-5F) {
                    float straightness = (inX * outX + inY * outY + inZ * outZ) / (inLength * outLength);
                    miter = Math.min(1.6F, 2.0F / Math.max(0.5F, 1.0F + straightness));
                }
            }

            int offset = point * 3;
            axis[offset] = axisX;
            axis[offset + 1] = axisY;
            axis[offset + 2] = axisZ;
            width[point] = halfWidth * (branch ? branchTaper(along) : trunkTaper(along)) * miter;
        }

        for (int point = 0; point < points - 1; point++) {
            int indexA = from + point;
            int indexB = indexA + 1;
            int offsetA = point * 3;
            int offsetB = (point + 1) * 3;
            float alongA = point / (float) (to - from);
            float alongB = (point + 1) / (float) (to - from);
            float vA = vStart + (vEnd - vStart) * alongA;
            float vB = vStart + (vEnd - vStart) * alongB;

            float aPlusX = px[indexA] + axis[offsetA] * width[point];
            float aPlusY = Math.max(0.0F, py[indexA] + axis[offsetA + 1] * width[point]);
            float aPlusZ = pz[indexA] + axis[offsetA + 2] * width[point];
            float aMinusX = px[indexA] - axis[offsetA] * width[point];
            float aMinusY = Math.max(0.0F, py[indexA] - axis[offsetA + 1] * width[point]);
            float aMinusZ = pz[indexA] - axis[offsetA + 2] * width[point];
            float bPlusX = px[indexB] + axis[offsetB] * width[point + 1];
            float bPlusY = Math.max(0.0F, py[indexB] + axis[offsetB + 1] * width[point + 1]);
            float bPlusZ = pz[indexB] + axis[offsetB + 2] * width[point + 1];
            float bMinusX = px[indexB] - axis[offsetB] * width[point + 1];
            float bMinusY = Math.max(0.0F, py[indexB] - axis[offsetB + 1] * width[point + 1]);
            float bMinusZ = pz[indexB] - axis[offsetB + 2] * width[point + 1];

            writer.vertex(aPlusX, aPlusY, aPlusZ, 0.0F, vA, colour);
            writer.vertex(bPlusX, bPlusY, bPlusZ, 0.0F, vB, colour);
            writer.vertex(bMinusX, bMinusY, bMinusZ, 1.0F, vB, colour);
            writer.vertex(aMinusX, aMinusY, aMinusZ, 1.0F, vA, colour);
        }
    }

    /**
     * The trunk's width at a point along its run, as a fraction of its widest: full at the ground and
     * a little over half way up. Vanilla's ribbon is one constant width from one end to the other.
     */
    public static float trunkTaper(float along) {
        return 1.0F - 0.55F * clamp01(along);
    }

    /**
     * A branch's width at a point along its run. It leaves its parent at its own full width and runs
     * out to a point, which is what a real branch does rather than stopping in a blunt cut.
     */
    public static float branchTaper(float along) {
        return 1.0F - 0.92F * clamp01(along);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /**
     * The patch of ground the strike lights up: a disc whose U runs from the rim to the profile's own
     * axis, so the same cross-section profile that shades the channel's core shades this as a soft
     * radial falloff.
     *
     * <p>The U convention matters and is not free. The program treats U as a cross-section either side
     * of the channel's axis, brightest at 0.5 and dark at 0 and 1, so the strike point has to be
     * written as 0.5 and the rim as 0: written with U running from 0 at the centre to 1 at the rim,
     * which is the obvious thing to do, the disc comes out dark in the middle and brightest in a ring
     * at half its radius - a donut, not a strike lighting the ground it hit.
     */
    private static void groundGlow(Writer writer, float radius) {
        Colour glow = new Colour(0.90F, 0.93F, 1.00F, 0.46F);
        float y = 0.035F;
        for (int sector = 0; sector < GLOW_SECTORS; sector++) {
            double angleA = Math.PI * 2.0D * sector / GLOW_SECTORS;
            double angleB = Math.PI * 2.0D * (sector + 1) / GLOW_SECTORS;
            for (int ring = 0; ring < GLOW_RINGS; ring++) {
                float innerRadius = radius * ring / GLOW_RINGS;
                float outerRadius = radius * (ring + 1) / GLOW_RINGS;
                float uInner = 0.5F - 0.5F * ring / GLOW_RINGS;
                float uOuter = 0.5F - 0.5F * (ring + 1) / GLOW_RINGS;
                float ax = (float) Math.cos(angleA);
                float az = (float) Math.sin(angleA);
                float bx = (float) Math.cos(angleB);
                float bz = (float) Math.sin(angleB);
                writer.vertex(ax * innerRadius, y, az * innerRadius, uInner, GROUND_GLOW_V, glow);
                writer.vertex(bx * innerRadius, y, bz * innerRadius, uInner, GROUND_GLOW_V, glow);
                writer.vertex(bx * outerRadius, y, bz * outerRadius, uOuter, GROUND_GLOW_V, glow);
                writer.vertex(ax * outerRadius, y, az * outerRadius, uOuter, GROUND_GLOW_V, glow);
            }
        }
    }

    /** A vertex colour: the tint the shader shades from, and the alpha that tells core from halo. */
    public record Colour(float red, float green, float blue, float alpha) {
    }

    private static final class Writer {
        private final float[] data;
        private int vertices;

        private Writer(int vertexCapacity) {
            data = new float[vertexCapacity * STRIDE];
        }

        private void vertex(float x, float y, float z, float u, float v, Colour colour) {
            int offset = vertices * STRIDE;
            data[offset] = x;
            data[offset + 1] = y;
            data[offset + 2] = z;
            data[offset + 3] = u;
            data[offset + 4] = v;
            data[offset + 5] = colour.red();
            data[offset + 6] = colour.green();
            data[offset + 7] = colour.blue();
            data[offset + 8] = colour.alpha();
            vertices++;
        }
    }
}
