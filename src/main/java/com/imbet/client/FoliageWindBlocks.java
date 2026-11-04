package com.imbet.client;

import com.imbet.Config;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Which blocks the wind moves, and how each one is rooted.
 *
 * <p>The class is not just an on-off flag: it becomes the upper bits of the marker the vertex shader
 * reads in {@code Color.a}, and it tells the shader which end of the plant is anchored. Grass pivots at its
 * base, a vine hangs from the block above it, and a leaf block is neither - it is a cube, so it drifts as a
 * body rather than swinging off the ground.
 */
public final class FoliageWindBlocks {

    public static final int NONE = 0;
    /** A cube of leaves. Sways as a body, with only a little give across its height. */
    public static final int LEAVES = 1;
    /** A plant one block tall, rooted at the bottom of its block. */
    public static final int PLANT = 2;
    /** The lower block of a plant two blocks tall. */
    public static final int PLANT_LOWER = 3;
    /** The upper block of a plant two blocks tall, which continues the lower block's bend. */
    public static final int PLANT_UPPER = 4;
    /** A plant hanging from the block above - free at the bottom, anchored at the top. */
    public static final int HANGING = 5;
    /** The number of classes; the shader's constants have to match this list in order. */
    public static final int CLASSES = 5;

    private FoliageWindBlocks() {
    }

    public static int classFor(BlockState state) {
        if (state == null || !Config.VISUALS_ENABLED.get() || !Config.FOLIAGE_WIND_SHADER.get()) {
            return NONE;
        }
        if (Config.FOLIAGE_WIND_LEAVES.get() && state.is(BlockTags.LEAVES)) {
            return LEAVES;
        }
        if (PhysicsModFoliageBridge.physicsOwnsBlock(state)) {
            return NONE;
        }
        if (!Config.FOLIAGE_WIND_GRASS.get() || state.is(BlockTags.CROPS)) {
            return NONE;
        }
        // Two-block plants: the halves have to be told apart, because the bend runs continuously through
        // the join. Marking the upper half as an ordinary plant would make it hinge at its own base, which
        // is exactly the seam a player watching tall grass can see.
        if (state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN)) {
            return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                    ? PLANT_UPPER : PLANT_LOWER;
        }
        if (isHanging(state)) {
            return HANGING;
        }
        if (state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.DEAD_BUSH)) {
            return PLANT;
        }
        return NONE;
    }

    /**
     * Whether the block hangs from what is above it rather than standing on what is below.
     *
     * <p>It decides which end of the plant stays put, so it is the difference between a vine that swings
     * from its anchor and one that appears to be growing up out of thin air.
     */
    private static boolean isHanging(BlockState state) {
        return state.is(Blocks.VINE)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.HANGING_ROOTS);
    }
}
