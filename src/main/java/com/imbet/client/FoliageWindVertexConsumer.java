package com.imbet.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

/**
 * Tags the vertices of a plant so the terrain shader can bend it.
 *
 * <p>There is no room for extra vertex data in the vanilla block format, and the shader runs long after
 * anything on the CPU could intervene, so the marker is smuggled into the vertex alpha. Alpha is free here:
 * the cutout layer is an alpha test, so its value only has to stay on the right side of the discard
 * threshold, and the shader puts it back to 1.0 for every vertex it recognises.
 *
 * <p>The marker carries two things, not one:
 *
 * <ul>
 *   <li>the plant's <b>class</b>, which says which end of the plant is rooted, and
 *   <li>the vertex's <b>height</b> up the model, quantised into the low bits.
 * </ul>
 *
 * <p>The height is the part that makes grass bend instead of slide. A vertex at the base of a plant has to
 * stay exactly where it is, and a vertex at the tip has to move the full distance; without a per-vertex
 * height the only thing a vertex shader can do is translate the whole plant sideways, which reads as the
 * blades sliding out of the ground.
 *
 * <p>The height comes from the vertex's own Y. Chunk meshes are baked with their vertices relative to the
 * section origin, so a vertex's Y is the block's row within the section plus however far up its own model it
 * sits - and the row is known here, because the block position being rendered is. Subtracting one from the
 * other leaves exactly the height up the plant, in blocks, with no guesswork about models or units.
 */
public final class FoliageWindVertexConsumer implements VertexConsumer {

    /**
     * The bottom of the marked band. Markers occupy {@code BASE .. BASE + CLASSES * LEVELS - 1}, which keeps
     * them above 200 - a range vanilla never emits for the alpha test, where the only values that occur are
     * 255 for opaque geometry and 0 for the transparent parts of a texture.
     */
    public static final int BASE = 208;

    /** How many steps of height a marker carries. Three bits: eight steps is about 14 cm on a one block plant. */
    public static final int LEVELS = 8;

    /** The level written for a vertex whose true height cannot be trusted - see {@link #levelFor}. */
    private static final int TIP = LEVELS - 1;

    private final VertexConsumer delegate;
    private final int foliageClass;
    private final int rowBase;

    /** The level of the vertex currently being built: set at addVertex, spent at setColor. */
    private int level;

    /**
     * @param delegate     the consumer the vertices are really going to
     * @param foliageClass one of {@link FoliageWindBlocks}' classes
     * @param rowBase      the section-relative Y of the bottom of the block being rendered, which is what
     *                     turns a vertex's section-relative Y into a height up its own plant
     */
    public FoliageWindVertexConsumer(VertexConsumer delegate, int foliageClass, int rowBase) {
        this.delegate = delegate;
        this.foliageClass = foliageClass;
        this.rowBase = rowBase;
    }

    /**
     * How far up the plant this vertex sits, in steps of {@code 1 / LEVELS}.
     *
     * <p>A height outside the block is not a plant rendered where plants are. The same renderer is also used
     * for blocks placed by an entity's pose - a falling block, a moving piston, the break overlay - and there
     * the Y has nothing to do with the block's own height. Measured heights that mean nothing would bend a
     * plant on noise, so every vertex is marked at the tip instead: the whole plant then moves as one body,
     * which is what this mod did before it knew any better, rather than something visibly broken.
     */
    private int levelFor(float y) {
        float fraction = y - rowBase;
        if (fraction < -0.001F || fraction > 1.001F) {
            return TIP;
        }
        return Math.round(Mth.clamp(fraction, 0.0F, 1.0F) * TIP);
    }

    /** The class in the high bits, the height in the low ones. */
    private int marker() {
        return BASE + (foliageClass - 1) * LEVELS + level;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        level = levelFor(y);
        delegate.addVertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        delegate.setColor(red, green, blue, alpha <= 0 ? alpha : marker());
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
        delegate.setNormal(normalX, normalY, normalZ);
        return this;
    }
}
