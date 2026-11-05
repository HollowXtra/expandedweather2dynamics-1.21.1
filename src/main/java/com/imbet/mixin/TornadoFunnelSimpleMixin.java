package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.client.ClientWeatherEffects;
import com.imbet.client.CloudDeckSpriteProvider;
import com.imbet.client.LodViewDistance;
import com.imbet.client.StormBillboardPhase;
import com.imbet.client.VortexSpine;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import extendedrenderer.particle.ParticleRegistry;
import extendedrenderer.particle.entity.PivotingParticle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.tornado.simple.Layer;
import weather2.weathersystem.tornado.simple.TornadoFunnelSimple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mixin(value = TornadoFunnelSimple.class, remap = false)
public abstract class TornadoFunnelSimpleMixin {
    /**
     * Weather2's funnel culls itself at four times the vanilla render distance - the same far
     * plane vanilla clips terrain at - so when an LOD renderer (Distant Horizons, Voxy) is     * drawing terrain hundreds of blocks further, the funnel vanishes into the landscape: the
     * debris cloud around it keeps its LOD-extended range but the condensation column itself
     * is gone. This raises the cutoff to the LOD distance whenever that is further, so the
     * funnel keeps up with the terrain it stands in. Vanilla behaviour is untouched when no
     * LOD renderer is drawing, and the value still caps at the mod's LOD ceiling so a bad
     * renderer report cannot push the cull to absurd ranges.
     */
    // Anchored on the field write itself, not the getRenderDistance call: Weather2 multiplies
    // the call result by four before storing it, so an INVOKE_ASSIGN there would never match.
    // This class writes renderDistCutoff exactly once in tickClient, so the PUTFIELD anchor is
    // unambiguous. The shift to AFTER matters: a FIELD point injects before the instruction by
    // default, and a value written there would be overwritten by the pending store. After the
    // store, the raised value is in place before the layer loop below reads it.
    @Inject(method = "tickClient", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lweather2/weathersystem/tornado/simple/TornadoFunnelSimple;renderDistCutoff:F",
            shift = At.Shift.AFTER), remap = false)
    private void expandedweather2dynamics$extendFunnelRenderCutoff(CallbackInfo ci) {
        if (!Config.LOD_COMPAT_ENABLED.get()) {
            return;
        }
        float lod = LodViewDistance.activeDistanceBlocks();
        if (lod > renderDistCutoff) {
            renderDistCutoff = Math.min(lod, Config.LOD_COMPAT_MAX_VIEW_DISTANCE_BLOCKS.get());
        }
    }

    @Shadow
    private float renderDistCutoff;

    /** The funnel's centreline. See {@link VortexSpine} for why it is a thing with a history and not a formula. */
    @Unique
    private VortexSpine expandedweather2dynamics$spine;
    @Unique
    private long expandedweather2dynamics$spineTick = Long.MIN_VALUE;
    /** Layer-by-layer offsets on top of the spine: the eased part of a morphology's shape. */
    @Unique
    private List<Vec3> expandedweather2dynamics$smoothedOffsets;
    @Unique
    private List<Double> expandedweather2dynamics$smoothedVerticalOffsets;
    @Unique
    private Map<PivotingParticle, ParticleMorphState> expandedweather2dynamics$particleMorphStates;
    @Unique
    private Set<PivotingParticle> expandedweather2dynamics$activeParticles;
    /**
     * Per-layer rotation captured at tickClient HEAD for anti-cyclonic tornadoes. Weather2's
     * funnel spin is hard-coded cyclonic (positive) and ignores spinSpeed entirely, so on
     * anti-cyclonic tornadoes it fights our mirrored spin and the funnel twists against its
     * own dust cloud. We restore each layer to this snapshot at tickClient TAIL so only our
     * signed extraFunnelLayerSpinDegrees drives the funnel. Null when not neutralizing.
     */
    @Unique
    private Map<Layer, Float> expandedweather2dynamics$preTickClientRotations;
    /**
     * World game-time when this funnel instance was first seen by the client.
     * Drives the time-based birth ramp so the funnel always builds in from
     * nothing (top-down) instead of popping in at full strength, even when the
     * storm is already past the forming stage.
     */
    @Unique
    private long expandedweather2dynamics$birthGameTime = Long.MIN_VALUE;
    /**
     * Tick the sub-vortex bump table below was built for, so it is rebuilt once per tick rather
     * than once per funnel layer, and the bearings and amplitudes of the orbiting vortices that
     * currently deform the condensation funnel wall.
     */
    @Unique
    private long expandedweather2dynamics$lumpTick = Long.MIN_VALUE;
    @Unique
    private double[] expandedweather2dynamics$lumpBearings;
    @Unique
    private double[] expandedweather2dynamics$lumpAmplitudes;
    @Unique
    private int expandedweather2dynamics$lumpCount;

    /** How far up the funnel the sub-vortex lumps reach, as a fraction of its height. */
    @Unique
    private static final double LUMP_HEIGHT_FRACTION = 0.60D;
    /**
     * Weather2 sizes these for a sprite whose visible part is a blob in the middle of its square. This sheet fills the
     * square, so at Weather2's size the puffs spill far outside the wall and the funnel's shape is lost in them.
     */
    @Unique
    private static final float expandedweather2dynamics$FILLED_SHEET_SCALE = 0.58F;
    @Unique
    private static final float expandedweather2dynamics$MIN_PUFF_SCALE = 1.5F;
    @Unique
    private static final float expandedweather2dynamics$SHEATH_PUFF_SCALE = 2.4F;
    @Unique
    private static final float expandedweather2dynamics$SHEATH_ALPHA = 0.30F;
    /** Angular width of one lobe, in degrees. Wide enough to read as a lump instead of a spike. */
    @Unique
    private static final double LUMP_SIGMA_DEGREES = 30.0D;

    @Inject(method = "tickClient", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$hideMultivortexParentFunnel(CallbackInfo ci) {
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            // Three reasons to drop this funnel entirely. The classic look is the first one: this layer
            // funnel is the port's replacement for Weather2's 1.12.2 funnel, and with the classic look on
            // the original is drawing instead (see StormObjectMixin's tornadoV2 injection), so leaving this
            // one up would stand two funnels in the same place. The other two are a multi-vortex episode
            // with a lifted parent, and hurricane storms - the helper rain core and the native tropical
            // cyclone - whose point funnel would read as a tornado hanging in the hurricane's middle.
            if (!Config.classicLook()
                    && !DynamicStormController.shouldHideMultivortexParentFunnel(storm)
                    && !DynamicStormController.shouldSuppressHelperFunnel(storm)) {
                return;
            }
            expandedweather2dynamics$hideLayerParticles(funnel.listLayers);
            ci.cancel();
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void expandedweather2dynamics$bendFunnelLayers(CallbackInfo ci) {
        List<Vec3> smoothedOffsets = expandedweather2dynamics$ensureOffsets();
        List<Double> smoothedVerticalOffsets = expandedweather2dynamics$ensureVerticalOffsets();
        if (Config.classicLook()) {
            // Classic look: Weather2's own funnel geometry IS the look. No bend, no rope, no lump
            // offsets, no extra spin or particle resizing - the layers keep the positions, rotation
            // and scales Weather2 gave them.
            expandedweather2dynamics$clearState(smoothedOffsets, smoothedVerticalOffsets);
            return;
        }
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            if (storm == null || storm.manager == null || storm.manager.getWorld() == null || funnel.pos == null || funnel.listLayers == null || funnel.listLayers.size() < 2) {
                expandedweather2dynamics$clearState(smoothedOffsets, smoothedVerticalOffsets);
                return;
            }
            if (storm.attrib_waterSpout) {
                expandedweather2dynamics$clearState(smoothedOffsets, smoothedVerticalOffsets);
                return;
            }
            Level world = storm.manager.getWorld();
            if (!world.isClientSide()) {
                expandedweather2dynamics$clearState(smoothedOffsets, smoothedVerticalOffsets);
                return;
            }
            Vec3 base = funnel.pos;
            int count = funnel.listLayers.size();
            while (smoothedOffsets.size() < count) {
                smoothedOffsets.add(Vec3.ZERO);
            }
            while (smoothedOffsets.size() > count) {
                smoothedOffsets.remove(smoothedOffsets.size() - 1);
            }
            while (smoothedVerticalOffsets.size() < count) {
                smoothedVerticalOffsets.add(0.0D);
            }
            while (smoothedVerticalOffsets.size() > count) {
                smoothedVerticalOffsets.remove(smoothedVerticalOffsets.size() - 1);
            }
            float smoothing = Mth.clamp(Math.max(0.080F, Config.TORNADO_FUNNEL_BEND_SMOOTHING.get().floatValue()), 0.0F, 1.0F);
            expandedweather2dynamics$ensureParticleState();
            expandedweather2dynamics$activeParticles.clear();
            double cloudBase = DynamicStormController.cloudBaseWorldY(storm);
            VortexSpine spine = expandedweather2dynamics$advanceSpine(storm, world, base, cloudBase);
            double anchorFraction = Math.min(Config.TORNADO_FUNNEL_BEND_ANCHOR_FRACTION.get(), 0.08D);
            for (int i = 0; i < count; i++) {
                Layer layer = funnel.listLayers.get(i);
                if (layer == null || layer.getPos() == null) {
                    continue;
                }
                double targetLift = DynamicStormController.mainFunnelGroundLift(storm, i, count);
                if (i == 0 && targetLift <= 0.0001D) {
                    smoothedOffsets.set(i, Vec3.ZERO);
                    smoothedVerticalOffsets.set(i, 0.0D);
                    expandedweather2dynamics$scaleLayerParticles(storm, layer, i, count);
                    continue;
                }
                Vec3 layerPos = layer.getPos();
                float fraction = DynamicStormController.funnelLayerFraction(storm, i, count);
                spine.sample((float) Mth.clamp((fraction - anchorFraction) / Math.max(0.05D, 1.0D - anchorFraction), 0.0D, 1.0D));
                Vec3 targetOffset = DynamicStormController.funnelLayerMorphologyOffset(storm, i, count);
                Vec3 previousOffset = smoothedOffsets.get(i);
                if (previousOffset == null) {
                    previousOffset = Vec3.ZERO;
                }
                Vec3 desiredOffset = previousOffset.lerp(targetOffset, smoothing);
                Vec3 smoothedOffset = expandedweather2dynamics$limitBendStep(previousOffset, desiredOffset, targetOffset);
                smoothedOffsets.set(i, smoothedOffset);
                double previousLift = smoothedVerticalOffsets.get(i) == null ? 0.0D : smoothedVerticalOffsets.get(i);
                double smoothedLift = Mth.lerp(Mth.clamp(smoothing * 0.92D, 0.0D, 1.0D), previousLift, targetLift);
                smoothedLift = expandedweather2dynamics$limitVerticalStep(previousLift, smoothedLift, targetLift);
                smoothedVerticalOffsets.set(i, smoothedLift);
                // Evenly up the whole way to the cloud. Weather2 stacks its layers by how wide the funnel is, which leaves a
                // young funnel stopping in mid air a hundred blocks short of the cloud it should be coming out of.
                double layerY = Mth.lerp(fraction, base.y, cloudBase);
                layer.setPos(new Vec3(base.x + spine.sampleX + smoothedOffset.x, layerY + smoothedLift, base.z + spine.sampleZ + smoothedOffset.z));
                expandedweather2dynamics$scaleLayerParticles(storm, layer, i, count);
                layer.setRotation(layer.getRotation() + DynamicStormController.extraFunnelLayerSpinDegrees(storm, i, count));
            }
            if (expandedweather2dynamics$particleMorphStates != null && expandedweather2dynamics$activeParticles != null) {
                expandedweather2dynamics$particleMorphStates.keySet().removeIf(particle -> !expandedweather2dynamics$activeParticles.contains(particle));
            }
        } catch (RuntimeException exception) {
            expandedweather2dynamics$clearState(smoothedOffsets, smoothedVerticalOffsets);
        }
    }

    /**
     * Moves the centreline on one tick. The top takes longer to follow the taller the column is, which is what makes a
     * tall funnel curve through a turn and a short one snap round it. With the bend switched off the spine just goes
     * on sitting at the foot.
     */
    @Unique
    private VortexSpine expandedweather2dynamics$advanceSpine(StormObject storm, Level world, Vec3 base, double cloudBase) {
        if (expandedweather2dynamics$spine == null) {
            expandedweather2dynamics$spine = new VortexSpine();
        }
        long gameTime = world.getGameTime();
        float dt = expandedweather2dynamics$spineTick == Long.MIN_VALUE ? 1.0F : Mth.clamp(gameTime - expandedweather2dynamics$spineTick, 0L, 20L);
        expandedweather2dynamics$spineTick = gameTime;
        float height = (float) Math.max(16.0D, cloudBase - base.y);
        boolean bend = Config.TORNADO_FUNNEL_BEND_ENABLED.get();
        Vec3 lean = bend ? DynamicStormController.funnelLean(storm) : Vec3.ZERO;
        float sway = bend ? (float) DynamicStormController.funnelSway(storm) : 0.0F;
        double phase = DynamicStormController.stateFor(storm).bendPhase();
        float maxTop = bend ? (float) Math.max(36.0D, Config.TORNADO_FUNNEL_BEND_MAX_BLOCKS.get()) : 0.0F;
        expandedweather2dynamics$spine.advance(base.x, base.z, Mth.clamp(height * 0.35F, 30.0F, 130.0F), dt, height,
                (float) lean.x, (float) lean.z, sway, phase, Math.min(0.92F, maxTop / height));
        return expandedweather2dynamics$spine;
    }

    @Inject(method = "tickClient", at = @At("HEAD"))
    private void expandedweather2dynamics$snapshotPreTickClientRotation(CallbackInfo ci) {
        if (Config.classicLook()) {
            // The snapshot exists only to undo Weather2's base spin so the reverse spin above can be
            // applied instead. Classic mode applies no reverse spin, so undoing the base spin would
            // leave the funnel frozen: the snapshot stays empty and Weather2's own rotation stands.
            expandedweather2dynamics$preTickClientRotations = null;
            return;
        }
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            if (storm == null || DynamicStormController.rotationSign(storm) >= 0.0D
                    || funnel.listLayers == null || funnel.listLayers.isEmpty()) {
                expandedweather2dynamics$preTickClientRotations = null;
                return;
            }
            if (expandedweather2dynamics$preTickClientRotations == null) {
                expandedweather2dynamics$preTickClientRotations = new IdentityHashMap<>();
            } else {
                expandedweather2dynamics$preTickClientRotations.clear();
            }
            for (Layer layer : funnel.listLayers) {
                if (layer != null) {
                    expandedweather2dynamics$preTickClientRotations.put(layer, layer.getRotation());
                }
            }
        } catch (RuntimeException ignored) {
            expandedweather2dynamics$preTickClientRotations = null;
        }
    }

    @Inject(method = "tickClient", at = @At("TAIL"))
    private void expandedweather2dynamics$neutralizeWeather2BaseSpin(CallbackInfo ci) {
        try {
            if (expandedweather2dynamics$preTickClientRotations == null || expandedweather2dynamics$preTickClientRotations.isEmpty()) {
                return;
            }
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            if (funnel.listLayers == null) {
                return;
            }
            for (Layer layer : funnel.listLayers) {
                if (layer == null) {
                    continue;
                }
                Float pre = expandedweather2dynamics$preTickClientRotations.get(layer);
                if (pre != null) {
                    layer.setRotation(pre);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Inject(method = "tickClient", at = @At("TAIL"))
    private void expandedweather2dynamics$applyClientParticleMorphology(CallbackInfo ci) {
        if (Config.classicLook()) {
            return;
        }
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            if (storm == null || storm.manager == null || storm.manager.getWorld() == null || !storm.manager.getWorld().isClientSide() || funnel.listLayers == null || funnel.listLayers.size() < 2) {
                return;
            }
            expandedweather2dynamics$ensureParticleState();
            expandedweather2dynamics$applyCombinedSheet(funnel.listLayers);
            for (int i = 0; i < funnel.listLayers.size(); i++) {
                expandedweather2dynamics$scaleLayerParticles(storm, funnel.listLayers.get(i), i, funnel.listLayers.size());
            }
        } catch (RuntimeException ignored) {
            if (expandedweather2dynamics$particleMorphStates != null) {
                expandedweather2dynamics$particleMorphStates.clear();
            }
        }
    }

    @Unique
    private List<Vec3> expandedweather2dynamics$ensureOffsets() {
        if (expandedweather2dynamics$smoothedOffsets == null) {
            expandedweather2dynamics$smoothedOffsets = new ArrayList<>();
        }
        return expandedweather2dynamics$smoothedOffsets;
    }

    @Unique
    private List<Double> expandedweather2dynamics$ensureVerticalOffsets() {
        if (expandedweather2dynamics$smoothedVerticalOffsets == null) {
            expandedweather2dynamics$smoothedVerticalOffsets = new ArrayList<>();
        }
        return expandedweather2dynamics$smoothedVerticalOffsets;
    }

    @Unique
    private void expandedweather2dynamics$ensureParticleState() {
        if (expandedweather2dynamics$particleMorphStates == null) {
            expandedweather2dynamics$particleMorphStates = new IdentityHashMap<>();
        }
        if (expandedweather2dynamics$activeParticles == null) {
            expandedweather2dynamics$activeParticles = Collections.newSetFromMap(new IdentityHashMap<>());
        }
    }

    @Unique
    private void expandedweather2dynamics$clearState() {
        expandedweather2dynamics$clearState(expandedweather2dynamics$smoothedOffsets, expandedweather2dynamics$smoothedVerticalOffsets);
    }

    @Unique
    private void expandedweather2dynamics$clearState(List<Vec3> smoothedOffsets, List<Double> smoothedVerticalOffsets) {
        if (smoothedOffsets != null) {
            smoothedOffsets.clear();
        }
        if (smoothedVerticalOffsets != null) {
            smoothedVerticalOffsets.clear();
        }
        if (expandedweather2dynamics$particleMorphStates != null) {
            expandedweather2dynamics$particleMorphStates.clear();
        }
        if (expandedweather2dynamics$activeParticles != null) {
            expandedweather2dynamics$activeParticles.clear();
        }
        expandedweather2dynamics$preTickClientRotations = null;
    }

    @Unique
    private Vec3 expandedweather2dynamics$limitBendStep(Vec3 previousOffset, Vec3 desiredOffset, Vec3 targetOffset) {
        if (previousOffset == null || desiredOffset == null || targetOffset == null) {
            return desiredOffset == null ? Vec3.ZERO : desiredOffset;
        }
        Vec3 step = desiredOffset.subtract(previousOffset);
        double stepLength = step.horizontalDistance();
        if (stepLength < 0.0001D) {
            return desiredOffset;
        }
        double remaining = targetOffset.subtract(previousOffset).horizontalDistance();
        double maxStep = Mth.clamp(0.30D + remaining * 0.055D, 0.45D, 1.75D);
        if (stepLength <= maxStep) {
            return desiredOffset;
        }
        double scale = maxStep / stepLength;
        return previousOffset.add(step.x * scale, 0.0D, step.z * scale);
    }

    @Unique
    private double expandedweather2dynamics$limitVerticalStep(double previousLift, double desiredLift, double targetLift) {
        double step = desiredLift - previousLift;
        if (Math.abs(step) < 0.0001D) {
            return desiredLift;
        }
        double remaining = Math.abs(targetLift - previousLift);
        double maxStep = Mth.clamp(0.35D + remaining * 0.055D, 0.55D, 2.00D);
        if (Math.abs(step) <= maxStep) {
            return desiredLift;
        }
        return previousLift + Math.copySign(maxStep, step);
    }

    @Unique
    private void expandedweather2dynamics$scaleLayerParticles(StormObject storm, Layer layer, int layerIndex, int layerCount) {
        if (layer == null) {
            return;
        }
        expandedweather2dynamics$ensureParticleState();
        long gameTime = storm.manager != null && storm.manager.getWorld() != null
                ? storm.manager.getWorld().getGameTime()
                : Long.MIN_VALUE;
        if (expandedweather2dynamics$birthGameTime == Long.MIN_VALUE) {
            expandedweather2dynamics$birthGameTime = gameTime;
        }
        boolean hideParentFunnel = DynamicStormController.shouldHideMultivortexParentFunnel(storm);
        float radiusScale = DynamicStormController.funnelLayerRadiusScale(storm, layerIndex, layerCount);
        float particleScale = DynamicStormController.funnelParticleScale(storm, layerIndex, layerCount);
        float alphaScale = DynamicStormController.multivortexParentLayerAlpha(storm, layerIndex, layerCount);
        // lifecycle animation: the funnel builds in from nothing (condensation descends
        // top-down) and ropes out from the ground up while decaying. It is driven by the storm's own
        // persisted birth tick, so a mature funnel stays mature across a world reload instead of
        // replaying its eight-second birth ramp - which is what read as the funnel's dynamic size
        // resetting every time the world was left. The local anchor is only a fallback for a funnel
        // with no synced birth tick yet: a brand-new storm, or a save written before it was tracked.
        long birthTick = DynamicStormController.funnelBirthTick(storm);
        float ageTicks;
        if (birthTick != Long.MIN_VALUE && gameTime != Long.MIN_VALUE) {
            ageTicks = (float) Math.max(0L, gameTime - birthTick);
        } else if (expandedweather2dynamics$birthGameTime != Long.MIN_VALUE && gameTime != Long.MIN_VALUE) {
            ageTicks = (float) Math.max(0L, gameTime - expandedweather2dynamics$birthGameTime);
        } else {
            ageTicks = 0.0F;
        }
        particleScale *= DynamicStormController.funnelLayerFormationScale(storm, ageTicks, layerIndex, layerCount) * expandedweather2dynamics$FILLED_SHEET_SCALE;
        radiusScale *= DynamicStormController.funnelLayerFormationRadius(storm, ageTicks, layerIndex, layerCount);
        alphaScale *= DynamicStormController.funnelLayerFormationAlpha(storm, ageTicks, layerIndex, layerCount);
        // The base layer's formation alpha doubles as the funnel's "condensation has reached the
        // ground" signal. That is what gates the dust gradient: a funnel still filling downward has
        // not picked anything up yet, so it must not be painted dirt-brown before it touches down.
        float baseFormation = DynamicStormController.funnelLayerFormationAlpha(storm, ageTicks, 0, layerCount);
        // Ground contact is the other half of that gate. A ground swarm cuts its condensation off below
        // the tip and a floater is lifted clear of the surface, so in both cases the visible column has
        // lifted nothing - its lowest layer only *looks* low - and it must stay plain condensation.
        boolean grounded = !DynamicStormController.shouldCullMultivortexParentLayer(storm, 0, layerCount)
                && DynamicStormController.mainFunnelGroundLift(storm, 0, layerCount) <= 0.0001D;
        float dustGate = grounded ? baseFormation : 0.0F;
        boolean cullLayer = hideParentFunnel || DynamicStormController.shouldCullMultivortexParentLayer(storm, layerIndex, layerCount);
        float lumpTaper = expandedweather2dynamics$lumpTaper(storm, layerIndex, layerCount);
        expandedweather2dynamics$scaleParticleList(layer.getListParticles(), radiusScale, particleScale, alphaScale, cullLayer, lumpTaper);
        // Weather2's second ring stands just outside the wall. At the wall's own puff size it is a shell of separate dots round
        // the funnel; large and faint, it is the soft sheath of cloud a funnel has round it.
        expandedweather2dynamics$scaleParticleList(layer.getListParticlesExtra(), radiusScale, particleScale * expandedweather2dynamics$SHEATH_PUFF_SCALE,
                alphaScale * expandedweather2dynamics$SHEATH_ALPHA, cullLayer, lumpTaper);
        // Weather2 spawns fresh layer particles at funnel.pos (the funnel base) and only snaps them
        // to the layer anchor on the NEXT tick. In a bent/leaning funnel the top layer's anchor can be
        // tens of blocks away, so every new top particle visibly pops and teleports - the top-layer
        // jitter. Re-anchor freshly spawned particles that are far from the layer anchor immediately.
        Vec3 anchor = layer.getPos();
        if (anchor != null) {
            expandedweather2dynamics$reanchorFreshParticles(layer.getListParticles(), anchor);
            expandedweather2dynamics$reanchorFreshParticles(layer.getListParticlesExtra(), anchor);
        }
        expandedweather2dynamics$tintFunnelLayer(storm, layer, layerCount, dustGate);
    }

    /**
     * Paints one layer of the funnel: the condensation grey Weather2 would have given these particles,
     * drifting into the foot's dust tone the closer to the ground the layer sits. Runs every tick,
     * after the scale pass, because Weather2 tints its funnel particles only once - at creation - and
     * the only later recolour it does is the firenado swap, which we deliberately leave alone.
     */
    @Unique
    private void expandedweather2dynamics$tintFunnelLayer(StormObject storm, Layer layer, int layerCount, float formation) {
        if (!Config.FUNNEL_DUST_GRADIENT_ENABLED.get() || Config.classicLook()
                || storm == null || storm.manager == null || layer == null
                || layer.getPos() == null || storm.isFirenado
                || DynamicStormController.shouldHideMultivortexParentFunnel(storm)) {
            return;
        }
        Level world = storm.manager.getWorld();
        if (!(world instanceof ClientLevel level)) {
            return;
        }
        TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
        Vec3 base = funnel.pos;
        if (base == null) {
            return;
        }
        Vec3 top = funnel.getPosTop();
        double baseY = base.y;
        double topY = top == null ? baseY + 64.0D : Math.max(baseY + 1.0D, top.y);
        float heightFraction = (float) Mth.clamp((layer.getPos().y - baseY) / Math.max(1.0D, topY - baseY), 0.0D, 1.0D);
        expandedweather2dynamics$applyFunnelTint(level, storm, base, layer.getListParticles(), heightFraction, formation);
        expandedweather2dynamics$applyFunnelTint(level, storm, base, layer.getListParticlesExtra(), heightFraction, formation);
    }

    @Unique
    private void expandedweather2dynamics$applyFunnelTint(ClientLevel level, StormObject storm, Vec3 footPos, List<PivotingParticle> particles, float heightFraction, float formation) {
        if (particles == null || particles.isEmpty()) {
            return;
        }
        for (PivotingParticle particle : particles) {
            if (particle == null || !particle.isAlive()) {
                continue;
            }
            ClientWeatherEffects.FunnelTint tint = ClientWeatherEffects.funnelColumnTint(
                    level, storm, footPos, heightFraction, formation, ClientWeatherEffects.funnelParticleShade(particle));
            particle.setColor(tint.red(), tint.green(), tint.blue());
        }
    }

    @Unique
    private void expandedweather2dynamics$reanchorFreshParticles(List<PivotingParticle> particles, Vec3 anchor) {
        if (particles == null || particles.isEmpty()) {
            return;
        }
        for (PivotingParticle particle : particles) {
            if (particle == null || particle.getAge() > 1) {
                continue;
            }
            Vec3 position = particle.getPos();
            if (position == null || position.distanceToSqr(anchor) <= 16.0D) {
                continue;
            }
            particle.setPosition(anchor.x, anchor.y, anchor.z);
            particle.setPrevPosX(anchor.x);
            particle.setPrevPosY(anchor.y);
            particle.setPrevPosZ(anchor.z);
        }
    }

    /**
     * Puts the combined look's funnel on its own funnel sheet, one animation phase per puff.
     *
     * <p>The sheet is what makes the column read as condensation: a single 256x256 frame of long soft fibres.
     * Weather2's own sheet peaks at 81 of 255, so a funnel drawn on it can only ever be a haze -
     * the art is the ceiling - which is why this sheet is not part of the art switch.
     *
     * <p>The phase is dormant: the art is a still frame per role, so the three sibling sheets per role are not
     * shipped and every phase answers its role's own sheet. It is left in because it is the way back for an
     * animated set - with one sheet behind it every ring of every layer of a tornado would be on the same frame
     * of the animation at the same instant, and the column would pump as a single object. See
     * {@link StormBillboardPhase}.
     *
     * <p>Weather2's fire funnel is left alone: the layered funnel swaps its own sprite to the fire sheet for a
     * firenado, so anything already off the stock funnel sprite keeps whatever it chose. Re-swapping is skipped
     * when a particle is already on its phase's sheet, so this is a field write per particle per tick at most
     * and a no-op for the rest of a funnel's life.
     */
    @Unique
    private void expandedweather2dynamics$applyCombinedSheet(List<Layer> layers) {
        if (!Config.combinedLook() || layers == null || layers.isEmpty()) {
            return;
        }
        // Read once for the pass rather than per particle: this runs over every ring of every layer every tick,
        // and the phase only decides which sibling sheet each puff is handed.
        boolean phases = Config.billboardPhases();
        for (Layer layer : layers) {
            if (layer == null) {
                continue;
            }
            expandedweather2dynamics$applyCombinedSheet(layer.getListParticles(), phases);
            expandedweather2dynamics$applyCombinedSheet(layer.getListParticlesExtra(), phases);
        }
    }

    /**
     * Hands each puff the funnel sheet for its own phase of the animation.
     *
     * <p>The phase comes from the <em>puff</em> rather than from where it is standing, and that is not a
     * detail: this method re-sheets its particles every tick, so a phase read off a position that moves would
     * hand a puff a different sibling sheet as it drifted and the puff would flicker instead of advancing. An
     * identity hash is stable for as long as the object is, which is exactly the lifetime the phase needs, and
     * it costs no state to keep - no field to add to a class this mod does not own, and no table to clean up
     * after the puffs die.
     */
    @Unique
    private void expandedweather2dynamics$applyCombinedSheet(List<PivotingParticle> particles, boolean phases) {
        if (particles == null || particles.isEmpty()) {
            return;
        }
        for (PivotingParticle particle : particles) {
            if (particle == null || !particle.isAlive()) {
                continue;
            }
            int phase = phases ? StormBillboardPhase.forKey(System.identityHashCode(particle)) : 0;
            TextureAtlasSprite sheet = CloudDeckSpriteProvider.combinedSprite(phase);
            if (sheet == null) {
                return;
            }
            TextureAtlasSprite current = particle.getSprite();
            if (current == sheet || current == ParticleRegistry.cloud256_fire) {
                continue;
            }
            particle.setSprite(sheet);
        }
    }

    @Unique
    private void expandedweather2dynamics$hideLayerParticles(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        expandedweather2dynamics$ensureParticleState();
        for (Layer layer : layers) {
            if (layer == null) {
                continue;
            }
            expandedweather2dynamics$scaleParticleList(layer.getListParticles(), 1.0F, 1.0F, 0.0F, true, 0.0F);
            expandedweather2dynamics$scaleParticleList(layer.getListParticlesExtra(), 1.0F, 1.0F, 0.0F, true, 0.0F);
        }
    }

    /**
     * How strongly one funnel layer is deformed by the orbiting sub-vortices. Real multi-vortex
     * tornadoes are lumpy at the base, where the suction vortices are, and smooth higher up, so the
     * bulge fades out over the lower {@link #LUMP_HEIGHT_FRACTION} of the funnel.
     */
    @Unique
    private float expandedweather2dynamics$lumpTaper(StormObject storm, int layerIndex, int layerCount) {
        if (!expandedweather2dynamics$prepareLumps(storm)) {
            return 0.0F;
        }
        float fraction = layerCount <= 1 ? 0.0F : Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float t = Mth.clamp((float) (1.0D - fraction / LUMP_HEIGHT_FRACTION), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * Rebuilds the per-tick table of orbiting vortices deforming the funnel wall. Lumps come only
     * from vortices in ground contact: a hanging satellite funnel deforms the cloud base rather
     * than the base of the funnel, and a descending breakdown is a pinch, not a lump.
     */
    @Unique
    private boolean expandedweather2dynamics$prepareLumps(StormObject storm) {
        if (!Config.FUNNEL_LUMPS_ENABLED.get() || Config.classicLook()
                || storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            expandedweather2dynamics$lumpCount = 0;
            return false;
        }
        long gameTime = storm.manager.getWorld().getGameTime();
        if (gameTime != expandedweather2dynamics$lumpTick) {
            expandedweather2dynamics$lumpTick = gameTime;
            expandedweather2dynamics$lumpCount = 0;
            double strength = Config.FUNNEL_LUMP_STRENGTH.get();
            List<DynamicStormState.SubVortex> vortices = DynamicStormController.stateFor(storm).subVortices();
            int capacity = vortices.size() + 1;
            if (expandedweather2dynamics$lumpBearings == null || expandedweather2dynamics$lumpBearings.length < capacity) {
                expandedweather2dynamics$lumpBearings = new double[capacity];
                expandedweather2dynamics$lumpAmplitudes = new double[capacity];
            }
            for (DynamicStormState.SubVortex vortex : vortices) {
                if (vortex == null || !vortex.type().orbits() || vortex.type().descends() || !vortex.type().visibleAsFunnel()) {
                    continue;
                }
                if (!vortex.isGroundContact(gameTime)) {
                    continue;
                }
                // orbitDegrees measures the vortex outward as (cos, sin), while Weather2 places every
                // funnel ring particle by yaw (-sin, cos) out of its own pivotRot, so the two differ
                // by a quarter turn.
                expandedweather2dynamics$lumpBearings[expandedweather2dynamics$lumpCount] = vortex.orbitDegrees() - 90.0D;
                expandedweather2dynamics$lumpAmplitudes[expandedweather2dynamics$lumpCount] = strength
                        * Mth.clamp(vortex.intensityFraction(), 0.05F, 1.0F)
                        * (0.65F + vortex.radiusFraction() * 0.60F);
                expandedweather2dynamics$lumpCount++;
            }
        }
        return expandedweather2dynamics$lumpCount > 0;
    }

    /**
     * Radial factor for one ring particle: a sum of soft bumps centred on whatever vortices are
     * orbiting the funnel, evaluated at that particle's own bearing around the ring.
     */
    @Unique
    private double expandedweather2dynamics$lumpFactor(Vec3 pivotRot, float taper) {
        if (expandedweather2dynamics$lumpCount <= 0 || pivotRot == null || taper <= 0.0F) {
            return 1.0D;
        }
        double bearing = pivotRot.y;
        double bump = 0.0D;
        for (int i = 0; i < expandedweather2dynamics$lumpCount; i++) {
            double delta = Mth.wrapDegrees(bearing - expandedweather2dynamics$lumpBearings[i]);
            bump += expandedweather2dynamics$lumpAmplitudes[i]
                    * Math.exp(-(delta * delta) / (2.0D * LUMP_SIGMA_DEGREES * LUMP_SIGMA_DEGREES));
        }
        return 1.0D + Math.min(1.10D, bump) * taper;
    }

    @Unique
    private void expandedweather2dynamics$scaleParticleList(List<PivotingParticle> particles, float radiusScale, float particleScale, float alphaScale, boolean cullLayer, float lumpTaper) {
        if (particles == null || particles.isEmpty()) {
            return;
        }
        expandedweather2dynamics$ensureParticleState();
        for (PivotingParticle particle : particles) {
            if (particle == null) {
                continue;
            }
            if (cullLayer) {
                expandedweather2dynamics$particleMorphStates.remove(particle);
                particle.setAlpha(0.0F);
                particle.setScale(0.01F);
                particle.remove();
                continue;
            }
            expandedweather2dynamics$activeParticles.add(particle);
            // Weather2 tilts each of these by its place in the ring, so from most angles it is a thin flat chip. The place in
            // the ring is what makes the funnel turn, and the tilt adds nothing the sprite's own fibres do not already show.
            particle.facePlayer = Config.STORM_SOFT_PUFFS.get();
            ParticleMorphState previous = expandedweather2dynamics$particleMorphStates.get(particle);
            float appliedRadiusScale = radiusScale;
            if (lumpTaper > 0.0F && radiusScale > 1.0E-4F) {
                // Ease the bulge instead of snapping it: the ring bearing Weather2 derives each tick
                // moves with the layer's spin, and a full-strength step would shimmer.
                double target = expandedweather2dynamics$lumpFactor(particle.getPivotRot(), lumpTaper);
                double carried = previous != null && Math.abs(previous.radiusScale()) > 1.0E-4F
                        ? previous.radiusScale() / radiusScale
                        : target;
                appliedRadiusScale = (float) (radiusScale * (carried + (target - carried) * 0.30D));
            }
            Vec3 pivot = particle.getPivot();
            if (pivot != null) {
                Vec3 basePivot = previous != null && previous.matchesPivot(pivot)
                        ? previous.unscalePivot(pivot)
                        : pivot;
                pivot = new Vec3(basePivot.x * appliedRadiusScale, basePivot.y * appliedRadiusScale, basePivot.z * appliedRadiusScale);
                particle.setPivot(pivot);
            }
            // pivotPrev is deliberately NOT re-scaled every frame. Weather2 sets it from the pivot
            // field - which we have already scaled - so multiplying it again left the ring one
            // radius-scale too far out at the start of every tick, and since the renderer
            // interpolates the ring between pivotPrev and pivot, the layer visibly pumped wider and
            // snapped back each frame. It was worst on whichever layer has the largest scale, which
            // is the flared top of a bowl: the top layer grew larger than every layer below it and
            // then reset. It only needs scaling on the first tick we ever see a particle, before
            // Weather2 has copied a scaled pivot into it.
            if (previous == null) {
                Vec3 pivotPrev = particle.getPivotPrev();
                if (pivotPrev != null) {
                    particle.setPivotPrev(new Vec3(pivotPrev.x * radiusScale, pivotPrev.y * radiusScale, pivotPrev.z * radiusScale));
                }
            }
            float currentScale = particle.getScale();
            float baseScale = previous != null && previous.matchesScale(currentScale)
                    ? previous.unscaleParticle(currentScale)
                    : currentScale;
            // Never so small that the layers show as separate rungs up the funnel's thin foot.
            float newScale = Mth.clamp(baseScale * particleScale, expandedweather2dynamics$MIN_PUFF_SCALE, 64.0F);
            particle.setScale(newScale);
            float alpha = alphaScale < 0.995F
                    ? Mth.clamp(alphaScale * 0.94F, 0.0F, 0.94F)
                    : Mth.clamp(0.86F + (particleScale - 1.0F) * 0.06F, 0.78F, 0.98F);
            // The sheet carries a body of its own, against the faint art these alphas were first written for, so
            // the particle's own opacity comes down to keep the column's weight under control.
            alpha = Mth.clamp(alpha * ClientWeatherEffects.FUNNEL_SPRITE_DENSITY_ALLOWANCE, 0.0F, 0.94F);
            particle.setAlpha(alpha);
            particle.setFullAlphaTarget(alpha);
            // Store the radius scale INCLUDING the bulge: unscalePivot divides by this to recover the
            // unscaled radius, so omitting the bulge would compound it every single frame.
            expandedweather2dynamics$particleMorphStates.put(particle, new ParticleMorphState(appliedRadiusScale, particleScale, pivot, newScale));
        }
    }

    @Unique
    private record ParticleMorphState(float radiusScale, float particleScale, Vec3 lastPivot, float lastScale) {
        private boolean matchesPivot(Vec3 pivot) {
            return closeVec(pivot, lastPivot);
        }

        private boolean matchesScale(float scale) {
            return Math.abs(scale - lastScale) < 0.0005F;
        }

        private Vec3 unscalePivot(Vec3 pivot) {
            float divisor = Math.abs(radiusScale) < 0.0001F ? 1.0F : radiusScale;
            return new Vec3(pivot.x / divisor, pivot.y / divisor, pivot.z / divisor);
        }

        private float unscaleParticle(float scale) {
            float divisor = Math.abs(particleScale) < 0.0001F ? 1.0F : particleScale;
            return scale / divisor;
        }

        private static boolean closeVec(Vec3 current, Vec3 previous) {
            if (current == null || previous == null) {
                return current == previous;
            }
            return current.distanceToSqr(previous) < 0.0001D;
        }
    }
}
