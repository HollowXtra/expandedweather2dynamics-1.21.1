package com.imbet.radar;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.SpcRisk;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return List.of();
        }

        Vec3 radarPos = Vec3.atCenterOf(origin);
        List<RadarStormSnapshot> snapshots = new ArrayList<>();

        for (WeatherObject object : manager.getStormObjects()) {
            if (object == null || object.isDead || object.pos == null || object.pos.distanceTo(radarPos) > range) {
                continue;
            }
            if (object instanceof StormObject storm) {
                DynamicStormState state = DynamicStormController.stateFor(storm);
                if (state.isAbsorbedOrDecaying()) {
                    continue;
                }
                SupercellStructureService.tick(level, storm, state);
                StormSubtype subtype = StormClassifier.classify(storm, state);
                SpcRisk risk = StormClassifier.riskFor(storm);
                float width = state.damagePathWidth();
                TornadoWindField.WindSample wind = TornadoWindField.sample(storm, level, Vec3.atCenterOf(origin));
                TornadoWindField.WindSample supercellWind = SupercellStructureService.sampleWind(storm, level, Vec3.atCenterOf(origin));
                if (supercellWind.speedMph() > wind.speedMph()) {
                    wind = supercellWind;
                }
                double reflectivityDbz = reflectivityDbzFor(storm, subtype, wind, width, state);
                double radialVelocityMps = radialVelocityMps(radarPos, storm.pos, storm.motion);
                boolean tornadoLike = subtype == StormSubtype.TORNADO || subtype == StormSubtype.WATERSPOUT;
                double rotationMps = DynamicStormController.isDynamicVortex(storm)
                        ? TornadoWindField.maxWindMph(storm) * 0.44704D * (0.46D + Math.min(0.18D, state.subVortices().size() * 0.03D))
                        : Math.max(state.mesocycloneStrength() * 28.0D, wind.speedMph() * 0.44704D * 0.25D);
                if (!DynamicStormController.isDynamicVortex(storm) && Config.SUPERCELL_STRUCTURE_RADAR.get() && state.wallCloudPersistenceTicks() >= Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS.get()) {
                    rotationMps = Math.max(rotationMps, state.wallCloudRotationStrength() * 34.0D);
                }
                rotationMps *= DynamicStormController.rotationSign(storm);
                SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
                boolean persistentWallCloud = state.wallCloudStrength() > 0.05F && state.wallCloudPersistenceTicks() >= Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS.get();
                boolean hookEcho = subtype == StormSubtype.TORNADO || (Config.SUPERCELL_STRUCTURE_RADAR.get() && (state.mesocycloneStrength() >= 0.55F || persistentWallCloud));
                Vec3 circulation = DynamicStormController.isDynamicVortex(storm) ? storm.pos : persistentWallCloud ? structure.wallCloudCenter() : structure.mesocyclone();
                Vec3 hook = hookEcho ? circulation : storm.pos;
                boolean confirmedCirculation = subtype == StormSubtype.TORNADO;
                snapshots.add(new RadarStormSnapshot(
                        storm.ID,
                        StormClassifier.labelFor(storm, subtype),
                        risk.label(),
                        risk.severity(),
                        risk.color(),
                        storm.pos.x,
                        storm.pos.z,
                        storm.motion == null ? 0.0D : storm.motion.x,
                        storm.motion == null ? 0.0D : storm.motion.z,
                        width,
                        wind.speedMph(),
                        reflectivityDbz,
                        radialVelocityMps,
                        rotationMps,
                        hookEcho,
                        state.subVortices().size(),
                        tornadoLike,
                        circulation.x,
                        circulation.z,
                        hook.x,
                        hook.z,
                        confirmedCirculation
                ));
                for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                    Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                    double satelliteWind = DynamicStormController.satelliteWindMph(storm, satellite);
                    double satelliteWidth = DynamicStormController.satelliteRadius(storm, satellite) * 2.0D;
                    snapshots.add(new RadarStormSnapshot(
                            satellite.id(),
                            DynamicStormController.isAntiCyclonicTornado(storm) ? "Anti-Cyclonic Satellite Tornado" : StormSubtype.SATELLITE_TORNADO.label(),
                            SpcRisk.ENHANCED.label(),
                            SpcRisk.ENHANCED.severity(),
                            SpcRisk.ENHANCED.color(),
                            center.x,
                            center.z,
                            satellite.motion().x,
                            satellite.motion().z,
                            (float) satelliteWidth,
                            satelliteWind,
                            Mth.clamp(38.0D + satelliteWind / 6.0D, 38.0D, 68.0D),
                            radialVelocityMps(radarPos, center, storm.motion),
                            satelliteWind * 0.44704D * 0.38D * DynamicStormController.rotationSign(storm),
                            false,
                            0,
                            true,
                            center.x,
                            center.z,
                            center.x,
                            center.z,
                            true
                    ));
                }
            } else if (object instanceof WeatherObjectParticleStorm particleStorm) {
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

        snapshots.addAll(AddonWeatherEventService.radarSnapshots(level, radarPos, range));
        snapshots.sort(Comparator.comparingInt(RadarStormSnapshot::severity).reversed());
        return snapshots;
    }

    public static int powerFor(List<RadarStormSnapshot> snapshots) {
        int severity = snapshots.stream().mapToInt(RadarStormSnapshot::severity).max().orElse(0);
        return SpcRisk.fromSeverity(severity).redstonePower();
    }

    private static double reflectivityDbzFor(StormObject storm, StormSubtype subtype, TornadoWindField.WindSample wind, float width, DynamicStormState state) {
        double dbz = 18.0D + Math.max(0, storm.levelCurIntensityStage - StormObject.STATE_NORMAL) * 6.0D;
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            dbz += 4.0D;
        }
        if (DynamicStormController.isDynamicVortex(storm)) {
            dbz += subtype == StormSubtype.WATERSPOUT ? 2.0D : 6.0D;
            if (wind.speedMph() >= 150.0D) {
                dbz += 4.0D;
            }
            if (width >= 80.0F) {
                dbz += 3.0D;
            }
        }
        if (subtype == StormSubtype.CYCLONE) {
            dbz += storm.isHurricane() ? 8.0D : 4.0D;
        }
        if (Config.SUPERCELL_STRUCTURE_RADAR.get() && state.supercellStructureStrength() > 0.05F) {
            dbz = Math.max(dbz, 28.0D + state.ffdStrength() * 20.0D + state.hailCoreStrength() * 12.0D);
            if (state.mesocycloneStrength() >= 0.55F) {
                dbz += 3.0D;
            }
        }
        return Mth.clamp(dbz, 5.0D, 76.0D);
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
