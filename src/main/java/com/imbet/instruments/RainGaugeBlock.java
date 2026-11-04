package com.imbet.instruments;

import com.imbet.registry.ModContent;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
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

import java.util.Locale;

/**
 * The Rain Gauge block. Using it reads the total; sneaking while using it empties the gauge, which is
 * the same split the Weather Radio uses so that reading a value never destroys it.
 */
public class RainGaugeBlock extends InstrumentBlock {
    public static final MapCodec<RainGaugeBlock> CODEC = simpleCodec(RainGaugeBlock::new);

    /**
     * The head, the funnel with its steps, the measuring column and the base. The steps are folded into
     * the funnel's own box, since they differ from it by half a pixel.
     */
    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 13.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            Shapes.box(0.5D / 16.0D, 9.0D / 16.0D, 0.5D / 16.0D, 15.5D / 16.0D, 13.0D / 16.0D, 15.5D / 16.0D),
            Shapes.box(5.0D / 16.0D, 1.0D / 16.0D, 5.0D / 16.0D, 11.0D / 16.0D, 9.0D / 16.0D, 11.0D / 16.0D),
            Shapes.box(2.0D / 16.0D, 0.0D, 2.0D / 16.0D, 14.0D / 16.0D, 1.6D / 16.0D, 14.0D / 16.0D));

    public RainGaugeBlock(Properties properties) {
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
        return new RainGaugeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.RAIN_GAUGE_BLOCK_ENTITY.get(), RainGaugeBlockEntity::tick);
    }

    @Override
    protected InteractionResult useInstrument(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof RainGaugeBlockEntity gauge)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            double before = gauge.accumulatedMm();
            gauge.reset();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "Rain Gauge emptied: %.1f mm discarded.", before)).withStyle(ChatFormatting.AQUA));
            return InteractionResult.SUCCESS;
        }

        serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Rain Gauge: %.1f mm collected", gauge.accumulatedMm())).withStyle(ChatFormatting.AQUA));
        if (gauge.raining()) {
            serverPlayer.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                    "  Falling now at %.1f mm per hour.", gauge.rateMmPerHour())).withStyle(ChatFormatting.BLUE));
        } else {
            double dryHours = gauge.dryHours(level);
            String dry = dryHours < 0.0D
                    ? "  No rain has landed here yet."
                    : String.format(Locale.ROOT, "  Dry for %.1f in-game hours.", dryHours);
            serverPlayer.sendSystemMessage(Component.literal(dry).withStyle(ChatFormatting.GRAY));
        }
        serverPlayer.sendSystemMessage(Component.literal(
                "  Only rain that reaches the ground is counted; a virga shaft leaves this gauge empty.")
                .withStyle(ChatFormatting.DARK_GRAY));
        return InteractionResult.SUCCESS;
    }
}
