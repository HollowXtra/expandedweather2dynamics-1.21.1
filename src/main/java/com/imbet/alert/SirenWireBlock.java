package com.imbet.alert;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The wire a siren is armed through: a cable that plugs into whatever it touches.
 *
 * <p>It exists so that "the siren is not on yet" is a thing you can see and fix rather than a config
 * value. A run of it between a Siren Control Panel and a Weather Alert Siren is what arms the siren,
 * and the run is walked by {@link SirenWiring}.
 *
 * <p>Connections are six booleans on the block state, the way a redstone dust or a pipe does it, so a
 * straight run draws as a cable and a corner draws as a corner. Only cable, panels and sirens count as
 * connections: a wire that visually ran into a random block would read as connected to it, and the
 * whole point of the block is that the player can see what is joined to what.
 *
 * <p>The collision shape follows the drawn cable exactly - a 6 pixel core with an arm on every open
 * side - so it is something you walk over rather than through, and it never blocks a doorway.
 */
public class SirenWireBlock extends Block {
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;

    private static final BooleanProperty[] CONNECTIONS = {NORTH, EAST, SOUTH, WEST, UP, DOWN};

    /** Half-width of the core, in blocks: a 6 pixel cable. */
    private static final double CORE = 0.1875D;
    /** Half-width of an arm, one pixel narrower than the core so the pieces read as separate. */
    private static final double ARM = 0.125D;

    /**
     * The shape for every combination of the six connections, indexed by a bit per direction in
     * {@link Direction}'s own order. Built once because collision and outline queries ask for this on
     * every step a player takes, and a run of cable is a lot of blocks.
     */
    private static final VoxelShape[] SHAPES = buildShapes();

    public SirenWireBlock(Properties properties) {
        super(properties);
        registerDefaultState(applyConnections(defaultBlockState(), false, false, false, false, false, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    // ------------------------------------------------------------------ connections

    /** True when a wire drawn out of {@code from} should reach towards {@code direction}. */
    public static boolean connectsTo(BlockState neighbour) {
        return neighbour.getBlock() instanceof SirenWireBlock
                || neighbour.getBlock() instanceof SirenControlPanelBlock
                || neighbour.getBlock() instanceof WeatherAlertSirenBlock;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return connectionState(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        boolean connect = connectsTo(neighbourState);
        BooleanProperty property = propertyFor(direction);
        return state.getValue(property) == connect ? state : state.setValue(property, connect);
    }

    private static BlockState connectionState(BlockGetter level, BlockPos pos, BlockState state) {
        BlockState result = state;
        for (Direction direction : Direction.values()) {
            result = result.setValue(propertyFor(direction),
                    connectsTo(level.getBlockState(pos.relative(direction))));
        }
        return result;
    }

    private static BlockState applyConnections(BlockState state, boolean north, boolean east, boolean south,
                                               boolean west, boolean up, boolean down) {
        return state.setValue(NORTH, north).setValue(EAST, east).setValue(SOUTH, south)
                .setValue(WEST, west).setValue(UP, up).setValue(DOWN, down);
    }

    private static BooleanProperty propertyFor(Direction direction) {
        return CONNECTIONS[direction.ordinal()];
    }

    private static int indexOf(BlockState state) {
        int index = 0;
        for (Direction direction : Direction.values()) {
            if (state.getValue(propertyFor(direction))) {
                index |= 1 << direction.ordinal();
            }
        }
        return index;
    }

    private static VoxelShape[] buildShapes() {
        VoxelShape[] shapes = new VoxelShape[1 << 6];
        for (int mask = 0; mask < shapes.length; mask++) {
            VoxelShape shape = Shapes.box(0.5D - CORE, 0.5D - CORE, 0.5D - CORE,
                    0.5D + CORE, 0.5D + CORE, 0.5D + CORE);
            for (Direction direction : Direction.values()) {
                if ((mask & (1 << direction.ordinal())) == 0) {
                    continue;
                }
                shape = Shapes.or(shape, armShape(direction));
            }
            shapes[mask] = shape.optimize();
        }
        return shapes;
    }

    /** The arm reaching from the core to the block's face in one direction. */
    private static VoxelShape armShape(Direction direction) {
        return switch (direction) {
            case DOWN -> Shapes.box(0.5D - ARM, 0.0D, 0.5D - ARM, 0.5D + ARM, 0.5D - CORE, 0.5D + ARM);
            case UP -> Shapes.box(0.5D - ARM, 0.5D + CORE, 0.5D - ARM, 0.5D + ARM, 1.0D, 0.5D + ARM);
            case NORTH -> Shapes.box(0.5D - ARM, 0.5D - ARM, 0.0D, 0.5D + ARM, 0.5D + ARM, 0.5D - CORE);
            case SOUTH -> Shapes.box(0.5D - ARM, 0.5D - ARM, 0.5D + CORE, 0.5D + ARM, 0.5D + ARM, 1.0D);
            case WEST -> Shapes.box(0.0D, 0.5D - ARM, 0.5D - ARM, 0.5D - CORE, 0.5D + ARM, 0.5D + ARM);
            case EAST -> Shapes.box(0.5D + CORE, 0.5D - ARM, 0.5D - ARM, 1.0D, 0.5D + ARM, 0.5D + ARM);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[indexOf(state)];
    }
}
