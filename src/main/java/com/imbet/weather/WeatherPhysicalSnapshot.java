package com.imbet.weather;

import net.minecraft.world.phys.Vec3;

public record WeatherPhysicalSnapshot(
        String sourceKey,
        long sourceId,
        long parentId,
        StormSubtype subtype,
        Vec3 stormPosition,
        Vec3 circulationPosition,
        Vec3 motion,
        Vec3 windVectorMph,
        double maxWindMph,
        float damagePathWidth,
        float visibleFunnelWidth,
        float coreRadius,
        float radiusOfMaxWind,
        int stage,
        String stageLabel,
        boolean confirmedCirculation,
        boolean active
) {
    public WeatherPhysicalSnapshot {
        sourceKey = sourceKey == null || sourceKey.isBlank() ? "unknown:" + sourceId : sourceKey;
        subtype = subtype == null ? StormSubtype.NONE : subtype;
        stormPosition = safeVec(stormPosition);
        circulationPosition = safeVec(circulationPosition);
        motion = safeVec(motion);
        windVectorMph = safeVec(windVectorMph);
        maxWindMph = finite(maxWindMph);
        damagePathWidth = finite(damagePathWidth);
        visibleFunnelWidth = finite(visibleFunnelWidth);
        coreRadius = finite(coreRadius);
        radiusOfMaxWind = finite(radiusOfMaxWind);
        stageLabel = stageLabel == null ? "" : stageLabel;
    }

    public boolean trackableVortex() {
        return active && confirmedCirculation && (subtype == StormSubtype.TORNADO
                || subtype == StormSubtype.SATELLITE_TORNADO
                || subtype == StormSubtype.LANDSPOUT
                || subtype == StormSubtype.WATERSPOUT
                || subtype == StormSubtype.DUST_DEVIL
                || subtype == StormSubtype.SNOW_DEVIL
                || subtype == StormSubtype.FIRE_WHIRL
                || subtype == StormSubtype.FLOATER_TORNADO
                || subtype == StormSubtype.GUSTNADO);
    }

    public TornadoWindField.WindSample sampleAt(Vec3 position) {
        if (position == null || !active) {
            return TornadoWindField.WindSample.CALM;
        }
        if (subtype == StormSubtype.SQUALL_LINE) {
            Vec3 horizontal = new Vec3(windVectorMph.x, 0.0D, windVectorMph.z);
            double distance = position.distanceTo(circulationPosition);
            double falloff = Math.max(0.0D, 1.0D - distance / Math.max(1.0D, damagePathWidth));
            Vec3 vector = horizontal.scale(falloff);
            return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, damagePathWidth, radiusOfMaxWind);
        }
        Vec3 center = new Vec3(circulationPosition.x, position.y, circulationPosition.z);
        Vec3 fromCenter = position.subtract(center);
        double distance = Math.max(0.4D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double rmw = Math.max(1.2D, radiusOfMaxWind);
        double outer = Math.max(damagePathWidth * 0.75D, subtype == StormSubtype.GUSTNADO ? rmw * 7.4D : subtype == StormSubtype.FIRE_WHIRL ? rmw * 5.5D : subtype == StormSubtype.FLOATER_TORNADO ? rmw * 5.0D : subtype == StormSubtype.SNOW_DEVIL ? rmw * 3.3D : rmw * 4.0D);
        if (distance > outer) {
            return TornadoWindField.WindSample.CALM;
        }
        double tangential = distance <= rmw ? maxWindMph * (distance / rmw) : maxWindMph * Math.pow(rmw / distance, subtype == StormSubtype.HURRICANE ? 0.45D : 0.86D);
        if (subtype == StormSubtype.HURRICANE && distance < Math.max(8.0D, coreRadius)) {
            tangential *= Math.max(0.16D, distance / Math.max(8.0D, coreRadius));
        }
        Vec3 radial = fromCenter.scale(1.0D / distance);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double inflow = subtype == StormSubtype.DUST_DEVIL ? 0.10D : subtype == StormSubtype.SNOW_DEVIL ? 0.08D : subtype == StormSubtype.GUSTNADO ? 0.055D : subtype == StormSubtype.FIRE_WHIRL ? 0.22D : subtype == StormSubtype.FLOATER_TORNADO ? 0.14D : subtype == StormSubtype.WATERSPOUT ? 0.24D : subtype == StormSubtype.HURRICANE ? 0.08D : 0.18D;
        double lift = subtype == StormSubtype.SUPERCELL || subtype == StormSubtype.HURRICANE ? tangential * 0.04D : subtype == StormSubtype.GUSTNADO ? tangential * 0.10D : subtype == StormSubtype.FIRE_WHIRL ? tangential * 0.30D : subtype == StormSubtype.FLOATER_TORNADO ? tangential * 0.12D : tangential * 0.22D;
        Vec3 vector = tangent.scale(tangential).add(radial.scale(-tangential * inflow)).add(0.0D, lift, 0.0D);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, damagePathWidth, rmw);
    }

    private static Vec3 safeVec(Vec3 vec) {
        if (vec == null || !Double.isFinite(vec.x) || !Double.isFinite(vec.y) || !Double.isFinite(vec.z)) {
            return Vec3.ZERO;
        }
        return vec;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }
}
