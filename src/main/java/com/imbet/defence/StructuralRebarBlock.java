package com.imbet.defence;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A rod of steel driven into what a storm would otherwise take.
 *
 * <p>It does nothing on its own and it is not meant to be looked at. Placed against the bottom of a wall it anchors
 * the wall, the ground beside it and the blocks above them (see {@link RebarAnchors} for the reach, and
 * {@link Fortification} for how the rest of the mod is told about it), and what that buys is the one thing a player
 * cannot otherwise build against: a base whose roof stays on.
 *
 * <p>It deliberately keeps no state. Whether a rod is planted is a question about the block under it, and a rod
 * that remembered "planted" at the moment it was placed would still be claiming it after the ground was dug out
 * beneath it. Every answer is computed from the world when it is asked, which is a handful of block reads behind
 * the same per-tick sample cache the damage passes already use.
 *
 * <p>The shape is the rods themselves: four of them on a square footprint, so a rebar is something you can walk
 * through and a wall of them is something you can see through, which is what reinforcement looks like.
 */
public class StructuralRebarBlock extends Block {
    public static final MapCodec<StructuralRebarBlock> CODEC = simpleCodec(StructuralRebarBlock::new);

    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(1.5D / 16.0D, 0.0D, 1.5D / 16.0D, 3.5D / 16.0D, 1.0D, 3.5D / 16.0D),
            Shapes.box(12.5D / 16.0D, 0.0D, 1.5D / 16.0D, 14.5D / 16.0D, 1.0D, 3.5D / 16.0D),
            Shapes.box(1.5D / 16.0D, 0.0D, 12.5D / 16.0D, 3.5D / 16.0D, 1.0D, 14.5D / 16.0D),
            Shapes.box(12.5D / 16.0D, 0.0D, 12.5D / 16.0D, 14.5D / 16.0D, 1.0D, 14.5D / 16.0D),
            // The two hoops that tie the rods together. They are why a rebar reads as reinforcement rather than as
            // four posts, and they are what a player actually sees at eye level.
            Shapes.box(1.5D / 16.0D, 4.0D / 16.0D, 1.5D / 16.0D, 14.5D / 16.0D, 5.0D / 16.0D, 3.5D / 16.0D),
            Shapes.box(1.5D / 16.0D, 4.0D / 16.0D, 12.5D / 16.0D, 14.5D / 16.0D, 5.0D / 16.0D, 14.5D / 16.0D),
            Shapes.box(1.5D / 16.0D, 11.0D / 16.0D, 1.5D / 16.0D, 3.5D / 16.0D, 12.0D / 16.0D, 14.5D / 16.0D),
            Shapes.box(12.5D / 16.0D, 11.0D / 16.0D, 1.5D / 16.0D, 14.5D / 16.0D, 12.0D / 16.0D, 14.5D / 16.0D));

    public StructuralRebarBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SILHOUETTE;
    }
}
