package com.imbet.weather;

import com.imbet.Config;
import com.imbet.compat.immersiveaircraft.ImmersiveAircraftStormCompat;
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
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import com.imbet.network.ModNetworking;
import weather2.weathersystem.WeatherManagerServer;
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
        updateHorizontalVortices(storm, state);
        if (world instanceof ServerLevel serverLevel) {
            updateSatelliteTornadoes(storm, state);
            TornadoMergeService.tick(serverLevel, storm, state);
            CirculationDamage.tick(serverLevel, storm, state);
            FlyingBlockImpactDamage.tick(serverLevel, storm, state);
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
        if (state.wedgeMorphologyActive()) {
            int forcedWedgeCap = (int) Math.round(Math.min(Config.milesToBlocks(1.0D), Math.max(state.visibleFunnelWidth(), Config.milesToBlocks(0.5D))));
            visibleCap = Math.max(visibleCap, forcedWedgeCap);
        }
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

    public static double mainFunnelGroundLift(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return 0.0D;
        }
        DynamicStormState state = stateFor(storm);
        FunnelMorphology morphology = state.targetFunnelMorphology().shaped() ? state.targetFunnelMorphology() : state.funnelMorphology();
        if (morphology != FunnelMorphology.FLOATER || !Config.FLOATER_TORNADOES_ENABLED.get()) {
            return 0.0D;
        }
        float blend = state.funnelMorphologyBlend();
        if (blend <= 0.001F) {
            return 0.0D;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        double v = Mth.clamp(fraction / 0.68D, 0.0D, 1.0D);
        double lowerWeight = 1.0D - (v * v * (3.0D - 2.0D * v));
        double lift = Mth.clamp(18.0D + state.visibleFunnelWidth() * 0.22D, 18.0D, 64.0D);
        return lift * lowerWeight * blend;
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
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (!BlockGrabSafety.canEdit(sample)) {
            return false;
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, level, Vec3.atCenterOf(pos));
        if (!BlockGrabSafety.canPickupByWind(sample, wind.strongestLocalMph())) {
            return false;
        }
        if (Config.DAMAGE_OPEN_DOORS.get() && BlockGrabSafety.tryOpenDoor(level, sample)) {
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
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, state);
        if (!BlockGrabSafety.canEdit(sample)) {
            return false;
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, level, Vec3.atCenterOf(pos));
        double localWindMph = wind.strongestLocalMph();
        if (!BlockGrabSafety.canPickupByWind(sample, localWindMph)) {
            return false;
        }
        float hardness = sample.destroySpeed();
        if (hardness < 0.0F) {
            return false;
        }
        float strengthMultiplier = strengthMultiplierFor(helper.storm);
        if (wind.strongestLocalMph() > TornadoWindField.maxWindMph(helper.storm) * 0.82D) {
            strengthMultiplier *= Config.MULTIVORTEX_DAMAGE_MULTIPLIER.get().floatValue();
        }
        float maxHardness = (float) Math.max(0.75D, ((localWindMph - 45.0D) / 28.0D) * strengthMultiplier);
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
        if (isVehiclePickupTarget(entity)) {
            return shouldForceGrabEntity(helper, entity);
        }
        if (entity instanceof Player) {
            return Config.DAMAGE_GRAB_PLAYERS.get();
        }
        if (entity instanceof LivingEntity) {
            return Config.DAMAGE_GRAB_LIVING_ENTITIES.get();
        }
        return true;
    }

    public static boolean shouldForceGrabEntity(TornadoHelper helper, Entity entity) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null || entity == null) {
            return false;
        }
        if (!isVehiclePickupTarget(entity)) {
            return false;
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, entity.level(), entity.position());
        return isInsideVehiclePickupWindField(helper.storm, entity.level(), entity.position(), wind);
    }

    private static boolean isVehiclePickupTarget(Entity entity) {
        return entity instanceof Boat || ImmersiveAircraftStormCompat.isAircraftEntity(entity);
    }

    private static boolean isInsideVehiclePickupWindField(StormObject storm, Level level, Vec3 position, TornadoWindField.WindSample wind) {
        if (storm == null || level == null || position == null || wind == null) {
            return false;
        }
        double windMph = Math.max(wind.speedMph(), wind.strongestLocalMph());
        if (windMph < 35.0D) {
            return false;
        }
        double parentPickupRadius = Math.max(wind.baseRadius() * 2.15D, wind.radiusMaxWind() * 4.25D);
        if (wind.distanceFromCore() <= parentPickupRadius) {
            return true;
        }

        DynamicStormState state = stateFor(storm);
        double baseRadius = Math.max(6.0D, wind.baseRadius());
        for (DynamicStormState.SubVortex vortex : state.subVortices()) {
            Vec3 center = TornadoWindField.subVortexGroundCenter(level, storm, vortex, position.y);
            double distance = Math.sqrt(position.distanceToSqr(center.x, position.y, center.z));
            double subCoreRadius = Math.max(1.5D, baseRadius * vortex.radiusFraction() * 0.28D);
            if (distance <= subCoreRadius * 3.5D) {
                return true;
            }
        }
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 center = satelliteCenter(storm, satellite, position.y);
            double distance = Math.sqrt(position.distanceToSqr(center.x, position.y, center.z));
            double satelliteCore = Math.max(2.0D, satellite.radiusOfMaxWind());
            if (distance <= satelliteCore * 4.2D) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDynamicVortex(StormObject storm) {
        if (storm == null) {
            return false;
        }
        if (storm.attrib_waterSpout) {
            return true;
        }
        if (isNativeCyclone(storm) || AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return false;
        }
        return storm.isTornadoFormingOrGreater();
    }

    public static boolean isNativeCyclone(StormObject storm) {
        return storm != null
                && storm.stormType == StormObject.TYPE_WATER
                && storm.levelCurIntensityStage >= StormObject.STATE_FORMING;
    }

    public static boolean isNativeTropicalCyclone(StormObject storm) {
        return storm != null
                && storm.stormType == StormObject.TYPE_WATER
                && storm.levelCurIntensityStage >= StormObject.STATE_STAGE1;
    }

    public static boolean isNativeHurricane(StormObject storm) {
        return storm != null
                && storm.stormType == StormObject.TYPE_WATER
                && storm.levelCurIntensityStage >= StormObject.STATE_STAGE5;
    }

    public static Vec3 tornadoCirculationCenter(StormObject storm) {
        if (storm == null || storm.pos == null) {
            return Vec3.ZERO;
        }
        Vec3 center = finiteVector(storm.posBaseFormationPos) ? storm.posBaseFormationPos : null;
        if (center == null) {
            try {
                center = storm.calculateBaseFormationPos();
            } catch (RuntimeException ignored) {
            }
        }
        if (!finiteVector(center)) {
            try {
                center = storm.getFunnelCenter(storm.pos);
            } catch (RuntimeException ignored) {
            }
        }
        return finiteVector(center) ? center : storm.pos;
    }

    private static boolean finiteVector(Vec3 value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    public static int cycloneStageForWind(double windMph) {
        if (windMph >= 157.0D) {
            return StormObject.STATE_STAGE5;
        }
        if (windMph >= 130.0D) {
            return StormObject.STATE_STAGE4;
        }
        if (windMph >= 111.0D) {
            return StormObject.STATE_STAGE3;
        }
        if (windMph >= 96.0D) {
            return StormObject.STATE_STAGE2;
        }
        if (windMph >= 74.0D) {
            return StormObject.STATE_STAGE1;
        }
        return StormObject.STATE_FORMING;
    }

    public static boolean promoteNativeCyclone(ServerLevel level, StormObject storm, boolean force, double targetWindMph) {
        if (!Config.CYCLONES_ENABLED.get() || level == null || storm == null || storm.pos == null || storm.manager == null) {
            return false;
        }
        if (!force && !isOverWaterOrOcean(level, storm.pos.x, storm.pos.z)) {
            return false;
        }
        storm.stormType = StormObject.TYPE_WATER;
        storm.attrib_waterSpout = false;
        storm.setPrecipitating(true);
        storm.setCloudlessStorm(false);
        storm.isGrowing = true;
        storm.hasStormPeaked = false;
        storm.canBeDeadly = true;

        int requestedStage = cycloneStageForWind(targetWindMph);
        int rolledMax = storm.rollDiceOnMaxIntensity();
        int stageFloor = force ? requestedStage : Math.max(storm.levelCurIntensityStage, StormObject.STATE_FORMING);
        int maxFloor = force ? requestedStage : Math.max(stageFloor, StormObject.STATE_STAGE1);
        storm.levelCurIntensityStage = Math.max(storm.levelCurIntensityStage, stageFloor);
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity, force ? 0.72F : 0.42F);
        storm.levelStormIntensityMax = Math.max(Math.max(storm.levelStormIntensityMax, rolledMax), maxFloor);
        storm.maxIntensityStage = Math.max(storm.maxIntensityStage, storm.levelStormIntensityMax);
        storm.levelWindMomentum = Math.max(storm.levelWindMomentum, (float) Mth.clamp(targetWindMph / 90.0D, 0.70D, 2.20D));
        storm.levelWater = Math.max(storm.levelWater, Math.max(120, storm.levelWaterStartRaining * 4));

        int sizeFloor = Mth.clamp(180 + (int) Math.round(targetWindMph * 2.35D), 220, force ? 720 : 520);
        int maxSizeFloor = Mth.clamp(sizeFloor + (int) Math.round(targetWindMph * 1.65D), 320, force ? 1200 : 760);
        storm.size = Math.max(storm.size, sizeFloor);
        storm.maxSize = Math.max(storm.maxSize, maxSizeFloor);
        if (Math.abs(storm.spinSpeed) < 0.02D) {
            storm.spinSpeed = 0.035D;
        }
        stateFor(storm).setSubtype(isNativeHurricane(storm) ? StormSubtype.HURRICANE : StormSubtype.CYCLONE);
        if (storm.manager instanceof WeatherManagerServer manager) {
            manager.syncStormUpdate(storm);
        }
        return true;
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

    public static double visualRotationSign(StormObject storm) {
        if (!Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get() || storm == null || storm.attrib_waterSpout) {
            return 1.0D;
        }
        DynamicStormState state = stateFor(storm);
        return state.rotationAssigned() && state.isAntiCyclonic() ? -1.0D : 1.0D;
    }

    public static boolean canUseElephantTrunkVisuals(DynamicStormState state) {
        if (state == null) {
            return false;
        }
        float damageWidth = state.damagePathWidth();
        float visibleWidth = state.visibleFunnelWidth();
        if (damageWidth > 260.0F || visibleWidth > 150.0F) {
            return false;
        }
        float widthFraction = damageWidth <= 1.0F ? 1.0F : visibleWidth / damageWidth;
        return widthFraction <= Math.max(0.70F, Config.TORNADO_ELEPHANT_TRUNK_MAX_VISIBLE_WIDTH_FRACTION.get().floatValue());
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
        float baseline = Math.max(0.0F, Config.TORNADO_WIND_SPEED_SPIN_MIN.get().floatValue());
        DynamicStormState state = stateFor(storm);
        boolean drillBit = state.targetFunnelMorphology() == FunnelMorphology.DRILL_BIT || state.funnelMorphology() == FunnelMorphology.DRILL_BIT;
        boolean floater = state.targetFunnelMorphology() == FunnelMorphology.FLOATER || state.funnelMorphology() == FunnelMorphology.FLOATER;
        float spinDelta = Math.max(0.0F, state.visualSpinSpeed() - baseline) * (drillBit ? 108.0F : floater ? 84.0F : 70.0F);
        float spinLimit = drillBit ? 36.0F : floater ? 30.0F : 24.0F;
        return Mth.clamp(spinDelta * layerScale * (float) rotationSign(storm), -spinLimit, spinLimit);
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

    public static double subVortexWindMph(StormObject storm, DynamicStormState.SubVortex vortex) {
        if (storm == null || vortex == null) {
            return 0.0D;
        }
        double parentWind = TornadoWindField.maxWindMph(storm);
        double intensity = Mth.clamp(vortex.intensityFraction(), 0.05F, 1.0F);
        double stageBlend = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0D, 0.0D, 1.0D);
        double physicsWind = parentWind * (0.58D + intensity * 0.86D);
        double explicitRangeWind = 100.0D + intensity * 155.0D + stageBlend * 74.0D;
        return Mth.clamp(Math.max(physicsWind, explicitRangeWind), 100.0D, 335.0D);
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
        BlockPos surface = BlockGrabSafety.safeSurface(level, sample, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
        if (surface == null) {
            return false;
        }
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
        BlockPos surface = BlockGrabSafety.safeSurface(level, pos, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
        if (surface == null) {
            return 0;
        }
        return Math.max(0, surface.getY() - 1 - pos.getY());
    }

    private static void updateSubVortices(StormObject storm, DynamicStormState state) {
        int effectiveMinStage = Math.max(Config.MULTIVORTEX_MIN_STAGE.get(), StormObject.STATE_STAGE2);
        int effectiveMaxCount = Math.min(Math.max(0, Config.MULTIVORTEX_MAX_COUNT.get()), 10);
        if (!Config.MULTIVORTEX_ENABLED.get() || storm.levelCurIntensityStage < effectiveMinStage || !isDynamicVortex(storm) || effectiveMaxCount <= 0) {
            state.updateSubVortices(storm.manager.getWorld().getGameTime(), 0, baseRadiusForWind(storm), storm.levelCurIntensityStage, storm.levelCurStagesIntensity);
            return;
        }
        int stageOverMinimum = Math.max(0, storm.levelCurIntensityStage - effectiveMinStage);
        double maxWind = TornadoWindField.maxWindMph(storm);
        int desired = 1 + stageOverMinimum;
        if (storm.levelCurStagesIntensity > 0.55F) {
            desired++;
        }
        if (storm.levelCurStagesIntensity > 0.78F) {
            desired += 2;
        }
        if (state.wedgeMorphologyActive()) {
            desired += 2;
        }
        if (maxWind >= 190.0D) {
            desired++;
        }
        if (maxWind >= 250.0D) {
            desired++;
        }
        desired = Mth.clamp(desired, 0, effectiveMaxCount);
        state.updateSubVortices(storm.manager.getWorld().getGameTime(), desired, baseRadiusForWind(storm), storm.levelCurIntensityStage, storm.levelCurStagesIntensity);
    }

    private static void updateHorizontalVortices(StormObject storm, DynamicStormState state) {
        int effectiveMaxCount = Math.min(Math.max(0, Config.HORIZONTAL_VORTICES_MAX_COUNT.get()), 10);
        int effectiveMinStage = Math.max(Config.HORIZONTAL_VORTICES_MIN_STAGE.get(), StormObject.STATE_STAGE1);
        if (!Config.HORIZONTAL_VORTICES_ENABLED.get() || storm.levelCurIntensityStage < effectiveMinStage || !isDynamicVortex(storm) || effectiveMaxCount <= 0) {
            state.updateHorizontalVortices(storm.manager.getWorld().getGameTime(), 0, baseRadiusForWind(storm), false);
            return;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        int desired = maxWind >= 90.0D ? 1 : 0;
        if (maxWind >= 135.0D) {
            desired++;
        }
        if (maxWind >= 180.0D) {
            desired++;
        }
        if (state.multiVortexVisualActive()) {
            desired += Math.min(3, Math.max(1, state.subVortices().size() / 3));
        }
        if (state.wedgeMorphologyActive()) {
            desired++;
        }
        desired = Mth.clamp(desired, 0, effectiveMaxCount);
        state.updateHorizontalVortices(storm.manager.getWorld().getGameTime(), desired, baseRadiusForWind(storm), state.multiVortexVisualActive());
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
        ModNetworking.sendToPlayersNear(
                level,
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
        int maximumStage = Math.min(storm.maxIntensityStage, Math.min(StormObject.STATE_STAGE5, Config.RAPID_INTENSIFICATION_MAX_STAGE.get()));
        boolean canStrengthen = storm.levelCurIntensityStage < maximumStage;
        if (Config.RAPID_INTENSIFICATION_ENABLED.get() && score >= Config.RAPID_INTENSIFICATION_THRESHOLD.get() && canStrengthen && state.canRapidIntensify(gameTime)) {
            storm.hasStormPeaked = false;
            storm.levelCurStagesIntensity = Math.min(1.05F, storm.levelCurStagesIntensity + Config.RAPID_INTENSIFICATION_PULSE.get().floatValue());
            if (storm.levelCurStagesIntensity >= 1.0F) {
                storm.stageNext();
                state.markRapidIntensityChanged(gameTime);
            }
        } else if (score >= Config.ENVIRONMENT_REINTENSIFY_THRESHOLD.get() && canStrengthen) {
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
                BlockPos surface = BlockGrabSafety.safeSurface(level, sample, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
                if (surface == null) {
                    continue;
                }
                int y = surface.getY();
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
