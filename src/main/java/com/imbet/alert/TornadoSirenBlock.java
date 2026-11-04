package com.imbet.alert;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import com.imbet.weather.AdvancementAwarder;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * One of the pole-mounted tornado sirens.
 *
 * <p>Each tone class is its own block rather than one block with a setting, because the machines are
 * genuinely different: a mechanical siren is a rotor with a turning projector, an electronic siren is a
 * stack of horns with a lamp, and a two-tone siren is a pair of projectors on a rocking yoke. A player
 * choosing a siren is choosing which of those they want standing in their town, and each one both looks
 * and sounds like itself.
 */
public class TornadoSirenBlock extends BaseEntityBlock {
    private final SirenTone tone;

    public TornadoSirenBlock(SirenTone tone, Properties properties) {
        super(properties);
        this.tone = tone;
    }

    public SirenTone tone() {
        return tone;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(properties -> new TornadoSirenBlock(tone, properties));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * The pole's own volume, so the mast is something you can stand beside rather than a full cube of
     * invisible collision around a two pixel column.
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TornadoSirenBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.TORNADO_SIREN_BLOCK_ENTITY.get(), TornadoSirenBlockEntity::tick);
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
        if (level.getBlockEntity(pos) instanceof TornadoSirenBlockEntity siren) {
            siren.cycleMode();
            if (level instanceof ServerLevel serverLevel) {
                AlertStationNetwork.recordStation(serverLevel, pos, siren);
            }
            if (player instanceof ServerPlayer serverPlayer) {
                AdvancementAwarder.award(serverPlayer, AdvancementAwarder.USE_RADAR);
                serverPlayer.sendSystemMessage(Component.literal(tone.label() + " - " + tone.spec()));
                serverPlayer.sendSystemMessage(Component.literal(siren.wired()
                        ? "Armed. Setting: " + siren.modeLabel()
                        : "Not armed - wire this siren to a switched-on Siren Control Panel. Setting: " + siren.modeLabel()));
                if (!Config.ALERTS_SIREN_WIRING_REQUIRED.get()) {
                    serverPlayer.sendSystemMessage(Component.literal("Wiring is switched off in this world's config, so every siren is live."));
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof TornadoSirenBlockEntity siren ? siren.getPower() : 0;
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
        return blockEntity instanceof TornadoSirenBlockEntity siren ? siren.getPower() : 0;
    }
}
