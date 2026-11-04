package com.imbet.spc;

import com.imbet.instruments.SensorFeed;
import com.imbet.registry.ModContent;
import net.minecraft.world.SimpleMenuProvider;
import com.imbet.weather.OutbreakService;
import com.imbet.weather.WeatherInstruments;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import com.imbet.chase.ChaseLogService;
import com.imbet.photo.PhotoService;
import com.imbet.spotter.SpotterService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The weather computer: a desk machine whose monitor shows the day's SPC outlook.
 *
 * <p>It exists next to the flat SPC risk panel as the thing you put in a room rather than on a wall. The
 * numbers are not its own - it is a {@link WeatherComputerBlockEntity}, which is the panel's own
 * instrument - so the monitor and the panel always read the same issuance.
 *
 * <p>It faces the player who places it, and right-clicking steps it through the categorical view and the
 * three hazards, the same way the panel does. It also back-feeds its category as a redstone level, so a
 * computer can be wired to a siren and left to decide.
 */
public class WeatherComputerBlock extends BaseEntityBlock {
    public static final MapCodec<WeatherComputerBlock> CODEC = simpleCodec(WeatherComputerBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public WeatherComputerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeatherComputerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModContent.WEATHER_COMPUTER_BLOCK_ENTITY.get(), SpcRiskBlockEntity::tick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (cycle(level, pos, player) == InteractionResult.SUCCESS) {
            return ItemInteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /**
     * Using the computer with a bare hand opens its screen - unless the player is sneaking, which is the readout.
     *
     * <p>Both interactions have to exist. The screen is what the station is for: three tabs, read at a glance. The
     * chat readout is what it was before the screen and is still the only way to read a station from inside a
     * command block or a script, so it is kept on the sneak - the same split every other instrument in this mod
     * uses, where the plain use opens the interface and the sneak use is the raw readout.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) {
            return cycle(level, pos, player);
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, openedPlayer) -> new WeatherComputerMenu(containerId, inventory, pos),
                    Component.translatable("block.expandedweather2dynamics.weather_computer")));
        }
        return InteractionResult.SUCCESS;
    }

    private InteractionResult cycle(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof SpcRiskBlockEntity computer) {
            computer.cycleMode(level, level.getBlockState(pos));
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal("Weather Computer: " + computer.getModeLabel()
                        + " / " + computer.currentRisk().label()));
                serverPlayer.sendSystemMessage(Component.literal("  Belt: "
                        + WeatherInstruments.beltLine(serverPlayer.getZ())).withStyle(ChatFormatting.DARK_AQUA));
                OutbreakService.Episode outbreak = level instanceof ServerLevel serverLevel
                        ? OutbreakService.active(serverLevel)
                        : null;
                if (outbreak != null) {
                    serverPlayer.sendSystemMessage(Component.literal("  " + OutbreakService.progressLine(outbreak))
                            .withStyle(ChatFormatting.DARK_RED));
                }
                // The instruments wired to this computer, which is the other half of what a station is for: the
                // outlook says what the sky is doing over there, and the network says what it is doing at the
                // three fences the player actually put instruments on.
                if (level instanceof ServerLevel sensorLevel) {
                    for (String line : SensorFeed.reportLines(sensorLevel, pos)) {
                        serverPlayer.sendSystemMessage(Component.literal("  " + line)
                                .withStyle(ChatFormatting.BLUE));
                    }
                }
                // The chase log rides along with the computer rather than owning a screen of its own: it is
                // four lines of numbers about storms you have already met, and a station that tells you what
                // the sky is doing is the right place to be told what it did to you.
                for (String line : ChaseLogService.readoutLines(serverPlayer)) {
                    serverPlayer.sendSystemMessage(Component.literal("  " + line).withStyle(ChatFormatting.GOLD));
                }
                // The spotter log rides along for the same reason: a station that tells you what the sky is
                // doing is where you find out whether the warning you filed was worth the flare.
                for (String line : SpotterService.readoutLines(serverPlayer)) {
                    serverPlayer.sendSystemMessage(Component.literal("  " + line).withStyle(ChatFormatting.GOLD));
                }
                // And the album, which is the third thing this mod keeps about a player: the storms they were
                // close to, the warnings they filed, and the photographs they took of them.
                for (String line : PhotoService.readoutLines(serverPlayer)) {
                    serverPlayer.sendSystemMessage(Component.literal("  " + line).withStyle(ChatFormatting.AQUA));
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
        return blockEntity instanceof SpcRiskBlockEntity computer ? computer.getPower() : 0;
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
        return blockEntity instanceof SpcRiskBlockEntity computer ? computer.getPower() : 0;
    }
}
