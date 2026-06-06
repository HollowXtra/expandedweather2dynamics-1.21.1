package com.imbet.weather;

import com.imbet.Config;
import com.imbet.compat.immersiveaircraft.ImmersiveAircraftStormCompat;
import com.imbet.network.NetworkSafety;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.TornadoHelper;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class DynamicStormController {
    private static final Map<StormObject, DynamicStormState> STATES = new WeakHashMap<>();
    private static final String NBT_KEY = "expandedweather2dynamics";
    private static final float MULTIVORTEX_PARENT_CULL_FRACTION = 0.34F;
    private static final float MULTIVORTEX_PARENT_FADE_FRACTION = 0.24F;
    private static final float MULTIVORTEX_PARENT_LIFT_FRACTION = 0.64F;

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
            applyNativeCycloneProgression(serverLevel, storm);
            state.tickServer(serverLevel, storm, gameTime);
            applyRotationDirection(serverLevel, storm, state);
            applyMergeOrbit(storm, state, gameTime);
        } else {
            CompoundTag syncedState = ClientStormStateStore.consume(storm.ID);
            if (syncedState != null && !syncedState.isEmpty()) {
                state.loadClientSync(syncedState);
            }
            applyRotationDirection(world, storm, state);
            state.tickVisual(gameTime);
            state.steerSatelliteTornadoes(storm.pos, storm.motion);
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
        if (world instanceof ServerLevel serverLevel) {
            updateSubVortices(storm, state);
            updateHorizontalVortices(storm, state);
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
        if (WindSteeringManager.shouldSteer(storm)) {
            return WindSteeringManager.steeringAngleDegrees(storm, originalAngle);
        }
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
        double anchor = Math.min(Config.TORNADO_FUNNEL_BEND_ANCHOR_FRACTION.get(), 0.08D);
        if (fraction <= anchor) {
            return layerPos;
        }
        double effectiveFraction = Mth.clamp((fraction - anchor) / Math.max(0.05D, 1.0D - anchor), 0.0D, 1.0D);
        double configuredMax = Math.max(36.0D, Config.TORNADO_FUNNEL_BEND_MAX_BLOCKS.get());
        double widthFreedom = Math.max(baseRadiusForWind(storm) * 1.35D, state.visibleFunnelWidth() * 0.62D);
        double sidewinding = Mth.clamp(state.sidewindingBendBlend(), 0.0F, 1.0F);
        double curveIntensity = 0.34D + sidewinding * 0.66D;
        double localYawIntensity = 0.18D + sidewinding * 0.82D;
        double maxBend = Math.min(Math.max(configuredMax, widthFreedom), configuredMax * 2.75D) * state.bendStrength() * curveIntensity;
        double sway = Math.max(14.0D, Config.TORNADO_FUNNEL_BEND_SWAY_BLOCKS.get()) * (0.30D + sidewinding * 0.70D);
        double phase = state.bendPhase();
        double yawFlex = Math.sin(phase * 0.54D + effectiveFraction * Math.PI * 1.55D) * 0.62D;
        yawFlex += Math.sin(phase * 0.93D - effectiveFraction * Math.PI * 2.90D) * 0.34D * Math.sin(effectiveFraction * Math.PI);
        double localRadians = radians + yawFlex * Math.min(1.35D, state.bendStrength()) * localYawIntensity;
        Vec3 localLean = new Vec3(Math.cos(localRadians), 0.0D, Math.sin(localRadians));
        Vec3 localCross = new Vec3(-localLean.z, 0.0D, localLean.x);
        double heightEase = Math.pow(effectiveFraction, 0.92D);
        double midBody = Math.sin(effectiveFraction * Math.PI) * sway * 0.92D;
        double freeSnake = Math.sin(phase * 1.18D + effectiveFraction * Math.PI * 2.95D) * sway * (0.20D + sidewinding * 0.22D + effectiveFraction * (0.34D + sidewinding * 0.68D));
        double counterSnake = Math.cos(phase * 0.76D + effectiveFraction * Math.PI * 3.85D) * sway * (0.16D + sidewinding * 0.36D) * Math.sin(effectiveFraction * Math.PI);
        double reverseCurl = Math.sin(phase * 1.31D + effectiveFraction * Math.PI * 4.20D) * sway * (0.10D + sidewinding * 0.38D) * Math.sin(effectiveFraction * Math.PI);
        double upperKink = Math.sin(effectiveFraction * Math.PI * 2.0D + phase * 0.72D) * sway * (0.16D + sidewinding * 0.30D) * effectiveFraction * effectiveFraction;
        double primaryLean = maxBend * heightEase + upperKink + counterSnake;
        Vec3 offset = localLean.scale(primaryLean).add(localCross.scale(midBody + freeSnake + reverseCurl));
        return new Vec3(basePos.x + offset.x, layerPos.y, basePos.z + offset.z);
    }

    public static Vec3 funnelLayerMorphologyOffset(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return Vec3.ZERO;
        }
        DynamicStormState state = stateFor(storm);
        FunnelMorphology morphology = state.targetFunnelMorphology().shaped() ? state.targetFunnelMorphology() : state.funnelMorphology();
        if (morphology == FunnelMorphology.DEFAULT) {
            return Vec3.ZERO;
        }
        if (morphology == FunnelMorphology.ELEPHANT_TRUNK && (!Config.TORNADO_ELEPHANT_TRUNK_ENABLED.get() || !canUseElephantTrunkVisuals(state))) {
            return Vec3.ZERO;
        }
        if (morphology == FunnelMorphology.DRILL_BIT && !Config.TORNADO_DRILL_BIT_ENABLED.get()) {
            return Vec3.ZERO;
        }
        if (morphology == FunnelMorphology.FLOATER && !Config.FLOATER_TORNADOES_ENABLED.get()) {
            return Vec3.ZERO;
        }
        if (morphology == FunnelMorphology.WEDGE) {
            return Vec3.ZERO;
        }
        float blend = state.funnelMorphologyBlend();
        if (blend <= 0.001F) {
            return Vec3.ZERO;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        double anchor = Math.min(0.08D, Math.max(0.035D, Config.TORNADO_FUNNEL_BEND_ANCHOR_FRACTION.get() * 0.55D));
        if (fraction <= anchor) {
            return Vec3.ZERO;
        }
        double effective = Mth.clamp((fraction - anchor) / Math.max(0.05D, 1.0D - anchor), 0.0D, 1.0D);
        double angle = Math.toRadians(state.bendAngle());
        if (storm.motion != null && storm.motion.horizontalDistanceSqr() > 0.0004D) {
            angle = Math.toRadians(Mth.rotLerp(0.56F, state.bendAngle(), motionAngleDegrees(storm)));
        }
        Vec3 lean = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        Vec3 cross = new Vec3(-lean.z, 0.0D, lean.x);
        double bendLimit = Math.max(36.0D, Config.TORNADO_FUNNEL_BEND_MAX_BLOCKS.get()) * 1.35D;
        double widthScale = Math.max(7.0D, Math.min(Math.max(state.visibleFunnelWidth() * 0.34D, baseRadiusForWind(storm) * 0.78D), bendLimit));
        if (morphology == FunnelMorphology.DRILL_BIT) {
            double tightScale = Math.max(2.75D, Math.min(widthScale * 0.34D, 8.0D));
            double helix = Math.sin(effective * Math.PI * 5.50D + state.trunkCurvePhase() * 2.25D) * tightScale * (0.20D + effective * 0.50D);
            double wobble = Math.cos(effective * Math.PI * 3.10D + state.trunkCurvePhase()) * tightScale * 0.30D * Math.sin(effective * Math.PI);
            return lean.scale(wobble * blend).add(cross.scale(helix * blend));
        }
        if (morphology == FunnelMorphology.FLOATER) {
            double floatScale = Math.max(4.0D, Math.min(widthScale * 0.42D, 14.0D));
            double weave = Math.sin(effective * Math.PI * 2.35D + state.trunkCurvePhase() * 0.72D) * floatScale * 0.22D * Math.sin(effective * Math.PI);
            double hook = Math.cos(effective * Math.PI * 1.60D + state.trunkCurvePhase()) * floatScale * 0.12D * effective;
            return lean.scale(hook * blend).add(cross.scale(weave * blend));
        }
        double strength = blend * state.trunkCurveStrength() * Math.max(1.12D, Config.TORNADO_ELEPHANT_TRUNK_CURVE_STRENGTH.get());
        double neck = Mth.clamp(state.trunkNeckFraction(), 0.28F, 0.70F);
        double neckPull = Math.sin(Mth.clamp((effective - neck + 0.32D) / 0.64D, 0.0D, 1.0D) * Math.PI);
        double middleBow = Math.sin(effective * Math.PI) * widthScale * 0.82D;
        double upperLean = effective * effective * widthScale * 0.34D;
        double lowerHook = Math.sin((1.0D - effective) * Math.PI) * widthScale * 0.42D * Math.max(0.0D, 0.46D - effective);
        double curl = Math.sin(effective * Math.PI * 2.70D + state.trunkCurvePhase()) * widthScale * 0.30D * effective;
        double neckCurl = Math.cos(state.trunkCurvePhase() * 0.84D + effective * Math.PI * 3.35D) * widthScale * 0.16D * neckPull;
        return lean.scale((middleBow + upperLean + lowerHook) * strength).add(cross.scale((curl + neckCurl) * strength));
    }

    public static float funnelLayerRadiusScale(StormObject storm, int layerIndex, int layerCount) {
        float multivortexScale = multivortexParentLowerScale(storm, layerIndex, layerCount);
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return multivortexScale;
        }
        DynamicStormState state = stateFor(storm);
        FunnelMorphology morphology = state.targetFunnelMorphology().shaped() ? state.targetFunnelMorphology() : state.funnelMorphology();
        if (morphology == FunnelMorphology.DEFAULT) {
            return multivortexScale;
        }
        if (morphology == FunnelMorphology.ELEPHANT_TRUNK && (!Config.TORNADO_ELEPHANT_TRUNK_ENABLED.get() || !canUseElephantTrunkVisuals(state))) {
            return multivortexScale;
        }
        if (morphology == FunnelMorphology.DRILL_BIT && !Config.TORNADO_DRILL_BIT_ENABLED.get()) {
            return multivortexScale;
        }
        if (morphology == FunnelMorphology.FLOATER && !Config.FLOATER_TORNADOES_ENABLED.get()) {
            return multivortexScale;
        }
        float blend = state.funnelMorphologyBlend();
        if (blend <= 0.001F) {
            return multivortexScale;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        if (morphology == FunnelMorphology.WEDGE) {
            float diameterScale = state.damagePathWidth() / Math.max(1.0F, state.visibleFunnelWidth());
            float groundScale = Mth.clamp(Math.max(1.35F, diameterScale), 1.35F, 5.25F);
            float shoulderScale = Mth.clamp(1.10F + diameterScale * 0.20F, 1.25F, 2.45F);
            float groundWeight = 1.0F - smoothstep(Mth.clamp(fraction / 0.42F, 0.0F, 1.0F));
            float upperTaper = smoothstep(Mth.clamp((fraction - 0.62F) / 0.38F, 0.0F, 1.0F));
            float shoulderWeight = (float) Math.sin(Mth.clamp(fraction / 0.82F, 0.0F, 1.0F) * Math.PI) * (1.0F - upperTaper * 0.55F);
            float wedgeScale = 1.0F + groundWeight * (groundScale - 1.0F) + shoulderWeight * (shoulderScale - 1.0F) - upperTaper * 0.12F;
            wedgeScale = Mth.clamp(wedgeScale, 0.82F, groundScale);
            return Mth.lerp(blend, 1.0F, wedgeScale) * multivortexScale;
        }
        if (morphology == FunnelMorphology.DRILL_BIT) {
            float lowerContact = 1.0F - smoothstep(Mth.clamp(fraction / 0.16F, 0.0F, 1.0F));
            float upperTaper = smoothstep(Mth.clamp((fraction - 0.58F) / 0.42F, 0.0F, 1.0F));
            float drillScale = 0.18F + upperTaper * 0.10F + lowerContact * 0.05F;
            drillScale = Mth.clamp(drillScale, 0.16F, 0.34F);
            return Mth.lerp(blend, 1.0F, drillScale) * multivortexScale;
        }
        if (morphology == FunnelMorphology.FLOATER) {
            float lowerDetached = smoothstep(Mth.clamp(fraction / 0.24F, 0.0F, 1.0F));
            float upperBase = smoothstep(Mth.clamp((fraction - 0.54F) / 0.46F, 0.0F, 1.0F));
            float waist = 1.0F - Mth.clamp(Math.abs(fraction - 0.46F) / 0.34F, 0.0F, 1.0F);
            float floaterScale = 0.16F + lowerDetached * 0.20F + upperBase * 0.18F + waist * 0.10F;
            floaterScale = Mth.clamp(floaterScale, 0.14F, 0.60F);
            return Mth.lerp(blend, 1.0F, floaterScale) * multivortexScale;
        }
        float neck = Mth.clamp(state.trunkNeckFraction(), 0.28F, 0.70F);
        float neckNarrow = 1.0F - Mth.clamp(Math.abs(fraction - neck) / 0.31F, 0.0F, 1.0F);
        float upperBase = smoothstep(Mth.clamp((fraction - 0.62F) / 0.38F, 0.0F, 1.0F));
        float upperCapNarrow = smoothstep(Mth.clamp((fraction - 0.84F) / 0.16F, 0.0F, 1.0F));
        float lowerContact = 1.0F - smoothstep(Mth.clamp(fraction / 0.16F, 0.0F, 1.0F));
        float lowerRope = smoothstep(Mth.clamp((0.34F - fraction) / 0.34F, 0.0F, 1.0F));
        float trunkScale = 0.44F - neckNarrow * 0.20F + upperBase * 0.42F - upperCapNarrow * 0.16F + lowerContact * 0.08F - lowerRope * 0.06F;
        trunkScale = Mth.clamp(trunkScale, 0.30F, 0.88F);
        return Mth.lerp(blend, 1.0F, trunkScale) * multivortexScale;
    }

    public static float funnelParticleScale(StormObject storm, int layerIndex, int layerCount) {
        float radiusScale = funnelLayerRadiusScale(storm, layerIndex, layerCount);
        float multivortexScale = multivortexParentLowerScale(storm, layerIndex, layerCount);
        if (radiusScale >= 0.999F && multivortexScale >= 0.999F) {
            return 1.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) Math.max(1, layerCount - 1), 0.0F, 1.0F);
        float topSoftening = Mth.lerp(fraction, 0.05F, 0.22F);
        return Mth.clamp(radiusScale * 0.88F + topSoftening * multivortexScale, 0.06F, 1.20F);
    }

    public static boolean shouldCullMultivortexParentLayer(StormObject storm, int layerIndex, int layerCount) {
        return hasActiveMultivortexParent(storm, layerCount);
    }

    public static float multivortexParentLayerAlpha(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return 1.0F;
        }
        return 0.0F;
    }

    public static boolean hasActiveMultivortexVisual(StormObject storm) {
        if (storm == null || !Config.MULTIVORTEX_ENABLED.get() || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout) {
            return false;
        }
        return stateFor(storm).multiVortexVisualActive();
    }

    public static boolean shouldHideMultivortexParentFunnel(StormObject storm) {
        return hasActiveMultivortexVisual(storm);
    }

    public static double mainFunnelGroundLift(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return 0.0D;
        }
        DynamicStormState state = stateFor(storm);
        FunnelMorphology morphology = state.targetFunnelMorphology().shaped() ? state.targetFunnelMorphology() : state.funnelMorphology();
        if (morphology == FunnelMorphology.FLOATER && Config.FLOATER_TORNADOES_ENABLED.get()) {
            float blend = state.funnelMorphologyBlend();
            if (blend <= 0.001F) {
                return 0.0D;
            }
            float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
            double lowerWeight = 1.0D - smoothstep(Mth.clamp(fraction / 0.68F, 0.0F, 1.0F));
            double lift = Mth.clamp(18.0D + state.visibleFunnelWidth() * 0.22D, 18.0D, 64.0D);
            return lift * lowerWeight * blend;
        }
        if (!Config.MULTIVORTEX_ENABLED.get()) {
            return 0.0D;
        }
        if (state.subVortices().isEmpty()) {
            return 0.0D;
        }
        return 0.0D;
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

    private static float multivortexParentLowerScale(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return 1.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float lowerBlend = 1.0F - smoothstep(Mth.clamp(fraction / MULTIVORTEX_PARENT_LIFT_FRACTION, 0.0F, 1.0F));
        return Mth.lerp(lowerBlend, 1.0F, 0.018F);
    }

    private static boolean hasActiveMultivortexParent(StormObject storm, int layerCount) {
        if (layerCount <= 1) {
            return false;
        }
        return hasActiveMultivortexVisual(storm);
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
        if (isNativeCyclone(storm)) {
            stateFor(storm).setSubtype(StormSubtype.CYCLONE);
        }
    }

    public static int adjustRolledMaxIntensity(StormObject storm, int originalMax) {
        if (!Config.TORNADO_WEIGHTED_INTENSITY_DISTRIBUTION.get() || storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            return originalMax;
        }
        if (storm.weatherMachineControlled
                || storm.isPlayerControlled()
                || storm.isPet()
                || storm.attrib_waterSpout
                || storm.stormType == StormObject.TYPE_WATER
                || AddonWeatherEventService.isSquallLineHelperStorm(storm)
                || AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return originalMax;
        }
        Level world = storm.manager.getWorld();
        if (!(world instanceof ServerLevel level)) {
            return originalMax;
        }
        BlockPos origin = storm.pos == null ? level.getSharedSpawnPos() : BlockPos.containing(storm.pos);
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, origin);
        double severity = Mth.clamp((outlook.risk().severity() - SpcRisk.MARGINAL.severity()) / 4.0D, 0.0D, 1.0D);
        double cape = Mth.clamp(outlook.profile().capeJkg() / 4500.0D, 0.0D, 1.0D);
        double shear = Mth.clamp(outlook.profile().deepLayerShearMps() / 32.0D, 0.0D, 1.0D);
        boolean alreadyTornadoCapable = originalMax >= StormObject.STATE_FORMING || storm.levelCurIntensityStage >= StormObject.STATE_FORMING;
        if (!alreadyTornadoCapable) {
            double rateBoost = Mth.clamp(Config.TORNADO_CAPABLE_RATE_BOOST.get(), 0.0D, 4.0D);
            double tornadoCapableChance = Mth.clamp((0.025D + outlook.tornadoProbability() * 0.34D + severity * 0.035D) * rateBoost, 0.0D, 0.34D);
            if (level.random.nextDouble() >= tornadoCapableChance) {
                return originalMax;
            }
        }
        int weightedStage = weightedNaturalTornadoMaxStage(level, severity, cape, shear, outlook.allowsViolentOutbreak());
        return Mth.clamp(Math.max(weightedStage, storm.levelCurIntensityStage), originalMax >= StormObject.STATE_FORMING ? StormObject.STATE_FORMING : originalMax, StormObject.STATE_STAGE5);
    }

    private static int weightedNaturalTornadoMaxStage(ServerLevel level, double severity, double cape, double shear, boolean violentOutbreak) {
        double formingWeight = 58.0D - severity * 12.0D;
        double ef1Weight = 43.0D + severity * 8.0D;
        double ef2Weight = 16.0D + severity * 14.0D + cape * 5.0D;
        double ef3Weight = 7.0D + severity * 10.0D + shear * 7.0D;
        double ef4Weight = 1.0D + severity * 3.2D + (severity >= 0.70D ? 1.4D : 0.0D);
        double ef5Weight = 0.20D + (violentOutbreak ? 1.15D : 0.0D) + Math.max(0.0D, severity - 0.82D) * 3.0D;
        double total = formingWeight + ef1Weight + ef2Weight + ef3Weight + ef4Weight + ef5Weight;
        double roll = level.random.nextDouble() * total;
        if ((roll -= formingWeight) < 0.0D) {
            return StormObject.STATE_FORMING;
        }
        if ((roll -= ef1Weight) < 0.0D) {
            return StormObject.STATE_STAGE1;
        }
        if ((roll -= ef2Weight) < 0.0D) {
            return StormObject.STATE_STAGE2;
        }
        if ((roll -= ef3Weight) < 0.0D) {
            return StormObject.STATE_STAGE3;
        }
        if ((roll -= ef4Weight) < 0.0D) {
            return StormObject.STATE_STAGE4;
        }
        return StormObject.STATE_STAGE5;
    }

    public static boolean shouldCancelNaturalPlayerAim(StormObject storm) {
        return Config.NO_PLAYER_AIM_ON_SPAWN.get()
                && storm != null
                && !storm.weatherMachineControlled
                && !storm.isPlayerControlled()
                && !storm.isPet();
    }

    public static Vec3 adjustEntityMotion(StormObject storm, Vec3 position, Vec3 originalMotion) {
        return adjustEntityMotion(storm, position, originalMotion, false, false, 1.0F);
    }

    public static Vec3 adjustEntityMotion(StormObject storm, Vec3 position, Vec3 originalMotion, boolean forPlayer, boolean forCube, float grabAdj) {
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
        return TornadoDamageModel.adjustEntityMotion(storm, sample, originalMotion, forPlayer, forCube, grabAdj);
    }

    public static boolean shouldTryRip(TornadoHelper helper, Level level, BlockPos pos) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null) {
            return false;
        }
        if (!CompatHooks.useVanillaBlockLifting()) {
            return false;
        }
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
        if (Config.DAMAGE_LOADED_CHUNKS_ONLY.get() && !sample.loaded()) {
            return false;
        }
        if (!BlockGrabSafety.canEdit(sample)) {
            return false;
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, level, Vec3.atCenterOf(pos));
        TornadoDamageModel.PickupDecision decision = TornadoDamageModel.evaluatePickup(helper.storm, level, sample, wind);
        if (decision.action() == TornadoDamageModel.PickupAction.OPEN && BlockGrabSafety.tryOpenDoor(level, sample)) {
            return false;
        }
        if (!decision.allowsWeather2Pickup()) {
            return false;
        }
        return stateFor(helper.storm).tryConsumeBlockDamage(level.getGameTime());
    }

    public static boolean canGrabBlock(TornadoHelper helper, Level level, BlockState state, BlockPos pos) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null) {
            return false;
        }
        if (!CompatHooks.useVanillaBlockLifting()) {
            return false;
        }
        BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, state);
        if (Config.DAMAGE_LOADED_CHUNKS_ONLY.get() && !sample.loaded()) {
            return false;
        }
        if (!BlockGrabSafety.canEdit(sample)) {
            return false;
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, level, Vec3.atCenterOf(pos));
        TornadoDamageModel.PickupDecision decision = TornadoDamageModel.evaluatePickup(helper.storm, level, sample, wind);
        if (!decision.allowsWeather2Pickup()) {
            return false;
        }
        float hardness = sample.destroySpeed();
        if (hardness < 0.0F) {
            return false;
        }
        if (hardness > decision.hardnessLimit()) {
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
        if (!force && !isOceanCycloneCandidate(level, storm)) {
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

    private static void applyNativeCycloneProgression(ServerLevel level, StormObject storm) {
        if (!Config.CYCLONES_ENABLED.get() || storm == null || storm.pos == null) {
            return;
        }
        if (isNativeCyclone(storm) || storm.stormType == StormObject.TYPE_WATER) {
            storm.attrib_waterSpout = false;
            if (storm.levelCurIntensityStage >= StormObject.STATE_FORMING) {
                stateFor(storm).setSubtype(isNativeHurricane(storm) ? StormSubtype.HURRICANE : StormSubtype.CYCLONE);
            }
            return;
        }
        if (isOceanCycloneCandidate(level, storm)) {
            double targetWind = Math.max(74.0D, TornadoWindField.maxWindMph(storm) * 0.42D);
            promoteNativeCyclone(level, storm, false, targetWind);
        }
    }

    private static boolean isOceanCycloneCandidate(ServerLevel level, StormObject storm) {
        if (level == null || storm == null || storm.pos == null || storm.isDead) {
            return false;
        }
        if (storm.weatherMachineControlled
                || storm.isPlayerControlled()
                || storm.isPet()
                || AddonWeatherEventService.isSquallLineHelperStorm(storm)
                || AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return false;
        }
        if (storm.stormType == StormObject.TYPE_WATER) {
            return true;
        }
        if (storm.stormType != StormObject.TYPE_LAND || storm.levelCurIntensityStage < StormObject.STATE_THUNDER || storm.levelCurIntensityStage > StormObject.STATE_FORMING) {
            return false;
        }
        return isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
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
        int configuredMax = Config.SATELLITE_TORNADOES_MAX_ACTIVE.get();
        int desiredMax = Mth.clamp(configuredMax, 0, 1);
        int minParentStage = Mth.clamp(Math.min(Config.SATELLITE_TORNADOES_MIN_PARENT_STAGE.get(), StormObject.STATE_STAGE1), StormObject.STATE_FORMING, StormObject.STATE_STAGE5);
        state.updateSatelliteTornadoes(
                storm.manager.getWorld().getGameTime(),
                desiredMax,
                storm.levelCurIntensityStage,
                minParentStage,
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
        assignRotationDirectionIfNeeded(world, storm, state);
        if (!storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout) {
            if (Config.ANTI_CYCLONIC_INVERT_WEATHER2_SPIN.get() && storm.spinSpeed < 0.0D) {
                storm.spinSpeed = Math.abs(storm.spinSpeed);
            }
            return;
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

    private static void assignRotationDirectionIfNeeded(Level world, StormObject storm, DynamicStormState state) {
        if (state.rotationAssigned()) {
            return;
        }
        if (!(world instanceof ServerLevel level)) {
            return;
        }
        if (rotationAssignmentDisqualified(storm)) {
            state.assignCyclonicIfUnset();
            return;
        }
        if (storm == null || (!storm.isTornadoFormingOrGreater() && storm.levelCurIntensityStage < StormObject.STATE_HAIL)) {
            return;
        }
        boolean antiCyclonic = Config.ANTI_CYCLONIC_TORNADOES_ENABLED.get()
                && level.random.nextDouble() < Config.ANTI_CYCLONIC_TORNADO_CHANCE.get();
        state.setAntiCyclonic(antiCyclonic);
    }

    private static boolean rotationAssignmentDisqualified(StormObject storm) {
        return storm == null
                || storm.attrib_waterSpout
                || storm.stormType != StormObject.TYPE_LAND
                || isNativeCyclone(storm)
                || AddonWeatherEventService.isSquallLineHelperStorm(storm)
                || AddonWeatherEventService.isHurricaneHelperStorm(storm);
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
        Vec3 current = storm.motion == null ? Vec3.ZERO : storm.motion;
        Vec3 currentHorizontal = new Vec3(current.x, 0.0D, current.z);
        double currentSpeed = currentHorizontal.length();
        boolean decaying = state.isAbsorbedOrDecaying();
        Vec3 desiredDirection = horizontal.normalize();
        double steer = Mth.clamp(Config.MERGE_ORBIT_STRENGTH.get(), 0.0D, 1.0D);
        double activeSpeedFloor = decaying ? 0.0D : 0.012D;
        double preservedSpeed = currentSpeed > 1.0E-4D ? Math.max(currentSpeed, activeSpeedFloor) : Math.max(activeSpeedFloor, Math.min(0.035D, steer * 0.12D));
        Vec3 blendedDirection = currentSpeed > 1.0E-4D
                ? currentHorizontal.normalize().scale(1.0D - steer).add(desiredDirection.scale(steer))
                : desiredDirection;
        if (blendedDirection.horizontalDistanceSqr() < 1.0E-5D) {
            blendedDirection = desiredDirection;
        }
        Vec3 newHorizontal = blendedDirection.normalize().scale(preservedSpeed);
        if (decaying) {
            newHorizontal = newHorizontal.scale(0.82D);
        }
        storm.motion = new Vec3(newHorizontal.x, current.y, newHorizontal.z);
    }

    private static float smoothstep(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private static void syncAddonState(ServerLevel level, StormObject storm, DynamicStormState state, long gameTime) {
        int interval = Math.max(5, Config.MULTIPLAYER_STATE_SYNC_INTERVAL_TICKS.get());
        if (!Config.PERSISTENCE_ENABLED.get() || !isDynamicVortex(storm) || gameTime % interval != 0L) {
            return;
        }
        NetworkSafety.sendToPlayersNear(level, storm.pos.x, storm.pos.y, storm.pos.z, Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get(), new StormStateSyncPayload(storm.ID, state.save()));
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
