package com.imbet.weather;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
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
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.TornadoHelper;
import weather2.weathersystem.storm.WeatherObject;
import weather2.weathersystem.tornado.ActiveTornadoConfig;
import weather2.weathersystem.tornado.simple.Layer;
import weather2.weathersystem.tornado.simple.TornadoFunnelSimple;

import java.util.ArrayList;
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

    /**
     * The game tick this storm's funnel last began forming, or {@link Long#MIN_VALUE} when it has no
     * funnel or the value is not known yet (a save from before it was tracked, or a client that has
     * not received the synced state). Callers must treat the unknown case as "assume the ramp is
     * finished" - treating it as newborn is what replayed the birth animation on every rejoin.
     */
    public static long funnelBirthTick(StormObject storm) {
        return storm == null ? Long.MIN_VALUE : stateFor(storm).funnelBirthTick();
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
        state.smooth(storm, gameTime);
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
            StructureLift.tick(serverLevel, storm, state);
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
        double sidewinding = Mth.clamp(state.sidewindingBendBlend(), 0.0F, 1.0F);
        double phase = state.bendPhase();
        double strength = Mth.clamp(state.bendStrength(), 0.0D, 1.0D);

        // Downshear tilt: the dominant shape. The funnel leans over along its
        // motion direction, growing top-heavy with height (the top lags
        // downwind). Weak and rope tornadoes lean much harder than violent
        // wedges, which stay near vertical.
        double weakness = Mth.clamp(1.0D - (state.damagePathWidth() - 12.0D) / 450.0D, 0.0D, 1.0D);
        double weaknessScale = Config.TORNADO_FUNNEL_TILT_WEAKNESS_SCALE.get();
        double tiltMax = Math.max(20.0D, Config.TORNADO_FUNNEL_TILT_BLOCKS.get()) * strength * (1.0D + weakness * weaknessScale);
        double tilt = tiltMax * Math.pow(effectiveFraction, 1.6D);

        // Bend: ONE clean traveling sway mode drifting up the funnel, plus a
        // slow whole-funnel lateral sweep. No competing snake modes fighting
        // the lean. Rope/weak funnels dance a bit more, wedges stay straight.
        double sway = Math.max(14.0D, Config.TORNADO_FUNNEL_BEND_SWAY_BLOCKS.get()) * (0.30D + sidewinding * 0.70D);
        double wave = Math.sin(phase * 0.55D + effectiveFraction * Math.PI * 2.3D) * Math.sin(effectiveFraction * Math.PI);
        double sweep = Math.sin(phase * 0.90D) * effectiveFraction * effectiveFraction;
        double bend = (wave * 0.85D + sweep * 0.45D) * sway * (0.65D + weakness * 0.35D);

        Vec3 leanDir = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
        Vec3 cross = new Vec3(-leanDir.z, 0.0D, leanDir.x);
        double offsetX = leanDir.x * tilt + cross.x * bend;
        double offsetZ = leanDir.z * tilt + cross.z * bend;
        double cap = Math.max(36.0D, Config.TORNADO_FUNNEL_BEND_MAX_BLOCKS.get());
        double length = Math.hypot(offsetX, offsetZ);
        if (length > cap) {
            offsetX *= cap / length;
            offsetZ *= cap / length;
        }
        return new Vec3(basePos.x + offsetX, layerPos.y, basePos.z + offsetZ);
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
        if (morphology == FunnelMorphology.ROPE) {
            // thin sinuous rope: a strong traveling snake that grows near the ground
            double ropeScale = Math.max(2.2D, Math.min(widthScale * 0.22D, 5.0D));
            double wiggle = Math.sin(effective * Math.PI * 3.2D + state.trunkCurvePhase() * 1.6D) * ropeScale * (0.35D + effective * 0.65D);
            double snake = Math.sin(effective * Math.PI * 1.7D + state.trunkCurvePhase() * 0.9D) * ropeScale * 0.5D * Math.sin(effective * Math.PI);
            return lean.scale(snake * 0.6D * blend).add(cross.scale((wiggle * 0.72D + snake * 0.42D) * blend));
        }
        if (morphology == FunnelMorphology.CORKSCREW) {
            // pronounced helical twist around the axis on a moderate body
            double twistScale = Math.max(3.5D, Math.min(widthScale * 0.34D, 9.0D));
            double helix = Math.sin(effective * Math.PI * 4.2D + state.trunkCurvePhase() * 2.1D) * twistScale * (0.30D + effective * 0.45D);
            double wobble = Math.cos(effective * Math.PI * 2.6D + state.trunkCurvePhase()) * twistScale * 0.35D * Math.sin(effective * Math.PI);
            return lean.scale(wobble * blend).add(cross.scale(helix * blend));
        }
        if (morphology == FunnelMorphology.BOWL) {
            // the bowl shape lives in the width profile; just a gentle sway
            double sway = Math.sin(effective * Math.PI * 1.8D + state.trunkCurvePhase() * 0.8D) * widthScale * 0.16D * Math.sin(effective * Math.PI);
            return cross.scale(sway * blend);
        }
        if (morphology == FunnelMorphology.CONE || morphology == FunnelMorphology.STOVEPIPE) {
            return Vec3.ZERO; // pure width-profile shapes
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

    /**
     * Funnel layer radius scale including any vortex breakdown pinch currently travelling down it.
     * The pinch multiplies the morphology scale so a breakdown bubble visibly necks the funnel as
     * it descends, and anything anchored to the wall (horizontal vortices, radar) follows it.
     */
    public static float funnelLayerRadiusScale(StormObject storm, int layerIndex, int layerCount) {
        float scale = funnelMorphologyRadiusScale(storm, layerIndex, layerCount);
        float pinch = funnelBreakdownPinch(storm, layerIndex, layerCount);
        return pinch >= 0.999F ? scale : scale * pinch;
    }

    private static float funnelBreakdownPinch(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null || layerCount <= 1) {
            return 1.0F;
        }
        DynamicStormState state = stateFor(storm);
        if (state.subVortices().isEmpty()) {
            return 1.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        return state.funnelPinchAt(fraction, storm.manager.getWorld().getGameTime());
    }

    private static float funnelMorphologyRadiusScale(StormObject storm, int layerIndex, int layerCount) {
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
        if (morphology == FunnelMorphology.CONE) {
            // textbook cone: narrow top, steadily widening to a solid ground base
            float coneScale = Mth.lerp(fraction, 0.42F, 1.32F);
            return Mth.lerp(blend, 1.0F, coneScale) * multivortexScale;
        }
        if (morphology == FunnelMorphology.STOVEPIPE) {
            // tall uniform column with a slight taper at the top and a base flare
            float topTaper = smoothstep(Mth.clamp((fraction - 0.80F) / 0.20F, 0.0F, 1.0F));
            float baseFlare = 1.0F - smoothstep(Mth.clamp(fraction / 0.16F, 0.0F, 1.0F));
            float pipe = 1.0F - topTaper * 0.20F + baseFlare * 0.18F;
            pipe = Mth.clamp(pipe, 0.72F, 1.22F);
            return Mth.lerp(blend, 1.0F, pipe) * multivortexScale;
        }
        if (morphology == FunnelMorphology.ROPE) {
            // thin writhing rope: narrow all the way, slight taper to the ground
            float pulse = 0.5F + 0.5F * (float) Math.sin(fraction * Math.PI * 3.0D + state.trunkCurvePhase() * 1.7D);
            float rope = 0.24F + (1.0F - fraction) * 0.10F + pulse * 0.08F;
            rope = Mth.clamp(rope, 0.20F, 0.46F);
            return Mth.lerp(blend, 1.0F, rope) * multivortexScale;
        }
        if (morphology == FunnelMorphology.CORKSCREW) {
            // helical body: the radius pulses around a moderate core as it twists
            float twist = 0.5F + 0.5F * (float) Math.sin(fraction * Math.PI * 4.0D + state.trunkCurvePhase() * 2.0D);
            float cork = 0.62F + twist * 0.22F - fraction * 0.10F;
            cork = Mth.clamp(cork, 0.45F, 0.95F);
            return Mth.lerp(blend, 1.0F, cork) * multivortexScale;
        }
        if (morphology == FunnelMorphology.BOWL) {
            // Top flares into the wall cloud, narrows to a waist, then a firm base. The flare is a
            // RIM that peaks just under the cloud base and feathers back in at the very top, rather
            // than a ramp that keeps growing all the way up: otherwise the topmost layer became the
            // widest ring in the whole funnel, so the top layer visibly swelled past every layer
            // below it (and snapped back whenever the shape ended). A bowl's widest ring belongs
            // under the wall cloud, where it merges into the cloud base.
            float topFlare = smoothstep(Mth.clamp((fraction - 0.55F) / 0.45F, 0.0F, 1.0F));
            float rimTaper = smoothstep(Mth.clamp((fraction - 0.90F) / 0.10F, 0.0F, 1.0F));
            float waist = 1.0F - smoothstep(Mth.clamp(Math.abs(fraction - 0.38F) / 0.24F, 0.0F, 1.0F));
            float base = 1.0F - smoothstep(Mth.clamp(fraction / 0.18F, 0.0F, 1.0F));
            float bowl = 0.62F + topFlare * 0.66F - rimTaper * 0.22F + waist * 0.22F + base * 0.30F;
            bowl = Mth.clamp(bowl, 0.55F, 1.35F);
            return Mth.lerp(blend, 1.0F, bowl) * multivortexScale;
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

    /**
     * Lifecycle alpha for funnel layers, driven by the funnel's own age.
     *
     * BIRTH: the condensation extends downward out of the cloud base over the storm's own genesis
     * timeline - the real-time formation clock, which by default runs about 75 seconds and is carried
     * in the storm's synced state. A layer only condenses once the descending sheath has passed its
     * height, so the funnel visibly reaches down while the surface dust whirl turns underneath it and
     * clear air is still between the two. A funnel whose genesis length is unknown (a save written
     * before the timeline existed) keeps the legacy eight-second build-in.
     *
     * DECAY: the funnel ropes out from the ground up - the bottom lifts first,
     * then the whole funnel thins before it is removed.
     */
    public static float funnelLayerFormationAlpha(StormObject storm, float ageTicks, int layerIndex, int layerCount) {
        if (storm == null || layerCount <= 1 || !Config.TORNADO_FORMING_ANIMATION_ENABLED.get() || storm.attrib_waterSpout) {
            return 1.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        float intensity = Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F);
        boolean decaying = stage >= 0.35F && (storm.hasStormPeaked && !storm.isGrowing || isAbsorbedOrDecaying(storm));
        if (decaying) {
            // rope-out: bottom layers lift first, the top hangs on longest
            float rope = Mth.clamp((1.0F - intensity) * 1.55F, 0.0F, 1.0F);
            float bottomLift = (1.0F - fraction) * (1.0F - fraction);
            float layerFade = 1.0F - bottomLift * rope;
            // late in the rope the whole funnel thins toward nothing
            float globalThin = Mth.clamp(rope * 1.18F - 0.42F, 0.0F, 1.0F);
            return Mth.clamp(layerFade * (1.0F - globalThin * 0.55F), 0.0F, 1.0F);
        }
        float descend = condensationDescent(storm, ageTicks, fraction);
        float fadeIn = descend * descend * (3.0F - 2.0F * descend);
        // stage still gates overall strength: an early-stage funnel is wispy
        // even after it has reached the ground, a violent one solidifies fully
        float overall = Mth.lerp(Mth.clamp(stage / 0.25F, 0.0F, 1.0F), 0.5F, 1.0F);
        return Mth.clamp(fadeIn * overall, 0.0F, 1.0F);
    }

    /**
     * How condensed a funnel layer is right now: 0 while the descending condensation is still above it,
     * 1 once the sheath has fully passed it. Layer fraction 1 is the cloud base and 0 is the ground, so
     * the bottom of the funnel is the last thing to condense - which is exactly the order real
     * tornadoes form in.
     */
    private static float condensationDescent(StormObject storm, float ageTicks, float fraction) {
        DynamicStormState state = stateFor(storm);
        float reach = TornadoGenesis.condensationReachOrLegacy(
                state.funnelBirthTick(), stormGameTime(storm), state.genesisDurationTicks(), ageTicks);
        float requiredReach = (1.0F - fraction) * 0.78F;
        return Mth.clamp((reach - requiredReach) / 0.26F, 0.0F, 1.0F);
    }

    private static long stormGameTime(StormObject storm) {
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            return Long.MIN_VALUE;
        }
        return storm.manager.getWorld().getGameTime();
    }

    /**
     * Lifecycle scale for funnel layers. During birth the descending
     * condensation narrows toward the ground (bottom layers start thin and
     * widen as the funnel reaches down); while roping out the whole funnel
     * thins. Mirrors the alpha ramp in timing.
     */
    public static float funnelLayerFormationScale(StormObject storm, float ageTicks, int layerIndex, int layerCount) {
        if (storm == null || layerCount <= 1 || !Config.TORNADO_FORMING_ANIMATION_ENABLED.get() || storm.attrib_waterSpout) {
            return 1.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        float intensity = Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F);
        boolean decaying = stage >= 0.35F && (storm.hasStormPeaked && !storm.isGrowing || isAbsorbedOrDecaying(storm));
        if (decaying) {
            float rope = Mth.clamp((1.0F - intensity) * 1.55F, 0.0F, 1.0F);
            float globalThin = Mth.clamp(rope * 1.18F - 0.42F, 0.0F, 1.0F);
            return Mth.clamp(1.0F - globalThin * 0.38F, 0.35F, 1.0F);
        }
        // bottom layers start thin and widen as the condensation reaches down
        float descend = condensationDescent(storm, ageTicks, fraction);
        float baseWiden = Mth.lerp(descend, 0.45F, 1.0F);
        float overall = Mth.lerp(Mth.clamp(stage * 0.55F + intensity * 0.45F, 0.0F, 1.0F), 0.78F, 1.0F);
        return Mth.clamp(overall * baseWiden, 0.30F, 1.25F);
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
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return false;
        }
        DynamicStormState state = stateFor(storm);
        return switch (state.multiVortexFlavour()) {
            // Lifted: the parent is out of the way entirely, which is what the vortices are for.
            case LIFTED -> true;
            // Ground swarm: only the layers below the tip are cut, so the condensation visibly stops
            // short of the ground with the swirls turning in the gap under it.
            case GROUND_SWARM -> MultiVortexParentProfile.groundSwarmCulled(state.parentTipFraction(), layerFraction(layerIndex, layerCount));
            // Embedded: the parent is planted on the ground exactly as usual. Nothing is cut.
            case EMBEDDED -> false;
        };
    }

    public static float multivortexParentLayerAlpha(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return 1.0F;
        }
        DynamicStormState state = stateFor(storm);
        float fraction = layerFraction(layerIndex, layerCount);
        return switch (state.multiVortexFlavour()) {
            case LIFTED -> 0.0F;
            case GROUND_SWARM -> MultiVortexParentProfile.groundSwarmAlpha(state.parentTipFraction(), fraction);
            case EMBEDDED -> MultiVortexParentProfile.embeddedAlpha(fraction);
        };
    }

    public static boolean hasActiveMultivortexVisual(StormObject storm) {
        if (storm == null || !Config.MULTIVORTEX_ENABLED.get() || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout) {
            return false;
        }
        return stateFor(storm).multiVortexVisualActive();
    }

    public static boolean shouldHideMultivortexParentFunnel(StormObject storm) {
        if (!hasActiveMultivortexVisual(storm)) {
            return false;
        }
        // Only a lifted parent is hidden. A ground swarm's parent still has to tick - it is drawn as a
        // thin rope that stops short - and an embedded one is the parent a tornado normally has.
        return stateFor(storm).multiVortexFlavour() == MultiVortexFlavour.LIFTED;
    }

    /**
     * Whether Weather2's own condensation funnel should be suppressed on this storm entirely.
     *
     * <p>A hurricane is a broad rain core with rotating bands and no tornado hanging in its middle,
     * but both of our hurricane storms are still real ticking StormObjects, and Weather2 draws its
     * point funnel on any storm that reaches the forming stage: the helper rain core because its
     * progression can climb there on its own, and the seeded native cyclone because it is DESIGNED
     * to climb the ladder to stage 5. Weather2's funnel is a tornado's silhouette, so on either of
     * those storms it reads as a tornado embedded in the hurricane's center. Our damage gates below
     * refuse grabs and rips on the same storms, so nothing is being rendered hollow.</p>
     */
    public static boolean shouldSuppressHelperFunnel(StormObject storm) {
        // Native waterspouts are exempt on purpose: they are also TYPE_WATER storms at the forming
        // stage, but a waterspout IS a funnel - hiding it would erase the feature.
        if (storm == null || storm.attrib_waterSpout) {
            return false;
        }
        if (Config.HURRICANES_ENABLED.get() && AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return true;
        }
        return Config.CYCLONES_ENABLED.get() && isNativeCyclone(storm);
    }

    /**
     * Whether the parent's own dust cloud and debris should be held back during an episode. They are:
     * under a lifted parent there is nothing at the surface to raise them, and under a ground swarm the
     * surface is the vortices' business. A planted parent keeps its own dust exactly as it always has -
     * its base is on the ground and should look like it.
     */
    public static boolean suppressMultivortexParentDust(StormObject storm) {
        return hasActiveMultivortexVisual(storm) && !stateFor(storm).multiVortexFlavour().parentOnTheGround();
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
        // Every multi-vortex arrangement leaves the main funnel where it is. A lifted parent is culled
        // layer by layer, a ground swarm's rope is culled below its tip, and an embedded parent is not
        // moved at all - the vortices come to it. So there is no ground lift for any of them.
        return 0.0D;
    }

    public static double subVortexWindMph(StormObject storm, DynamicStormState.SubVortex vortex) {
        if (storm == null || vortex == null) {
            return 0.0D;
        }
        // A breakdown bubble aloft has left the surface circulation behind, so it only adds wind
        // once it has descended into contact with the ground.
        if (storm.manager != null && storm.manager.getWorld() != null
                && !vortex.isGroundContact(storm.manager.getWorld().getGameTime())) {
            return 0.0D;
        }
        double parentWind = TornadoWindField.maxWindMph(storm);
        double intensity = Mth.clamp(vortex.intensityFraction(), 0.05F, 1.0F);
        double stageBlend = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0D, 0.0D, 1.0D);
        double physicsWind = parentWind * (0.58D + intensity * 0.86D);
        double explicitRangeWind = 100.0D + intensity * 155.0D + stageBlend * 74.0D;
        double wind = Mth.clamp(Math.max(physicsWind, explicitRangeWind), 100.0D, 335.0D);
        return wind * vortex.type().windScale();
    }

    private static float multivortexParentLowerScale(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return 1.0F;
        }
        float fraction = layerFraction(layerIndex, layerCount);
        DynamicStormState state = stateFor(storm);
        MultiVortexFlavour flavour = state.multiVortexFlavour();
        if (flavour == MultiVortexFlavour.GROUND_SWARM) {
            return MultiVortexParentProfile.groundSwarmWidthScale(state.parentTipFraction(), fraction);
        }
        if (flavour == MultiVortexFlavour.EMBEDDED) {
            return MultiVortexParentProfile.embeddedWidthScale(fraction);
        }
        float lowerBlend = 1.0F - smoothstep(Mth.clamp(fraction / MULTIVORTEX_PARENT_LIFT_FRACTION, 0.0F, 1.0F));
        return Mth.lerp(lowerBlend, 1.0F, 0.018F);
    }

    /**
     * Height of a funnel layer as a fraction of the whole funnel: 0 is the ground and 1 the cloud base.
     * Every layer index in the mod is interpreted this way, so the layer-profile helpers share it.
     */
    private static float layerFraction(int layerIndex, int layerCount) {
        return Mth.clamp(layerIndex / (float) Math.max(1, layerCount - 1), 0.0F, 1.0F);
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
        // A hurricane's Weather2 core is a rain engine, not a tornado: even if its progression
        // creeps past the forming stage, it does not get to start ripping blocks out of the world.
        if (shouldSuppressHelperFunnel(helper.storm)) {
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
        if (StructureLift.isCommitted(helper.storm, pos)) {
            // Already committed to demolition as part of a whole building: the structure failed as a
            // whole, so its individual blocks no longer get a vote.
            return stateFor(helper.storm).tryConsumeBlockDamage(level.getGameTime(), helper.storm);
        }
        TornadoWindField.WindSample wind = TornadoWindField.sample(helper.storm, level, Vec3.atCenterOf(pos));
        TornadoDamageModel.PickupDecision decision = TornadoDamageModel.evaluatePickup(helper.storm, level, sample, wind);
        if (decision.action() == TornadoDamageModel.PickupAction.OPEN && BlockGrabSafety.tryOpenDoor(level, sample)) {
            return false;
        }
        if (!decision.allowsWeather2Pickup()) {
            return false;
        }
        return stateFor(helper.storm).tryConsumeBlockDamage(level.getGameTime(), helper.storm);
    }

    public static boolean canGrabBlock(TornadoHelper helper, Level level, BlockState state, BlockPos pos) {
        if (!Config.DAMAGE_ENABLED.get() || helper == null || helper.storm == null) {
            return false;
        }
        if (shouldSuppressHelperFunnel(helper.storm)) {
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
        if (StructureLift.isCommitted(helper.storm, pos)) {
            // Committed demolition: masonry that out-resists the wind on its own still goes, because
            // the tornado has already failed the building it belongs to.
            return stateFor(helper.storm).tryConsumeBlockDamage(level.getGameTime(), helper.storm);
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
        if (shouldSuppressHelperFunnel(helper.storm)) {
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
        if (shouldSuppressHelperFunnel(helper.storm)) {
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
        boolean antiCyclonic = rotationSign(storm) < 0.0D;
        if (antiCyclonic) {
            // Weather2's own funnel spin is hard-coded cyclonic and ignores spinSpeed, so
            // the client neutralizes it for anti-cyclonic tornadoes (the funnel would
            // otherwise twist against its own dust cloud). Give our signed spin a floor
            // and extra range so the anti-cyclonic funnel spins at the same visual
            // energy as a normal one instead of nearly standing still.
            spinDelta = (Math.max(0.0F, state.visualSpinSpeed() - baseline) * 1.55F + 0.10F) * (drillBit ? 108.0F : floater ? 84.0F : 70.0F);
            spinLimit = drillBit ? 58.0F : floater ? 52.0F : 46.0F;
        }
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

    /**
     * Radius of the funnel's ACTUAL rendered wall at a given height fraction (0 = ground/
     * funnel base, 1 = cloud base). Weather2's simple funnel flares upward - each layer is
     * {@code radiusOfBase + radiusIncreasePerLayer * layerIndex} wide, so up where these
     * horizontal vortices sit the wall is several times the base radius. Reading the live
     * funnel (with our per-layer morphology scale) keeps the tentacle anchor on the wall
     * rather than floating somewhere inside the cone.
     */
    public static float funnelWallRadiusAt(StormObject storm, float heightFraction) {
        float fallback = storm == null ? 4.0F : Math.max(4.0F, rawBaseRadiusForWind(storm) * 0.5F);
        if (storm == null) {
            return fallback;
        }
        TornadoFunnelSimple funnel = storm.getTornadoFunnelSimple();
        if (funnel == null || funnel.getConfig() == null) {
            return fallback;
        }
        ActiveTornadoConfig config = funnel.getConfig();
        int layerCount = funnel.listLayers == null ? 0 : funnel.listLayers.size();
        if (layerCount <= 0) {
            return Math.max(2.0F, config.getRadiusOfBase());
        }
        int layerIndex = Mth.clamp(Math.round(Mth.clamp(heightFraction, 0.0F, 1.0F) * (layerCount - 1)), 0, layerCount - 1);
        float layerRadius = config.getRadiusOfBase() + config.getRadiusIncreasePerLayer() * layerIndex;
        return Math.max(2.0F, layerRadius * funnelLayerRadiusScale(storm, layerIndex, layerCount));
    }

    /**
     * Center line of the funnel at a given height fraction, read from Weather2's own rendered
     * layers (interpolated between the two nearest ones) so anything anchored to the funnel
     * stays on it even when the funnel leans or bends away from the storm coordinate.
     */
    public static Vec3 funnelLayerCenterAt(StormObject storm, float heightFraction) {
        if (storm == null) {
            return null;
        }
        TornadoFunnelSimple funnel = storm.getTornadoFunnelSimple();
        if (funnel == null || funnel.listLayers == null || funnel.listLayers.isEmpty()) {
            return storm.pos;
        }
        int layerCount = funnel.listLayers.size();
        double scaled = Mth.clamp(heightFraction, 0.0F, 1.0F) * (layerCount - 1);
        int lower = Mth.clamp((int) Math.floor(scaled), 0, layerCount - 1);
        int upper = Mth.clamp(lower + 1, 0, layerCount - 1);
        Layer low = funnel.listLayers.get(lower);
        Layer high = funnel.listLayers.get(upper);
        Vec3 lowPos = low == null ? null : low.getPos();
        Vec3 highPos = high == null ? null : high.getPos();
        if (lowPos == null) {
            return highPos == null ? storm.pos : highPos;
        }
        if (highPos == null) {
            return lowPos;
        }
        return lowPos.lerp(highPos, Mth.clamp(scaled - lower, 0.0D, 1.0D));
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
            state.updateHorizontalVortices(storm.manager.getWorld().getGameTime(), 0, funnelWallRadiusAt(storm, 0.0F), funnelWallRadiusAt(storm, 1.0F), false);
            return;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        int desired = maxWind >= 60.0D ? 1 : 0;
        if (maxWind >= 100.0D) {
            desired++;
        }
        if (maxWind >= 140.0D) {
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
        // Anchor the tubes to the real funnel wall: the funnel flares toward the cloud base,
        // so the wall radius is read live at the base and at the top and interpolated by each
        // tentacle's own height fraction.
        state.updateHorizontalVortices(storm.manager.getWorld().getGameTime(), desired, funnelWallRadiusAt(storm, 0.0F), funnelWallRadiusAt(storm, 1.0F), state.multiVortexVisualActive());
    }

    private static void updateSatelliteTornadoes(StormObject storm, DynamicStormState state) {
        if (!Config.SATELLITE_TORNADOES_ENABLED.get() || !Config.SATELLITE_TORNADOES_REAL_CIRCULATION.get() || !storm.isTornadoFormingOrGreater() || storm.manager == null || storm.manager.getWorld() == null) {
            state.updateSatelliteTornadoes(0L, 0, storm == null ? 0 : storm.levelCurIntensityStage, Config.SATELLITE_TORNADOES_MIN_PARENT_STAGE.get(), 0.0F, storm == null ? null : storm.pos, storm == null ? null : storm.motion, Config.SATELLITE_TORNADOES_MAX_WIND_FRACTION.get());
            return;
        }
        int configuredMax = Mth.clamp(Config.SATELLITE_TORNADOES_MAX_ACTIVE.get(), 0, 4);
        double maxWind = TornadoWindField.maxWindMph(storm);
        int desiredMax = 0;
        if (maxWind >= 110.0D) {
            desiredMax = 1;
        }
        if (maxWind >= 160.0D) {
            desiredMax = 2;
        }
        if (maxWind >= 210.0D) {
            desiredMax = 3;
        }
        desiredMax = Math.min(desiredMax, configuredMax);
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
        if (gameTime % interval != 0L || !hasSyncableAddonState(storm)) {
            return;
        }
        NetworkSafety.sendToPlayersNear(level, storm.pos.x, storm.pos.y, storm.pos.z, Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get(), new StormStateSyncPayload(storm.ID, state.save()));
    }

    /**
     * Whether this storm carries addon state a client needs. Kept as the single gate shared by the
     * periodic push and the join push, so a joining player can never receive something the
     * steady-state sync would not have sent them a moment later.
     */
    private static boolean hasSyncableAddonState(StormObject storm) {
        return Config.PERSISTENCE_ENABLED.get()
                && storm != null
                && storm.pos != null
                && isDynamicVortex(storm);
    }

    /**
     * Push every nearby dynamic storm's addon state to a player immediately, instead of leaving them
     * on the distance- and interval-gated {@link #syncAddonState} schedule.
     *
     * <p>That schedule only fires on every {@code multiplayer.stateSyncIntervalTicks} boundary and
     * only for storms currently inside {@code multiplayer.syncRangeBlocks}, so a player who has just
     * logged in renders whatever default the client holds until the next boundary: a mature wedge as
     * a sliver, no satellites, no sub-vortices, no horizontal vortices, and its funnel replaying its
     * birth ramp. The server state is correct the whole time - the client simply has not been handed
     * it yet, and it only reads a payload on a storm's next tick, never mid-render.
     *
     * <p>Sending it at login closes that window: the states reach the client's pending map before
     * Weather2 has even synced the storm objects, and each is applied on that storm's next tick, so
     * the first frame the player sees already matches the server.
     */
    public static void pushAddonStateTo(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        double range = Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get();
        double rangeSqr = range * range;
        int pushed = 0;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || !hasSyncableAddonState(storm)) {
                continue;
            }
            if (player.distanceToSqr(storm.pos.x, storm.pos.y, storm.pos.z) > rangeSqr) {
                continue;
            }
            if (NetworkSafety.sendToPlayer(player, new StormStateSyncPayload(storm.ID, stateFor(storm).save()))) {
                pushed++;
            }
        }
        if (pushed > 0 && Config.LOG_STARTUP.getAsBoolean()) {
            ExpandedWeather2Dynamics.LOGGER.debug(
                    "Pushed addon state for {} dynamic storm(s) to {} on join.",
                    pushed,
                    player.getGameProfile().getName()
            );
        }
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
