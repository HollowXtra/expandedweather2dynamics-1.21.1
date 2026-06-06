package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
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

    @Inject(method = "tickClient", at = @At("HEAD"), cancellable = true)
    private void expandedweather2dynamics$hideMultivortexParentFunnel(CallbackInfo ci) {
        try {
            TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
            StormObject storm = funnel.getStormObject();
            if (!DynamicStormController.shouldHideMultivortexParentFunnel(storm)) {
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
        boolean hideParentFunnel = DynamicStormController.shouldHideMultivortexParentFunnel(storm);
        float radiusScale = DynamicStormController.funnelLayerRadiusScale(storm, layerIndex, layerCount);
        float particleScale = DynamicStormController.funnelParticleScale(storm, layerIndex, layerCount);
        float alphaScale = DynamicStormController.multivortexParentLayerAlpha(storm, layerIndex, layerCount);
        boolean cullLayer = hideParentFunnel || DynamicStormController.shouldCullMultivortexParentLayer(storm, layerIndex, layerCount);
        expandedweather2dynamics$scaleParticleList(layer.getListParticles(), radiusScale, particleScale, alphaScale, cullLayer);
        expandedweather2dynamics$scaleParticleList(layer.getListParticlesExtra(), radiusScale, particleScale, alphaScale, cullLayer);
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
            expandedweather2dynamics$scaleParticleList(layer.getListParticles(), 1.0F, 1.0F, 0.0F, true);
            expandedweather2dynamics$scaleParticleList(layer.getListParticlesExtra(), 1.0F, 1.0F, 0.0F, true);
        }
    }

    @Unique
    private void expandedweather2dynamics$scaleParticleList(List<PivotingParticle> particles, float radiusScale, float particleScale, float alphaScale, boolean cullLayer) {
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
            Vec3 pivot = particle.getPivot();
            if (pivot != null) {
                Vec3 basePivot = previous != null && previous.matchesPivot(pivot)
                        ? previous.unscalePivot(pivot)
                        : pivot;
                pivot = new Vec3(basePivot.x * radiusScale, basePivot.y * radiusScale, basePivot.z * radiusScale);
                particle.setPivot(pivot);
            }
            Vec3 pivotPrev = particle.getPivotPrev();
            if (pivotPrev != null) {
                Vec3 basePivotPrev = previous != null && previous.matchesPivotPrev(pivotPrev)
                        ? previous.unscalePivot(pivotPrev)
                        : pivotPrev;
                pivotPrev = new Vec3(basePivotPrev.x * radiusScale, basePivotPrev.y * radiusScale, basePivotPrev.z * radiusScale);
                particle.setPivotPrev(pivotPrev);
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
            expandedweather2dynamics$particleMorphStates.put(particle, new ParticleMorphState(radiusScale, particleScale, pivot, pivotPrev, newScale));
        }
    }

    @Unique
    private record ParticleMorphState(float radiusScale, float particleScale, Vec3 lastPivot, Vec3 lastPivotPrev, float lastScale) {
        private boolean matchesPivot(Vec3 pivot) {
            return closeVec(pivot, lastPivot);
        }

        private boolean matchesPivotPrev(Vec3 pivotPrev) {
            return closeVec(pivotPrev, lastPivotPrev);
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
