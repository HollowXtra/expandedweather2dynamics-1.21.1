package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.StormStateSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.TornadoHelper;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class DynamicStormController {
    private static final Map<StormObject, DynamicStormState> STATES = new WeakHashMap<>();
    private static final String NBT_KEY = "expandedweather2dynamics";

    private DynamicStormController() {
    }

    public static DynamicStormState stateFor(StormObject storm) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(storm, key -> new DynamicStormState(key.ID));
        }
    }

    public static void tickStorm(StormObject storm) {
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            return;
        }
        Level world = storm.manager.getWorld();
        DynamicStormState state = stateFor(storm);
        long gameTime = world.getGameTime();
        if (world instanceof ServerLevel serverLevel) {
            state.tickServer(serverLevel, storm, gameTime);
            applyRotationDirection(serverLevel, storm, state);
            applyMergeOrbit(storm, state, gameTime);
        } else {
            CompoundTag syncedState = ClientStormStateStore.consume(storm.ID);
            if (syncedState != null && !syncedState.isEmpty()) {
                state.load(syncedState);
            }
            applyRotationDirection(world, storm, state);
            state.tickVisual(gameTime);
        }
        state.smooth();
        SupercellStructureService.tick(world, storm, state);
        state.setSubtype(StormClassifier.classify(storm, state));
        if (state.isAbsorbedOrDecaying()) {
            if (world instanceof ServerLevel serverLevel) {
                syncAddonState(serverLevel, storm, state, gameTime);
            }
            return;
        }
        updateSubVortices(storm, state);
        if (world instanceof ServerLevel serverLevel) {
            updateSatelliteTornadoes(storm, state);
            TornadoMergeService.tick(serverLevel, storm, state);
            CirculationDamage.tick(serverLevel, storm, state);
            applyEnvironmentalIntensity(serverLevel, storm, state);
            TreeDamage.tick(serverLevel, storm, state);
            if (CycloidalMarks.tick(serverLevel, storm, state)) {
                awardNearby(serverLevel, storm.pos, AdvancementAwarder.CYCLOIDAL_MARKS, 96.0D);
            }
            if (!state.subVortices().isEmpty()) {
                awardNearby(serverLevel, storm.pos, AdvancementAwarder.MULTIVORTEX, 220.0D);
            }
            if (state.recentlyReintensified()) {
                awardNearby(serverLevel, storm.pos, AdvancementAwarder.REINTENSIFICATION, 320.0D);
                state.clearRecentlyReintensified();
            }
            syncAddonState(serverLevel, storm, state, gameTime);
        }
    }

    public static float adjustedAngle(StormObject storm, float originalAngle) {
        if (!Config.DYNAMICS_ENABLED.get() || storm == null || !isDynamicVortex(storm)) {
            return originalAngle;
        }
        if (storm.weatherMachineControlled || storm.isPlayerControlled() || storm.isPet()) {
            return originalAngle;
        }
        return originalAngle + stateFor(storm).wanderAngle();
    }

    public static int adjustedTornadoBaseSize(StormObject storm, int originalSize) {
        if (!Config.VARIABLE_WIDTHS_ENABLED.get() || storm == null || !isDynamicVortex(storm)) {
            return originalSize;
        }
        DynamicStormState state = stateFor(storm);
        int visibleCap = Math.max(3, (int) Math.min(Integer.MAX_VALUE, Math.round(Config.effectiveMaxVisibleFunnelWidthBlocks())));
        if (Config.TORNADO_REALISTIC_WIDTH_MODEL.get()) {
            return Mth.clamp(Math.round(state.visibleFunnelWidth()), 3, visibleCap);
        }
        return Mth.clamp(Math.round(originalSize * state.widthMultiplier()), 3, visibleCap);
    }

    public static Vec3 bendFunnelLayer(StormObject storm, Vec3 basePos, Vec3 layerPos, int layerIndex, int layerCount) {
        if (!Config.TORNADO_FUNNEL_BEND_ENABLED.get() || storm == null || basePos == null || layerPos == null || !isDynamicVortex(storm) || layerCount <= 1) {
            return layerPos;
        }
        DynamicStormState state = stateFor(storm);
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float motionAngle = motionAngleDegrees(storm);
        float bendAngle = state.bendAngle();
        if (storm.motion != null && storm.motion.horizontalDistanceSqr() > 0.0004D) {
            bendAngle = Mth.rotLerp(0.42F, bendAngle, motionAngle);
        }
        double radians = Math.toRadians(bendAngle);
        Vec3 lean = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
        Vec3 cross = new Vec3(-lean.z, 0.0D, lean.x);
        double anchor = Config.TORNADO_FUNNEL_BEND_ANCHOR_FRACTION.get();
        if (fraction <= anchor) {
            return layerPos;
        }
        double effectiveFraction = Mth.clamp((fraction - anchor) / Math.max(0.05D, 1.0D - anchor), 0.0D, 1.0D);
        double radiusClamp = Math.max(2.0D, baseRadiusForWind(storm) * Config.TORNADO_FUNNEL_BEND_MAX_HEIGHT_FRACTION.get());
        double maxBend = Math.min(Config.TORNADO_FUNNEL_BEND_MAX_BLOCKS.get(), radiusClamp) * state.bendStrength();
        double sway = Config.TORNADO_FUNNEL_BEND_SWAY_BLOCKS.get();
        double heightEase = Math.pow(effectiveFraction, 1.55D);
        double curve = Math.sin(effectiveFraction * Math.PI) * sway * Math.sin(state.bendPhase() + effectiveFraction * Math.PI * 1.35D);
        double upperKink = Math.sin(effectiveFraction * Math.PI * 2.0D + state.bendPhase() * 0.72D) * sway * 0.24D * effectiveFraction;
        Vec3 offset = lean.scale(maxBend * heightEase + upperKink).add(cross.scale(curve));
        return new Vec3(basePos.x + offset.x, layerPos.y, basePos.z + offset.z);
    }

    public static void saveStateToStorm(StormObject storm) {
        if (!Config.PERSISTENCE_ENABLED.get() || storm == null || storm.getNbtCache() == null) {
            return;
        }
        storm.getNbtCache().put(NBT_KEY, stateFor(storm).save());
    }

    public static void loadStateFromStorm(StormObject storm) {
        if (!Config.PERSISTENCE_ENABLED.get() || storm == null || storm.getNbtCache() == null || !storm.getNbtCache().contains(NBT_KEY)) {
            return;
        }
        CompoundTag tag = storm.getNbtCache().get(NBT_KEY);
        stateFor(storm).load(tag);
    }

    public static void afterStageNext(StormObject storm) {
        if (!Config.CYCLONES_ENABLED.get() || storm == null) {
            return;
        }
        if (storm.isTropicalCyclone() || storm.isHurricane() || storm.isCycloneFormingOrGreater()) {
            stateFor(storm).setSubtype(StormSubtype.CYCLONE);
        }
    }

    public static boolean shouldCancelNaturalPlayerAim(StormObject storm) {
        return Config.NO_PLAYER_AIM_ON_SPAWN.get()
                && storm != null
                && !storm.weatherMachineControlled
                && !storm.isPlayerControlled()
                && !storm.isPet();
    }

    public static Vec3 adjustEntityMotion(StormObject storm, Vec3 position, Vec3 originalMotion) {
        if (!Config.WINDFIELD_ENABLED.get() || storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            return originalMotion;
        }
        TornadoWindField.WindSample sample = isDynamicVortex(storm)
                ? TornadoWindField.sample(storm, storm.manager.getWorld(), position)
                : SupercellStructureService.sampleWind(storm, storm.manager.getWorld(), position);
        if (!isDynamicVortex(storm) && !Config.SUPERCELL_STRUCTURE_RFD_STRAIGHT_LINE_WIND.get()) {
            return originalMotion;
        }
        if (sample.speedMph() <= 1.0D) {
            return originalMotion;
        }
        return originalMotion.add(TornadoWindField.mphToEntityDelta(sample.mphVector()));
    }

    public static boolean shouldTryRip(TornadoHelper helper, Level level, BlockPos pos) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null) {
            return false;
        }
        if (Config.DAMAGE_LOADED_CHUNKS_ONLY.get() && !level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (Config.DAMAGE_OPEN_DOORS.get() && state.hasProperty(BlockStateProperties.OPEN) && !state.getValue(BlockStateProperties.OPEN)) {
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, true), 3);
            level.updateNeighborsAt(pos, state.getBlock());
            return false;
        }
        return stateFor(helper.storm).tryConsumeBlockDamage(level.getGameTime());
    }

    public static boolean canGrabBlock(TornadoHelper helper, Level level, BlockState state, BlockPos pos) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null) {
            return false;
        }
        if (Config.DAMAGE_LOADED_CHUNKS_ONLY.get() && !level.isLoaded(pos)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.CHAIN_COMMAND_BLOCK) || state.is(Blocks.REPEATING_COMMAND_BLOCK)) {
            return false;
        }
        if (Config.DAMAGE_PRESERVE_INVENTORY_BLOCK_ENTITIES.get() && level.getBlockEntity(pos) != null) {
            return false;
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, level, Vec3.atCenterOf(pos));
        float strengthMultiplier = strengthMultiplierFor(helper.storm);
        if (wind.strongestLocalMph() > TornadoWindField.maxWindMph(helper.storm) * 0.82D) {
            strengthMultiplier *= Config.MULTIVORTEX_DAMAGE_MULTIPLIER.get().floatValue();
        }
        float stageAllowance = Math.max(0, helper.storm.levelCurIntensityStage - StormObject.STATE_FORMING + 1) * 1.25F;
        float maxHardness = Math.max(1.0F, stageAllowance * strengthMultiplier);
        if (hardness > maxHardness) {
            return false;
        }
        int surfaceDepth = depthBelowSurface(level, pos);
        if (surfaceDepth > 0) {
            if (!Config.DAMAGE_GROUND_SCOURING.get()) {
                return false;
            }
            int requiredStage = Config.DAMAGE_LIGHT_SCOURING_STAGE.get();
            if (surfaceDepth >= 2) {
                requiredStage = Config.DAMAGE_MEDIUM_SCOURING_STAGE.get();
            }
            if (surfaceDepth >= 4) {
                requiredStage = Config.DAMAGE_HEAVY_SCOURING_STAGE.get();
            }
            if (helper.storm.levelCurIntensityStage < requiredStage) {
                return false;
            }
        }
        return true;
    }

    public static boolean canGrabEntity(TornadoHelper helper, Entity entity) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null) {
            return false;
        }
        if (entity instanceof Player) {
            return Config.DAMAGE_GRAB_PLAYERS.get();
        }
        if (entity instanceof LivingEntity) {
            return Config.DAMAGE_GRAB_LIVING_ENTITIES.get();
        }
        return true;
    }

    public static boolean isDynamicVortex(StormObject storm) {
        return storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout;
    }

    public static boolean isAntiCyclonicTornado(StormObject storm) {
        return Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get()
                && storm != null
                && storm.isTornadoFormingOrGreater()
                && !storm.attrib_waterSpout
                && stateFor(storm).isAntiCyclonic();
    }

    public static double rotationSign(StormObject storm) {
        return isAntiCyclonicTornado(storm) ? -1.0D : 1.0D;
    }

    public static float visualSpinSpeed(StormObject storm) {
        return storm == null ? 0.0F : stateFor(storm).visualSpinSpeed();
    }

    public static void applyClientVisualSpin(StormObject storm) {
        if (storm == null || !Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get() || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout) {
            return;
        }
        DynamicStormState state = stateFor(storm);
        applySignedVisualSpin(storm, state);
    }

    public static float extraFunnelLayerSpinDegrees(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || !Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get() || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return 0.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float layerScale = Mth.lerp(fraction, 0.65F, 1.0F);
        float spinDelta = (stateFor(storm).visualSpinSpeed() - 0.08F) * 70.0F;
        return Mth.clamp(spinDelta * layerScale * (float) rotationSign(storm), -6.0F, 24.0F);
    }

    public static boolean isAbsorbedOrDecaying(StormObject storm) {
        return storm != null && stateFor(storm).isAbsorbedOrDecaying();
    }

    public static int rawBaseRadiusForWind(StormObject storm) {
        if (storm == null || storm.tornadoHelper == null) {
            return 8;
        }
        return Math.max(8, storm.tornadoHelper.getTornadoBaseSize());
    }

    public static int baseRadiusForWind(StormObject storm) {
        if (storm == null) {
            return 8;
        }
        if (Config.TORNADO_REALISTIC_WIDTH_MODEL.get()) {
            return Math.max(8, Math.round(stateFor(storm).damagePathWidth() * 0.5F));
        }
        return rawBaseRadiusForWind(storm);
    }

    public static double radiusOfMaxWind(StormObject storm) {
        if (storm == null) {
            return 3.0D;
        }
        if (Config.TORNADO_REALISTIC_WIDTH_MODEL.get()) {
            return Math.max(3.0D, stateFor(storm).radiusOfMaxWind());
        }
        return Math.max(3.0D, baseRadiusForWind(storm) * 0.55D);
    }

    public static Vec3 satelliteCenter(StormObject storm, DynamicStormState.TornadoCirculationState satellite, double y) {
        return satellite.center(storm.pos, y);
    }

    public static double satelliteWindMph(StormObject storm, DynamicStormState.TornadoCirculationState satellite) {
        return TornadoWindField.maxWindMph(storm) * satellite.windFraction();
    }

    public static double satelliteRadius(StormObject storm, DynamicStormState.TornadoCirculationState satellite) {
        return Math.max(3.0D, satellite.damagePathWidth() * 0.5D);
    }

    private static float motionAngleDegrees(StormObject storm) {
        if (storm.motion != null && storm.motion.horizontalDistanceSqr() > 0.0004D) {
            return (float) Math.toDegrees(Math.atan2(storm.motion.z, storm.motion.x));
        }
        return storm.getAdjustedAngle();
    }

    public static boolean isOverWaterOrOcean(Level level, double x, double z) {
        BlockPos sample = new BlockPos(Mth.floor(x), 0, Mth.floor(z));
        if (!level.isLoaded(sample)) {
            return false;
        }
        BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sample);
        BlockState below = level.getBlockState(surface.below());
        if (below.getFluidState().is(FluidTags.WATER)) {
            return true;
        }
        return level.getBiome(surface).unwrapKey()
                .map(key -> key.location().getPath().contains("ocean") || key.location().getPath().contains("beach"))
                .orElse(false);
    }

    private static float strengthMultiplierFor(StormObject storm) {
        float min = Config.DAMAGE_BLOCK_STRENGTH_MIN_MULTIPLIER.get().floatValue();
        float max = Config.DAMAGE_BLOCK_STRENGTH_MAX_MULTIPLIER.get().floatValue();
        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        return Mth.lerp(stage, Math.min(min, max), Math.max(min, max));
    }

    private static int depthBelowSurface(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return 0;
        }
        BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos);
        return Math.max(0, surface.getY() - 1 - pos.getY());
    }

    private static void updateSubVortices(StormObject storm, DynamicStormState state) {
        if (!Config.MULTIVORTEX_ENABLED.get() || storm.levelCurIntensityStage < Config.MULTIVORTEX_MIN_STAGE.get() || !isDynamicVortex(storm)) {
            state.updateSubVortices(storm.manager.getWorld().getGameTime(), 0, baseRadiusForWind(storm));
            return;
        }
        int stageOverMinimum = Math.max(0, storm.levelCurIntensityStage - Config.MULTIVORTEX_MIN_STAGE.get());
        int desired = Mth.clamp(1 + stageOverMinimum + (storm.levelCurStagesIntensity > 0.65F ? 1 : 0), 0, Config.MULTIVORTEX_MAX_COUNT.get());
        state.updateSubVortices(storm.manager.getWorld().getGameTime(), desired, baseRadiusForWind(storm));
    }

    private static void updateSatelliteTornadoes(StormObject storm, DynamicStormState state) {
        if (!Config.SATELLITE_TORNADOES_ENABLED.get() || !Config.SATELLITE_TORNADOES_REAL_CIRCULATION.get() || !storm.isTornadoFormingOrGreater() || storm.manager == null || storm.manager.getWorld() == null) {
            state.updateSatelliteTornadoes(0L, 0, storm == null ? 0 : storm.levelCurIntensityStage, Config.SATELLITE_TORNADOES_MIN_PARENT_STAGE.get(), 0.0F, storm == null ? null : storm.pos, storm == null ? null : storm.motion, Config.SATELLITE_TORNADOES_MAX_WIND_FRACTION.get());
            return;
        }
        state.updateSatelliteTornadoes(
                storm.manager.getWorld().getGameTime(),
                Config.SATELLITE_TORNADOES_MAX_ACTIVE.get(),
                storm.levelCurIntensityStage,
                Config.SATELLITE_TORNADOES_MIN_PARENT_STAGE.get(),
                baseRadiusForWind(storm),
                storm.pos,
                storm.motion,
                Config.SATELLITE_TORNADOES_MAX_WIND_FRACTION.get()
        );
    }

    private static void applyRotationDirection(Level world, StormObject storm, DynamicStormState state) {
        if (storm == null || state == null) {
            return;
        }
        if (!storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout) {
            if (Config.ANTI_CYCLONIC_INVERT_WEATHER2_SPIN.get() && storm.spinSpeed < 0.0D) {
                storm.spinSpeed = Math.abs(storm.spinSpeed);
            }
            return;
        }
        if (world instanceof ServerLevel level && !state.rotationAssigned()) {
            boolean eligible = Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get()
                    && !storm.weatherMachineControlled
                    && !storm.isPlayerControlled()
                    && !storm.isPet();
            state.setAntiCyclonic(eligible && level.random.nextDouble() < Config.ANTI_CYCLONIC_TORNADO_CHANCE.get());
        }
        if (Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get()) {
            state.updateVisualSpin(TornadoWindField.maxWindMph(storm));
            applySignedVisualSpin(storm, state);
            return;
        }
        if (!Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get() || !Config.ANTI_CYCLONIC_INVERT_WEATHER2_SPIN.get()) {
            return;
        }
        double magnitude = Math.abs(storm.spinSpeed);
        if (magnitude < 1.0E-5D) {
            magnitude = 0.08D;
        }
        storm.spinSpeed = state.isAntiCyclonic() ? -magnitude : magnitude;
    }

    private static void applySignedVisualSpin(StormObject storm, DynamicStormState state) {
        double magnitude = Math.max(0.0D, state.visualSpinSpeed());
        storm.spinSpeed = magnitude * rotationSign(storm);
    }

    private static void applyMergeOrbit(StormObject storm, DynamicStormState state, long gameTime) {
        if (!Config.MERGE_ORBIT_BEFORE_ABSORB.get() || storm == null || storm.pos == null || !state.mergeOrbitActive(gameTime)) {
            return;
        }
        Vec3 target = state.mergeOrbitTarget(storm.pos.y);
        Vec3 delta = target.subtract(storm.pos);
        Vec3 horizontal = new Vec3(delta.x, 0.0D, delta.z);
        if (horizontal.horizontalDistanceSqr() < 1.0E-4D) {
            return;
        }
        Vec3 desired = horizontal.normalize().scale(Config.MERGE_ORBIT_STRENGTH.get());
        Vec3 current = storm.motion == null ? Vec3.ZERO : storm.motion;
        storm.motion = current.scale(0.84D).add(desired.scale(0.16D));
    }

    private static void syncAddonState(ServerLevel level, StormObject storm, DynamicStormState state, long gameTime) {
        int interval = Math.max(5, Config.MULTIPLAYER_STATE_SYNC_INTERVAL_TICKS.get());
        if (!Config.PERSISTENCE_ENABLED.get() || !isDynamicVortex(storm) || gameTime % interval != 0L) {
            return;
        }
        PacketDistributor.sendToPlayersNear(
                level,
                null,
                storm.pos.x,
                storm.pos.y,
                storm.pos.z,
                Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get(),
                new StormStateSyncPayload(storm.ID, state.save())
        );
    }

    private static void applyEnvironmentalIntensity(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.ENVIRONMENTAL_INTENSITY_ENABLED.get() || !storm.isRealStorm() || storm.weatherMachineControlled) {
            return;
        }
        long gameTime = level.getGameTime();
        if (!state.shouldEvaluateIntensity(gameTime)) {
            return;
        }
        state.scheduleNextIntensityEvaluation(gameTime, Config.ENVIRONMENT_SCAN_INTERVAL_TICKS.get());
        if (!state.canChangeIntensity(gameTime)) {
            return;
        }

        double score = environmentScore(level, storm);
        if (score >= Config.ENVIRONMENT_REINTENSIFY_THRESHOLD.get() && storm.levelCurIntensityStage < Math.min(storm.maxIntensityStage, StormObject.STATE_STAGE5)) {
            storm.hasStormPeaked = false;
            storm.levelCurStagesIntensity = Math.min(1.05F, storm.levelCurStagesIntensity + 0.22F);
            if (storm.levelCurStagesIntensity >= 1.0F) {
                storm.stageNext();
                state.markIntensityChanged(gameTime, true);
            }
        } else if (score <= Config.ENVIRONMENT_DEINTENSIFY_THRESHOLD.get() && storm.levelCurIntensityStage > StormObject.STATE_THUNDER) {
            storm.levelCurStagesIntensity = Math.max(-0.05F, storm.levelCurStagesIntensity - 0.24F);
            if (storm.levelCurStagesIntensity <= 0.0F) {
                storm.stagePrev();
                state.markIntensityChanged(gameTime, false);
            }
        }
    }

    private static double environmentScore(ServerLevel level, StormObject storm) {
        BlockPos pos = BlockPos.containing(storm.pos);
        float temperature = level.getBiome(pos).value().getBaseTemperature();
        double temperatureScore = Mth.clamp((temperature + 0.15D) / 1.45D, 0.0D, 1.0D);
        boolean overWater = isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
        double moistureScore = overWater || storm.isPrecipitating() ? 1.0D : 0.42D;
        double windScore = Mth.clamp(storm.manager.getWindManager().getWindSpeedForClouds() / 1.2D, 0.0D, 1.0D);
        double inflowScore = Mth.clamp(TornadoWindField.maxWindMph(storm) / 280.0D, 0.0D, 1.0D);
        double terrainPenalty = terrainRoughnessPenalty(level, pos);
        return Mth.clamp((temperatureScore * 0.28D) + (moistureScore * 0.24D) + (windScore * 0.24D) + (inflowScore * 0.24D) - terrainPenalty, 0.0D, 1.0D);
    }

    private static double terrainRoughnessPenalty(ServerLevel level, BlockPos center) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = -24; x <= 24; x += 12) {
            for (int z = -24; z <= 24; z += 12) {
                BlockPos sample = center.offset(x, 0, z);
                if (!level.isLoaded(sample)) {
                    continue;
                }
                int y = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sample).getY();
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (min == Integer.MAX_VALUE) {
            return 0.0D;
        }
        return Mth.clamp((max - min) / 80.0D, 0.0D, 0.28D);
    }

    private static void awardNearby(ServerLevel level, Vec3 pos, String advancement, double range) {
        List<ServerPlayer> players = level.players();
        double rangeSqr = range * range;
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(pos) <= rangeSqr) {
                AdvancementAwarder.award(player, advancement);
            }
        }
    }
}
