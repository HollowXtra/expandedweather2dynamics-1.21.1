package com.imbet.instruments;

import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
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

/**
 * The deployable Tornado Probe block.
 *
 * <p>Using it reads the deployment: while armed it reports that it is waiting, once it has recorded it
 * prints the full chaser summary and awards the advancement, and an overrun probe reports what it saw
 * before it gave out. Sneaking while using it re-arms it for another deployment, which is the one
 * action that clears the numbers it is holding.
 */
public class TornadoProbeBlock extends InstrumentBlock {
    public static final MapCodec<TornadoProbeBlock> CODEC = simpleCodec(TornadoProbeBlock::new);

    /**
     * The instrument head, the body it is mounted on, and the footprint of the four legs. The legs share
     * one box rather than four, because the gap between them is smaller than a player either way.
     */
    private static final VoxelShape SILHOUETTE = Shapes.or(
            Shapes.box(0.0D, 11.0D / 16.0D, 0.0D, 1.0D, 1.0D, 1.0D),
            Shapes.box(1.0D / 16.0D, 4.0D / 16.0D, 1.0D / 16.0D, 15.0D / 16.0D, 11.0D / 16.0D, 15.0D / 16.0D),
            Shapes.box(2.0D / 16.0D, 0.0D, 2.0D / 16.0D, 14.0D / 16.0D, 4.0D / 16.0D, 14.0D / 16.0D));

    public TornadoProbeBlock(Properties properties) {
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
        return new TornadoProbeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.TORNADO_PROBE_BLOCK_ENTITY.get(), TornadoProbeBlockEntity::tick);
    }

    @Override
    protected InteractionResult useInstrument(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof TornadoProbeBlockEntity probe)) {
            return InteractionResult.PASS;
        }
        if (player.isSecondaryUseActive()) {
            probe.reset();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            serverPlayer.sendSystemMessage(Component.literal("Tornado Probe re-armed; previous reading cleared.")
                    .withStyle(ChatFormatting.AQUA));
            return InteractionResult.SUCCESS;
        }

        serverPlayer.sendSystemMessage(Component.literal("Tornado Probe - " + probe.stateLabel())
                .withStyle(probe.state() == TornadoProbeBlockEntity.STATE_LOST ? ChatFormatting.RED : ChatFormatting.AQUA));
        if (!probe.hasReading()) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "  Nothing recorded yet. Plant it in the path and come back after the storm.")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }

        if (probe.state() == TornadoProbeBlockEntity.STATE_LOST) {
            serverPlayer.sendSystemMessage(Component.literal(
                    "  Overrun: the wind destroyed it. These are the readings it held when it gave out.")
                    .withStyle(ChatFormatting.RED));
        }
        for (String line : probe.summaryLines()) {
            serverPlayer.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        if (!probe.readOnce()) {
            probe.markRead();
            AdvancementAwarder.award(serverPlayer, AdvancementAwarder.TORNADO_PROBE);
        }
        return InteractionResult.SUCCESS;
    }
}
