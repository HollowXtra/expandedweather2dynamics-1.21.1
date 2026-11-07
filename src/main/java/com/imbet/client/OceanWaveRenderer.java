package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Arrays;

/**
 * Draws the ocean's displaced surface - the visible half of {@link OceanWaveField}.
 *
 * <p>What is drawn is one quad per cell of a lattice, lifted by the wave field and shaded by it: troughs
 * darken, crests brighten, and the very top of a crest whitens into foam. That shading is not decoration -
 * a displaced surface with a single flat colour is almost invisible, because the displacement only shows as a
 * silhouette; the legacy Weather2 renderer shaded each quad by its own wave height for the same reason.
 *
 * <p>The lattice is where the cost is, so it is cached. The expensive part is finding the water surface
 * under each column, which takes a heightmap lookup and a handful of block states, and it only changes when
 * the world does - not when the camera moves within a cell. So the surface Y, the light and the biome water
 * colour are cached per corner and rebuilt on a timer or when the camera crosses a cell boundary, and only
 * the wave heights and colours are recomputed per frame. Adjacent cells share corner samples, so the surface
 * is continuous across them rather than a mosaic of independent squares.
 *
 * <p>Five rules keep it from drawing nonsense:
 *
 * <ul>
 *   <li>A cell is only drawn when all four of its samples are water - or sand the swash can reach. A
 *   shoreline would otherwise get half a quad of water hanging over the beach, and a one-block puddle would
 *   get a wave taller than itself.
 *   <li>A sample's water must be the {@code water} block itself (or below a plant sitting in it). Ice, a
 *   waterlogged fence and a bubble column all fail that test, which is what stops the surface being drawn
 *   over frozen or obstructed water.
 *   <li>The swell stands up as it comes into shallow water and then breaks. A wave is cut back to the height
 *   its depth can hold - the depth-limited breaking limit - and the water it loses becomes whitewater. It is
 *   what puts a breaker line on a beach and leaves clean swell outside it, and because the limit scales with
 *   the wave's own height, a hurricane's surf zone is many blocks wide and a light chop barely wets the sand.
 *   <li>Water that has broken stays broken as it runs ashore, and the swash line advances and drains back
 *   with the waves, so the whitewater reads as arriving rather than as a painted band.
 *   <li>Land is met at block resolution. A cell is four blocks across, so leaving the rule above to whole
 *   cells drops the surface a full cell short of the sand. Cells that touch land are therefore refined to
 *   one block per sample, which puts the edge of the water where the water actually ends.
 * </ul>
 *
 * <p>Nothing here sizes itself from the storm beyond the sea state the field already carries. The wind does
 * that, through the field, and the field's amplitude is smoothed over time, so the sea builds - and its surf
 * with it - as a hurricane approaches rather than switching on.
 */
public final class OceanWaveRenderer {

    private static final ResourceLocation WATER_STILL = ResourceLocation.withDefaultNamespace("block/water_still");

    /**
     * A depth that marks water nothing can happen in: outside the probed band around the shoreline, so deep
     * that neither shoaling nor breaking nor a run-up reaches it.
     */
    private static final float DEEP = 64.0F;

    /** How far above the sand the film of a swash sits, so it does not fight the block it is lying on. */
    private static final float SWASH_FILM = 0.02F;

    /** How much of a climb each block across the sand counts as, so a level beach is run up like a gentle one. */
    private static final float SWASH_BEACH_SLOPE = 0.45F;

    /** The sea state the run-up is at its configured size at, in blocks of crest height. */
    private static final float RUN_UP_REFERENCE_AMPLITUDE = 0.9F;

    /** The smallest and largest run-up scale, so a pond does not wash and a monster swell does not flood. */
    private static final float RUN_UP_MIN_SCALE = 0.35F;
    private static final float RUN_UP_MAX_SCALE = 1.5F;

    /**
     * One step of a beach, in blocks: the top of a block of sand is a whole block above the water's own surface.
     *
     * <p>A shore is made of blocks, so the first block of sand past the water stands a full block above the
     * still-water line - and that is what the swash has to clear before any of it can lie on dry sand at all.
     * A run-up measured only from the still-water line is therefore a run-up that has to beat a whole block
     * before it draws anything, which is why this is a term in the reach rather than an accident of the
     * arithmetic: the block the water is standing against is climbed by the swell's own share of a step, and
     * the reach the knob selects is spent on the beach above it.
     */
    private static final float BEACH_STEP = 1.0F;

    /**
     * How many lattice cells wide the band of probes around land is, at most.
     *
     * <p>The band has to cover the whole depth the surf cares about, or a breaker line would fall on its edge
     * and the surface would step there; the cap keeps a freak amplitude setting from turning the shoreline
     * into a thousand probes.
     */
    private static final int MAX_SHOAL_RINGS = 8;

    /** The deepest the probe will ever walk, in blocks. */
    private static final int MAX_PROBE_DEPTH = 40;

