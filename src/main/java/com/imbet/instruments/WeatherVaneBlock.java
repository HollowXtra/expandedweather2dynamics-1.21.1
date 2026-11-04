package com.imbet.instruments;

import com.imbet.registry.ModContent;
import com.imbet.weather.WeatherInstruments;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The Weather Vane block. Using it prints the wind speed, the direction it is coming from and the
 * bearing to the nearest storm, which is the part a chaser actually wants.
 */
public class WeatherVaneBlock extends InstrumentBlock {
    public static final MapCodec<WeatherVaneBlock> CODEC = simpleCodec(WeatherVaneBlock::new);

    /**
     * The cap the metalwork pivots on, the collar under it, the post and the base plate. Without this the
     * four pixel post would collide as a whole block, which is the one shape you notice instantly.
     */
    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 13.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            Shapes.box(1.0D / 16.0D, 11.5D / 16.0D, 1.0D / 16.0D, 15.0D / 16.0D, 13.0D / 16.0D, 15.0D / 16.0D),
            Shapes.box(5.0D / 16.0D, 3.0D / 16.0D, 5.0D / 16.0D, 11.0D / 16.0D, 11.5D / 16.0D, 11.0D / 16.0D),
            Shapes.box(1.0D / 16.0D, 0.0D, 1.0D / 16.0D, 15.0D / 16.0D, 3.0D / 16.0D, 15.0D / 16.0D));

    public WeatherVaneBlock(Properties properties) {
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
        return new WeatherVaneBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.WEATHER_VANE_BLOCK_ENTITY.get(), WeatherVaneBlockEntity::tick);
    }

    @Override
    protected InteractionResult useInstrument(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        var wind = WeatherInstruments.wind(level, center);
        serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Weather Vane: wind %s from %s (bearing %.0f\u00b0)",
                WeatherInstruments.mph(wind.speedMph()),
                WeatherInstruments.windFromLabel(wind.mphVector()),
                WeatherInstruments.windFromDegrees(wind.mphVector())))
                .withStyle(ChatFormatting.AQUA));

        WeatherInstruments.StormReading storm = WeatherInstruments.nearestStorm(serverLevel, center, 4096.0D);
        if (storm != null) {
            serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "  Nearest: %s bearing %s, %.0f blocks, %s",
                    storm.label(), storm.bearing(), storm.distanceBlocks(), WeatherInstruments.stageLabel(storm.stage())))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            serverPlayer.sendSystemMessage(Component.literal("  No tracked storm within 4096 blocks.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return InteractionResult.SUCCESS;
    }
}
