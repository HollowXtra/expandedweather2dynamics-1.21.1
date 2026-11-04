package com.imbet.instruments;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shared shape of every weather instrument block.
 *
 * <p>All five behave the same way: they are block entities with a model, using them does something,
 * and they can drive redstone from their reading. Only that last "does something" differs, so it is
 * the single abstract method here; everything else - the interaction plumbing, the render shape and
 * the four signal methods - lives in one place.
 */
public abstract class InstrumentBlock extends BaseEntityBlock {
    protected InstrumentBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected final RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * The instrument's occupied volume, as the shape its collision, outline and light come from.
     *
     * <p>Every instrument is a modelled machine rather than a cube, and a full-cube collision under a
     * machine is something you feel immediately: a two pixel post you cannot walk through, or a thin
     * column you can stand on top of. So the shape follows the model's main masses - head, body, base -
     * and deliberately skips the proud details, because a fifth of a pixel of collision is not
     * something a player can stand on either way.
     *
     * <p>Each model's top mass is a full block face, so the shape still answers as a sturdy surface on
     * top and a torch can be planted on an instrument.
     */
    protected VoxelShape silhouette() {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return silhouette();
    }

    /** Whatever this instrument does when used. Sneak-use is left to each block to interpret. */
    protected abstract InteractionResult useInstrument(Level level, BlockPos pos, Player player);

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (useInstrument(level, pos, player) == InteractionResult.SUCCESS) {
            return ItemInteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return useInstrument(level, pos, player);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return powerAt(level, pos);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return powerAt(level, pos);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return powerAt(level, pos);
    }

    private static int powerAt(BlockGetter level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof PoweredInstrument powered ? powered.instrumentPower() : 0;
    }
}
