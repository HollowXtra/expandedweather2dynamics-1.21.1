package com.imbet.client;

import com.imbet.Config;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class FoliageWindBlocks {
    public static final int NONE = 0;
    public static final int LEAVES = 254;
    public static final int GRASS = 253;

    private FoliageWindBlocks() {
    }

    public static int markerFor(BlockState state) {
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
        if (state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.VINE)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.HANGING_ROOTS)) {
            return GRASS;
        }
        return NONE;
    }
}
