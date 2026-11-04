package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
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
        // The cycloidal marks are gouged into the ground, so they are destruction like any other: the simple
        // settings' master switch has to reach them, or it would be a switch that turns off everything except
        // the scars.
        if (!Config.damageEnabled() || !Config.CYCLOIDAL_MARKS_ENABLED.get()
                || storm.levelCurIntensityStage < Config.CYCLOIDAL_MIN_STAGE.get()) {
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

        boolean multiVortexGroundContact = !state.subVortices().isEmpty();
        double radius = Math.max(5.0D, DynamicStormController.baseRadiusForWind(storm) * 0.95D);
        if (!multiVortexGroundContact) {
            double heading = storm.motion == null || storm.motion.lengthSqr() < 1.0E-4D ? 0.0D : Math.atan2(storm.motion.z, storm.motion.x);
            // The heading is the storm's, not the sample's: one cosine and one sine for the whole swath rather
            // than a pair per scallop.
            double headingCos = Math.cos(heading);
            double headingSin = Math.sin(heading);
            int samples = Config.CYCLOIDAL_SWATH_SAMPLES.get();
            for (int i = 0; i < samples && changed < max; i++) {
                double t = i / (double) Math.max(1, samples - 1);
                double phase = (level.getGameTime() * 0.16D) + t * Math.PI * 2.5D;
                double scallop = Math.sin(phase) * radius * 0.58D;
                double along = (t - 0.5D) * radius * 2.2D;
                double lateral = radius + scallop;
                Vec3 offset = new Vec3(headingCos * along - headingSin * lateral, 0.0D, headingSin * along + headingCos * lateral);
                if (markSurface(level, storm, storm.pos.add(offset), false)) {
                    changed++;
                }
            }
        }

        long markGameTime = level.getGameTime();
        for (DynamicStormState.SubVortex subVortex : state.subVortices()) {
            if (changed >= max) {
                break;
            }
            // Only vortices in ground contact cut marks, and each type cuts its own pattern: the
            // fast inner-core and suction vortices chew tight, dense scallops, while wide peripheral
            // vortices graze a broader, sparser one. Breakdown bubbles leave no ground marks.
            if (!subVortex.type().marksGround() || !subVortex.isGroundContact(markGameTime)) {
                continue;
            }
            Vec3 center = TornadoWindField.subVortexGroundCenter(level, storm, subVortex, storm.pos.y);
            double subRadius = Mth.clamp(radius * Math.max(0.35D, subVortex.radiusFraction()) * 0.45D, 2.5D, 20.0D);
            int subSamples = Mth.clamp((int) Math.round((5 + storm.levelCurIntensityStage + subVortex.intensityFraction() * 6.0D)
                    * (subVortex.type() == SubVortexType.CORE ? 1.35D : subVortex.type() == SubVortexType.PERIPHERAL ? 0.65D : 1.0D)), 5, 20);
            boolean heavy = subVortex.type() != SubVortexType.PERIPHERAL;
            if (markSurface(level, storm, center, heavy)) {
                changed++;
            }
            for (int i = 0; i < subSamples && changed < max; i++) {
                double phase = level.getGameTime() * 0.36D + subVortex.id() * 0.61D + i * Math.PI * 2.0D / Math.max(1, subSamples);
                double distance = subRadius * (0.28D + 0.72D * (((i * 37) % Math.max(1, subSamples)) / (double) Math.max(1, subSamples - 1)));
                double wobble = Math.sin(level.getGameTime() * 0.17D + i * 1.7D + subVortex.orbitDegrees() * 0.04D) * subRadius * 0.18D;
                Vec3 offset = new Vec3(Math.cos(phase) * (distance + wobble), 0.0D, Math.sin(phase) * (distance - wobble));
                if (markSurface(level, storm, center.add(offset), heavy)) {
                    changed++;
                }
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
                if (markSurface(level, storm, center.add(offset), false)) {
                    changed++;
                }
            }
        }
        if (changed > 0) {
            state.setCycloidalMarksRecorded();
        }
        return changed > 0;
    }

    private static boolean markSurface(ServerLevel level, StormObject storm, Vec3 position, boolean heavy) {
        BlockPos sample = BlockPos.containing(position);
        if (!level.isLoaded(sample) || storm == null || storm.pos == null) {
            return false;
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level, sample, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (surface == null) {
            return false;
        }
        BlockPos pos = surface.below();
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockGrabSafety.BlockSample sampleBlock = BlockGrabSafety.sample(level, pos, null);
        if (!BlockGrabSafety.canEdit(sampleBlock)) {
            return false;
        }
        BlockState state = sampleBlock.state();
        if (heavy) {
            removeLooseTopBlock(level, pos.above());
        }
        // Swath-scale damage wind: the core scours deep, the swath edge only scuffs.
        double wind = TornadoDamageModel.damageWindMph(storm, TornadoWindField.sample(storm, level, Vec3.atCenterOf(pos)));
        if (wind < 95.0D && !heavy) {
            return false;
        }
        // Patchy edges: weak wind spots mark inconsistently, the violent core is solid.
        float patchiness = (float) Mth.clamp((wind - 90.0D) / 95.0D, 0.18D, 1.0D);
        if (!heavy && level.random.nextFloat() > patchiness) {
            return false;
        }
        // Violent core: the topsoil is scoured clean off into a shallow pit instead
        // of just being converted - real tornado cores excavate bare earth.
        if (Config.DAMAGE_SCOURING_EXCAVATION.get() && wind >= 185.0D
                && !state.is(BlockTags.BASE_STONE_OVERWORLD)
                && !state.is(Blocks.BEDROCK)
                && !state.is(Blocks.SANDSTONE)
                && !state.is(Blocks.SMOOTH_SANDSTONE)) {
            BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        Block replacement = replacementFor(state, wind);
        if (replacement == null) {
            if ((wind >= 120.0D || !Config.CYCLOIDAL_COSMETIC_ONLY.get()) && (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES))) {
                BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                return true;
            }
            return false;
        }
        // Bare-earth variation: violent scouring leaves rough exposed soil mixed
        // with rooted clumps, not a uniform path carpet.
        if (wind >= 130.0D && (replacement == Blocks.DIRT_PATH || replacement == Blocks.COARSE_DIRT) && level.random.nextFloat() < 0.30F) {
            replacement = Blocks.ROOTED_DIRT;
        }
        BlockState replacementState = replacement.defaultBlockState();
        if (state.is(replacementState.getBlock())) {
            return false;
        }
        BlockGrabSafety.safeSetBlock(level, pos, replacementState, Block.UPDATE_ALL);
        return true;
    }

    private static Block replacementFor(BlockState state, double wind) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
            return wind >= 150.0D ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH;
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

    private static void removeLooseTopBlock(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return;
        }
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (!BlockGrabSafety.canEdit(sample)) {
            return;
        }
        BlockState state = sample.state();
        if (state.is(BlockTags.CROPS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS)) {
            BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
