package com.imbet.alert;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a Weather Alert Siren is wired to a control panel that is switched on.
 *
 * <p>A siren is inert on its own: it will not raise its mast, will not warn anyone and will not sound
 * until a run of wire reaches a Siren Control Panel whose switch is on (or which is being driven by
 * redstone). That is what makes a warning system something you build rather than something a lone
 * block does - and it is why the panel, not the siren, is the thing a player switches.
 *
 * <p>The run is searched by breadth-first walk through wire blocks, which is a search of a few dozen
 * blocks at most: a wire network is a line or a small tree, never a field. Two rules keep it honest.
 * Chunks are never loaded to look at them - a wire run that leaves the loaded area simply stops
 * connecting, so a siren cannot be armed by a panel the server is not simulating. And a run longer
 * than {@code maxWires} counts as no connection at all, so a long enough cable cannot be used to arm a
 * siren from across the map.
 */
public final class SirenWiring {

    private SirenWiring() {
    }

    /**
     * True when a switched-on panel is reachable from {@code from} through wire blocks.
     *
     * <p>{@code from} is the siren's own position, and the panel may sit directly against it or at the
     * far end of a run of wire.
     */
    public static boolean isArmed(Level level, BlockPos from, int maxWires) {
        if (level == null || from == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            // A panel plugged straight into the siren needs no wire at all.
            Boolean arm = panelArmed(level, from.relative(direction));
            if (arm != null) {
                return arm;
            }
        }
        Run run = wireRun(level, from, maxWires);
        for (BlockPos wire : run.wires()) {
            for (Direction direction : Direction.values()) {
                Boolean arm = panelArmed(level, wire.relative(direction));
                if (arm != null) {
                    return arm;
                }
            }
        }
        // Nothing on the run we walked, or nothing on the part of it inside the budget. An over-long run that
        // reaches nothing is not a connection, which is what stops a cable to the horizon from arming every
        // siren it passes near.
        return false;
    }

    /**
     * A run of wire: the wire blocks reachable from {@code from}, and whether the walk stopped short.
     *
     * <p>{@code truncated} is not a detail. A siren treats an over-long run as no connection at all, and a
     * sensor network refuses to report itself as complete from a run it could not finish walking - so the
     * caller has to be told, rather than being handed a partial list that looks like the whole thing.
     *
     * <p>The walk is the same one the siren arming check has always made, in the same order, so a run is one
     * thing in this mod rather than two answers to the same question. It is deliberately bounded and never
     * loads a chunk: a wire run that leaves the loaded area simply stops connecting.
     */
    public record Run(List<BlockPos> wires, boolean truncated) {
        public Run {
            wires = wires == null ? List.of() : List.copyOf(wires);
        }

        /** The whole run fitted inside the budget, so it can be reasoned about as the network itself. */
        public boolean complete() {
            return !truncated;
        }

        /** Whether a position is one of the run's own wire blocks, or touching one. */
        public boolean touches(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            for (BlockPos wire : wires) {
                if (Math.abs(wire.getX() - pos.getX()) <= 1
                        && Math.abs(wire.getY() - pos.getY()) <= 1
                        && Math.abs(wire.getZ() - pos.getZ()) <= 1) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The wire blocks a run reaches from {@code from}, up to {@code maxWires} of them, in the order they were
     * found. The position given may be any block on the run: a siren, a panel or the computer at its head.
     */
    public static Run wireRun(Level level, BlockPos from, int maxWires) {
        if (level == null || from == null) {
            return new Run(List.of(), false);
        }
        int budget = Math.max(1, maxWires);
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> wires = new ArrayList<>();
        boolean truncated = false;

        for (Direction direction : Direction.values()) {
            BlockPos neighbour = from.relative(direction);
            if (level.isLoaded(neighbour) && level.getBlockState(neighbour).getBlock() instanceof SirenWireBlock) {
                queue.add(neighbour.immutable());
            }
        }

        while (!queue.isEmpty()) {
            BlockPos wire = queue.poll();
            if (!visited.add(wire)) {
                continue;
            }
            if (wires.size() >= budget) {
                truncated = true;
                break;
            }
            wires.add(wire);
            for (Direction direction : Direction.values()) {
                BlockPos next = wire.relative(direction);
                if (visited.contains(next) || !level.isLoaded(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof SirenWireBlock) {
                    queue.add(next.immutable());
                }
            }
        }
        return new Run(wires, truncated);
    }

    /**
     * The panel's state at a position, or null when there is no panel there.
     *
     * <p>Armed covers both ways a panel can be switched on: the lever on its face, and a redstone signal
     * fed into it. A player who wires their siren to a vanilla lever instead of a panel is served by
     * placing the panel and putting a signal into it, which keeps exactly one block meaning "this is on".
     */
    private static Boolean panelArmed(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof SirenControlPanelBlock ? SirenControlPanelBlock.armed(state) : null;
    }
}
