package com.imbet.village;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/** The builders' bench: a workbench on four legs, and the post a builder villager claims. */
public class BuildersBenchBlock extends WorkstationBlock {
    public static final MapCodec<BuildersBenchBlock> CODEC = simpleCodec(BuildersBenchBlock::new);

    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 12.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            // The tool board at the back, so the collision reaches as low as the board does.
            Shapes.box(0.0D, 4.0D / 16.0D, 15.0D / 16.0D, 1.0D, 1.0D, 1.0D),
            Shapes.box(1.0D / 16.0D, 0.0D, 1.0D / 16.0D, 3.0D / 16.0D, 12.0D / 16.0D, 3.0D / 16.0D),
            Shapes.box(13.0D / 16.0D, 0.0D, 1.0D / 16.0D, 15.0D / 16.0D, 12.0D / 16.0D, 3.0D / 16.0D),
            Shapes.box(1.0D / 16.0D, 0.0D, 13.0D / 16.0D, 3.0D / 16.0D, 12.0D / 16.0D, 15.0D / 16.0D),
            Shapes.box(13.0D / 16.0D, 0.0D, 13.0D / 16.0D, 15.0D / 16.0D, 12.0D / 16.0D, 15.0D / 16.0D));

    public BuildersBenchBlock(Properties properties) {
        super(properties, SILHOUETTE, "Builders' Bench",
                "  A builder villager claims this and repairs storm damage nearby:",
                "  re-sodding scoured ground and unbarking stripped trunks, a block at a time.");
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * Asking the bench why nothing is happening, answered from the builder's own rules.
     *
     * <p>A builder is idle when it is finished, when it is short of a material, when there is nowhere to put
     * a building, or when the only damage left is damage the village cannot pay for - and all four look
     * identical from outside, which is the same as looking broken.
     */
    @Override
    protected List<String> statusLines(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        return BuilderGoal.status(serverLevel, pos);
    }

    /**
     * Offering the village something: right-clicking the bench while holding a material the village builds
     * or repairs with is a sale, not a block placement. Anything else - and anything at all while sneaking,
     * which is how a player builds beside a bench - is left to the game, so the bench never gets in the way
     * of the person working on the building it is standing next to.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.isEmpty() || player.isShiftKeyDown() || VillageMaterials.goodsOf(stack.getItem()) == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel
                && player instanceof ServerPlayer serverPlayer) {
            VillageCounter.sell(serverLevel, pos, serverPlayer, stack);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }
}
