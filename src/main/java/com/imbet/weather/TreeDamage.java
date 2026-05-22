package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.Map;

public final class TreeDamage {
    private static final Map<Block, Block> STRIPPED = Map.ofEntries(
            Map.entry(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG),
            Map.entry(Blocks.SPRUCE_LOG, Blocks.STRIPPED_SPRUCE_LOG),
            Map.entry(Blocks.BIRCH_LOG, Blocks.STRIPPED_BIRCH_LOG),
            Map.entry(Blocks.JUNGLE_LOG, Blocks.STRIPPED_JUNGLE_LOG),
            Map.entry(Blocks.ACACIA_LOG, Blocks.STRIPPED_ACACIA_LOG),
            Map.entry(Blocks.DARK_OAK_LOG, Blocks.STRIPPED_DARK_OAK_LOG),
            Map.entry(Blocks.MANGROVE_LOG, Blocks.STRIPPED_MANGROVE_LOG),
            Map.entry(Blocks.CHERRY_LOG, Blocks.STRIPPED_CHERRY_LOG),
            Map.entry(Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_WOOD),
            Map.entry(Blocks.SPRUCE_WOOD, Blocks.STRIPPED_SPRUCE_WOOD),
            Map.entry(Blocks.BIRCH_WOOD, Blocks.STRIPPED_BIRCH_WOOD),
            Map.entry(Blocks.JUNGLE_WOOD, Blocks.STRIPPED_JUNGLE_WOOD),
            Map.entry(Blocks.ACACIA_WOOD, Blocks.STRIPPED_ACACIA_WOOD),
            Map.entry(Blocks.DARK_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD),
            Map.entry(Blocks.MANGROVE_WOOD, Blocks.STRIPPED_MANGROVE_WOOD),
            Map.entry(Blocks.CHERRY_WOOD, Blocks.STRIPPED_CHERRY_WOOD),
            Map.entry(Blocks.CRIMSON_STEM, Blocks.STRIPPED_CRIMSON_STEM),
            Map.entry(Blocks.WARPED_STEM, Blocks.STRIPPED_WARPED_STEM),
            Map.entry(Blocks.CRIMSON_HYPHAE, Blocks.STRIPPED_CRIMSON_HYPHAE),
            Map.entry(Blocks.WARPED_HYPHAE, Blocks.STRIPPED_WARPED_HYPHAE)
    );

    private TreeDamage() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.DAMAGE_TREE_DEBARKING.get() || storm == null || storm.pos == null || level.getGameTime() % 10L != 0L) {
            return;
        }
        damageNear(level, storm, storm.pos, Math.max(8.0D, DynamicStormController.baseRadiusForWind(storm) * 0.9D), TornadoWindField.maxWindMph(storm), 18);
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
            damageNear(level, storm, center, DynamicStormController.satelliteRadius(storm, satellite), DynamicStormController.satelliteWindMph(storm, satellite), 8);
        }
    }

    private static void damageNear(ServerLevel level, StormObject storm, Vec3 center, double radius, double maxWindMph, int attempts) {
        if (maxWindMph < Config.DAMAGE_TREE_DEBARK_MPH.get()) {
            return;
        }
        int changed = 0;
        int maxChanges = maxWindMph >= Config.DAMAGE_HEAVY_TREE_DEBARK_MPH.get() ? 12 : 6;
        for (int i = 0; i < attempts && changed < maxChanges; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = level.random.nextDouble() * radius * 1.65D;
            BlockPos base = BlockPos.containing(center.x + Math.cos(angle) * distance, center.y, center.z + Math.sin(angle) * distance);
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base);
            BlockPos.MutableBlockPos cursor = surface.mutable();
            for (int y = -2; y <= 10 && changed < maxChanges; y++) {
                cursor.set(surface.getX(), surface.getY() + y, surface.getZ());
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                BlockState blockState = level.getBlockState(cursor);
                TornadoWindField.WindSample sample = TornadoWindField.sample(storm, level, Vec3.atCenterOf(cursor));
                double localMph = Math.max(maxWindMph * Mth.clamp(1.0D - Vec3.atCenterOf(cursor).distanceTo(center) / Math.max(1.0D, radius * 2.0D), 0.0D, 1.0D), sample.strongestLocalMph());
                if (stripLog(level, cursor, blockState, localMph)) {
                    changed++;
                } else if (thinLeaves(level, cursor, blockState, localMph)) {
                    changed++;
                }
            }
        }
    }

    private static boolean stripLog(ServerLevel level, BlockPos pos, BlockState state, double localMph) {
        if (localMph < Config.DAMAGE_TREE_DEBARK_MPH.get()) {
            return false;
        }
        Block stripped = STRIPPED.get(state.getBlock());
        if (stripped == null) {
            return false;
        }
        BlockState replacement = stripped.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.AXIS) && replacement.hasProperty(BlockStateProperties.AXIS)) {
            replacement = replacement.setValue(BlockStateProperties.AXIS, state.getValue(BlockStateProperties.AXIS));
        }
        level.setBlock(pos, replacement, 3);
        return true;
    }

    private static boolean thinLeaves(ServerLevel level, BlockPos pos, BlockState state, double localMph) {
        if (localMph < Config.DAMAGE_HEAVY_TREE_DEBARK_MPH.get() || !state.is(BlockTags.LEAVES)) {
            return false;
        }
        if (level.random.nextFloat() > 0.55F) {
            return false;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        return true;
    }
}
