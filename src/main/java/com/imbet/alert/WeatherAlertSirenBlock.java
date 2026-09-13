package com.imbet.alert;

import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class WeatherAlertSirenBlock extends BaseEntityBlock {
    public static final MapCodec<WeatherAlertSirenBlock> CODEC = simpleCodec(WeatherAlertSirenBlock::new);

    public WeatherAlertSirenBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeatherAlertSirenBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.WEATHER_ALERT_SIREN_BLOCK_ENTITY.get(), WeatherAlertSirenBlockEntity::tick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (cycle(level, pos, player) == InteractionResult.SUCCESS) {
            return ItemInteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return cycle(level, pos, player);
    }

    private InteractionResult cycle(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof WeatherAlertSirenBlockEntity siren) {
            siren.cycleMode();
            // Muting a siren has to take effect at once, so its stored state is refreshed here
            // rather than waiting for the next broadcast sweep.
            if (level instanceof ServerLevel serverLevel) {
                AlertStationNetwork.recordStation(serverLevel, pos, siren);
            }
            if (player instanceof ServerPlayer serverPlayer) {
                AdvancementAwarder.award(serverPlayer, AdvancementAwarder.USE_RADAR);
                serverPlayer.sendSystemMessage(Component.literal("Weather Alert Siren: " + siren.modeLabel()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.core.Direction direction) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof WeatherAlertSirenBlockEntity siren ? siren.getPower() : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.core.Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof WeatherAlertSirenBlockEntity siren ? siren.getPower() : 0;
    }
}
