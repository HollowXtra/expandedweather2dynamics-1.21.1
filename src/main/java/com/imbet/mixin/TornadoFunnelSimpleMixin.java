package com.imbet.mixin;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import net.minecraft.util.Mth;
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
import java.util.List;

@Mixin(value = TornadoFunnelSimple.class, remap = false)
public abstract class TornadoFunnelSimpleMixin {
    @Unique
    private final List<Vec3> expandedweather2dynamics$smoothedOffsets = new ArrayList<>();
    @Unique
    private final List<Double> expandedweather2dynamics$smoothedVerticalOffsets = new ArrayList<>();

    @Inject(method = "tick", at = @At("TAIL"))
    private void expandedweather2dynamics$bendFunnelLayers(CallbackInfo ci) {
        TornadoFunnelSimple funnel = (TornadoFunnelSimple) (Object) this;
        StormObject storm = funnel.getStormObject();
        if (storm == null || funnel.listLayers == null || funnel.listLayers.size() < 2) {
            expandedweather2dynamics$smoothedOffsets.clear();
            expandedweather2dynamics$smoothedVerticalOffsets.clear();
            return;
        }
        Vec3 base = funnel.pos;
        int count = funnel.listLayers.size();
        while (expandedweather2dynamics$smoothedOffsets.size() < count) {
            expandedweather2dynamics$smoothedOffsets.add(Vec3.ZERO);
        }
        while (expandedweather2dynamics$smoothedVerticalOffsets.size() < count) {
            expandedweather2dynamics$smoothedVerticalOffsets.add(0.0D);
        }
        while (expandedweather2dynamics$smoothedOffsets.size() > count) {
            expandedweather2dynamics$smoothedOffsets.remove(expandedweather2dynamics$smoothedOffsets.size() - 1);
        }
        while (expandedweather2dynamics$smoothedVerticalOffsets.size() > count) {
            expandedweather2dynamics$smoothedVerticalOffsets.remove(expandedweather2dynamics$smoothedVerticalOffsets.size() - 1);
        }
        float smoothing = Config.TORNADO_FUNNEL_BEND_SMOOTHING.get().floatValue();
        for (int i = 0; i < count; i++) {
            Layer layer = funnel.listLayers.get(i);
            Vec3 target = DynamicStormController.bendFunnelLayer(storm, base, layer.getPos(), i, count);
            Vec3 targetOffset = new Vec3(target.x - base.x, 0.0D, target.z - base.z);
            Vec3 previousOffset = expandedweather2dynamics$smoothedOffsets.get(i);
            Vec3 smoothedOffset = previousOffset.lerp(targetOffset, smoothing);
            expandedweather2dynamics$smoothedOffsets.set(i, smoothedOffset);
            double targetLift = DynamicStormController.mainFunnelGroundLift(storm, i, count);
            double previousLift = expandedweather2dynamics$smoothedVerticalOffsets.get(i);
            double smoothedLift = Mth.lerp(Mth.clamp(smoothing * 0.92D, 0.02D, 0.72D), previousLift, targetLift);
            expandedweather2dynamics$smoothedVerticalOffsets.set(i, smoothedLift);
            layer.setPos(new Vec3(base.x + smoothedOffset.x, layer.getPos().y - previousLift + smoothedLift, base.z + smoothedOffset.z));
            layer.setRotation(layer.getRotation() + DynamicStormController.extraFunnelLayerSpinDegrees(storm, i, count));
        }
    }
}
