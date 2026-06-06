package com.imbet.weather;

import net.minecraft.world.phys.Vec3;

public record ThermodynamicProfile(
        double capeJkg,
        double deepLayerShearMps,
        Vec3 shearVector,
        double stormModeIndex,
        double temperatureModifier,
        double moisture,
        boolean sereneSeasons,
        String seasonLabel
) {
    public ThermodynamicProfile {
        capeJkg = finite(capeJkg);
        deepLayerShearMps = finite(deepLayerShearMps);
        shearVector = safeVec(shearVector);
        stormModeIndex = clamp01(stormModeIndex);
        temperatureModifier = finite(temperatureModifier);
        moisture = clamp01(moisture);
        seasonLabel = seasonLabel == null || seasonLabel.isBlank() ? "Vanilla" : seasonLabel;
    }

    public static ThermodynamicProfile calm() {
        return new ThermodynamicProfile(0.0D, 0.0D, Vec3.ZERO, 0.0D, 0.0D, 0.0D, false, "Vanilla");
    }

    private static Vec3 safeVec(Vec3 vec) {
        if (vec == null || !Double.isFinite(vec.x) || !Double.isFinite(vec.y) || !Double.isFinite(vec.z)) {
            return Vec3.ZERO;
        }
        return vec;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
