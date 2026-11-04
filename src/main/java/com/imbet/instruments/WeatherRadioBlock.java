package com.imbet.instruments;

import com.imbet.registry.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The Weather Radio block.
 *
 * <p>Using it reads the current product and the local conditions out into chat; sneaking while using
 * it cycles the channel. Reads and changes are split like that on purpose, because a set you cannot
 * read without also changing the channel is annoying to live with.
 */
public class WeatherRadioBlock extends InstrumentBlock {
    public static final MapCodec<WeatherRadioBlock> CODEC = simpleCodec(WeatherRadioBlock::new);

    /**
     * The lid, the case under it and the footprint the feet stand on. The feet get one flat base rather
     * than four slivers, since the gap between them is smaller than a player anyway.
     */
    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 13.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            Shapes.box(1.0D / 16.0D, 3.0D / 16.0D, 1.0D / 16.0D, 15.0D / 16.0D, 12.0D / 16.0D, 15.0D / 16.0D),
            Shapes.box(1.3D / 16.0D, 0.0D, 1.3D / 16.0D, 14.7D / 16.0D, 3.6D / 16.0D, 14.7D / 16.0D));

    public WeatherRadioBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends InstrumentBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape silhouette() {
        return SILHOUETTE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeatherRadioBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.WEATHER_RADIO_BLOCK_ENTITY.get(), WeatherRadioBlockEntity::tick);
    }

    @Override
    protected InteractionResult useInstrument(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof WeatherRadioBlockEntity radio)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            radio.cycleMode(level, level.getBlockState(pos));
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal("Weather Radio: " + radio.modeLabel()));
            }
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            radio.readout(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
