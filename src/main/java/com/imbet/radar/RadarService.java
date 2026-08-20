package com.imbet.radar;

import com.imbet.Config;
import com.imbet.weather.SpcRisk;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.vortex.VortexEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;
import weather2.weathersystem.storm.WeatherObjectParticleStorm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RadarService {
    private RadarService() {
    }

    public static List<RadarStormSnapshot> buildSnapshot(ServerLevel level, BlockPos origin, int range) {
        Vec3 radarPos = Vec3.atCenterOf(origin);
        List<RadarStormSnapshot> snapshots = new ArrayList<>();

        snapshots.addAll(VortexEngine.radarSnapshots(level, radarPos, range));

        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
            for (WeatherObject object : manager.getStormObjects()) {
                if (object == null || object.isDead || object.pos == null || object.pos.distanceTo(radarPos) > range) {
                    continue;
                }
                if (object instanceof WeatherObjectParticleStorm particleStorm) {
                    StormSubtype subtype = StormClassifier.classify(particleStorm);
                    if (subtype == StormSubtype.NONE) {
                        continue;
                    }
                    SpcRisk risk = StormClassifier.riskFor(particleStorm);
                    double reflectivityDbz = subtype == StormSubtype.SNOWSTORM ? 24.0D : subtype == StormSubtype.SANDSTORM ? 18.0D : 32.0D;
                    snapshots.add(new RadarStormSnapshot(
                            particleStorm.ID,
                            subtype.label(),
                            risk.label(),
                            risk.severity(),
                            risk.color(),
                            particleStorm.pos.x,
                            particleStorm.pos.z,
                            particleStorm.motion == null ? 0.0D : particleStorm.motion.x,
                            particleStorm.motion == null ? 0.0D : particleStorm.motion.z,
                            particleStorm.getSize(),
                            0.0D,
                            reflectivityDbz,
                            radialVelocityMps(radarPos, particleStorm.pos, particleStorm.motion),
                            0.0D,
                            false,
                            0,
                            false,
                            particleStorm.pos.x,
                            particleStorm.pos.z,
                            particleStorm.pos.x,
                            particleStorm.pos.z,
                            false
                    ));
                }
            }
        }

        snapshots.sort(Comparator.comparingInt(RadarStormSnapshot::severity).reversed());
        return snapshots;
    }

    public static int powerFor(List<RadarStormSnapshot> snapshots) {
        int severity = snapshots.stream().mapToInt(RadarStormSnapshot::severity).max().orElse(0);
        return SpcRisk.fromSeverity(severity).redstonePower();
    }

    private static double radialVelocityMps(Vec3 radarPos, Vec3 stormPos, Vec3 motion) {
        if (radarPos == null || stormPos == null || motion == null) {
            return 0.0D;
        }
        return radialVelocityFromMotion(motion.x, motion.z, stormPos.x, stormPos.z, radarPos);
    }

    private static double radialVelocityFromMotion(double motionX, double motionZ, double x, double z, Vec3 radarPos) {
        if (radarPos == null) {
            return 0.0D;
        }
        double dx = x - radarPos.x;
        double dz = z - radarPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.001D) {
            return 0.0D;
        }
        return ((motionX * dx + motionZ * dz) / distance) * 20.0D;
    }
}
