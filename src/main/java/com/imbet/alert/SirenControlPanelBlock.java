package com.imbet.alert;

import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The switch a siren network is armed from.
 *
 * <p>A Weather Alert Siren on its own is inert: it sits with its mast stowed and warns nobody. It comes
 * alive only when a run of {@link SirenWireBlock} reaches one of these with the lever on, which is what
 * makes building a warning system a decision rather than a placement - which sirens are on the same run,
 * and where the switch is, are the player's own arrangements.
 *
 * <p>The panel has two ways to be on, and they are deliberately the same switch as far as a siren is
 * concerned: its own lever, toggled by hand, and a redstone signal from anything else. So a base can
 * run its sirens off a lever, and an automated setup can drive the panel from a comparator on a storm
 * detector without the mod having to know anything about it. A lamp on the face shows which it is -
 * switch-lit when the lever is on, signal-lit when redstone is driving it - so a player standing in
 * front of it can always see why the sirens are or are not sounding.
 *
 * <p>The lever and the signal live on the block state rather than in a block entity. That is not only
 * less code: a state change is a normal block update, so a lever thrown on the far side of a wire run
 * is visible to the client immediately and survives a reload, and the model can carry the lever and the
 * lamp itself.
 */
public class SirenControlPanelBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** The lever on the face. */
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");
    /** A redstone signal is arriving. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** The panel is a plate on a wall or a box on the floor; the shape follows the model's body. */
    private static final VoxelShape SHAPE = Shapes.box(0.0625D, 0.0D, 0.0625D, 0.9375D, 0.625D, 0.9375D);

    public SirenControlPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ARMED, false)
                .setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ARMED, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /** True when the network this panel is on should be live: its own lever, or a signal into it. */
    public static boolean armed(BlockState state) {
        return state.getValue(ARMED) || state.getValue(POWERED);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        boolean armed = !state.getValue(ARMED);
        level.setBlockAndUpdate(pos, state.setValue(ARMED, armed));
        // The lever's own click, so throwing it reads as throwing a switch.
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6F, armed ? 1.1F : 0.8F);
        // Every siren the run reaches re-checks within a second, so there is nothing to push from here -
        // but a hard state change is worth a block update so the lamp and lever redraw at once.
        level.updateNeighborsAt(pos, this);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(Component.literal("Siren Network: " + (armed ? "ARMED" : "DISARMED")), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbourBlock,
                                   BlockPos neighbourPos, boolean movedByPiston) {
        if (level.isClientSide()) {
            return;
        }
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlockAndUpdate(pos, state.setValue(POWERED, powered));
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Exposed for the item tooltip and the creative tab: the block whose panel arms a run. */
    public static Block block() {
        return ModContent.SIREN_CONTROL_PANEL_BLOCK.get();
    }
}
