package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import extendedrenderer.particle.entity.PivotingParticle;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
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
    /** Angular width of one lobe, in degrees. Wide enough to read as a lump instead of a spike. */
    @Unique
    private static final double LUMP_SIGMA_DEGREES = 30.0D;

    @Inject(method = "tickClient", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$hideMultivortexParentFunnel(CallbackInfo ci) {
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            // Two reasons to drop Weather2's funnel entirely: a multi-vortex episode with a lifted
            // parent, and hurricane storms - the helper rain core and the native tropical cyclone -
            // whose point funnel would read as a tornado hanging in the hurricane's middle.
            if (!DynamicStormController.shouldHideMultivortexParentFunnel(storm)
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
                Vec3 target = DynamicStormController.bendFunnelLayer(storm, base, layerPos, i, count);
                if (target == null) {
                    target = layerPos;
                }
                Vec3 morphologyOffset = DynamicStormController.funnelLayerMorphologyOffset(storm, i, count);
                Vec3 targetOffset = new Vec3(target.x - base.x + morphologyOffset.x, 0.0D, target.z - base.z + morphologyOffset.z);
                Vec3 previousOffset = smoothedOffsets.get(i);
                if (previousOffset == null) {
                    previousOffset = Vec3.ZERO;
                }
                Vec3 desiredOffset = previousOffset.lerp(targetOffset, smoothing);
                Vec3 smoothedOffset = expandedweather2dynamics$limitBendStep(previousOffset, desiredOffset, targetOffset);
                smoothedOffsets.set(i, smoothedOffset);
                double previousLift = smoothedVerticalOffsets.get(i) == null ? 0.0D : smoothedVerticalOffsets.get(i);
                double rawLayerY = layerPos.y - previousLift;
                double smoothedLift = Mth.lerp(Mth.clamp(smoothing * 0.92D, 0.0D, 1.0D), previousLift, targetLift);
                smoothedLift = expandedweather2dynamics$limitVerticalStep(previousLift, smoothedLift, targetLift);
                smoothedVerticalOffsets.set(i, smoothedLift);
                layer.setPos(new Vec3(base.x + smoothedOffset.x, rawLayerY + smoothedLift, base.z + smoothedOffset.z));
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

    @Inject(method = "tickClient", at = @At("HEAD"))
    private void expandedweather2dynamics$snapshotPreTickClientRotation(CallbackInfo ci) {
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
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            if (storm == null || storm.manager == null || storm.manager.getWorld() == null || !storm.manager.getWorld().isClientSide() || funnel.listLayers == null || funnel.listLayers.size() < 2) {
                return;
            }
            expandedweather2dynamics$ensureParticleState();
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
        particleScale *= DynamicStormController.funnelLayerFormationScale(storm, ageTicks, layerIndex, layerCount);
        alphaScale *= DynamicStormController.funnelLayerFormationAlpha(storm, ageTicks, layerIndex, layerCount);
        boolean cullLayer = hideParentFunnel || DynamicStormController.shouldCullMultivortexParentLayer(storm, layerIndex, layerCount);
        float lumpTaper = expandedweather2dynamics$lumpTaper(storm, layerIndex, layerCount);
        expandedweather2dynamics$scaleParticleList(layer.getListParticles(), radiusScale, particleScale, alphaScale, cullLayer, lumpTaper);
        expandedweather2dynamics$scaleParticleList(layer.getListParticlesExtra(), radiusScale, particleScale, alphaScale, cullLayer, lumpTaper);
        // Weather2 spawns fresh layer particles at funnel.pos (the funnel base) and only snaps them
        // to the layer anchor on the NEXT tick. In a bent/leaning funnel the top layer's anchor can be
        // tens of blocks away, so every new top particle visibly pops and teleports - the top-layer
        // jitter. Re-anchor freshly spawned particles that are far from the layer anchor immediately.
        Vec3 anchor = layer.getPos();
        if (anchor != null) {
            expandedweather2dynamics$reanchorFreshParticles(layer.getListParticles(), anchor);
            expandedweather2dynamics$reanchorFreshParticles(layer.getListParticlesExtra(), anchor);
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
        if (!Config.FUNNEL_LUMPS_ENABLED.get() || storm == null || storm.manager == null || storm.manager.getWorld() == null) {
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
            float newScale = Mth.clamp(baseScale * particleScale, 0.05F, 64.0F);
            particle.setScale(newScale);
            float alpha = alphaScale < 0.995F
                    ? Mth.clamp(alphaScale * 0.94F, 0.0F, 0.94F)
                    : Mth.clamp(0.86F + (particleScale - 1.0F) * 0.06F, 0.78F, 0.98F);
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
