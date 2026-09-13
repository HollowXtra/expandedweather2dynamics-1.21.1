package com.imbet.radar;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.SpcIntensity;
import com.imbet.weather.SpcRisk;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherDataStreamService;
import com.imbet.weather.WeatherPhysicalSnapshot;
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
        WeatherDataStreamService.refresh(level);
        List<WeatherPhysicalSnapshot> physicalSnapshots = WeatherDataStreamService.snapshots(level);
        if (!physicalSnapshots.isEmpty()) {
            for (WeatherPhysicalSnapshot snapshot : physicalSnapshots) {
                if (snapshot == null || !snapshot.active() || snapshot.circulationPosition().distanceTo(radarPos) > range) {
                    continue;
                }
                snapshots.add(fromPhysicalSnapshot(radarPos, snapshot));
            }
            snapshots.sort(Comparator.comparingInt(RadarStormSnapshot::severity).reversed());
            return snapshots;
        }

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
                boolean tropicalSystem = subtype == StormSubtype.HURRICANE
                        || subtype == StormSubtype.CYCLONE
                        || DynamicStormController.isNativeCyclone(storm)
                        || AddonWeatherEventService.isHurricaneHelperStorm(storm);
                boolean dynamicVortex = !tropicalSystem && DynamicStormController.isDynamicVortex(storm);
                boolean tornadoLike = !tropicalSystem && (subtype == StormSubtype.TORNADO || subtype == StormSubtype.WATERSPOUT);
                double snapshotWindMph = dynamicVortex ? TornadoWindField.maxWindMph(storm) : wind.speedMph();
                // A measured violent vortex is off the published ladder, read through the same rule the
                // SPC panel's live outlook uses, so the radar colours and the panel agree about it.
                risk = SpcRisk.escalateObserved(risk, dynamicVortex ? SpcIntensity.tornadoCigForWind(snapshotWindMph) : 0);
                double rotationMps = dynamicVortex
                        ? TornadoWindField.maxWindMph(storm) * 0.44704D * (0.46D + Math.min(0.18D, state.subVortices().size() * 0.03D))
                        : Math.max(state.mesocycloneStrength() * 28.0D, wind.speedMph() * 0.44704D * 0.25D);
                if (!dynamicVortex && !tropicalSystem && Config.SUPERCELL_STRUCTURE_RADAR.get() && state.wallCloudPersistenceTicks() >= Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS.get()) {
                    rotationMps = Math.max(rotationMps, state.wallCloudRotationStrength() * 34.0D);
                }
                rotationMps *= DynamicStormController.visualRotationSign(storm);
                SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
                boolean persistentWallCloud = state.wallCloudStrength() > 0.05F && state.wallCloudPersistenceTicks() >= Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS.get();
                boolean hookEcho = tornadoLike || (!tropicalSystem && Config.SUPERCELL_STRUCTURE_RADAR.get() && (state.mesocycloneStrength() >= 0.55F || persistentWallCloud));
                Vec3 circulation = dynamicVortex ? DynamicStormController.tornadoCirculationCenter(storm) : persistentWallCloud ? structure.wallCloudCenter() : structure.mesocyclone();
                Vec3 hook = hookEcho ? circulation : storm.pos;
                boolean confirmedCirculation = tornadoLike;
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
                        snapshotWindMph,
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
                        confirmedCirculation,
                        storm.levelCurIntensityStage,
                        stageLabelFor(storm.levelCurIntensityStage, storm.levelStormIntensityMax)
                ));
                if (dynamicVortex) {
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
                                true,
                                storm.levelCurIntensityStage,
                                stageLabelFor(storm.levelCurIntensityStage, storm.levelStormIntensityMax)
                        ));
                    }
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
                        false,
                        0,
                        ""
                ));
            }
        }

        snapshots.addAll(AddonWeatherEventService.radarSnapshots(level, radarPos, range));
        snapshots.sort(Comparator.comparingInt(RadarStormSnapshot::severity).reversed());
        return snapshots;
    }

    private static RadarStormSnapshot fromPhysicalSnapshot(Vec3 radarPos, WeatherPhysicalSnapshot snapshot) {
        SpcRisk risk = riskForSnapshot(snapshot);
        boolean confirmed = snapshot.confirmedCirculation();
        boolean vortex = snapshot.trackableVortex();
        boolean hook = confirmed || vortex;
        double reflectivity = reflectivityDbzFor(snapshot);
        Vec3 storm = snapshot.stormPosition();
        Vec3 circulation = snapshot.circulationPosition();
        Vec3 motion = snapshot.motion() == null ? Vec3.ZERO : snapshot.motion();
        double rotationMps = vortex
                ? snapshot.maxWindMph() * 0.44704D * rotationFactor(snapshot.subtype())
                : snapshot.windVectorMph().length() * 0.44704D * 0.22D;
        return new RadarStormSnapshot(
                snapshot.sourceId(),
                labelFor(snapshot),
                risk.label(),
                risk.severity(),
                risk.color(),
                storm.x,
                storm.z,
                motion.x,
                motion.z,
                snapshot.damagePathWidth(),
                snapshot.maxWindMph(),
                reflectivity,
                radialVelocityMps(radarPos, circulation, motion),
                rotationMps,
                hook,
                0,
                vortex,
                circulation.x,
                circulation.z,
                hook ? circulation.x : storm.x,
                hook ? circulation.z : storm.z,
                confirmed,
                snapshot.stage(),
                snapshot.stageLabel()
        );
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
        if (subtype == StormSubtype.HURRICANE) {
            dbz += 10.0D;
        } else if (subtype == StormSubtype.CYCLONE) {
            dbz += DynamicStormController.isNativeHurricane(storm) ? 8.0D : 4.0D;
        } else if (subtype == StormSubtype.THUNDERSTORM) {
            dbz = Math.max(dbz, storm.levelCurIntensityStage >= StormObject.STATE_THUNDER ? 30.0D : 24.0D);
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

    private static String labelFor(WeatherPhysicalSnapshot snapshot) {
        if (snapshot.subtype() == null || snapshot.subtype() == StormSubtype.NONE) {
            return "Storm Cell";
        }
        return snapshot.subtype().label();
    }

    private static SpcRisk riskForSnapshot(WeatherPhysicalSnapshot snapshot) {
        StormSubtype type = snapshot.subtype();
        double wind = snapshot.maxWindMph();
        if (type == StormSubtype.HURRICANE) {
            return wind >= 130.0D ? SpcRisk.HIGH : wind >= 96.0D ? SpcRisk.MODERATE : SpcRisk.ENHANCED;
        }
        if (type == StormSubtype.TORNADO || type == StormSubtype.SATELLITE_TORNADO || type == StormSubtype.FLOATER_TORNADO) {
            return wind >= 200.0D ? SpcRisk.HIGH : wind >= 166.0D ? SpcRisk.MODERATE : wind >= 136.0D ? SpcRisk.ENHANCED : wind >= 86.0D ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
        }
        if (type == StormSubtype.SQUALL_LINE) {
            return wind >= 90.0D ? SpcRisk.MODERATE : wind >= 75.0D ? SpcRisk.ENHANCED : wind >= 58.0D ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
        }
        if (type == StormSubtype.WATERSPOUT || type == StormSubtype.LANDSPOUT || type == StormSubtype.GUSTNADO) {
            return SpcRisk.MARGINAL;
        }
        if (type == StormSubtype.FIRE_WHIRL) {
            return wind >= 100.0D ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
        }
        if (type == StormSubtype.WILDFIRE) {
            return wind >= 40.0D ? SpcRisk.ENHANCED : SpcRisk.SLIGHT;
        }
        if (type == StormSubtype.FLAMMAGENITUS) {
            return SpcRisk.ENHANCED;
        }
        if (type == StormSubtype.DUST_DEVIL || type == StormSubtype.SNOW_DEVIL || type == StormSubtype.SANDSTORM || type == StormSubtype.SNOWSTORM) {
            return SpcRisk.TSTM;
        }
        if (type == StormSubtype.SUPERCELL || snapshot.stage() >= StormObject.STATE_HAIL) {
            return SpcRisk.ENHANCED;
        }
        if (snapshot.stage() >= StormObject.STATE_HIGHWIND) {
            return SpcRisk.SLIGHT;
        }
        if (snapshot.stage() >= StormObject.STATE_THUNDER || reflectivityDbzFor(snapshot) >= 28.0D) {
            return SpcRisk.MARGINAL;
        }
        return SpcRisk.TSTM;
    }

    private static double reflectivityDbzFor(WeatherPhysicalSnapshot snapshot) {
        double wind = snapshot.maxWindMph();
        double dbz = 14.0D + Math.max(0, snapshot.stage()) * 4.0D;
        StormSubtype type = snapshot.subtype();
        if (type == StormSubtype.HURRICANE) {
            dbz = Math.max(dbz, 42.0D + Math.min(22.0D, wind / 7.0D));
        } else if (type == StormSubtype.SQUALL_LINE) {
            dbz = Math.max(dbz, 36.0D + Math.min(20.0D, wind / 5.5D));
        } else if (type == StormSubtype.TORNADO || type == StormSubtype.SATELLITE_TORNADO || type == StormSubtype.WATERSPOUT || type == StormSubtype.LANDSPOUT || type == StormSubtype.GUSTNADO || type == StormSubtype.FIRE_WHIRL || type == StormSubtype.FLOATER_TORNADO) {
            dbz = Math.max(dbz, 34.0D + Math.min(30.0D, wind / 7.0D));
            if (snapshot.damagePathWidth() >= 80.0F) {
                dbz += 3.0D;
            }
        } else if (type == StormSubtype.WILDFIRE) {
            dbz = 26.0D + Math.min(18.0D, wind / 5.0D);
        } else if (type == StormSubtype.FLAMMAGENITUS) {
            dbz = 34.0D + Math.min(24.0D, wind / 4.0D);
        } else if (type == StormSubtype.SNOWSTORM) {
            dbz = 24.0D;
        } else if (type == StormSubtype.SNOW_DEVIL) {
            dbz = 16.0D;
        } else if (type == StormSubtype.SANDSTORM || type == StormSubtype.DUST_DEVIL) {
            dbz = 18.0D;
        } else if (type == StormSubtype.SUPERCELL) {
            dbz = Math.max(dbz, 42.0D);
        } else if (type == StormSubtype.THUNDERSTORM) {
            dbz = Math.max(dbz, snapshot.stage() >= StormObject.STATE_THUNDER ? 30.0D : 24.0D);
        }
        return Mth.clamp(dbz, 5.0D, 76.0D);
    }

    private static double rotationFactor(StormSubtype type) {
        if (type == StormSubtype.DUST_DEVIL || type == StormSubtype.SNOW_DEVIL) {
            return 0.18D;
        }
        if (type == StormSubtype.FIRE_WHIRL) {
            return 0.34D;
        }
        if (type == StormSubtype.FLOATER_TORNADO) {
            return 0.38D;
        }
        return 0.42D;
    }

    private static String stageLabelFor(int stage, int maxStage) {
        if (stage <= 0) {
            return "";
        }
        return maxStage > 0 ? "Stage " + stage + "/" + maxStage : "Stage " + stage;
    }
}
