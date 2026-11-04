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

/** The Barometer block. Using it prints the reading broken into its parts and the tendency. */
public class BarometerBlock extends InstrumentBlock {
    public static final MapCodec<BarometerBlock> CODEC = simpleCodec(BarometerBlock::new);

    /**
     * The lid, the dial drum under it and the plinth. The steel band around the plinth is left out: it
     * stands four hundredths of a block proud, which is below the resolution of a collision box.
     */
    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 15.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            Shapes.box(2.0D / 16.0D, 3.5D / 16.0D, 2.0D / 16.0D, 14.0D / 16.0D, 15.0D / 16.0D, 14.0D / 16.0D),
            Shapes.box(1.0D / 16.0D, 0.0D, 1.0D / 16.0D, 15.0D / 16.0D, 3.4D / 16.0D, 15.0D / 16.0D));

    public BarometerBlock(Properties properties) {
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
        return new BarometerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.BAROMETER_BLOCK_ENTITY.get(), BarometerBlockEntity::tick);
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
        WeatherInstruments.PressureReading reading = WeatherInstruments.pressure(serverLevel, center);
        WeatherInstruments.Tendency tendency = level.getBlockEntity(pos) instanceof BarometerBlockEntity barometer
                ? WeatherInstruments.tendency(barometer.rateHpaPerHour())
                : WeatherInstruments.tendency(0.0D);

        serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Barometer: %.1f hPa", reading.hpa()))
                .withStyle(ChatFormatting.AQUA));
        serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "  %s (%+.1f hPa per in-game hour)", tendency.label(), tendency.hPaPerHour()))
                .withStyle(tendency.rapidlyFalling() ? ChatFormatting.RED
                        : tendency.falling() ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "  Altitude base %.1f, low deficit %.1f, vortex deficit %.1f",
                reading.altitudeHpa(), reading.lowDeficitHpa(), reading.coreDeficitHpa()))
                .withStyle(ChatFormatting.GRAY));
        // Which belt the instrument is standing in, which is the other half of why the pressure is what
        // it is: the deficit above is the weather, the belt is the climate it is happening in.
        serverPlayer.sendSystemMessage(Component.literal("  Belt: " + WeatherInstruments.beltLine(serverPlayer.getZ()))
                .withStyle(ChatFormatting.DARK_AQUA));
        if (reading.lowDistanceBlocks() >= 0.0D) {
            serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "  Nearest low: %s bearing %s, %.0f blocks",
                    reading.lowLabel().isBlank() ? "Low" : reading.lowLabel(),
                    reading.lowBearing(), reading.lowDistanceBlocks()))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            serverPlayer.sendSystemMessage(Component.literal("  No organized low within range.")
                    .withStyle(ChatFormatting.GRAY));
        }
        return InteractionResult.SUCCESS;
    }
}
