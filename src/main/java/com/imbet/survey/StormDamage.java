package com.imbet.survey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * What counts as tornado damage, and what it goes back to.
 *
 * <p>One definition for three callers: the surveyor item counts these blocks, the surveyor villager
 * counts the same ones, and the builder villager repairs exactly this set. Splitting them would mean a
 * builder that leaves damage a surveyor still reports, which reads as a bug from the ground even
 * though both halves would be working as written.
 */
public final class StormDamage {
    /**
     * The stripped forms the storm leaves behind when it takes bark off a trunk. The same list the
     * surveyor has always counted.
     */
    public static final Set<Block> STRIPPED_LOGS = Set.of(
            Blocks.STRIPPED_OAK_LOG,
            Blocks.STRIPPED_SPRUCE_LOG,
            Blocks.STRIPPED_BIRCH_LOG,
            Blocks.STRIPPED_JUNGLE_LOG,
            Blocks.STRIPPED_ACACIA_LOG,
            Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.STRIPPED_MANGROVE_LOG,
            Blocks.STRIPPED_CHERRY_LOG,
            Blocks.STRIPPED_OAK_WOOD,
            Blocks.STRIPPED_SPRUCE_WOOD,
            Blocks.STRIPPED_BIRCH_WOOD,
            Blocks.STRIPPED_JUNGLE_WOOD,
            Blocks.STRIPPED_ACACIA_WOOD,
            Blocks.STRIPPED_DARK_OAK_WOOD,
            Blocks.STRIPPED_MANGROVE_WOOD,
            Blocks.STRIPPED_CHERRY_WOOD,
            Blocks.STRIPPED_CRIMSON_STEM,
            Blocks.STRIPPED_WARPED_STEM,
            Blocks.STRIPPED_CRIMSON_HYPHAE,
            Blocks.STRIPPED_WARPED_HYPHAE
    );

    /**
     * The surfaces scouring leaves, in the order a tie between two neighbour kinds is settled. Grass
     * and dirt first because that is what most scoured ground was.
     */
    private static final List<Block> NATURAL_SURFACES = List.of(
            Blocks.GRASS_BLOCK,
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.SNOW_BLOCK,
            Blocks.STONE
    );

    private StormDamage() {
    }

    public static boolean isScouredSurface(BlockState state) {
        return state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT);
    }

    public static boolean isDebarkedLog(BlockState state) {
        return STRIPPED_LOGS.contains(state.getBlock());
    }

    /**
     * A stripped trunk still standing on its stump, as opposed to a barked log lying on its side.
     *
     * <p>Both are storm damage and the surveyor counts both, but only one of them is a repair: a trunk
     * whose bark the wind took off is put back the way it was, and a trunk the wind tore out of the ground
     * and laid down the length of its throw is a tree that came down. Unbarking a lying log would put it
     * back upright in the middle of a field, which is not a repair at all - so the builder wants the
     * standing ones and the lumberjack the lying ones, and each of the two jobs is the other's evidence.
     *
     * <p>A block with no axis property is standing: the convention the storm's own stripped forms follow,
     * and the safe reading for anything else that happens to be in the set.
     */
    public static boolean isStandingDebarkedLog(BlockState state) {
        if (!isDebarkedLog(state)) {
            return false;
        }
        if (!state.hasProperty(RotatedPillarBlock.AXIS)) {
            return true;
        }
        return state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }

    public static boolean isStormLeaves(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    /** Whether this block is something the builder villagers can put back. */
    public static boolean isRepairable(BlockState state) {
        return isScouredSurface(state) || isDebarkedLog(state);
    }

    /**
     * Whether scoured ground is torn enough for a villager to re-sod it unaided.
     *
     * <p>Deliberately narrower than {@link #isScouredSurface}, and this is the whole reason the builder was
     * busy doing nothing: the lightest scouring and a vanilla village's own street are <em>the same block</em>
     * ({@code dirt_path}), so a repair rule that accepts one accepts the other. A surveyor counting roads as
     * storm damage is a reading that can be explained; a builder bulldozing the village it lives in,
     * endlessly, is not. So course dirt and rooted dirt - the forms nothing but a storm leaves - are what a
     * villager will re-sod, and a road stays a road. The heavy scours are the ones with the deep EF ratings
     * behind them and are exactly the damage a village would want put back.
     */
    public static boolean isVillagerRepairableGround(BlockState state) {
        return state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT);
    }

    /**
     * What scoured ground goes back to, read off the ground around it: a builder re-sods a track with
     * whatever the untouched surface beside it is, so a scour through grass comes back as grass and a
     * scour through sand comes back as sand. With nothing to copy from it is plain dirt rather than a
     * guess about the biome.
     */
    public static BlockState repairedSurface(Level level, BlockPos pos) {
        int[] tally = new int[NATURAL_SURFACES.size()];
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = pos.relative(direction);
            if (!level.isLoaded(neighbour)) {
                continue;
            }
            BlockState state = level.getBlockState(neighbour);
            int index = NATURAL_SURFACES.indexOf(state.getBlock());
            if (index >= 0) {
                tally[index]++;
            }
        }
        int best = -1;
        for (int i = 0; i < tally.length; i++) {
            if (tally[i] > 0 && (best < 0 || tally[i] > tally[best])) {
                best = i;
            }
        }
        if (best < 0) {
            return Blocks.DIRT.defaultBlockState();
        }
        return NATURAL_SURFACES.get(best).defaultBlockState();
    }

    /**
     * The unbarked original of a stripped trunk, found by name: the storm's own convention is
     * {@code stripped_<wood>}, so the repair is the same block without that prefix, in whatever
     * namespace the stripped block came from. Null when the name does not resolve, which is how a
     * builder refuses a trunk it cannot honestly put back rather than replacing it with something else.
     */
    @Nullable
    public static BlockState repairedLog(BlockState stripped) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(stripped.getBlock());
        String path = key.getPath();
        if (!path.startsWith("stripped_")) {
            return null;
        }
        ResourceLocation original = ResourceLocation.fromNamespaceAndPath(key.getNamespace(), path.substring("stripped_".length()));
        Block block = BuiltInRegistries.BLOCK.get(original);
        if (block == Blocks.AIR || !block.defaultBlockState().is(BlockTags.LOGS)) {
            return null;
        }
        return block.defaultBlockState();
    }
}
