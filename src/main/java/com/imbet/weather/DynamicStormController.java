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
            // Restore-from-mirror before anything else: if Weather2's own NBT round trip dropped
            // our tag (the rejoin size-reset bug), this puts the storm's saved width/bend back on
            // its first server tick instead of letting it re-grow from defaults. A storm that
            // loaded fine from Weather2's NBT finds nothing here and loses nothing but one map
            // lookup - restore() consumes, so a deliberately gone storm cannot resurrect forever.
            //
            // Only on the state's first server tick. The mirror is saved again at the end of every tick, so restoring it
            // every tick reloaded the storm's own state from a copy of itself each tick - a whole write and read per storm
            // per tick - and wiped everything the copy does not carry, such as how fast the funnel's width was changing.
            if (state.markServerStarted()) {
                CompoundTag mirrored = StormAddonStateMirror.restore(serverLevel, storm.ID);
                if (mirrored != null && !mirrored.isEmpty()) {
                    state.load(mirrored);
                }
            }
            applyNativeCycloneProgression(serverLevel, storm);
            state.tickServer(serverLevel, storm, gameTime);
            applyRotationDirection(serverLevel, storm, state);
            applyMergeOrbit(storm, state, gameTime);
            StormAddonStateMirror.save(serverLevel, storm.ID, state, gameTime);
            if (gameTime % 600L == 0L) {
                StormAddonStateMirror.get(serverLevel).prune(gameTime);
            }
        } else {
            CompoundTag syncedState = ClientStormStateStore.consume(storm.ID);
            if (syncedState != null && !syncedState.isEmpty()) {
                state.loadClientSync(syncedState);
            }
            clearStrayWaterspoutTag(storm);
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
            TreeLift.tick(serverLevel, storm, state);
            applyEnvironmentalIntensity(serverLevel, storm, state);
            TreeDamage.tick(serverLevel, storm, state);
            HailDamage.tick(serverLevel, storm, state);
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

    /**
     * The storm's rung, raised by how wide the funnel actually is. Scouring depth used to key on
     * {@code levelCurIntensityStage} alone, so a widening wedge ploughed the same shallow groove as a
     * rope at the same rung - the ground never answered the funnel growing. Width is taken from the live
     * visible funnel, so this tracks growth and retreat both, and a big-boned but low-rung tornado still
     * has to be genuinely wide before it earns the deeper tiers.
     */
    public static int scouringStage(StormObject storm) {
        int stage = storm.levelCurIntensityStage;
        if (!Config.VARIABLE_WIDTHS_ENABLED.get() || !isDynamicVortex(storm)) {
            return stage;
        }
        float visible = stateFor(storm).visibleFunnelWidth();
        if (visible >= Config.milesToBlocks(1.0D)) {
            return Math.max(stage, Config.DAMAGE_HEAVY_SCOURING_STAGE.get());
        }
        if (visible >= Config.milesToBlocks(0.4D)) {
            return Math.max(stage, Config.DAMAGE_MEDIUM_SCOURING_STAGE.get());
        }
        return stage;
    }

    public static int adjustedTornadoBaseSize(StormObject storm, int originalSize) {
        if (!Config.VARIABLE_WIDTHS_ENABLED.get() || storm == null || !isDynamicVortex(storm)) {
            return originalSize;
        }
        DynamicStormState state = stateFor(storm);
        int visibleCap = Math.max(3, (int) Math.min(Integer.MAX_VALUE, Math.round(Config.effectiveMaxVisibleFunnelWidthBlocks())));
        // Keyed on the width, not on the wedge: a wide cone or barrel is drawn by the same Weather2
        // particles and needs the same allowance, or its particles collapse to the plain cap while its own
        // state says it is a mile across.
        if (state.wideTornado()) {
            int forcedWideCap = (int) Math.round(Math.min(Config.milesToBlocks(3.0D), Math.max(state.visibleFunnelWidth(), Config.milesToBlocks(0.5D))));
            visibleCap = Math.max(visibleCap, forcedWideCap);
        }
        if (Config.TORNADO_REALISTIC_WIDTH_MODEL.get()) {
            return Mth.clamp(Math.round(state.visibleFunnelWidth()), 3, visibleCap);
        }
        return Mth.clamp(Math.round(originalSize * state.widthMultiplier()), 3, visibleCap);
    }

    /** The most a funnel's rings are drawn out to by Weather2 itself; past it the same rings are spread wider. */
    private static final float FUNNEL_RING_RADIUS_CAP = 60.0F;

    /**
     * The radius the funnel's foot should be drawn at: half the storm's own visible width, unrounded. Weather2 creeps its
     * own radius toward a whole-block target at a hundredth of a block a tick, so the drawn funnel used to lag the
     * storm by minutes and kept creeping long after the storm had settled. A storm this mod does not size keeps
     * {@code fallback}, the radius Weather2 already has.
     */
    public static float funnelDrawnBaseRadius(StormObject storm, float fallback) {
        if (!Config.VARIABLE_WIDTHS_ENABLED.get() || storm == null || !isDynamicVortex(storm)) {
            return fallback;
        }
        DynamicStormState state = stateFor(storm);
        float cap = Math.max(3.0F, (float) Config.effectiveMaxVisibleFunnelWidthBlocks());
        if (state.wideTornado()) {
            cap = Math.max(cap, (float) Math.min(Config.milesToBlocks(3.0D), Math.max(state.visibleFunnelWidth(), Config.milesToBlocks(0.5D))));
        }
        float width = Config.TORNADO_REALISTIC_WIDTH_MODEL.get()
                ? state.visibleFunnelWidth()
                : fallback * 2.0F * state.widthMultiplier();
        return Mth.clamp(width, 3.0F, cap) * 0.5F;
    }

    /**
     * The radius Weather2's own rings are set to. Weather2 puts more puffs on a ring the wider it is, so a mile-wide
     * funnel drawn at its full radius would need more puffs than the particle queue holds. Past the cap the rings are
     * drawn at the cap and spread out to the full width instead, see {@link #funnelRingOverflow}.
     */
    public static float funnelRingBaseRadius(StormObject storm, float fallback) {
        return Math.min(funnelDrawnBaseRadius(storm, fallback), FUNNEL_RING_RADIUS_CAP);
    }

    /** How much further out than Weather2's own ring a layer has to be drawn to stand at the storm's real width. */
    private static float funnelRingOverflow(StormObject storm, int layerIndex) {
        TornadoFunnelSimple funnel = storm == null ? null : storm.getTornadoFunnelSimple();
        if (funnel == null || funnel.getConfig() == null) {
            return 1.0F;
        }
        float drawn = funnel.getConfig().getRadiusOfBase();
        float wanted = funnelDrawnBaseRadius(storm, drawn);
        if (wanted <= drawn + 0.01F) {
            return 1.0F;
        }
        float step = funnel.getConfig().getRadiusIncreasePerLayer() * layerIndex;
        return (wanted + step) / Math.max(0.5F, drawn + step);
    }

    /**
     * Where the shear wants the top of the funnel, against its foot, in blocks. It leans along the storm's own motion,
     * because the foot is held back by the ground while the cloud above carries on. A rope leans hard and a wedge stands
     * nearly upright, and a funnel on its way out leans furthest of all: the foot is left behind and the column is
     * stretched into the long thin line a rope-out is known for.
     */
    public static Vec3 funnelLean(StormObject storm) {
        DynamicStormState state = stateFor(storm);
        float bendAngle = state.bendAngle();
        if (storm.motion != null && storm.motion.horizontalDistanceSqr() > 0.0004D) {
            bendAngle = Mth.rotLerp(0.42F, bendAngle, motionAngleDegrees(storm));
        }
        double radians = Math.toRadians(bendAngle);
        double strength = Mth.clamp(state.bendStrength(), 0.0D, 1.0D);
        double tilt = Math.max(20.0D, Config.TORNADO_FUNNEL_TILT_BLOCKS.get()) * strength
                * (1.0D + funnelWeakness(state) * Config.TORNADO_FUNNEL_TILT_WEAKNESS_SCALE.get())
                * (1.0D + funnelRopeOut(storm) * 1.25D);
        return new Vec3(Math.cos(radians) * tilt, 0.0D, Math.sin(radians) * tilt);
    }

    /** How far the column may wander either side of its lean, in blocks: a rope dances, a wedge barely moves. */
    public static double funnelSway(StormObject storm) {
        DynamicStormState state = stateFor(storm);
        double sidewinding = Mth.clamp(state.sidewindingBendBlend(), 0.0F, 1.0F);
        return Math.max(14.0D, Config.TORNADO_FUNNEL_BEND_SWAY_BLOCKS.get())
                * (0.45D + sidewinding * 0.55D)
                * (0.30D + funnelWeakness(state) * 0.70D)
                * (1.0D + funnelRopeOut(storm) * 0.60D);
    }

    /** 1 for a thread of a funnel, 0 for one a quarter-mile across. */
    private static double funnelWeakness(DynamicStormState state) {
        return Mth.clamp(1.0D - (state.damagePathWidth() - 12.0D) / 450.0D, 0.0D, 1.0D);
    }

    /**
     * How far through roping out this funnel is: nought until it is in its last tornadic stage and failing, or being
     * absorbed. A storm past its peak steps down through every stage on its way out, and counting the whole of that as a
     * rope-out kept a violent tornado drawn as a thin leaning rope for most of its life.
     */
    public static float funnelRopeOut(StormObject storm) {
        boolean absorbed = isAbsorbedOrDecaying(storm);
        boolean lastStage = storm.hasStormPeaked && !storm.isGrowing && storm.levelCurIntensityStage <= StormObject.STATE_STAGE1;
        return absorbed || lastStage ? Mth.clamp((1.0F - Mth.clamp(storm.levelCurStagesIntensity, 0.0F, 1.0F)) * 1.55F, 0.0F, 1.0F) : 0.0F;
    }

    /** A layer's height up the column, 0 at the foot and 1 at the cloud base. */
    public static float funnelLayerFraction(StormObject storm, int layerIndex, int layerCount) {
        return layerFraction(storm, layerIndex, layerCount);
    }

    public static Vec3 funnelLayerMorphologyOffset(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return Vec3.ZERO;
        }
        DynamicStormState state = stateFor(storm);
        double x = 0.0D;
        double z = 0.0D;
        for (FunnelMorphology shape : SHAPES) {
            float share = state.shapeShare(shape);
            if (share <= 0.001F || !shape.shaped()) {
                continue;
            }
            Vec3 offset = shapeOffset(storm, state, shape, layerIndex, layerCount);
            x += offset.x * share;
            z += offset.z * share;
        }
        return x == 0.0D && z == 0.0D ? Vec3.ZERO : new Vec3(x, 0.0D, z);
    }

    /** One shape's bend of the axis at a layer, at full strength. */
    private static Vec3 shapeOffset(StormObject storm, DynamicStormState state, FunnelMorphology morphology, int layerIndex, int layerCount) {
        if (!shapeEnabled(state, morphology) || morphology == FunnelMorphology.WEDGE
                || morphology == FunnelMorphology.CONE || morphology == FunnelMorphology.STOVEPIPE) {
            return Vec3.ZERO;
        }
        float fraction = layerFraction(storm, layerIndex, layerCount);
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
        double phase = state.trunkCurvePhase();
        switch (morphology) {
            case DRILL_BIT -> {
                double tightScale = Math.max(2.75D, Math.min(widthScale * 0.34D, 8.0D));
                double helix = Math.sin(effective * Math.PI * 5.50D + phase * 2.25D) * tightScale * (0.20D + effective * 0.50D);
                double wobble = Math.cos(effective * Math.PI * 3.10D + phase) * tightScale * 0.30D * Math.sin(effective * Math.PI);
                return lean.scale(wobble).add(cross.scale(helix));
            }
            case FLOATER -> {
                double floatScale = Math.max(4.0D, Math.min(widthScale * 0.42D, 14.0D));
                double weave = Math.sin(effective * Math.PI * 2.35D + phase * 0.72D) * floatScale * 0.22D * Math.sin(effective * Math.PI);
                double hook = Math.cos(effective * Math.PI * 1.60D + phase) * floatScale * 0.12D * effective;
                return lean.scale(hook).add(cross.scale(weave));
            }
            case ROPE -> {
                // A thin sinuous rope: a travelling snake that grows toward the ground.
                double ropeScale = Math.max(2.2D, Math.min(widthScale * 0.22D, 5.0D));
                double wiggle = Math.sin(effective * Math.PI * 3.2D + phase * 1.6D) * ropeScale * (0.35D + effective * 0.65D);
                double snake = Math.sin(effective * Math.PI * 1.7D + phase * 0.9D) * ropeScale * 0.5D * Math.sin(effective * Math.PI);
                return lean.scale(snake * 0.6D).add(cross.scale(wiggle * 0.72D + snake * 0.42D));
            }
            case CORKSCREW -> {
                double twistScale = Math.max(3.5D, Math.min(widthScale * 0.34D, 9.0D));
                double helix = Math.sin(effective * Math.PI * 4.2D + phase * 2.1D) * twistScale * (0.30D + effective * 0.45D);
                double wobble = Math.cos(effective * Math.PI * 2.6D + phase) * twistScale * 0.35D * Math.sin(effective * Math.PI);
                return lean.scale(wobble).add(cross.scale(helix));
            }
            case BOWL -> {
                double sway = Math.sin(effective * Math.PI * 1.8D + phase * 0.8D) * widthScale * 0.16D * Math.sin(effective * Math.PI);
                return cross.scale(sway);
            }
            default -> {
                // The elephant trunk: a bow through the middle, a lean at the top and a hook at the foot.
                double strength = state.trunkCurveStrength() * Math.max(1.12D, Config.TORNADO_ELEPHANT_TRUNK_CURVE_STRENGTH.get());
                double neck = Mth.clamp(state.trunkNeckFraction(), 0.28F, 0.70F);
                double neckPull = Math.sin(Mth.clamp((effective - neck + 0.32D) / 0.64D, 0.0D, 1.0D) * Math.PI);
                double middleBow = Math.sin(effective * Math.PI) * widthScale * 0.82D;
                double upperLean = effective * effective * widthScale * 0.34D;
                double lowerHook = Math.sin((1.0D - effective) * Math.PI) * widthScale * 0.42D * Math.max(0.0D, 0.46D - effective);
                double curl = Math.sin(effective * Math.PI * 2.70D + phase) * widthScale * 0.30D * effective;
                double neckCurl = Math.cos(phase * 0.84D + effective * Math.PI * 3.35D) * widthScale * 0.16D * neckPull;
                return lean.scale((middleBow + upperLean + lowerHook) * strength).add(cross.scale((curl + neckCurl) * strength));
            }
        }
    }

    /** Whether a shape is switched on in the config. A switched-off shape is drawn as a plain funnel. */
    private static boolean shapeEnabled(DynamicStormState state, FunnelMorphology shape) {
        return switch (shape) {
            case ELEPHANT_TRUNK -> Config.TORNADO_ELEPHANT_TRUNK_ENABLED.get() && canUseElephantTrunkVisuals(state);
            case DRILL_BIT -> Config.TORNADO_DRILL_BIT_ENABLED.get();
            case FLOATER -> Config.FLOATER_TORNADOES_ENABLED.get();
            default -> true;
        };
    }

    /**
     * Funnel layer radius scale including any vortex breakdown pinch currently travelling down it.
     * The pinch multiplies the morphology scale so a breakdown bubble visibly necks the funnel as
     * it descends, and anything anchored to the wall (horizontal vortices, radar) follows it.
     */
    public static float funnelLayerRadiusScale(StormObject storm, int layerIndex, int layerCount) {
        float scale = funnelMorphologyRadiusScale(storm, layerIndex, layerCount) * funnelRingOverflow(storm, layerIndex);
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
        float fraction = layerFraction(storm, layerIndex, layerCount);
        return state.funnelPinchAt(fraction, storm.manager.getWorld().getGameTime());
    }

    /** How much wider than Weather2's taper a plain funnel is where it meets the cloud, on top of 1. */
    private static final float DEFAULT_FUNNEL_FLARE = 0.95F;
    private static final FunnelMorphology[] SHAPES = FunnelMorphology.values();

    private static float funnelMorphologyRadiusScale(StormObject storm, int layerIndex, int layerCount) {
        float multivortexScale = multivortexParentLowerScale(storm, layerIndex, layerCount);
        float fraction = layerFraction(storm, layerIndex, layerCount);
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return multivortexScale;
        }
        DynamicStormState state = stateFor(storm);
        float scale = 0.0F;
        float total = 0.0F;
        for (FunnelMorphology shape : SHAPES) {
            float share = state.shapeShare(shape);
            if (share <= 0.001F) {
                continue;
            }
            scale += shapeRadius(state, shape, fraction) * share;
            total += share;
        }
        return (total > 0.0F ? scale / total : plainRadius(fraction)) * multivortexScale;
    }

    /**
     * The plain funnel: the shape everyone draws when asked to draw a tornado, a slender trunk that opens out fast as it
     * nears the cloud. Weather2's own straight taper reads as a tube standing under the storm.
     */
    private static float plainRadius(float fraction) {
        return 1.0F + DEFAULT_FUNNEL_FLARE * fraction * fraction * fraction;
    }

    /**
     * One shape's outline at a height, as a scale on the funnel's own radius. The width of the funnel is the storm's, so
     * these are outlines rather than sizes: a rope is only chosen once the funnel is already thin, and is not made thin
     * a second time here.
     */
    private static float shapeRadius(DynamicStormState state, FunnelMorphology shape, float fraction) {
        if (!shapeEnabled(state, shape)) {
            return plainRadius(fraction);
        }
        float topFlare = fraction * fraction * fraction;
        switch (shape) {
            case WEDGE -> {
                float diameterScale = state.damagePathWidth() / Math.max(1.0F, state.visibleFunnelWidth());
                float groundScale = Mth.clamp(Math.max(1.35F, diameterScale), 1.35F, 5.25F);
                float shoulderScale = Mth.clamp(1.10F + diameterScale * 0.20F, 1.25F, 2.45F);
                float groundWeight = 1.0F - smoothstep(Mth.clamp(fraction / 0.42F, 0.0F, 1.0F));
                float upperTaper = smoothstep(Mth.clamp((fraction - 0.62F) / 0.38F, 0.0F, 1.0F));
                float shoulderWeight = (float) Math.sin(Mth.clamp(fraction / 0.82F, 0.0F, 1.0F) * Math.PI) * (1.0F - upperTaper * 0.55F);
                float wedge = 1.0F + groundWeight * (groundScale - 1.0F) + shoulderWeight * (shoulderScale - 1.0F) - upperTaper * 0.12F;
                return Mth.clamp(wedge, 0.82F, groundScale);
            }
            case DRILL_BIT -> {
                float lowerContact = 1.0F - smoothstep(Mth.clamp(fraction / 0.16F, 0.0F, 1.0F));
                float upperTaper = smoothstep(Mth.clamp((fraction - 0.58F) / 0.42F, 0.0F, 1.0F));
                return Mth.clamp(0.48F + upperTaper * 0.16F + lowerContact * 0.08F, 0.44F, 0.72F) + topFlare * 0.40F;
            }
            case FLOATER -> {
                float lowerDetached = smoothstep(Mth.clamp(fraction / 0.24F, 0.0F, 1.0F));
                float upperBase = smoothstep(Mth.clamp((fraction - 0.54F) / 0.46F, 0.0F, 1.0F));
                float waist = 1.0F - Mth.clamp(Math.abs(fraction - 0.46F) / 0.34F, 0.0F, 1.0F);
                return Mth.clamp(0.30F + lowerDetached * 0.30F + upperBase * 0.30F + waist * 0.10F, 0.28F, 0.95F);
            }
            case CONE -> {
                // The textbook cone: narrow at the ground and widening steadily up to the cloud.
                return Mth.lerp(fraction, 0.42F, 1.32F);
            }
            case STOVEPIPE -> {
                // A tall even column with a slight taper at the top and a flare at its foot.
                float topTaper = smoothstep(Mth.clamp((fraction - 0.80F) / 0.20F, 0.0F, 1.0F));
                float baseFlare = 1.0F - smoothstep(Mth.clamp(fraction / 0.16F, 0.0F, 1.0F));
                return Mth.clamp(1.0F - topTaper * 0.20F + baseFlare * 0.18F, 0.72F, 1.22F);
            }
            case ROPE -> {
                // Even down its length with a slow ripple travelling along it, opening out into the cloud at the top.
                float pulse = 0.5F + 0.5F * (float) Math.sin(fraction * Math.PI * 3.0D + state.trunkCurvePhase() * 1.7D);
                return Mth.clamp(0.72F + (1.0F - fraction) * 0.10F + pulse * 0.06F, 0.68F, 0.92F) + topFlare * 0.50F;
            }
            case CORKSCREW -> {
                float twist = 0.5F + 0.5F * (float) Math.sin(fraction * Math.PI * 4.0D + state.trunkCurvePhase() * 2.0D);
                return Mth.clamp(0.62F + twist * 0.22F - fraction * 0.10F, 0.45F, 0.95F) + topFlare * 0.40F;
            }
            case BOWL -> {
                // Flares into the cloud at the top, narrows to a waist and stands on a firm base.
                float bowlFlare = smoothstep(Mth.clamp((fraction - 0.55F) / 0.45F, 0.0F, 1.0F));
                float waist = 1.0F - smoothstep(Mth.clamp(Math.abs(fraction - 0.38F) / 0.24F, 0.0F, 1.0F));
                float base = 1.0F - smoothstep(Mth.clamp(fraction / 0.18F, 0.0F, 1.0F));
                return Mth.clamp(0.62F + bowlFlare * 0.66F + waist * 0.22F + base * 0.30F, 0.55F, 1.35F);
            }
            case ELEPHANT_TRUNK -> {
                float neck = Mth.clamp(state.trunkNeckFraction(), 0.28F, 0.70F);
                float neckNarrow = 1.0F - Mth.clamp(Math.abs(fraction - neck) / 0.31F, 0.0F, 1.0F);
                float upperBase = smoothstep(Mth.clamp((fraction - 0.62F) / 0.38F, 0.0F, 1.0F));
                float upperCapNarrow = smoothstep(Mth.clamp((fraction - 0.84F) / 0.16F, 0.0F, 1.0F));
                float lowerContact = 1.0F - smoothstep(Mth.clamp(fraction / 0.16F, 0.0F, 1.0F));
                float lowerRope = smoothstep(Mth.clamp((0.34F - fraction) / 0.34F, 0.0F, 1.0F));
                float trunk = 0.62F - neckNarrow * 0.20F + upperBase * 0.42F - upperCapNarrow * 0.16F + lowerContact * 0.08F - lowerRope * 0.06F;
                return Mth.clamp(trunk, 0.46F, 1.06F);
            }
            default -> {
                return plainRadius(fraction);
            }
        }
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
        float fraction = layerFraction(storm, layerIndex, layerCount);
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
        float fraction = layerFraction(storm, layerIndex, layerCount);
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

    /**
     * The funnel's width through its life. Condensation comes down out of the cloud as a thin tip, each layer widening
     * only after the tip has gone past it, and a dying tornado draws in along its whole length into a thin rope.
     */
    public static float funnelLayerFormationRadius(StormObject storm, float ageTicks, int layerIndex, int layerCount) {
        if (storm == null || layerCount <= 1 || !Config.TORNADO_FORMING_ANIMATION_ENABLED.get() || storm.attrib_waterSpout) {
            return 1.0F;
        }
        float rope = funnelRopeOut(storm);
        if (rope > 0.0F) {
            return Mth.lerp(rope, 1.0F, 0.32F);
        }
        return Mth.lerp(condensationDescent(storm, ageTicks, layerFraction(storm, layerIndex, layerCount)), 0.30F, 1.0F);
    }

    public static float funnelParticleScale(StormObject storm, int layerIndex, int layerCount) {
        float overflow = funnelRingOverflow(storm, layerIndex);
        float radiusScale = funnelLayerRadiusScale(storm, layerIndex, layerCount) / overflow;
        float multivortexScale = multivortexParentLowerScale(storm, layerIndex, layerCount);
        float fraction = layerFraction(storm, layerIndex, layerCount);
        float topSoftening = Mth.lerp(fraction, 0.05F, 0.22F);
        return Mth.clamp(radiusScale * 0.88F + topSoftening * multivortexScale, 0.06F, 1.20F) * overflow;
    }

    public static boolean shouldCullMultivortexParentLayer(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return false;
        }
        DynamicStormState state = stateFor(storm);
        return switch (state.multiVortexFlavour()) {
            // Lifted: only the part of the column below the retraction is clear air. What is above it is
            // the parent, so an episode takes the funnel's foot away, never the funnel.
            case LIFTED -> liftedParentHidden()
                    ? true
                    : MultiVortexParentProfile.liftedCulled(liftedTipFraction(storm, state), layerFraction(storm, layerIndex, layerCount));
            // Ground swarm: only the layers below the tip are cut, so the condensation visibly stops
            // short of the ground with the swirls turning in the gap under it.
            case GROUND_SWARM -> MultiVortexParentProfile.groundSwarmCulled(state.parentTipFraction(), layerFraction(storm, layerIndex, layerCount));
            // Embedded: the parent is planted on the ground exactly as usual. Nothing is cut.
            case EMBEDDED -> false;
        };
    }

    public static float multivortexParentLayerAlpha(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return 1.0F;
        }
        DynamicStormState state = stateFor(storm);
        float fraction = layerFraction(storm, layerIndex, layerCount);
        return switch (state.multiVortexFlavour()) {
            case LIFTED -> liftedParentHidden()
                    ? 0.0F
                    : MultiVortexParentProfile.liftedAlpha(liftedTipFraction(storm, state), fraction);
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
        // Only a lifted parent is ever hidden, and only when that is what the config asks for -
        // by default its foot simply retracts instead. A ground swarm's parent still has to tick - it is
        // drawn as a thin rope that stops short - and an embedded one is the parent a tornado normally
        // has.
        return stateFor(storm).multiVortexFlavour() == MultiVortexFlavour.LIFTED && liftedParentHidden();
    }

    /**
     * Whether a lifted episode hides the parent completely. Off by default: a lifted parent normally
     * keeps drawing the column above its retraction, so a multi-vortex tornado is still a tornado.
     */
    private static boolean liftedParentHidden() {
        return !Config.MULTIVORTEX_LIFTED_PARENT_VISIBLE.get();
    }

    /** How far up this storm's lifted parent has retracted right now, as a layer fraction. */
    private static float liftedTipFraction(StormObject storm, DynamicStormState state) {
        return state.parentLiftFraction(stormGameTime(storm));
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
        if (storm == null) {
            return false;
        }
        // A tropical system is decided before the waterspout exemption, because Weather2's waterspout pass
        // tags water storms and a tagged hurricane would otherwise escape suppression and draw its own
        // tornado. A waterspout that is genuinely one is still exempt: it has not climbed the ladder.
        boolean tropical = isTropicalSystem(storm) || isNativeCyclone(storm);
        if (!tropical && storm.attrib_waterSpout) {
            return false;
        }
        if (AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return Config.HURRICANES_ENABLED.get();
        }
        if (!tropical) {
            return false;
        }
        // The real water storm this mod now forms is a hurricane, not a tornado, however strong it
        // gets - and Weather2's own funnel branch only ever fires for a LAND storm, so the storm type
        // is the whole story here. This is also the gate Weather2's block grabbing, ripping and the
        // ground scouring read, so a hurricane can neither wear a tornado's silhouette nor tear a
        // tornado's trench through the world while it does it.
        if (!isNativeCyclone(storm)) {
            return false;
        }
        return Config.HURRICANES_ENABLED.get() || Config.CYCLONES_ENABLED.get();
    }

    /**
     * Whether the parent's own dust cloud and debris should be held back during an episode. They are:
     * under a lifted parent the base has pulled up off the surface, so there is nothing down there to
     * raise them, and under a ground swarm the surface is the vortices' business. A planted parent keeps
     * its own dust exactly as it always has - its base is on the ground and should look like it.
     */
    public static boolean suppressMultivortexParentDust(StormObject storm) {
        return hasActiveMultivortexVisual(storm) && !stateFor(storm).multiVortexFlavour().parentOnTheGround();
    }

    public static double mainFunnelGroundLift(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || !storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout || layerCount <= 1) {
            return 0.0D;
        }
        DynamicStormState state = stateFor(storm);
        float blend = Config.FLOATER_TORNADOES_ENABLED.get() ? state.shapeShare(FunnelMorphology.FLOATER) : 0.0F;
        if (blend > 0.001F) {
            float fraction = layerFraction(storm, layerIndex, layerCount);
            double lowerWeight = 1.0D - smoothstep(Mth.clamp(fraction / 0.68F, 0.0F, 1.0F));
            double lift = Mth.clamp(18.0D + state.visibleFunnelWidth() * 0.22D, 18.0D, 64.0D);
            return lift * lowerWeight * blend;
        }
        // Every multi-vortex arrangement leaves the main funnel where it is. A lifted parent is culled
        // layer by layer below its retraction, a ground swarm's rope is culled below its tip, and an
        // embedded parent is not moved at all - the vortices come to it. So there is no ground lift for
        // any of them: the funnel's foot is what changes, not the funnel's height in the sky.
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
        // Suction vortices live on gusts: each one surges and relaxes on its own deterministic
        // event train (id-keyed, so server and clients agree without syncing), which is what
        // makes real multi-vortex damage look swirly and stochastic instead of uniform.
        long gameTime = storm.manager != null && storm.manager.getWorld() != null
                ? storm.manager.getWorld().getGameTime() : 0L;
        wind *= 1.0D + 0.28D * TornadoGustModel.pointTurbulence(vortex.id() * 31L + 7L, gameTime);
        return wind * vortex.type().windScale();
    }

    private static float multivortexParentLowerScale(StormObject storm, int layerIndex, int layerCount) {
        if (!hasActiveMultivortexParent(storm, layerCount)) {
            return 1.0F;
        }
        float fraction = layerFraction(storm, layerIndex, layerCount);
        DynamicStormState state = stateFor(storm);
        MultiVortexFlavour flavour = state.multiVortexFlavour();
        if (flavour == MultiVortexFlavour.GROUND_SWARM) {
            return MultiVortexParentProfile.groundSwarmWidthScale(state.parentTipFraction(), fraction);
        }
        if (flavour == MultiVortexFlavour.EMBEDDED) {
            return MultiVortexParentProfile.embeddedWidthScale(fraction);
        }
        if (!liftedParentHidden()) {
            return MultiVortexParentProfile.liftedWidthScale(liftedTipFraction(storm, state), fraction);
        }
        float lowerBlend = 1.0F - smoothstep(Mth.clamp(fraction / MULTIVORTEX_PARENT_LIFT_FRACTION, 0.0F, 1.0F));
        return Mth.lerp(lowerBlend, 1.0F, 0.018F);
    }

    /**
     * Height of a funnel layer as a fraction of the whole funnel: 0 is the ground and 1 the cloud base.
     * Every layer index in the mod is interpreted this way, so the layer-profile helpers share it.
     *
     * <p>The quotient's denominator is the layer count, and Weather2 does not fix that count for the
     * life of a funnel: it derives it from the tornado's own height every tick and appends a layer the
     * moment the funnel grew enough to need one. So `index / (count - 1)` re-indexes EVERY layer in one
     * tick, and the layer it moves most is the one at the top of the profile, where the shape itself
     * changes fastest - a bowl's flare climbs over its whole upper half. The ring one layer down jumped
     * wider than the ring above it, then the next append did it again: the top of the funnel swelled and
     * reset, over and over, and it was the top layer that paid for it. Nothing below the top was clamped
     * to fix that - the count the profile is read against is glided instead (see
     * {@link DynamicStormState#glideLayerCount}), so the shape moves through a growing funnel instead of
     * stepping, and a steady funnel is untouched.
     */
    private static float layerFraction(StormObject storm, int layerIndex, int layerCount) {
        float effectiveCount = effectiveLayerCount(storm, layerCount);
        return Mth.clamp(layerIndex / Math.max(1.0F, effectiveCount - 1.0F), 0.0F, 1.0F);
    }

    /**
     * The layer count the shape profiles are actually read against: Weather2's own count, glided over a
     * handful of ticks so a layer appearing re-shapes the column smoothly rather than snapping every
     * ring at once. It is never LOWER than the count that exists (a layer must keep a sane fraction), so
     * the newest, topmost layer reads 1.0 - the cloud base - while the ones under it slide down into
     * their new places.
     */
    private static float effectiveLayerCount(StormObject storm, int layerCount) {
        if (storm == null || layerCount <= 1) {
            return Math.max(1, layerCount);
        }
        return stateFor(storm).glideLayerCount(layerCount, stormGameTime(storm));
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
        // The simple settings' storm limit: at the cap, no further storm in this player's weather is promoted
        // to tornado-capable, so the storms already running are the ones that finish. A storm that is already a
        // tornado keeps its intensity - the limit is on new ones, not a way to disarm the sky mid-funnel.
        if (storm.levelCurIntensityStage < StormObject.STATE_FORMING
                && Config.atStormLimit(countPromotedStormsNear(level, storm.pos))) {
            return originalMax;
        }
        boolean alreadyTornadoCapable = originalMax >= StormObject.STATE_FORMING || storm.levelCurIntensityStage >= StormObject.STATE_FORMING;
        if (!alreadyTornadoCapable) {
            // The ground first, then the odds: a storm over country a tornado does not belong to is
            // refused at the one place a natural promotion happens, and never gets to roll at all. A
            // tornado already on the ground is not sent back by this - the gate is on becoming one, not
            // on being one - so a funnel that wanders over a forest keeps its funnel.
            if (!TornadoGenesisGate.allows(level, origin)) {
                return originalMax;
            }
            double rateBoost = Mth.clamp(Config.TORNADO_CAPABLE_RATE_BOOST.get(), 0.0D, 4.0D);
            // A front decides how much of this environment's instability can actually reach the ground as
            // rotation: a warm front and the warm sector produce tornadoes, a cold front forces a line and
            // a post-frontal air mass produces almost none.
            // What the country itself is worth, on top of the air: the same storm is promoted more often in
            // dryline country than over frozen ground. The base chance keeps its own ceiling, and the belt is
            // allowed to lift that ceiling by a quarter rather than being open-ended, so an alley day is a
            // busy day without the promotion rate being unbounded.
            double tornadoOdds = BiomeClimateService.odds(level, origin).tornado();
            // And the season on top of the country: the same plains afternoon is worth more in spring
            // than in autumn, which is the whole reason a world has a calendar.
            double seasonOdds = SeasonCalendarService.effect(level).tornado();
            double tornadoCapableChance = Mth.clamp((0.025D + outlook.tornadoProbability() * 0.34D + severity * 0.035D)
                    * rateBoost * outlook.frontalTornadoSpawnFactor() * tornadoOdds * seasonOdds, 0.0D, 0.42D);
            if (level.random.nextDouble() >= tornadoCapableChance) {
                return originalMax;
            }
        }
        int weightedStage = weightedNaturalTornadoMaxStage(level, severity, cape, shear,
                outlook.allowsViolentOutbreak(), outlook.linearForcedConvection());
        return Mth.clamp(Math.max(weightedStage, storm.levelCurIntensityStage), originalMax >= StormObject.STATE_FORMING ? StormObject.STATE_FORMING : originalMax, StormObject.STATE_STAGE5);
    }

    /**
     * The tornado intensity a naturally spawning storm may reach, drawn from a weighted distribution.
     *
     * <p>{@code linearForced} is the cold front's shape of convection: a squall line does spin up
     * tornadoes, but they are the short-lived QLCS kind, so the violent end of the distribution is
     * damped rather than the whole roll being scaled down. A line still produces tornadoes; it just
     * almost never produces an EF4.
     */
    private static int weightedNaturalTornadoMaxStage(ServerLevel level, double severity, double cape, double shear,
                                                      boolean violentOutbreak, boolean linearForced) {
        double formingWeight = 58.0D - severity * 12.0D;
        double ef1Weight = 43.0D + severity * 8.0D;
        double ef2Weight = 16.0D + severity * 14.0D + cape * 5.0D;
        double ef3Weight = 7.0D + severity * 10.0D + shear * 7.0D;
        double ef4Weight = 1.0D + severity * 3.2D + (severity >= 0.70D ? 1.4D : 0.0D);
        double ef5Weight = 0.20D + (violentOutbreak ? 1.15D : 0.0D) + Math.max(0.0D, severity - 0.82D) * 3.0D;
        if (linearForced) {
            ef3Weight *= 0.75D;
            ef4Weight *= 0.40D;
            ef5Weight *= 0.20D;
        }
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
        boolean dynamicVortex = isDynamicVortex(storm);
        // A hurricane is neither a tornado nor a supercell: it has its own broad wind field, and the
        // 1.12.2 hurricane was a violent wind storm first and a rain system second. Standing in its eye
        // wall has to move the player, so the shield storm is sampled through the vortex profile (its
        // eyewall), pushed by the shared wind-push path, and only denied the tornado's core capture -
        // there is nothing to be caught in, because the middle of this storm is calm.
        boolean shieldStorm = !dynamicVortex && HurricaneShield.isShieldStorm(storm);
        TornadoWindField.WindSample sample = dynamicVortex || shieldStorm
                ? TornadoWindField.sample(storm, storm.manager.getWorld(), position)
                : SupercellStructureService.sampleWind(storm, storm.manager.getWorld(), position);
        if (!dynamicVortex && !shieldStorm && !Config.SUPERCELL_STRUCTURE_RFD_STRAIGHT_LINE_WIND.get()) {
            return originalMotion;
        }
        if (sample.speedMph() <= 1.0D) {
            return originalMotion;
        }
        return TornadoDamageModel.adjustEntityMotion(storm, sample, originalMotion, forPlayer, forCube, grabAdj, !shieldStorm);
    }

    public static boolean shouldTryRip(TornadoHelper helper, Level level, BlockPos pos) {
        if (!Config.damageEnabled() || helper == null || helper.storm == null) {
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
        if (!Config.damageEnabled() || helper == null || helper.storm == null) {
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
            if (scouringStage(helper.storm) < requiredStage) {
                return false;
            }
        }
        return true;
    }

    public static boolean canGrabEntity(TornadoHelper helper, Entity entity) {
        if (!Config.damageEnabled() || helper == null || helper.storm == null) {
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
        if (!Config.damageEnabled() || helper == null || helper.storm == null || entity == null) {
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
        // The tropical check comes FIRST, before the waterspout tag, and that order is the whole point of this
        // method. Weather2 sets attrib_waterSpout on water storms in its own waterspout pass, and this branch
        // used to read that tag first - so a hurricane carrying it was treated as a tornado: a tornado's wind
        // field, a tornado's damage path, and a tornado's funnel drawn down the middle of the hurricane. A
        // storm that has climbed the tropical ladder is not a waterspout no matter what the tag says; a real
        // waterspout never leaves the low rungs, so it is still caught by the tag below.
        if (isTropicalSystem(storm) || AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return false;
        }
        if (storm.attrib_waterSpout) {
            return true;
        }
        return storm.isTornadoFormingOrGreater();
    }

    /**
     * Whether this storm is a tropical system - a hurricane or a cyclone - <b>regardless of its waterspout
     * tag</b>.
     *
     * <p>That exemption is deliberate and it is the fix for a real bug. {@link #isNativeTropicalSystem} cannot
     * answer this question, because it excludes waterspouts by the very flag that gets set on hurricanes: with
     * the flag set it says a hurricane is not a tropical system, every consumer believes it, and the storm is
     * handed to the tornado code - which is how a hurricane ended up wearing a funnel in its middle. The rung
     * the storm has climbed is evidence the flag cannot touch: a waterspout is a funnel over water and never
     * reaches the tropical storm rung, while everything on the ladder above it is a tropical system by
     * definition, which is what 1.12.2's {@code StormType.WATER} meant.
     */
    public static boolean isTropicalSystem(StormObject storm) {
        if (storm == null || storm.stormType != StormObject.TYPE_WATER) {
            return false;
        }
        StormSubtype subtype = stateFor(storm).subtype();
        if (subtype == StormSubtype.HURRICANE || subtype == StormSubtype.CYCLONE) {
            return true;
        }
        return storm.levelCurIntensityStage >= TropicalCyclone.STORM_STAGE;
    }

    /**
     * Whether the southern hemisphere of this world is the cyclones' half.
     *
     * <p>Minecraft has no latitude, so there is no such thing as "the tropics" and no such thing as a
     * hemisphere until a line is drawn. This draws it on the Z axis - south is +Z, which is the direction a
     * compass reads as south - with the line's position configurable so a world whose warm water sits on one
     * side can be lined up with it.
     */
    public static boolean southernHemisphere(double z) {
        return z > Config.TROPICAL_HEMISPHERE_EQUATOR_Z.get();
    }

    /**
     * Which tropical family a storm here may join: {@code TRUE} for an anticyclonic cyclone, {@code FALSE}
     * for a cyclonic hurricane, or {@code null} when that family is switched off and the storm must stay a
     * land storm.
     *
     * <p>With the split on, the hemisphere decides the family and the family's own switch decides whether it
     * exists at all - so the northern half of a world with cyclones disabled simply has no tropical systems
     * south of the line, instead of quietly forming hurricanes on both sides.
     *
     * <p>With the split off this falls back to the older behaviour, kept because a player who liked the old
     * rule should not lose it: whichever families are enabled are chosen between at random, anywhere.
     */
    public static Boolean tropicalFamilyAt(Level level, double z) {
        if (Config.TROPICAL_HEMISPHERE_SPLIT.get()) {
            if (southernHemisphere(z)) {
                return Config.CYCLONES_ENABLED.get() ? Boolean.TRUE : null;
            }
            return Config.HURRICANES_ENABLED.get() ? Boolean.FALSE : null;
        }
        if (Config.HURRICANES_ENABLED.get() && Config.CYCLONES_ENABLED.get()) {
            return level != null && level.random.nextDouble() < Config.ANTI_CYCLONIC_TORNADO_CHANCE.get();
        }
        if (Config.HURRICANES_ENABLED.get()) {
            return Boolean.FALSE;
        }
        return Config.CYCLONES_ENABLED.get() ? Boolean.TRUE : null;
    }

    public static boolean isNativeCyclone(StormObject storm) {
        return storm != null
                && storm.stormType == StormObject.TYPE_WATER
                && storm.levelCurIntensityStage >= StormObject.STATE_FORMING;
    }

    /**
     * Any storm on the tropical ladder, a tropical disturbance upward - not just the ones that have
     * already climbed to the forming rung or to hurricane strength. A water storm is a tropical system
     * for its whole life, which is what 1.12.2's {@code StormType.WATER} meant; the names it wears over
     * that life (disturbance, depression, tropical storm, hurricane) all hang off this.
     */
    public static boolean isNativeTropicalSystem(StormObject storm) {
        return storm != null
                && !storm.attrib_waterSpout
                && storm.stormType == StormObject.TYPE_WATER
                && TropicalCyclone.isTropical(storm.levelCurIntensityStage);
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
        // One path into being a water storm, so a promoted cyclone and a hurricane that formed on
        // its own are the same kind of storm wearing opposite spins. The cyclone command then only
        // has to add the strength it was asked for.
        if (!startTropicalStorm(level, storm, true)) {
            return false;
        }

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
        float fraction = layerFraction(storm, layerIndex, layerCount);
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
     * World Y of the funnel's top (cloud-base) anchor, or NaN when no funnel exists yet.
     * Together with the ground this gives the funnel's real world height, so debris-cloud
     * features can be placed as a FRACTION of the funnel instead of absolute block heights
     * - a wedge's dust climbs hundreds of blocks while a rope's climbs tens, which is what
     * keeps a big storm's debris cloud from reading as a flat disc under a tall funnel.
     */
    public static double funnelTopWorldY(StormObject storm) {
        if (storm == null) {
            return Double.NaN;
        }
        return storm.getTornadoFunnelSimple() == null ? Double.NaN : cloudBaseWorldY(storm);
    }

    /** How far under the height of a storm's deck its flat cloud base hangs, in blocks. */
    private static final double CLOUD_BASE_BELOW_DECK_BLOCKS = 40.0D;
    /** The least room a funnel gets under the cloud, over high ground, in blocks. */
    private static final double CLOUD_BASE_MIN_CLEARANCE_BLOCKS = 56.0D;
    /** The ground over a column that is not loaded, which answers the bottom of the world. */
    private static final int UNLOADED_GROUND_Y = 64;

    /**
     * The underside of a storm's cloud, in world height: the one level the funnel ends at, the deck's puffs stop at, and
     * the wall cloud hangs from. They used to disagree by a hundred blocks, with a funnel ending in mid air inside a
     * deck whose biggest puffs hung almost to the ground.
     */
    public static double cloudBaseWorldY(StormObject storm) {
        return Math.max(groundUnderStorm(storm) + CLOUD_BASE_MIN_CLEARANCE_BLOCKS, storm.pos.y - CLOUD_BASE_BELOW_DECK_BLOCKS);
    }

    /** How tall a storm's funnel is, ground to cloud base, in blocks. */
    public static double funnelHeightBlocks(StormObject storm) {
        return cloudBaseWorldY(storm) - groundUnderStorm(storm);
    }

    private static int groundUnderStorm(StormObject storm) {
        Level world = storm.manager == null ? null : storm.manager.getWorld();
        int ground = world == null ? UNLOADED_GROUND_Y
                : world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, Mth.floor(storm.pos.x), Mth.floor(storm.pos.z));
        return world != null && ground <= world.getMinBuildHeight() + 1 ? UNLOADED_GROUND_Y : ground;
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

    /**
     * The storm's precipitation extent in blocks - how big it actually looks in the sky, by the same rule the
     * volumetric precipitation renderer draws with.
     *
     * <p>Exists so the radar echoes a storm of the size the player can see. The radar used to size its returns
     * from the tornado's damage-path width on a fixed base, which made every storm roughly the same size and
     * made a violent tornado's storm look broader than a large storm with a weak one.
     */
    public static double stormPrecipRadius(StormObject storm) {
        if (storm == null) {
            return 56.0D;
        }
        double size = Math.max(Math.max(storm.getSize(), storm.size), storm.maxSize * 0.55D);
        if (isDynamicVortex(storm)) {
            size = Math.max(size, stateFor(storm).damagePathWidth() * 1.4D);
        }
        return Mth.clamp(size * 1.25D, 56.0D, 360.0D);
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
        if (storm == null || storm.pos == null) {
            return;
        }
        // A water storm is a tropical system for its whole life. Its subtype is set once, at
        // genesis, and only ever refreshed - the ladder name it wears (disturbance, depression,
        // tropical storm, hurricane) is read off its stage by TropicalCyclone, so it does not need
        // to be re-derived here.
        if (storm.stormType == StormObject.TYPE_WATER) {
            storm.attrib_waterSpout = false;
            DynamicStormState state = stateFor(storm);
            if (storm.levelCurIntensityStage >= StormObject.STATE_FORMING) {
                StormSubtype expected = state.isAntiCyclonic() ? StormSubtype.CYCLONE : StormSubtype.HURRICANE;
                if (state.subtype() != expected) {
                    state.setSubtype(expected);
                }
            }
            return;
        }
        if (Config.CYCLONES_ENABLED.get() && isOceanCycloneCandidate(level, storm)) {
            double targetWind = Math.max(74.0D, TornadoWindField.maxWindMph(storm) * 0.42D);
            promoteNativeCyclone(level, storm, false, targetWind);
            return;
        }
        if (Config.HURRICANES_ENABLED.get()) {
            maybeBeginTropicalGenesis(level, storm);
        }
    }

    /**
     * The 1.12.2 hurricane's way in: a storm sitting over warm ocean in the tropical window can
     * become a water storm and thereafter live on the tropical ladder. Weather2 1.12.2 decided this
     * once, at storm genesis, from the biome underneath - which meant a hurricane could never form
     * anywhere a player could watch it happen, and never over a warm sea the storm had drifted onto.
     * This decides it per attempt instead, so the same thing 1.12.2 did is available to storms that
     * reach warm water, and the storm visibly changes character rather than being born decided.
     */
    private static void maybeBeginTropicalGenesis(ServerLevel level, StormObject storm) {
        if (storm.stormType == StormObject.TYPE_WATER
                || storm.attrib_waterSpout
                || storm.weatherMachineControlled
                || storm.isPlayerControlled()
                || storm.isPet()
                || storm.isDead
                || AddonWeatherEventService.isHurricaneHelperStorm(storm)
                || AddonWeatherEventService.isSquallLineHelperStorm(storm)) {
            return;
        }
        // Below a thunderstorm there is nothing to organize; past the forming stage the storm has
        // already committed to being a land storm, and a tornado that is already on the ground is
        // not going to turn into a hurricane.
        if (storm.levelCurIntensityStage < StormObject.STATE_THUNDER
                || storm.levelCurIntensityStage > StormObject.STATE_FORMING
                || storm.isTornadoFormingOrGreater()) {
            return;
        }
        if (countNativeTropicalStorms(level) >= Config.HURRICANE_MAX_ACTIVE.get()) {
            return;
        }
        if (!DailySpcOutlookService.hurricaneWindow(level, BlockPos.containing(storm.pos))) {
            return;
        }
        if (!isOverWaterOrOcean(level, storm.pos.x, storm.pos.z)) {
            return;
        }
        // Which family this water may grow belongs to is decided before the roll, not after it: a storm in a
        // hemisphere whose family is switched off must not spend the chance and then produce nothing.
        Boolean family = tropicalFamilyAt(level, storm.pos.z);
        if (family == null) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(storm.pos));
        double chance = Config.naturalSpawnChance(
                Config.HURRICANE_SPAWN_CHANCE.get() * Math.max(0.10D, outlook.hurricaneSpawnMultiplier())
                        * BiomeClimateService.odds(level, storm.pos).hurricane());
        if (level.random.nextDouble() >= chance) {
            return;
        }
        startTropicalStorm(level, storm, family);
    }

    /**
     * Turns a storm into a tropical system. Everything downstream - the ladder name, the category,
     * the wind profile, radar, warnings - reads the storm's own type and stage from here on, so this
     * only has to establish the storm as a water storm and give it the room a hurricane needs.
     *
     * @param antiCyclonic the anticyclonic sibling: same ladder, opposite spin, named a cyclone.
     */
    public static boolean startTropicalStorm(ServerLevel level, StormObject storm, boolean antiCyclonic) {
        if (level == null || storm == null || storm.pos == null) {
            return false;
        }
        storm.stormType = StormObject.TYPE_WATER;
        storm.attrib_waterSpout = false;
        storm.setPrecipitating(true);
        storm.setCloudlessStorm(false);
        // A tropical system is one of this mod's own storms too, so whether it may be promoted into a deadly
        // one is the air's question like everywhere else: a hurricane born under a capped sky is still the
        // storm its own ladder makes it, it is simply not handed Weather2's deadly promotion on top.
        storm.canBeDeadly = AddonWeatherEventService.deadlyAirSupports(level, storm.pos);
        storm.isGrowing = true;
        storm.hasStormPeaked = false;
        storm.tempAngleFormingTornado = 0.0F;
        storm.angleMovementTornadoOverride = 0.0F;
        storm.angleIsOverridden = false;

        // A hurricane's shield is twice a supercell's (1.12.2 gave water storms three times the base
        // size a land storm got, against one and a half), so the storm is grown into that footprint
        // as it climbs. The floor is a tropical storm's size, not a hurricane's: this is genesis.
        double shield = Config.HURRICANE_SHIELD_SIZE_MULTIPLIER.get();
        int sizeFloor = Mth.clamp((int) Math.round(220.0D * shield), 220, 1400);
        int maxSizeFloor = Mth.clamp((int) Math.round(360.0D * shield), 320, 2000);
        storm.size = Math.max(storm.size, sizeFloor);
        storm.maxSize = Math.max(storm.maxSize, maxSizeFloor);
        storm.levelStormIntensityMax = Math.max(storm.levelStormIntensityMax, StormObject.STATE_STAGE5);
        storm.maxIntensityStage = Math.max(storm.maxIntensityStage, StormObject.STATE_STAGE5);
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity, 0.30F);
        storm.levelWater = Math.max(storm.levelWater, Math.max(160, storm.levelWaterStartRaining * 4));
        storm.levelWindMomentum = Math.max(storm.levelWindMomentum, 0.9F);

        DynamicStormState state = stateFor(storm);
        state.setAntiCyclonic(antiCyclonic);
        state.setSubtype(antiCyclonic ? StormSubtype.CYCLONE : StormSubtype.HURRICANE);
        applyRotationDirection(level, storm, state);
        if (storm.manager instanceof WeatherManagerServer manager) {
            manager.syncStormUpdate(storm);
        }
        ExpandedWeather2Dynamics.LOGGER.info("Storm {} began tropical genesis over warm ocean: {}", storm.ID,
                TropicalCyclone.label(storm.ID, storm.levelCurIntensityStage,
                        TornadoWindField.maxWindMph(storm), antiCyclonic));
        return true;
    }

    /**
     * Clears a stray waterspout tag on a tropical system, on whichever side is ticking it.
     *
     * <p>Weather2 sets {@code attrib_waterSpout} from its own waterspout pass, and the value it settles on for
     * a hurricane is not ours to trust. The server already clears it every tick for any water storm, but the
     * flag is synced, so a tag set after that clear - or carried across from a save - puts a client into a
     * state where its own copy says "waterspout" and every classification that reads the flag follows it. This
     * is the client half of that clear: a tropical system is never a waterspout, and the tag says otherwise
     * only because nobody asked it not to.
     */
    private static void clearStrayWaterspoutTag(StormObject storm) {
        if (storm != null && storm.attrib_waterSpout && isTropicalSystem(storm)) {
            storm.attrib_waterSpout = false;
        }
    }

    /** How many storms are currently tropical systems at or past hurricane strength. */
    /**
     * How many of the mod's own storms are already going near a position: what the simple settings' limit is
     * counted against.
     *
     * <p>Deliberately counts storms that have committed - a supercell that is tornadic or is at least a
     * thunderstorm of the mod's own making - rather than every Weather2 storm entity in the world, because the
     * modest rain showers Weather2 spawns on its own are not what a player means by "too many storms". Only
     * storms within the configured ring count, so weather in another biome does not suppress the one forming
     * overhead, and helper storms belonging to a squall line or a hurricane are skipped because they are parts
     * of one event rather than separate ones.
     */
    private static int countPromotedStormsNear(ServerLevel level, Vec3 near) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null || near == null) {
            return 0;
        }
        double range = Config.stormLimitRangeBlocks();
        double rangeSqr = range * range;
        int count = 0;
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject candidate) || candidate.pos == null || candidate.isDead) {
                continue;
            }
            if (AddonWeatherEventService.isSquallLineHelperStorm(candidate)
                    || AddonWeatherEventService.isHurricaneHelperStorm(candidate)) {
                continue;
            }
            if (candidate.levelCurIntensityStage < StormObject.STATE_THUNDER && !isDynamicVortex(candidate)) {
                continue;
            }
            if (candidate.pos.distanceToSqr(near) > rangeSqr) {
                continue;
            }
            count++;
        }
        return count;
    }

    private static int countNativeTropicalStorms(ServerLevel level) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return 0;
        }
        int count = 0;
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject candidate
                    && candidate.stormType == StormObject.TYPE_WATER
                    && candidate.levelCurIntensityStage >= TropicalCyclone.HURRICANE_STAGE) {
                count++;
            }
        }
        return count;
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
        // With the split on, the northern half never promotes a storm to a cyclone: the two families belong to
        // opposite hemispheres, and the storm that would have been promoted here goes on to become a hurricane
        // through the ordinary genesis path instead if hurricanes are enabled.
        if (Config.TROPICAL_HEMISPHERE_SPLIT.get() && !southernHemisphere(storm.pos.z)) {
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
        boolean episodePossible = Config.MULTIVORTEX_ENABLED.get() && isDynamicVortex(storm) && effectiveMaxCount > 0;
        if (!episodePossible || storm.levelCurIntensityStage < effectiveMinStage) {
            // The 5th argument holds an already-running episode through a stage dip: Weather2's
            // stage ladder oscillates with intensity, and without the hold a live episode was torn
            // down the first time the stage breathed below the minimum - for a ground swarm that
            // meant the whole tornado vanishing, since its faint parent rope is drawn culled by
            // design and the vortices were the only visible part. The stage gate still prevents
            // new episodes from starting while the storm is below it.
            state.updateSubVortices(storm.manager.getWorld().getGameTime(), 0, baseRadiusForWind(storm), storm.levelCurIntensityStage, storm.levelCurStagesIntensity, episodePossible);
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
        if (state.wideTornado()) {
            // A big tornado is a big deal whatever shape it took; this used to ask only about wedges, which
            // would have made an identically sized cone read two stages weaker.
            desired += 2;
        }
        if (maxWind >= 190.0D) {
            desired++;
        }
        if (maxWind >= 250.0D) {
            desired++;
        }
        desired = Mth.clamp(desired, 0, effectiveMaxCount);
        state.updateSubVortices(storm.manager.getWorld().getGameTime(), desired, baseRadiusForWind(storm), storm.levelCurIntensityStage, storm.levelCurStagesIntensity, false);
    }

    private static void updateHorizontalVortices(StormObject storm, DynamicStormState state) {
        int effectiveMaxCount = Math.min(Math.max(0, Config.HORIZONTAL_VORTICES_MAX_COUNT.get()), 10);
        int effectiveMinStage = Math.max(Config.HORIZONTAL_VORTICES_MIN_STAGE.get(), StormObject.STATE_STAGE1);
        if (!Config.HORIZONTAL_VORTICES_ENABLED.get() || storm.levelCurIntensityStage < effectiveMinStage || !isDynamicVortex(storm) || effectiveMaxCount <= 0) {
            state.updateHorizontalVortices(storm.manager.getWorld().getGameTime(), 0, funnelWallRadiusAt(storm, 0.0F), funnelWallRadiusAt(storm, 1.0F), false);
            return;
        }
        // Not every tornado grows them: whether this storm is one of the carriers is rolled here, on
        // the first tick it is strong enough to have them, and held for the storm's life. The roll
        // must happen after the eligibility gate above, so a storm that never reaches the stage
        // never spends its roll.
        if (!state.horizontalVorticesAllowed(storm.levelCurIntensityStage, storm.levelCurStagesIntensity)) {
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
        if (state.wideTornado()) {
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
        // The same distance the storm box was widened to, so a funnel out over LOD-only terrain reaches the
        // client that can see it with its real circulation instead of its default sliver.
        NetworkSafety.sendToPlayersNear(level, storm.pos.x, storm.pos.y, storm.pos.z,
                LodStormEnvelope.syncRange(Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get()),
                new StormStateSyncPayload(storm.ID, state.save()));
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
     * only for storms currently inside {@code multiplayer.syncRangeBlocks} (widened, with the LOD compat on,
     * to whatever distance the storm box was widened to), so a player who has just
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
        double range = LodStormEnvelope.syncRange(Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get());
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
        if (tryStrengthen(storm, state, score, gameTime)) {
            return;
        }
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

    /**
     * Lets a tornado over-perform the intensity ceiling it rolled at birth.
     *
     * <p>The ceiling is what Weather2 rolls at genesis - the thing that makes EF0s common and EF5s rare - and
     * it is a <em>forecast</em>, made before the storm has finished organizing in air nobody has measured
     * yet. Two consequences followed from treating it as final. A tornado that rolled low kept that ceiling
     * for its whole life no matter what it moved into, and a tornado that had already reached its own ceiling
     * could only ever weaken: both branches of the intensity logic below are gated on
     * {@code stage < maxIntensityStage}, so for that storm the {@code >=} branch was simply unreachable.
     *
     * <p>This is the missing third case. A storm already <em>at</em> its ceiling, in air scoring above the
     * strengthening threshold, earns one more rung - up to the top of the ladder, and at most the configured
     * number of rungs in its life. It is deliberately scarce three times over: the score has to beat a
     * threshold set above the ordinary reintensify one, the ordinary 20-second intensity cooldown still
     * applies because this runs inside it, and the allowance is persisted per storm so a reload cannot
     * refill it.
     *
     * <p>Raising the ceiling is not the same as reaching it. The next scan's ordinary reintensify path still
     * has to fill the rung's intensity and call {@code stageNext}, so a tornado grows over minutes with the
     * wind, the width and the scouring all following the stage they always follow - which is what makes this
     * read as a storm strengthening rather than as a number changing.
     */
    private static boolean tryStrengthen(StormObject storm, DynamicStormState state, double score, long gameTime) {
        if (!Config.TORNADO_STRENGTHENING_ENABLED.get()) {
            return false;
        }
        int ceiling = Math.min(storm.maxIntensityStage, StormObject.STATE_STAGE5);
        if (storm.levelCurIntensityStage < ceiling || ceiling >= StormObject.STATE_STAGE5) {
            return false;
        }
        if (state.strengtheningRungs() >= Config.TORNADO_STRENGTHENING_MAX_RUNGS.get()) {
            return false;
        }
        if (score < Config.TORNADO_STRENGTHENING_SCORE.get()) {
            return false;
        }
        storm.maxIntensityStage = Math.min(StormObject.STATE_STAGE5, ceiling + 1);
        storm.levelStormIntensityMax = Math.max(storm.levelStormIntensityMax, storm.maxIntensityStage);
        // Weather2 sets this once a storm has begun its decline and will not climb again while it is set; the
        // whole point here is that this storm has not finished organizing.
        storm.hasStormPeaked = false;
        storm.levelCurStagesIntensity = Mth.clamp(storm.levelCurStagesIntensity + 0.18F, -1.0F, 1.0F);
        state.noteStrengtheningRung();
        state.markIntensityChanged(gameTime, true);
        return true;
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
