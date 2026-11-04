package com.imbet.village;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * The sawmill: a saw bed with a blade standing on it, which is what a villager claims to become a
 * lumberjack.
 *
 * <p>The blade is the whole difference between this and the builder's bench: a bench puts blocks back and
 * a mill takes a tree apart, and a village with a mill in it can turn a felled trunk into the planks its
 * own walls are made of instead of buying every plank off a player.
 *
 * <p>The shape is the model's own masses - a bed, a blade and four legs - so the block is not a cube you
 * cannot walk past.
 */
public class SawmillBlock extends WorkstationBlock {
    public static final MapCodec<SawmillBlock> CODEC = simpleCodec(SawmillBlock::new);

    private static final VoxelShape SILHOUETTE = Shapes.or(
            // The saw bed: what a log is laid on.
            Shapes.box(0.0D, 8.0D / 16.0D, 0.0D, 1.0D, 12.0D / 16.0D, 1.0D),
            // The blade, standing up through the bed.
            Shapes.box(7.0D / 16.0D, 12.0D / 16.0D, 6.0D / 16.0D, 9.0D / 16.0D, 1.0D, 15.0D / 16.0D),
            Shapes.box(1.0D / 16.0D, 0.0D, 1.0D / 16.0D, 3.0D / 16.0D, 8.0D / 16.0D, 3.0D / 16.0D),
            Shapes.box(13.0D / 16.0D, 0.0D, 1.0D / 16.0D, 15.0D / 16.0D, 8.0D / 16.0D, 3.0D / 16.0D),
            Shapes.box(1.0D / 16.0D, 0.0D, 13.0D / 16.0D, 3.0D / 16.0D, 8.0D / 16.0D, 15.0D / 16.0D),
            Shapes.box(13.0D / 16.0D, 0.0D, 13.0D / 16.0D, 15.0D / 16.0D, 8.0D / 16.0D, 15.0D / 16.0D));

    public SawmillBlock(Properties properties) {
        super(properties, SILHOUETTE, "Sawmill",
                "  A lumberjack villager claims this and fells the trees a tornado laid down,",
                "  stocking the chests with the logs and sawing what the village is short of into planks.");
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * What the mill has to say: the felled trees it can reach, what the village has to work with, and which
     * of the three ways a lumberjack can be idle this is - no tree, no chest, or no room in one.
     */
    @Override
    protected List<String> statusLines(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        return LumberjackGoal.status(serverLevel, pos);
    }
}
