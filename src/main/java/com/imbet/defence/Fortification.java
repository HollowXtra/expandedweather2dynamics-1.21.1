package com.imbet.defence;

import com.imbet.Config;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a block is fortified: a question the damage passes ask, answered from the world.
 *
 * <p>This is the whole of the defence feature's integration with the rest of the mod, and it is one method on
 * purpose. Every hazard in this mod that edits blocks - the wind's pickup and impact passes, the hail, the derecho's
 * roof stripping, the volcanic ingest - asks {@code BlockGrabSafety} whether a block may be edited, and that class
 * asks this one. So a fortress is a fortress to every storm at once rather than to the one pass a player happened to
 * test, and there is exactly one place to look when somebody says a wall came off anyway.
 *
 * <p>Two things are fortified, and they are fortified for different reasons:
 *
 * <ul>
 *   <li><b>Reinforced glass</b> is fortified because of what it is made of. Nothing about where it stands matters:
 *       a pane of it in a field is still obsidian-set glass.</li>
 *   <li><b>Anchored blocks</b> are fortified because of what is holding them. That is a local question - a planted
 *       rebar within its own reach - and it is asked of the blocks around the candidate, which is a bounded scan
 *       (see {@link RebarAnchors#heldCells(int)}) rather than a search.</li>
 * </ul>
 *
 * <p>The rebar itself counts as anchored even with nothing under it, because a rod lying on the ground being blown
 * away is not a mechanic: the planting rule is about what a rod holds, and the rod is held by the same rule as
 * everything else it covers. It is the one place where "on its own" and "planted" would otherwise be the same
 * question and the answer would be wrong either way.
 */
public final class Fortification {

    private Fortification() {
    }

    /** Whether a block may be edited by a storm, as far as the defence blocks are concerned. */
    public static boolean fortified(LevelReader level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        if (Config.REINFORCED_GLASS_ENABLED.get() && state.is(ModContent.REINFORCED_GLASS.get())) {
            return true;
        }
        if (state.is(ModContent.STRUCTURAL_REBAR.get())) {
            return true;
        }
        if (!Config.REBAR_ANCHORING_ENABLED.get()) {
            return false;
        }
        return anchored(level, pos);
    }

    /**
     * Whether a planted rebar holds this position.
     *
     * <p>The scan walks the offsets a rebar can reach from, which is a square of columns around the position from
     * its own height down one and up to the configured reach - at most forty-five positions, and only for blocks a
     * damage pass was already considering. Chunks are never loaded to look: an unloaded position is not a rebar.
     *
     * <p>The check is {@code hasChunkAt} rather than {@code isLoaded} because this is asked through a
     * {@link LevelReader}, which is the interface a block entity's own level, a chunk's own level and a server level
     * all satisfy - so the same question can be asked from a tick, from a pass, and from a block.
     */
    public static boolean anchored(LevelReader level, BlockPos pos) {
        int reach = RebarAnchors.reachUp(Config.REBAR_ANCHOR_HEIGHT.get());
        for (int dx = -RebarAnchors.REACH_HORIZONTAL; dx <= RebarAnchors.REACH_HORIZONTAL; dx++) {
            for (int dz = -RebarAnchors.REACH_HORIZONTAL; dz <= RebarAnchors.REACH_HORIZONTAL; dz++) {
                for (int dy = -reach; dy <= RebarAnchors.REACH_DOWN; dy++) {
                    // The rod is at the opposite offset from the block: a rod below a wall holds the wall, so the
                    // rod is found one block down and the block is one block up from it.
                    if (!RebarAnchors.covers(-dx, -dy, -dz, reach)) {
                        continue;
                    }
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (!level.hasChunkAt(candidate)) {
                        continue;
                    }
                    BlockState state;
                    try {
                        state = level.getBlockState(candidate);
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    if (state.is(ModContent.STRUCTURAL_REBAR.get()) && planted(level, candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether a rebar is driven into something.
     *
     * <p>Solid ground will do, and so will another rebar: a stack of them is a wall of reinforcement and the rule
     * has to let it be, or no length of it would count. Air, water and leaves do not.
     */
    public static boolean planted(LevelReader level, BlockPos rebar) {
        BlockPos below = rebar.below();
        if (!level.hasChunkAt(below)) {
            return false;
        }
        BlockState state;
        try {
            state = level.getBlockState(below);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (state.is(ModContent.STRUCTURAL_REBAR.get())) {
            return true;
        }
        return state.isSolidRender(level, below) || state.isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }
}
