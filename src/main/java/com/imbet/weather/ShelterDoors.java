package com.imbet.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The doors of a village going shut ahead of a funnel.
 *
 * <p>{@link TornadoShelterGoal} walks villagers into cover and {@link ShelterPanic} decides when the village
 * has noticed the weather; this is the one block edit that follows from the second. A villager that has
 * just got itself inside a house shuts the doors it can reach, which is both what a household does and, in
 * this mod, a real difference: an open doorway is a way in for everything the vortex is carrying.
 *
 * <p>It only ever closes doors, never opens them, and only the ones a sheltering villager could reach. That
 * asymmetry is on purpose. A door that is shut when the funnel arrives is a door a player can open again,
 * and the villagers are not walking anywhere until the threat has passed - so the worst case of a wrong
 * close is a house with its door shut, which is what the house wanted anyway.
 */
public final class ShelterDoors {

    /** How many doors one call may touch, so a village made of doors cannot become a per-tick sweep. */
    private static final int MAX_DOORS = 12;

    private ShelterDoors() {
    }

    /**
     * Close every open, unpowered door within reach of {@code centre}, as close to the funnel as
     * {@code threatDistanceBlocks} says it is.
     *
     * <p>The reach and the decision both come from {@link ShelterPanic}: this class reads the world and
     * {@link ShelterPanic} owns the thresholds, so what "close enough to matter" means is stated in one
     * place rather than in the middle of a block loop.
     *
     * @return how many doors were shut
     */
    public static int closeAround(ServerLevel level, Vec3 centre, double threatDistanceBlocks) {
        if (level == null || centre == null || !ShelterPanic.shouldCloseDoors(threatDistanceBlocks)) {
            return 0;
        }
        double reachBlocks = ShelterPanic.doorReachBlocks(threatDistanceBlocks);
        int reach = (int) Math.ceil(reachBlocks);
        int closed = 0;
        BlockPos origin = BlockPos.containing(centre);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-reach, -2, -reach), origin.offset(reach, 2, reach))) {
            if (closed >= MAX_DOORS) {
                break;
            }
            double doorDistance = Math.sqrt(centre.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
            if (doorDistance > reachBlocks) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock door) || !isOpenUnpowered(state)) {
                continue;
            }
            if (!ShelterPanic.shutDoor(threatDistanceBlocks, doorDistance, true)) {
                continue;
            }
            if (close(level, door, state, pos)) {
                closed++;
            }
        }
        return closed;
    }

    /** A door that is standing open and is not being held open by redstone. */
    private static boolean isOpenUnpowered(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN)
                && state.getValue(BlockStateProperties.OPEN)
                && (!state.hasProperty(BlockStateProperties.POWERED) || !state.getValue(BlockStateProperties.POWERED));
    }

    /**
     * Shut one door.
     *
     * <p>Vanilla's own {@code setOpen} is used for the half that was found, because it plays the door's own
     * sound and sends the game event that sculk and the like listen for - so a door shut by a villager looks
     * and sounds like any other door shutting. The other half is written directly, silently, because it is
     * one door and should make one noise.
     */
    private static boolean close(ServerLevel level, DoorBlock door, BlockState state, BlockPos pos) {
        try {
            door.setOpen(null, level, state, pos, false);
            BlockGrabSafety.invalidate(level, pos);
            BlockPos other = pos.relative(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? Direction.UP
                    : Direction.DOWN);
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() == door && otherState.hasProperty(BlockStateProperties.OPEN)
                    && otherState.getValue(BlockStateProperties.OPEN)) {
                BlockGrabSafety.safeSetBlock(level, other, otherState.setValue(BlockStateProperties.OPEN, false), 3);
            }
            return true;
        } catch (RuntimeException ignored) {
            BlockGrabSafety.invalidate(level, pos);
            return false;
        }
    }
}
