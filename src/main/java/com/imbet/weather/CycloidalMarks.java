package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class CycloidalMarks {
    private CycloidalMarks() {
    }

    public static boolean tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.CYCLOIDAL_MARKS_ENABLED.get() || storm.levelCurIntensityStage < Config.CYCLOIDAL_MIN_STAGE.get()) {
            return false;
        }
        if (level.getGameTime() % 5L != 0L) {
            return false;
        }

        int changed = 0;
        int max = Config.CYCLOIDAL_MAX_BLOCKS_PER_TICK.get();
        if (max <= 0) {
            return false;
        }

        double radius = Math.max(5.0D, DynamicStormController.baseRadiusForWind(storm) * 0.95D);
        double heading = storm.motion == null || storm.motion.lengthSqr() < 1.0E-4D ? 0.0D : Math.atan2(storm.motion.z, storm.motion.x);
        int samples = Config.CYCLOIDAL_SWATH_SAMPLES.get();
        boolean strong = storm.levelCurIntensityStage >= Config.DAMAGE_MEDIUM_SCOURING_STAGE.get();
        for (int i = 0; i < samples && changed < max; i++) {
            double t = i / (double) Math.max(1, samples - 1);
            double phase = (level.getGameTime() * 0.16D) + t * Math.PI * 2.5D;
            double scallop = Math.sin(phase) * radius * 0.58D;
            double along = (t - 0.5D) * radius * 2.2D;
            double lateral = radius + scallop;
            Vec3 offset = new Vec3(Math.cos(heading) * along - Math.sin(heading) * lateral, 0.0D, Math.sin(heading) * along + Math.cos(heading) * lateral);
            if (markSurface(level, storm.pos.add(offset), strong)) {
                changed++;
            }
        }

        for (DynamicStormState.SubVortex subVortex : state.subVortices()) {
            if (changed >= max) {
                break;
            }
            if (markSurface(level, TornadoWindField.subVortexCenter(storm, subVortex, storm.pos.y), true)) {
                changed++;
            }
        }
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            if (changed >= max) {
                break;
            }
            Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
            for (int i = 0; i < 4 && changed < max; i++) {
                double angle = Math.toRadians(satellite.orbitDegrees()) + i * Math.PI * 0.5D;
                Vec3 offset = new Vec3(Math.cos(angle) * DynamicStormController.satelliteRadius(storm, satellite) * 0.65D, 0.0D, Math.sin(angle) * DynamicStormController.satelliteRadius(storm, satellite) * 0.65D);
                if (markSurface(level, center.add(offset), true)) {
                    changed++;
                }
            }
        }
        if (changed > 0) {
            state.setCycloidalMarksRecorded();
        }
        return changed > 0;
    }

    private static boolean markSurface(ServerLevel level, Vec3 position, boolean strong) {
        BlockPos sample = BlockPos.containing(position);
        if (!level.isLoaded(sample)) {
            return false;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample);
        BlockPos pos = surface.below();
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        Block replacement = replacementFor(state);
        if (replacement == null) {
            if ((strong || !Config.CYCLOIDAL_COSMETIC_ONLY.get()) && (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                return true;
            }
            return false;
        }
        BlockState replacementState = replacement.defaultBlockState();
        if (state.is(replacementState.getBlock())) {
            return false;
        }
        level.setBlock(pos, replacementState, 3);
        return true;
    }

    private static Block replacementFor(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
            return Blocks.DIRT_PATH;
        }
        if (state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)) {
            return Blocks.DIRT_PATH;
        }
        if (state.is(Blocks.FARMLAND)) {
            return Blocks.DIRT;
        }
        if (state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) {
            return Blocks.SANDSTONE;
        }
        if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)) {
            return Blocks.ICE;
        }
        return null;
    }
}
