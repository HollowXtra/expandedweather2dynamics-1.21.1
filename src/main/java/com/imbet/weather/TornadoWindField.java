package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

public final class TornadoWindField {
    private TornadoWindField() {
    }

    public static WindSample sample(StormObject storm, Level level, Vec3 position) {
        if (!Config.WINDFIELD_ENABLED.get() || storm == null || storm.pos == null) {
            return WindSample.CALM;
        }

        DynamicStormState state = DynamicStormController.stateFor(storm);
        Vec3 center = new Vec3(storm.pos.x, position.y, storm.pos.z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.5D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double baseRadius = Math.max(6.0D, DynamicStormController.baseRadiusForWind(storm));
        double radiusMaxWind = Math.max(3.0D, DynamicStormController.radiusOfMaxWind(storm));
        double maxWindMph = maxWindMph(storm);

        double tangentialMph;
        if (radius <= radiusMaxWind) {
            tangentialMph = maxWindMph * (radius / radiusMaxWind);
        } else {
            tangentialMph = maxWindMph * Math.pow(radiusMaxWind / radius, 0.72D);
        }

        double inflowMph = tangentialMph * Config.WINDFIELD_RADIAL_INFLOW_FRACTION.get();
        double verticalMph = maxWindMph * Config.WINDFIELD_VERTICAL_LIFT_FRACTION.get() * Mth.clamp((float) (1.0D - radius / (baseRadius * 1.8D)), 0.0F, 1.0F);

        double rotationSign = DynamicStormController.rotationSign(storm);
        Vec3 radialOut = fromCenter.scale(1.0D / radius);
        Vec3 inward = radialOut.scale(-1.0D);
        Vec3 tangent = new Vec3(-radialOut.z * rotationSign, 0.0D, radialOut.x * rotationSign);
        Vec3 mphVector = tangent.scale(tangentialMph).add(inward.scale(inflowMph)).add(0.0D, verticalMph, 0.0D);
        double strongestLocalMph = mphVector.length();

        for (DynamicStormState.SubVortex vortex : state.subVortices()) {
            Vec3 vortexCenter = subVortexCenter(storm, vortex, position.y);
            Vec3 fromSub = position.subtract(vortexCenter);
            double subRadius = Math.max(0.35D, Math.sqrt(fromSub.x * fromSub.x + fromSub.z * fromSub.z));
            double subCoreRadius = Math.max(1.5D, baseRadius * vortex.radiusFraction() * 0.28D);
            if (subRadius > subCoreRadius * 3.5D) {
                continue;
            }
            Vec3 subRadialOut = fromSub.scale(1.0D / subRadius);
            Vec3 subTangent = new Vec3(-subRadialOut.z * rotationSign, 0.0D, subRadialOut.x * rotationSign);
            double subMph = maxWindMph * vortex.intensityFraction() * Math.max(0.0D, 1.0D - (subRadius / (subCoreRadius * 3.5D)));
            Vec3 subVector = subTangent.scale(subMph).add(subRadialOut.scale(-subMph * 0.25D));
            mphVector = mphVector.add(subVector);
            strongestLocalMph = Math.max(strongestLocalMph, subVector.length());
        }
        for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
            Vec3 satelliteCenter = DynamicStormController.satelliteCenter(storm, satellite, position.y);
            Vec3 fromSatellite = position.subtract(satelliteCenter);
            double satelliteRadius = Math.max(0.35D, Math.sqrt(fromSatellite.x * fromSatellite.x + fromSatellite.z * fromSatellite.z));
            double satelliteCore = Math.max(2.0D, satellite.radiusOfMaxWind());
            if (satelliteRadius > satelliteCore * 4.2D) {
                continue;
            }
            Vec3 satelliteRadial = fromSatellite.scale(1.0D / satelliteRadius);
            Vec3 satelliteTangent = new Vec3(-satelliteRadial.z * rotationSign, 0.0D, satelliteRadial.x * rotationSign);
            double satelliteMax = DynamicStormController.satelliteWindMph(storm, satellite);
            double satelliteMph = satelliteRadius <= satelliteCore
                    ? satelliteMax * (satelliteRadius / satelliteCore)
                    : satelliteMax * Math.pow(satelliteCore / satelliteRadius, 0.82D);
            Vec3 satelliteVector = satelliteTangent.scale(satelliteMph).add(satelliteRadial.scale(-satelliteMph * 0.32D));
            mphVector = mphVector.add(satelliteVector);
            strongestLocalMph = Math.max(strongestLocalMph, satelliteVector.length());
        }

        return new WindSample(mphVector, mphVector.length(), strongestLocalMph, radius, baseRadius, radiusMaxWind);
    }

    public static Vec3 subVortexCenter(StormObject storm, DynamicStormState.SubVortex vortex, double y) {
        double angle = Math.toRadians(vortex.orbitDegrees());
        return new Vec3(
                storm.pos.x + Math.cos(angle) * vortex.orbitRadius(),
                y,
                storm.pos.z + Math.sin(angle) * vortex.orbitRadius()
        );
    }

    public static double maxWindMph(StormObject storm) {
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return Config.WINDFIELD_STAGE5_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return Config.WINDFIELD_STAGE4_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE3) {
            return Config.WINDFIELD_STAGE3_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return Config.WINDFIELD_STAGE2_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE1) {
            return Config.WINDFIELD_STAGE1_MPH.get();
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_FORMING || storm.attrib_waterSpout) {
            return Config.WINDFIELD_FORMING_MPH.get();
        }
        return 0.0D;
    }

    public static Vec3 mphToEntityDelta(Vec3 mphVector) {
        return mphVector.scale(Config.WINDFIELD_ENTITY_PUSH_SCALE.get());
    }

    public record WindSample(Vec3 mphVector, double speedMph, double strongestLocalMph, double distanceFromCore, double baseRadius, double radiusMaxWind) {
        public static final WindSample CALM = new WindSample(Vec3.ZERO, 0.0D, 0.0D, Double.MAX_VALUE, 0.0D, 0.0D);

        public int redstonePower() {
            return Mth.clamp((int) Math.round(speedMph / 20.0D), 0, 15);
        }
    }
}
