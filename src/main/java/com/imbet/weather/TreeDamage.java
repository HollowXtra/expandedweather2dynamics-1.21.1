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

/**
 * What a tornado does to the trees it passes: debarks the logs it has the wind for, and thins the crown of one it
 * has much more than that for.
 *
 * <p>Every wood, not the game's own. The stripped form of a log used to be a table of the game's eight woods
 * written into this class, which meant the mod's own trees were the only ones a tornado could debark: install a
 * biome mod and the woods a player actually came for were the ones left standing dressed while the vanilla ones
 * around them were stripped of their bark. The table is gone - {@link WoodForms} asks the game, and the wood's own
 * name, what a log strips to - so a fir is debarked like an oak, whichever mod the fir came from.
 *
 * <p>The rest of the shape is unchanged and deliberately so: it is the leaves tag that decides what a crown is, so
 * a mod's leaves thin exactly as the game's do, and the height window, the wind floor and the change budget are
 * the config's.
 */
public final class TreeDamage {

    private TreeDamage() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.damageEnabled() || !Config.DAMAGE_TREE_DEBARKING.get()
                || storm == null || storm.pos == null || level.getGameTime() % 10L != 0L) {
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
            BlockPos surface = BlockGrabSafety.safeSurface(level, base, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos.MutableBlockPos cursor = surface.mutable();
            for (int y = -2; y <= 10 && changed < maxChanges; y++) {
                cursor.set(surface.getX(), surface.getY() + y, surface.getZ());
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, cursor, null);
                if (!BlockGrabSafety.canEdit(block, false)) {
                    continue;
                }
                BlockState blockState = block.state();
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
        Block stripped = WoodForms.stripped(state.getBlock());
        if (stripped == null) {
            return false;
        }
        BlockState replacement = stripped.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.AXIS) && replacement.hasProperty(BlockStateProperties.AXIS)) {
            replacement = replacement.setValue(BlockStateProperties.AXIS, state.getValue(BlockStateProperties.AXIS));
        }
        return BlockGrabSafety.safeSetBlock(level, pos, replacement, 3);
    }

    private static boolean thinLeaves(ServerLevel level, BlockPos pos, BlockState state, double localMph) {
        if (localMph < Config.DAMAGE_HEAVY_TREE_DEBARK_MPH.get() || !state.is(BlockTags.LEAVES)) {
            return false;
        }
        if (level.random.nextFloat() > 0.55F) {
            return false;
        }
        return BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3);
    }
}
