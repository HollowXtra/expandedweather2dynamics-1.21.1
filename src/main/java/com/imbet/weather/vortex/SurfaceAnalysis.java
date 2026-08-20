package com.imbet.weather.vortex;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

record SurfaceAnalysis(
        ResourceKey<Level> dimension,
        long vortexId,
        int generation,
        int surfaceY,
        SurfaceMedium medium,
        ThermalState thermalState,
        float thermalScore,
        float drySurfaceScore,
        int waterDepth,
        float roughnessPenalty
) {
}
