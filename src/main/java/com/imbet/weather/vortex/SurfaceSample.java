package com.imbet.weather.vortex;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

record SurfaceSample(
        ResourceKey<Level> dimension,
        long vortexId,
        int generation,
        int centerX,
        int centerZ,
        int surfaceY,
        int waterCount,
        int deepWaterCount,
        int landCount,
        int aridCount,
        int hotSourceCount,
        int fireCount,
        int flammableCount,
        int minSurfaceY,
        int maxSurfaceY,
        float biomeTemperature,
        boolean raining,
        boolean canSeeSky
) {
    SurfaceAnalysis analyze() {
        int samples = Math.max(1, waterCount + landCount);
        float waterFraction = waterCount / (float) samples;
        float aridFraction = aridCount / (float) samples;
        float heatSources = hotSourceCount + fireCount * 1.35F + flammableCount * 0.18F;
        float thermalScore = clamp((heatSources / Math.max(4.0F, samples * 0.42F)) + Math.max(0.0F, biomeTemperature - 0.8F) * 0.22F);
        ThermalState thermalState = thermalScore >= 0.72F ? ThermalState.OVERDRIVE : thermalScore >= 0.45F ? ThermalState.HEATED : ThermalState.NORMAL;

        SurfaceMedium medium;
        if (waterFraction >= 0.58F) {
            medium = deepWaterCount >= Math.max(3, waterCount / 2) ? SurfaceMedium.DEEP_WATER : SurfaceMedium.WATER;
        } else if (thermalScore >= 0.72F) {
            medium = SurfaceMedium.HOT;
        } else if (aridFraction >= 0.45F && biomeTemperature >= 0.85F && !raining && canSeeSky) {
            medium = SurfaceMedium.ARID;
        } else if (samples <= 1 && landCount <= 0 && waterCount <= 0) {
            medium = SurfaceMedium.UNLOADED;
        } else {
            medium = SurfaceMedium.LAND;
        }

        float drySurfaceScore = clamp((aridFraction * 0.72F) + Math.max(0.0F, biomeTemperature - 0.75F) * 0.28F - (raining ? 0.35F : 0.0F));
        float roughness = clamp((maxSurfaceY - minSurfaceY) / 80.0F);
        return new SurfaceAnalysis(dimension, vortexId, generation, surfaceY, medium, thermalState, thermalScore, drySurfaceScore, deepWaterCount, roughness);
    }

    private static float clamp(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, value);
    }
}