    /**
     * The surface's render type.
     *
     * <p>It is the vanilla translucent program over the block atlas, which is what makes this read as water:
     * a biome tint in the vertex colour, the animated still-water sprite on the UVs, the real lightmap in the
     * vertex, and normal alpha blending. Depth testing is left on but depth writing is not - this is a skin
     * over water that is already in the depth buffer, and writing depth would let it occlude the particles
     * and weather drawn after it.
     *
     * <p>The polygon offset is what stops the shimmer. The wave rides a trough at zero displacement, which is
     * exactly the height of the vanilla water block's own top face, so the two surfaces are coplanar at every
     * trough and their depths tie within the depth buffer's precision - which is read as a stipple that crawls
     * as the camera moves. The offset pulls this surface a hair toward the eye in depth only, so it wins the tie
     * cleanly without moving the water a single block, and it is the standard cure for a skin drawn over the
     * surface it is skinning rather than a cosmetic fudge.
     */
    private static final RenderType WAVE_SURFACE = RenderType.create(
            ExpandedWeather2Dynamics.MODID + "_ocean_wave",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)
    );

    /** Cached per corner: the water surface's height, the depth under it, its packed light and its tint. */
    private static float[] surfaceY = new float[0];
    private static float[] cornerDepth = new float[0];
    private static int[] cornerLight = new int[0];
    private static int[] cornerTint = new int[0];

    /** Recomputed every frame: the displaced water height in world blocks, and the finished vertex colour. */
    private static float[] waveY = new float[0];
    private static int[] cornerColor = new int[0];

    /** The shoreline, refined to block resolution; rebuilt with the lattice. */
    private static ShoreCell[] shoreCells = new ShoreCell[0];
    private static int shoreCellCount;
    /** Coarse cell index -> index into {@link #shoreCells}, or -1 for open water. */
    private static int[] shoreLookup = new int[0];

    private static int side;
    private static int cell = 4;
    private static int originX;
    private static int originZ;
    private static ResourceKey<Level> dimension;
    private static long builtTick = Long.MIN_VALUE;
    private static boolean built;

    /** Reused so a rebuild of a few thousand columns does not allocate a position per column. */
    private static final BlockPos.MutableBlockPos SCRATCH_POS = new BlockPos.MutableBlockPos();

    private OceanWaveRenderer() {
    }

    /**
     * The surf knobs and the sea state they act on, read once per frame.
     *
     * <p>The whole of the run-up and of the surf's foam is behind {@code enabled}: with the surf off there is
     * no swash on the sand and no broken water, and the swell shoals down onto the beach in silence, which is
     * what the switch has always been documented to do.
     *
     * @param runUpReach how far above the still-water level the swash can ever reach at a full crest, in blocks
     */
    private record Surf(boolean enabled, float breakRatio, float shoalGain, float shoalDepth,
                        float runUpReach, float foam) {
    }

    /**
     * One cell of the shoreline, sampled once per block.
     *
     * <p>Refining only the cells that touch land keeps the extra cost where it is visible and leaves the open
     * sea on the cheap lattice. The samples are shared between neighbouring refined cells by position, so the
     * block-resolution edge is continuous across them, exactly as the coarse corners are.
     *
     * <p>Samples that are not water still carry what the swash needs to know about them: the top of the first
     * solid block, so a film can lie on the sand at the right height, and which water sample is nearest, so
     * that film pulses in step with the water it comes from.
     */
    private static final class ShoreCell {
        int steps = -1;
        int baseX;
        int baseZ;
        /** The still-water level of this cell's water, which is what the swash is measured against. */
        float plane;
        float[] surfaceY = new float[0];
        float[] depth = new float[0];
        float[] landTop = new float[0];
        int[] anchor = new int[0];
        int[] light = new int[0];
        int[] tint = new int[0];
        float[] waveY = new float[0];
        int[] color = new int[0];

        /** Sizes the arrays for a cell of this many blocks per side, reusing them when the size is unchanged. */
        void resize(int steps) {
            if (this.steps == steps) {
                return;
            }
            this.steps = steps;
            int samples = (steps + 1) * (steps + 1);
            surfaceY = new float[samples];
            depth = new float[samples];
            landTop = new float[samples];
            anchor = new int[samples];
            light = new int[samples];
            tint = new int[samples];
            waveY = new float[samples];
            color = new int[samples];
        }
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // Iris runs every level stage again for its shadow map; this belongs to the visible frame only.
        if (RenderingCompat.isRenderingShadowPass()) {
            return;
        }
        if (!Config.OCEAN_WAVES_ENABLED.get() || !OceanWaveField.enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        // A flat sea is not worth a single quad: with no swell there is nothing to displace.
        if (OceanWaveField.peakHeight() <= 0.0005F) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        float time = (level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0F;
        ensureLattice(level, camera);
        sampleFrame(camera, time);
        draw(minecraft, event.getPoseStack(), camera);
    }

    /**
     * Rebuilds the cached lattice when the world under it may have changed.
     *
     * <p>Three things invalidate it: the camera crossing a cell boundary (the lattice has to move with the
     * camera or the water would run out behind you), the dimension, and any change to the cell size or radius.
     * On top of those it is rebuilt every {@code latticeRefreshTicks} whether or not anything moved, which is
     * what catches water being added or removed under a stationary player.
     */
    private static void ensureLattice(ClientLevel level, Vec3 camera) {
        int requestedCell = Mth.clamp(Config.OCEAN_WAVES_CELL_SIZE.get(), 2, 16);
        int radius = Mth.clamp(Config.OCEAN_WAVES_RENDER_RADIUS_BLOCKS.get(), 16, 512);
        int cells = Math.max(2, Mth.ceil(radius / (float) requestedCell));
        int wantedSide = cells * 2 + 1;
        int wantedOriginX = Math.floorDiv(Mth.floor(camera.x), requestedCell) * requestedCell - cells * requestedCell;
        int wantedOriginZ = Math.floorDiv(Mth.floor(camera.z), requestedCell) * requestedCell - cells * requestedCell;
        long tick = level.getGameTime();

        boolean stale = !built
                || side != wantedSide
                || cell != requestedCell
                || originX != wantedOriginX
                || originZ != wantedOriginZ
                || !level.dimension().equals(dimension)
                || tick - builtTick >= Math.max(1, Config.OCEAN_WAVES_LATTICE_REFRESH_TICKS.get());
        if (!stale) {
            return;
        }

        side = wantedSide;
        cell = requestedCell;
        originX = wantedOriginX;
        originZ = wantedOriginZ;
        dimension = level.dimension();
        builtTick = tick;
        built = true;

        int corners = wantedSide * wantedSide;
        if (surfaceY.length < corners) {
            surfaceY = new float[corners];
            cornerDepth = new float[corners];
            cornerLight = new int[corners];
            cornerTint = new int[corners];
            waveY = new float[corners];
            cornerColor = new int[corners];
        }
        for (int iz = 0; iz < wantedSide; iz++) {
            for (int ix = 0; ix < wantedSide; ix++) {
                int index = iz * wantedSide + ix;
                int x = originX + ix * requestedCell;
                int z = originZ + iz * requestedCell;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                if (!findWater(level, x, top, z)) {
                    surfaceY[index] = Float.NaN;
                    continue;
                }
                BlockPos pos = SCRATCH_POS;
                FluidState fluid = level.getBlockState(pos).getFluidState();
                // The fluid's own height, not the block's: a flowing river surface is lower than the block
                // it sits in, and drawing it at the block top would float a sheet over running water.
                surfaceY[index] = pos.getY() + Mth.clamp(fluid.getHeight(level, pos), 0.0F, 1.0F);
                cornerLight[index] = LevelRenderer.getLightColor(level, pos.above());
                cornerTint[index] = BiomeColors.getAverageWaterColor(level, pos) & 0xFFFFFF;
            }
        }
        buildDepths(level);
        buildShoreCells(level);
    }

    /**
     * Finds the water surface in a column, from the top of the world down.
     *
     * <p>It starts at the topmost non-air block, which over open ocean is the top water block itself; where
     * there is seagrass, kelp or a lily pad the water is a block or two below it. It gives up at the first
     * solid block, so ice and a waterlogged solid stop the search rather than being mistaken for water.
     */
    private static boolean findWater(ClientLevel level, int x, int top, int z) {
        int minY = level.getMinBuildHeight();
        for (int dy = 0; dy < 4; dy++) {
            int y = top - dy;
            if (y <= minY) {
                return false;
            }
            SCRATCH_POS.set(x, y, z);
            BlockState state = level.getBlockState(SCRATCH_POS);
            if (state.is(Blocks.WATER)) {
                return true;
            }
            if (state.isSolidRender(level, SCRATCH_POS)) {
                return false;
            }
        }
        return false;
    }

    /**
     * The top of the first solid block in a column, or NaN where the search gives up.
     *
     * <p>This is what a swash film is laid on: the sand's own surface, not the top of whatever plant is
     * standing on it, and the reason a run-up can sit on a beach at the height a player can see.
     */
    private static float solidSurface(ClientLevel level, int x, int top, int z) {
        int minY = level.getMinBuildHeight();
        for (int dy = 0; dy < 4; dy++) {
            int y = top - dy;
            if (y <= minY) {
                return Float.NaN;
            }
            SCRATCH_POS.set(x, y, z);
            if (level.getBlockState(SCRATCH_POS).isSolidRender(level, SCRATCH_POS)) {
                return y + 1.0F;
            }
        }
        return Float.NaN;
    }

    /**
     * Measures the water's depth at every corner close enough to land for it to matter.
     *
     * <p>Both of the things the surface does near a shore are functions of the depth: a wave grows as the
     * water shallows and is cut back to the height the water can hold. The band the probes cover is therefore
     * sized from the depth either of those can care about - which is the breaking depth of the largest wave
     * the sea is running - so a breaker line can never fall on the band's edge, where the surface would step.
     * Every corner outside the band is deep water by construction and gets no probe at all.
     */
    private static void buildDepths(ClientLevel level) {
        int corners = side * side;
        float influence = influenceDepth();
        int cap = Mth.clamp((int) Math.ceil(influence) + 1, 2, MAX_PROBE_DEPTH);
        Arrays.fill(cornerDepth, 0, corners, DEEP);

        // The band starts as the land itself and is widened one cell at a time. Dilations over the mask cost
        // a handful of passes over the lattice rather than a neighbourhood scan per corner, and they reach
        // diagonally as well, which a per-corner neighbour test would have to spell out.
        //
        // Each ring is probed as it arrives - every corner once, when the ring first reaches it - and the
        // widening stops as soon as a whole ring comes back deeper than the waves can care about. How many
        // rings that takes is a property of the seabed and not of the lattice: a ring count worked out in
        // advance, from the influence depth over the cell size, is the shallow-water band of a bottom that
        // drops one whole cell per cell, and on the gentler bottom most shores actually have it gave up while
        // the water under it was still shallow enough to shoal and to break. Everything past the band is
        // drawn as open water, so a band that stops too early leaves the swell standing at its full height one
        // ring out from water the surf has cut back - a cliff standing in the sea all the way around a beach.
        boolean[] band = new boolean[corners];
        for (int i = 0; i < corners; i++) {
            band[i] = Float.isNaN(surfaceY[i]);
        }
        boolean[] next = new boolean[corners];
        for (int ring = 0; ring < MAX_SHOAL_RINGS; ring++) {
            System.arraycopy(band, 0, next, 0, corners);
            for (int iz = 0; iz < side; iz++) {
                for (int ix = 0; ix < side; ix++) {
                    int i = iz * side + ix;
                    if (band[i]) {
                        continue;
                    }
                    if ((ix > 0 && band[i - 1]) || (ix < side - 1 && band[i + 1])
                            || (iz > 0 && band[i - side]) || (iz < side - 1 && band[i + side])) {
                        next[i] = true;
                    }
                }
            }
            boolean shallow = false;
            for (int i = 0; i < corners; i++) {
                if (!next[i] || band[i]) {
                    continue;   // the land itself, or a corner an earlier ring already reached
                }
                band[i] = true;
                if (Float.isNaN(surfaceY[i])) {
                    continue;   // land: it holds no water to measure and stands in no wave
                }
                cornerDepth[i] = probeDepth(level, originX + (i % side) * cell, surfaceY[i],
                        originZ + (i / side) * cell, cap);
                shallow |= cornerDepth[i] < influence;
            }
            if (!shallow) {
                break;   // the band's own edge is water deep enough that nothing the waves do reaches it
            }
        }
    }

    /**
     * Refines every cell that holds both water and land to one sample per block.
     *
     * <p>Two jobs, and they need the same samples: the block-resolution edge that lets the water reach the
     * sand, and the block-resolution land next to it that a swash can run up. A cell of nothing but land is
     * left alone - there is no water in it either to be drawn or to run up out of.
     */
    private static void buildShoreCells(ClientLevel level) {
        int cells = side - 1;
        int cellSlots = cells * cells;
        if (shoreLookup.length < cellSlots) {
            shoreLookup = new int[cellSlots];
        }
        Arrays.fill(shoreLookup, 0, cellSlots, -1);
        shoreCellCount = 0;
        if (!Config.OCEAN_WAVES_SHORE_FIT.get()) {
            return;
        }
        int cap = Mth.clamp((int) Math.ceil(influenceDepth()) + 1, 2, MAX_PROBE_DEPTH);

        for (int iz = 0; iz < cells; iz++) {
            for (int ix = 0; ix < cells; ix++) {
                int minMin = iz * side + ix;
                boolean anyWater = !Float.isNaN(surfaceY[minMin]) || !Float.isNaN(surfaceY[minMin + 1])
                        || !Float.isNaN(surfaceY[minMin + side]) || !Float.isNaN(surfaceY[minMin + side + 1]);
                boolean anyLand = Float.isNaN(surfaceY[minMin]) || Float.isNaN(surfaceY[minMin + 1])
                        || Float.isNaN(surfaceY[minMin + side]) || Float.isNaN(surfaceY[minMin + side + 1]);
                if (!anyWater || !anyLand) {
                    continue;
                }
                ShoreCell shore = shoreCellCount < shoreCells.length ? shoreCells[shoreCellCount] : null;
                if (shore == null) {
                    shore = new ShoreCell();
                    if (shoreCellCount == shoreCells.length) {
                        shoreCells = Arrays.copyOf(shoreCells, Math.max(8, shoreCells.length * 2));
                    }
                    shoreCells[shoreCellCount] = shore;
                }
                shore.resize(cell);
                shore.baseX = originX + ix * cell;
                shore.baseZ = originZ + iz * cell;
                int samples = (cell + 1) * (cell + 1);
                Arrays.fill(shore.surfaceY, 0, samples, Float.NaN);
                Arrays.fill(shore.landTop, 0, samples, Float.NaN);
                Arrays.fill(shore.depth, 0, samples, DEEP);
                Arrays.fill(shore.anchor, 0, samples, -1);
                // The cell's own still-water level: the swash is measured up from the water it came out of.
                double planeSum = 0.0D;
                int planeCount = 0;
                int firstWater = -1;
                int firstTint = 0x3F76E4;
                for (int sz = 0; sz <= cell; sz++) {
                    for (int sx = 0; sx <= cell; sx++) {
                        int index = sz * (cell + 1) + sx;
                        int x = shore.baseX + sx;
                        int z = shore.baseZ + sz;
                        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                        if (findWater(level, x, top, z)) {
                            BlockPos pos = SCRATCH_POS;
                            FluidState fluid = level.getBlockState(pos).getFluidState();
                            shore.surfaceY[index] = pos.getY() + Mth.clamp(fluid.getHeight(level, pos), 0.0F, 1.0F);
                            shore.light[index] = LevelRenderer.getLightColor(level, pos.above());
                            shore.tint[index] = BiomeColors.getAverageWaterColor(level, pos) & 0xFFFFFF;
                            shore.depth[index] = probeDepth(level, x, shore.surfaceY[index], z, cap);
                            planeSum += shore.surfaceY[index];
                            planeCount++;
                            if (firstWater < 0) {
                                firstWater = index;
                                firstTint = shore.tint[index];
                            }
                        } else {
                            shore.landTop[index] = solidSurface(level, x, top, z);
                            shore.tint[index] = firstTint;
                        }
                    }
                }
                if (planeCount == 0) {
                    continue;   // no water in this cell after all: nothing to draw and nothing to run up out of
                }
                shore.plane = (float) (planeSum / planeCount);
                // Sand the swash could reach pulses with the water it came from, so every land sample keeps
                // the nearest water sample as its anchor rather than a phase of its own.
                for (int sz = 0; sz <= cell; sz++) {
                    for (int sx = 0; sx <= cell; sx++) {
                        int index = sz * (cell + 1) + sx;
                        if (Float.isNaN(shore.landTop[index])) {
                            continue;
                        }
                        int best = -1;
                        long bestDistance = Long.MAX_VALUE;
                        for (int az = 0; az <= cell; az++) {
                            for (int ax = 0; ax <= cell; ax++) {
                                int other = az * (cell + 1) + ax;
                                if (Float.isNaN(shore.surfaceY[other])) {
                                    continue;
                                }
                                long distance = (long) (ax - sx) * (ax - sx) + (long) (az - sz) * (az - sz);
                                if (distance < bestDistance) {
                                    bestDistance = distance;
                                    best = other;
                                }
                            }
                        }
                        if (best >= 0) {
                            shore.anchor[index] = best;
                            shore.tint[index] = shore.tint[best];
                            shore.light[index] = shore.light[best];
                        } else {
                            shore.landTop[index] = Float.NaN;
                        }
                    }
                }
                shoreLookup[iz * cells + ix] = shoreCellCount;
                shoreCellCount++;
            }
        }
    }

    /**
     * The depth at which the surface stops caring about the bottom, in blocks.
     *
     * <p>Two things are functions of the depth and each wants to be measured over its own range: the shoaling
     * of the swell, and the depth-limited breaking limit, which is the taller of the two because it is the
     * wave's own height over the break ratio. Measuring to their maximum is what keeps either from ending on
     * a step - there is no depth past this where the drawn surface differs from open water's.
     */
    private static float influenceDepth() {
        float influence = (float) Mth.clamp(Config.OCEAN_WAVES_SHOAL_DEPTH_BLOCKS.get(), 0.0D, 24.0D);
        if (Config.OCEAN_WAVES_SURF_ENABLED.get()) {
            float gain = (float) Mth.clamp(Config.OCEAN_WAVES_SURF_SHOAL_GAIN.get(), 1.0D, 2.0D);
            float ratio = (float) Mth.clamp(Config.OCEAN_WAVES_SURF_BREAK_RATIO.get(), 0.3D, 1.2D);
            influence = Math.max(influence, 2.0F * OceanWaveField.peakHeight() * gain / ratio);
        }
        return influence;
    }

    /**
     * Walks down from the water's surface while it is still water, up to {@code cap} blocks.
     *
     * <p>The floor's own height would answer this in a single lookup, but the client only receives the
     * world-surface and motion-blocking heightmaps - the ocean-floor one is kept on the server - so the depth
     * is measured a block at a time. The cap is what keeps a deep sea from being walked, and the probes only
     * happen inside the band around the shoreline, where the answer changes the shape of the surface.
     */
    private static float probeDepth(ClientLevel level, int x, float surface, int z, int cap) {
        int top = Mth.ceil(surface) - 1;
        int depth = 0;
        while (depth < cap) {
            SCRATCH_POS.set(x, top - depth, z);
            if (!level.getBlockState(SCRATCH_POS).is(Blocks.WATER)) {
                break;
            }
            depth++;
        }
        return depth;
    }

    /**
     * Shoaling without surf: full height at the configured depth and deeper, and none at all where the water
     * ends. The smoothstep is what keeps the last blocks of water from ending on a crease - the surface has to
     * lie down onto the beach, which is a curving profile rather than a straight ramp.
     */
    private static float shoalFactor(float depth, float shoalDepth) {
        float t = Mth.clamp(depth / Math.max(shoalDepth, 0.01F), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** The surf knobs and the sea state they act on. */
    private static Surf surfSettings() {
        boolean enabled = Config.OCEAN_WAVES_SURF_ENABLED.get();
        float amplitude = OceanWaveField.peakHeight();
        float swellShare = Mth.clamp(amplitude / RUN_UP_REFERENCE_AMPLITUDE, 0.0F, 1.0F);
        float scale = Mth.clamp(amplitude / RUN_UP_REFERENCE_AMPLITUDE, RUN_UP_MIN_SCALE, RUN_UP_MAX_SCALE);
        float runUp = (float) Mth.clamp(Config.OCEAN_WAVES_SURF_RUN_UP_BLOCKS.get(), 0.0D, 6.0D);
        // The step the sea can climb is its own share of a real swell, and the knob's reach is spent above it.
        // Both are behind the switch and behind the knob: surf off, or a run-up of nothing, is no swash at all
        // rather than a swash on the one row of sand the swash always used to wet whatever the settings said.
        // The run-up is the swell's own: a calm sea barely wets the sand, and a storm sea runs well up the beach. It used to
        // carry a floor, so even still water laid a band of white film several blocks up every beach.
        float reach = enabled && runUp > 0.0F ? BEACH_STEP * swellShare + runUp * scale * swellShare : 0.0F;
        float foam = enabled ? (float) Mth.clamp(Config.OCEAN_WAVES_SURF_FOAM.get(), 0.0D, 1.0D) : 0.0F;
        return new Surf(
                enabled,
                (float) Mth.clamp(Config.OCEAN_WAVES_SURF_BREAK_RATIO.get(), 0.3D, 1.2D),
                (float) Mth.clamp(Config.OCEAN_WAVES_SURF_SHOAL_GAIN.get(), 1.0D, 2.0D),
                (float) Mth.clamp(Config.OCEAN_WAVES_SHOAL_DEPTH_BLOCKS.get(), 0.0D, 24.0D),
                reach,
                foam);
    }

    /**
     * Fills the per-sample wave heights and vertex colours for this frame.
     *
     * <p>The colour carries several things at once, because the vanilla translucent program multiplies them
     * and there is only one vertex colour to work with: the biome's water tint, a shade that darkens troughs
     * and brightens crests, and foam. The alpha carries the distance fade, so the lattice's edge dissolves
     * instead of ending on a hard square, and on the sand it carries how much of the swash has arrived.
     *
     * <p>Both grids - the lattice and every refined shoreline cell - are sampled through here, so a cut-back
     * crest flattens its shading along with its height. Broken water should not still be rocking with the
     * wave that broke, and that is the surest way to make a surf zone look painted on.
     */
    private static void sampleFrame(Vec3 camera, float time) {
        float opacity = Mth.clamp(Config.OCEAN_WAVES_SURFACE_OPACITY.get().floatValue(), 0.0F, 1.0F);
        float foamThreshold = Mth.clamp(Config.OCEAN_WAVES_FOAM_THRESHOLD.get().floatValue(), 0.5F, 1.0F);
        float foamStrength = Mth.clamp(Config.OCEAN_WAVES_FOAM_STRENGTH.get().floatValue(), 0.0F, 1.0F);
        float radius = Math.max(1.0F, Config.OCEAN_WAVES_RENDER_RADIUS_BLOCKS.get());
        // The last third of the radius is the fade band.
        float fadeStart = radius * 0.66F;
        float fadeSpan = Math.max(1.0F, radius - fadeStart);
        Surf surf = surfSettings();
        // Whitecaps are the wind breaking the crests, so they need wind: none on a calm sea, starting at a moderate
        // breeze and full in a gale. They used to come from the crest's height alone, so still water was flecked white.
        float maxAmplitude = Math.max(0.1F, Config.OCEAN_WAVES_MAX_AMPLITUDE_BLOCKS.get().floatValue());
        float seaState = Mth.clamp((OceanWaveField.peakHeight() / maxAmplitude - 0.22F) / 0.40F, 0.0F, 1.0F);
        foamStrength *= seaState * seaState * (3.0F - 2.0F * seaState);

        for (int i = 0; i < shoreCellCount; i++) {
            ShoreCell shore = shoreCells[i];
            sampleGrid(shore.surfaceY, shore.depth, shore.landTop, shore.anchor, shore.tint, shore.waveY,
                    shore.color, shore.plane, shore.baseX, shore.baseZ, 1, shore.steps + 1,
                    camera, time, surf, opacity, foamThreshold, foamStrength, fadeStart, fadeSpan, radius);
        }
        sampleGrid(surfaceY, cornerDepth, null, null, cornerTint, waveY, cornerColor, 0.0F,
                originX, originZ, cell, side,
                camera, time, surf, opacity, foamThreshold, foamStrength, fadeStart, fadeSpan, radius);
    }

    /** One grid of samples, `count` squared of them, `step` blocks apart. */
    private static void sampleGrid(float[] gridSurfaceY, float[] gridDepth, float[] gridLandTop, int[] gridAnchor,
                                   int[] gridTint, float[] gridWaveY, int[] gridColor, float gridPlane,
                                   int gridOriginX, int gridOriginZ, int step, int count,
                                   Vec3 camera, float time, Surf surf, float opacity, float foamThreshold,
                                   float foamStrength, float fadeStart, float fadeSpan, float radius) {
        for (int iz = 0; iz < count; iz++) {
            double worldZ = gridOriginZ + (double) iz * step;
            for (int ix = 0; ix < count; ix++) {
                int index = iz * count + ix;
                double worldX = gridOriginX + (double) ix * step;
                double dx = worldX - camera.x;
                double dz = worldZ - camera.z;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) {
                    gridWaveY[index] = Float.NaN;
                    gridColor[index] = 0;
                    continue;
                }
                float fade = distance <= fadeStart ? 1.0F : 1.0F - Mth.clamp((distance - fadeStart) / fadeSpan, 0.0F, 1.0F);
                int alpha = Math.round(opacity * fade * 255.0F);

                if (!Float.isNaN(gridSurfaceY[index])) {
                    OceanWaveField.Surface sample = OceanWaveField.surfaceAt(worldX, worldZ, time);
                    float depth = gridDepth[index];
                    float drawn = sample.height();
                    float clip = 1.0F;
                    float surfFoam = 0.0F;
                    if (surf.enabled() && depth < DEEP) {
                        // Green's law: a wave stands up as the water under it shallows.
                        float gain = Mth.clamp((float) Math.pow(surf.shoalDepth() / Math.max(depth, 0.75F), 0.25D),
                                1.0F, surf.shoalGain());
                        float standing = sample.height() * gain;
                        // Depth-limited breaking: the water can only hold a wave so tall, and the height the
                        // crest loses is the height that becomes whitewater.
                        float allowed = 0.5F * surf.breakRatio() * depth;
                        drawn = Math.min(standing, allowed);
                        clip = standing > 1.0E-4F ? drawn / standing : 1.0F;
                        float breakerDepth = 2.0F * OceanWaveField.peakHeight() * gain / surf.breakRatio();
                        // Water that has broken stays broken on its way in, so the wash is thickest close to the
                        // sand and thinner but still milky over by the break line, where the crest itself is
                        // the brightest thing in it and is whitened by the clipping term below. The gate is what
                        // keeps the break line an edge: outside it the water is clean swell, not a fade-out.
                        float whitewater = depth >= breakerDepth ? 0.0F
                                : 0.9F * Mth.clamp(1.0F - 0.55F * depth / breakerDepth, 0.0F, 1.0F);
                        float breaking = standing > allowed
                                ? Mth.clamp((standing - allowed) / Math.max(0.35F * standing, 0.05F), 0.0F, 1.0F) : 0.0F;
                        surfFoam = surf.foam() * Math.max(breaking, whitewater);
                    } else if (!surf.enabled()) {
                        clip = depth < DEEP ? shoalFactor(depth, surf.shoalDepth()) : 1.0F;
                        drawn = sample.height() * clip;
                    }
                    if (alpha <= 2) {
                        gridColor[index] = 0;
                    } else {
                        float crestFoam = sample.crest() <= foamThreshold ? 0.0F
                                : Mth.clamp((sample.crest() - foamThreshold) / Math.max(0.001F, 1.0F - foamThreshold), 0.0F, 1.0F)
                                * foamStrength;
                        // Toward the edge the shading settles to flat water as well as the alpha fading, so the
                        // surface meets the plain sea beyond it without a line.
                        float shadeCrest = 0.5F + (sample.crest() - 0.5F) * clip * fade;
                        gridColor[index] = colorFor(gridTint[index], shadeCrest, Math.max(crestFoam, surfFoam) * fade, alpha);
                    }
                    // And it lies down onto the still water: drawn at full height out to the edge, it ended on a step.
                    gridWaveY[index] = gridSurfaceY[index] + drawn * fade;
                    continue;
                }

                // Not water: sand the swash may run up, or nothing at all.
                if (gridLandTop == null || gridAnchor == null || Float.isNaN(gridLandTop[index])
                        || gridAnchor[index] < 0) {
                    gridWaveY[index] = Float.NaN;
                    gridColor[index] = 0;
                    continue;
                }
                float above = gridLandTop[index] - gridPlane;
                if (above <= 0.0F || above > surf.runUpReach()) {
                    // Ground under the still-water line, or above anything the swash could ever reach. Neither
                    // is sand for a run-up to lie on: a film on ground below the waterline is whitewater
                    // painted on the seabed, which the water over it already covers, and a sample out of reach
                    // would smear the wash up a step. Both are dropped, so the quads into them go with them.
                    gridWaveY[index] = Float.NaN;
                    gridColor[index] = 0;
                    continue;
                }
                int anchor = gridAnchor[index];
                double anchorX = gridOriginX + (double) (anchor % count) * step;
                double anchorZ = gridOriginZ + (double) (anchor / count) * step;
                float phase = OceanWaveField.surfaceAt(anchorX, anchorZ, time).crest();
                // How far the swash has to climb to get here: up, and across. Most beaches in the game are level, a
                // block over the water, and measured only upward every sample of one is the same climb - the wash lay
                // over the whole beach at once as a flat slab with a ruler-straight edge. Across the sand counts as a
                // gentle slope, so the wash thins out and stops a few blocks in, wherever the beach is.
                double sampleX = gridOriginX + (double) (index % count) * step;
                double sampleZ = gridOriginZ + (double) (index / count) * step;
                float across = (float) Math.hypot(sampleX - anchorX, sampleZ - anchorZ);
                float climb = above + across * SWASH_BEACH_SLOPE;
                // The waterline advances with each crest and drains back between them, and no two stretches of it
                // arrive together: each bit of shore runs up on its own part of the wave train, which is what makes
                // the edge ragged. The profile turns that into a waterline rather than a fade: the swash is a single
                // depth that has run a certain way up the sand, so it wets everything below where it got to and
                // nothing above it.
                float along = shoreNoise(sampleX, sampleZ, time);
                float reach = surf.runUpReach() * (0.3F + 0.7F * phase) * (0.60F + 0.65F * along);
                float coverage = Mth.clamp((reach - climb) / Math.max(0.6F * climb, 0.25F), 0.0F, 1.0F);
                // Sand is only sampled inside the lattice cells that hold water, so the wash cannot run past the edge of
                // its cell. It thins out across the cell instead, so where that edge does cut it, little is left to cut.
                float acrossShare = Mth.clamp(across / Math.max(1.0F, cell * 0.8F), 0.0F, 1.0F);
                coverage *= 1.0F - acrossShare * acrossShare * (3.0F - 2.0F * acrossShare);
                gridWaveY[index] = gridLandTop[index] + SWASH_FILM;
                // A swash is a thin sheet of water running up the sand with a line of foam along its leading edge, not a
                // white sheet: clear and wet behind the edge, foamy at it. It used to be drawn at least half white all
                // the way up, which laid a grey slab along every beach.
                int swashAlpha = Math.round(alpha * coverage * 0.62F);
                float foamLine = 4.0F * coverage * (1.0F - coverage);
                gridColor[index] = swashAlpha <= 2 ? 0
                        : colorFor(gridTint[index], 0.55F, surf.foam() * (0.10F + 0.70F * foamLine), swashAlpha);
            }
        }
    }

    /**
     * How far up the sand this stretch of shore's swash gets, from 0 to 1: a smooth value noise a few blocks across that
     * drifts slowly, so neighbouring stretches arrive and drain at different moments. The wave field cannot give this -
     * where its crests run parallel to the shore, every point along it is at the same phase.
     */
    private static float shoreNoise(double x, double z, float time) {
        double u = x / 4.5D + time * 0.07D;
        double v = z / 4.5D - time * 0.05D;
        int ix = Mth.floor(u);
        int iz = Mth.floor(v);
        float fx = (float) (u - ix);
        float fz = (float) (v - iz);
        fx = fx * fx * (3.0F - 2.0F * fx);
        fz = fz * fz * (3.0F - 2.0F * fz);
        return Mth.lerp(fz, Mth.lerp(fx, latticeHash(ix, iz), latticeHash(ix + 1, iz)),
                Mth.lerp(fx, latticeHash(ix, iz + 1), latticeHash(ix + 1, iz + 1)));
    }

    private static float latticeHash(int x, int z) {
        long h = x * 73856093L ^ z * 19349663L;
        h = (h ^ (h >>> 13)) * 0x5BD1E995L;
        h ^= h >>> 15;
        return (h >>> 8 & 0xFFFFL) / 65535.0F;
    }

    /** One vertex colour: the biome tint, shaded by the wave phase, with the given amount of foam. */
    private static int colorFor(int tint, float crest, float foam, int alpha) {
        float shade = 0.62F + 0.64F * crest;
        float white = Mth.clamp(foam, 0.0F, 1.0F);
        int r = Math.round(((tint >> 16 & 255) * shade) * (1.0F - white) + 255.0F * white);
        int g = Math.round(((tint >> 8 & 255) * shade) * (1.0F - white) + 255.0F * white);
        int b = Math.round(((tint & 255) * shade) * (1.0F - white) + 255.0F * white);
        return alpha << 24 | Mth.clamp(r, 0, 255) << 16 | Mth.clamp(g, 0, 255) << 8 | Mth.clamp(b, 0, 255);
    }

    private static void draw(Minecraft minecraft, PoseStack poseStack, Vec3 camera) {
        TextureAtlasSprite sprite = minecraft.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER_STILL);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(WAVE_SURFACE);
        PoseStack.Pose pose = poseStack.last();
        float radius = Math.max(1.0F, Config.OCEAN_WAVES_RENDER_RADIUS_BLOCKS.get());

        for (int i = 0; i < shoreCellCount; i++) {
            ShoreCell shore = shoreCells[i];
            drawGrid(consumer, pose, sprite, shore.waveY, shore.color, shore.light,
                    shore.baseX, shore.baseZ, 1, shore.steps + 1, camera, radius, null, 0);
        }
        // The lattice skips the refined cells, so the shoreline is drawn once and only at block resolution.
        int cells = side - 1;
        drawGrid(consumer, pose, sprite, waveY, cornerColor, cornerLight,
                originX, originZ, cell, side, camera, radius, shoreLookup, cells);
        buffers.endBatch(WAVE_SURFACE);
    }

    /**
     * Draws one grid of samples as quads, skipping any cell {@code skip} marks as handled elsewhere.
     *
     * <p>A quad is emitted only when all four of its samples are drawable and at least one of them is visible:
     * a corner that is neither water nor sand the swash reaches means the quad would hang over the beach or
     * smear up a step, and a fully faded quad is not worth a vertex. The cell's centre is what the radius test
     * uses, so a cell is all in or all out.
     */
    private static void drawGrid(VertexConsumer consumer, PoseStack.Pose pose, TextureAtlasSprite sprite,
                                 float[] gridWaveY, int[] gridColor, int[] gridLight,
                                 int gridOriginX, int gridOriginZ, int step, int count,
                                 Vec3 camera, float radius, int[] skip, int skipStride) {
        float camX = (float) camera.x;
        float camY = (float) camera.y;
        float camZ = (float) camera.z;
        float radiusSqr = radius * radius;

        for (int iz = 0; iz < count - 1; iz++) {
            for (int ix = 0; ix < count - 1; ix++) {
                if (skip != null && skip[iz * skipStride + ix] >= 0) {
                    continue;
                }
                int minMin = iz * count + ix;
                int maxMin = minMin + 1;
                int minMax = minMin + count;
                int maxMax = minMax + 1;
                // The shoreline rule: one sample that cannot be drawn and the whole cell is left out, so no
                // quad hangs over a beach and no puddle gets a wave it cannot hold.
                if (Float.isNaN(gridWaveY[minMin]) || Float.isNaN(gridWaveY[maxMin])
                        || Float.isNaN(gridWaveY[minMax]) || Float.isNaN(gridWaveY[maxMax])) {
                    continue;
                }
                if (gridColor[minMin] == 0 && gridColor[maxMin] == 0
                        && gridColor[minMax] == 0 && gridColor[maxMax] == 0) {
                    continue;
                }
                float x0 = gridOriginX + ix * step;
                float x1 = x0 + step;
                float z0 = gridOriginZ + iz * step;
                float z1 = z0 + step;
                double centerX = (x0 + x1) * 0.5D - camX;
                double centerZ = (z0 + z1) * 0.5D - camZ;
                if (centerX * centerX + centerZ * centerZ > radiusSqr) {
                    continue;
                }
                // A different sub-rectangle of the still-water sprite per cell, kept strictly inside the
                // sprite so it can never sample a neighbouring one in the atlas. A cell spans whole blocks,
                // so its four corners would otherwise land on the same texel - the tile has to be stretched
                // across the cell - and without the per-cell offset every cell would stretch the identical
                // image and the sea would read as a grid. The drift of the surface comes from the sprite's
                // own animation and from the wave displacement, not from scrolling these UVs.
                //
                // The window is keyed to the cell's own place in the world and not to its slot in the
                // lattice. The lattice is rebuilt the moment the camera crosses a cell, and a window keyed to
                // the slot moves with it: every cell of the sea would show a different part of the sprite from
                // one step to the next, so the whole surface's texture would pop forward in step with nothing
                // a player can see. Keyed to the world, the same patch of sea draws the same window wherever
                // the lattice happens to be standing.
                int worldCellX = gridOriginX / step + ix;
                int worldCellZ = gridOriginZ / step + iz;
                float offsetU = 0.5F * hash(worldCellX, worldCellZ);
                float offsetV = 0.5F * hash(worldCellZ, worldCellX);
                float u0 = sprite.getU(offsetU);
                float u1 = sprite.getU(1.0F);
                float v0 = sprite.getV(offsetV);
                float v1 = sprite.getV(1.0F);
                vertex(consumer, pose, x0 - camX, gridWaveY[minMin] - camY, z0 - camZ, u0, v0, gridLight[minMin], gridColor[minMin]);
                vertex(consumer, pose, x1 - camX, gridWaveY[maxMin] - camY, z0 - camZ, u1, v0, gridLight[maxMin], gridColor[maxMin]);
                vertex(consumer, pose, x1 - camX, gridWaveY[maxMax] - camY, z1 - camZ, u1, v1, gridLight[maxMax], gridColor[maxMax]);
                vertex(consumer, pose, x0 - camX, gridWaveY[minMax] - camY, z1 - camZ, u0, v1, gridLight[minMax], gridColor[minMax]);
            }
        }
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, int light, int color) {
        consumer.addVertex(pose, x, y, z)
                .setColor(color >>> 16 & 255, color >>> 8 & 255, color & 255, color >>> 24 & 255)
                .setUv(u, v)
                .setLight(light)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    /** A stable per-cell value in [0,1), keyed to the world so the window a cell shows never changes at all. */
    private static float hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ h >>> 13) * 1274126177;
        return ((h ^ h >>> 16) & 0xFFFF) / 65536.0F;
    }
}
