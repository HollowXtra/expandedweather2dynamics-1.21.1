package com.imbet.weather;

import net.minecraft.util.Mth;
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
        boolean active,
        // Whether this object is putting anything in the air for a radar to see. A cloud that has not started
        // raining is not: a weather radar returns precipitation and the debris a circulation lifts, so a storm
        // in its cloud stage is invisible on the scope until its first echo appears.
        boolean precipitating,
        // Supercell structure, straight from SupercellStructureService. It travels with the snapshot because
        // the radar used to reconstruct a storm's hook echo from a procedural offset off the storm centre,
        // which put the hook somewhere other than where the storm actually had it - and left the tornado
        // marker sitting outside its own hook. stormRadius is the storm's precipitation extent in blocks and
        // is 0 for anything that is a circulation inside a parent storm rather than a storm itself.
        Vec3 structureMesocyclone,
        Vec3 structureHook,
        Vec3 structureHail,
        float stormRadius,
        float structureStrength,
        float mesocycloneStrength,
        float ffdStrength,
        float hailStrength,
        int structureMode
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
        structureMesocyclone = safeVec(structureMesocyclone);
        structureHook = safeVec(structureHook);
        structureHail = safeVec(structureHail);
        stormRadius = Math.max(0.0F, finite(stormRadius));
        structureStrength = clamp01(structureStrength);
        mesocycloneStrength = clamp01(mesocycloneStrength);
        ffdStrength = clamp01(ffdStrength);
        hailStrength = clamp01(hailStrength);
        structureMode = Mth.clamp(structureMode, 0, 2);
    }

    /** Whether this snapshot carries a real supercell structure worth shaping an echo around. */
    public boolean hasStructure() {
        return structureStrength > 0.05F && stormRadius > 1.0F;
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
                || subtype == StormSubtype.GUSTNADO
                || subtype == StormSubtype.FUNNEL_CLOUD);
    }

    /**
     * Whether this is a circulation a chase can be a chase of: a tornado on the ground, in each of the forms
     * the mod gives it - the supercell tornado, the satellite inside its parent, the floater and the
     * landspout - and nothing else.
     *
     * <p>A chase log is narrower than a radar. Everything it prints about an encounter is a statement about a
     * tornado: the class comes from the published EF bands, which rate tornadoes, and the line says a storm
     * was chased. A dust devil, a snow devil, a fire whirl and a gustnado are circulations the mod draws and
     * tracks, and none of them is a tornado or has an EF rating; a waterspout is the same circulation over
     * water that damages nothing on land; and a funnel cloud puts nothing at all on the ground. Those are
     * exactly the exclusions the spotter net and the storm camera already attribute to the chase log in prose
     * while the log itself was reading the wider list of trackable vortices, which is how a fire whirl came to
     * be filed as an EF1 chase.
     */
    public boolean chaseableVortex() {
        return active && confirmedCirculation && (subtype == StormSubtype.TORNADO
                || subtype == StormSubtype.SATELLITE_TORNADO
                || subtype == StormSubtype.LANDSPOUT
                || subtype == StormSubtype.FLOATER_TORNADO);
    }

    public TornadoWindField.WindSample sampleAt(Vec3 position) {
        if (position == null || !active) {
            return TornadoWindField.WindSample.CALM;
        }
        // A funnel cloud is trackable and visible but has no surface circulation whatsoever, so
        // every surface consumer of this snapshot reads calm - which is the type's whole definition.
        if (subtype == StormSubtype.FUNNEL_CLOUD) {
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

    private static float clamp01(float value) {
        return Mth.clamp(finite(value), 0.0F, 1.0F);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }
}
