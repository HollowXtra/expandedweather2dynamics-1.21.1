package com.imbet.anemometer;

import com.imbet.registry.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class AnemometerBlock extends BaseEntityBlock {
    public static final MapCodec<AnemometerBlock> CODEC = simpleCodec(AnemometerBlock::new);

    /**
     * The flange, the mast and the bearing housing. The bolts, the rim and the housing cap are left out:
     * they stand two hundredths of a block proud, which is below the resolution of a collision box.
     *
     * <p>The rotor is not in here either, because it is drawn rather than modelled and hangs above the
     * block's top face - it is spun metalwork, not something to stand on.
     */
    private static final VoxelShape SHAPE = Shapes.or(
            box(3.0D, 0.0D, 3.0D, 13.0D, 1.2D, 13.0D),
            box(6.6D, 1.6D, 6.6D, 9.4D, 12.2D, 9.4D),
            box(5.8D, 11.6D, 5.8D, 10.2D, 15.0D, 10.2D));

    public AnemometerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // MODEL, not INVISIBLE as it was: the mast used to be a flat quad in the renderer, and a flat quad
        // is invisible from the side. The mast and housing are a model now and only the rotor is drawn.
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AnemometerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.ANEMOMETER_BLOCK_ENTITY.get(), AnemometerBlockEntity::tick);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof AnemometerBlockEntity anemometer ? anemometer.getPower() : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof AnemometerBlockEntity anemometer ? anemometer.getPower() : 0;
    }
}
