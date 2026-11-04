package com.imbet.weather;

import com.imbet.Config;
import com.imbet.survey.TornadoTrackService;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;
import weather2.weathersystem.storm.WeatherObjectParticleStorm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WeatherDataStreamService {
    private static final Map<ResourceKey<Level>, List<WeatherPhysicalSnapshot>> SNAPSHOTS = new HashMap<>();

    private WeatherDataStreamService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null) {
            return;
        }
        int interval = Math.max(1, Math.min(5, Config.MULTIPLAYER_STATE_SYNC_INTERVAL_TICKS.get()));
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        refresh(level);
    }

    public static void refresh(ServerLevel level) {
        if (level != null) {
            SNAPSHOTS.put(level.dimension(), build(level));
        }
    }

    public static List<WeatherPhysicalSnapshot> snapshots(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        return SNAPSHOTS.getOrDefault(level.dimension(), List.of());
    }

    public static TornadoWindField.WindSample sampleWind(ServerLevel level, Vec3 position) {
        TornadoWindField.WindSample strongest = TornadoWindField.WindSample.CALM;
        for (WeatherPhysicalSnapshot snapshot : snapshots(level)) {
            TornadoWindField.WindSample sample = snapshot.sampleAt(position);
            if (sample.speedMph() > strongest.speedMph()) {
                strongest = sample;
            }
        }
        return strongest;
    }

    private static List<WeatherPhysicalSnapshot> build(ServerLevel level) {
        List<WeatherPhysicalSnapshot> result = new ArrayList<>();
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (object == null || object.pos == null) {
                    continue;
                }
                if (object instanceof StormObject storm) {
                    appendStorm(level, result, storm);
                } else if (object instanceof WeatherObjectParticleStorm particleStorm) {
                    appendParticleStorm(result, particleStorm);
                }
            }
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            appendAddonEvent(result, event);
        }
        return List.copyOf(result);
    }

    private static void appendStorm(ServerLevel level, List<WeatherPhysicalSnapshot> result, StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        StormSubtype subtype = StormClassifier.classify(storm, state);
        boolean dynamicVortex = !DynamicStormController.isNativeCyclone(storm)
                && !AddonWeatherEventService.isHurricaneHelperStorm(storm)
                && DynamicStormController.isDynamicVortex(storm);
        Vec3 circulationCenter = dynamicVortex ? DynamicStormController.tornadoCirculationCenter(storm) : storm.pos;
        Vec3 motion = storm.motion == null ? Vec3.ZERO : storm.motion;
        TornadoWindField.WindSample wind = dynamicVortex
                ? TornadoWindField.sample(storm, level, circulationCenter)
                : strongestCellWind(storm, level, storm.pos, subtype);
        double maxWind = dynamicVortex ? TornadoWindField.maxWindMph(storm) : wind.speedMph();
        float damageWidth = dynamicVortex ? Math.max(8.0F, state.damagePathWidth()) : Math.max(24.0F, storm.getSize());
        float visibleWidth = dynamicVortex ? Math.max(4.0F, state.visibleFunnelWidth()) : damageWidth;
        // The storm's own structure, sampled from the service the sky and the wind field already use, so an
        // echo can be shaped by where this storm's mesocyclone, hook and hail core actually are rather than
        // by a procedural offset from the storm centre. A vortex that is not itself a storm (everything
        // below, plus satellites) gets stormRadius 0 and no structure at all.
        SupercellStructureService.StructureSample structure = SupercellStructureService.sample(storm, state);
        boolean structured = SupercellStructureService.isEligible(storm) && state.supercellStructureStrength() > 0.05F;
        // The echo is the size of the storm the player can see, which is also what makes the LP and HP
        // difference land: character scales the drawn shield, not this.
        float stormRadius = (float) DynamicStormController.stormPrecipRadius(storm);
        boolean precipitating = precipitatingStorm(storm, subtype, structured);
        result.add(new WeatherPhysicalSnapshot(
                TornadoTrackService.stormSourceKey(storm.ID),
                storm.ID,
                Long.MIN_VALUE,
                subtype,
                storm.pos,
                circulationCenter,
                motion,
                wind.mphVector(),
                maxWind,
                damageWidth,
                visibleWidth,
                (float) Math.max(3.0D, DynamicStormController.baseRadiusForWind(storm) * 0.32D),
                (float) DynamicStormController.radiusOfMaxWind(storm),
                storm.levelCurIntensityStage,
                StormStageLabel.label(storm, state, maxWind, level.getGameTime()),
                dynamicVortex,
                !storm.isDead && !state.isAbsorbedOrDecaying(),
                precipitating,
                structured ? structure.mesocyclone() : storm.pos,
                structured ? structure.hookEchoNotch() : storm.pos,
                structured ? structure.hailShaft() : storm.pos,
                stormRadius,
                structured ? state.supercellStructureStrength() : 0.0F,
                structured ? state.mesocycloneStrength() : 0.0F,
                structured ? state.ffdStrength() : 0.0F,
                structured ? state.hailCoreStrength() : 0.0F,
                structured ? SupercellStructureService.modeFor(storm, state).ordinal() : SupercellStructureService.SupercellMode.CLASSIC.ordinal()
        ));
        if (dynamicVortex) {
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                if (satellite.absorbed()) {
                    continue;
                }
                Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                double satelliteWind = DynamicStormController.satelliteWindMph(storm, satellite);
                float width = Math.max(6.0F, satellite.damagePathWidth());
                result.add(new WeatherPhysicalSnapshot(
                        TornadoTrackService.satelliteSourceKey(storm.ID, satellite.id()),
                        satellite.id(),
                        storm.ID,
                        StormSubtype.SATELLITE_TORNADO,
                        storm.pos,
                        center,
                        satellite.motion(),
                        satellite.motion().horizontalDistanceSqr() > 1.0E-5D ? satellite.motion().normalize().scale(satelliteWind) : wind.mphVector(),
                        satelliteWind,
                        width,
                        Math.max(3.0F, width * 0.55F),
                        Math.max(2.0F, width * 0.20F),
                        Math.max(2.0F, width * 0.36F),
                        storm.levelCurIntensityStage,
                        // A satellite's stage is its own wind, not the parent's rung: the parent can sit at
                        // full strength while the satellite is a weak, roping circulation.
                        StormStageLabel.satelliteLabel(satelliteWind),
                        satellite.touchedDown(),
                        !storm.isDead && !state.isAbsorbedOrDecaying(),
                        // A satellite is a circulation, not a body of precipitation: it is kept on the radar by
                        // its circulation, and draws its signature inside the parent's echo.
                        false,
                        // A satellite tornado is a circulation inside the parent storm's echo, so it draws no
                        // precipitation shield of its own - it used to paint a second echo blob centred on the
                        // parent's position, one per satellite, right on top of the parent's own return.
                        Vec3.ZERO,
                        Vec3.ZERO,
                        Vec3.ZERO,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        SupercellStructureService.SupercellMode.CLASSIC.ordinal()
                ));
            }
        }
    }

    private static TornadoWindField.WindSample strongestCellWind(StormObject storm, ServerLevel level, Vec3 position, StormSubtype subtype) {
        TornadoWindField.WindSample structureWind = subtype == StormSubtype.SUPERCELL
                ? SupercellStructureService.sampleWind(storm, level, position)
                : TornadoWindField.WindSample.CALM;
        TornadoWindField.WindSample cellWind = thunderstormWind(storm, subtype);
        return structureWind.speedMph() >= cellWind.speedMph() ? structureWind : cellWind;
    }

    private static TornadoWindField.WindSample thunderstormWind(StormObject storm, StormSubtype subtype) {
        if (storm == null) {
            return TornadoWindField.WindSample.CALM;
        }
        double stageWind = 0.0D;
        if (storm.isPrecipitating()) {
            stageWind = 24.0D;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_THUNDER) {
            stageWind = Math.max(stageWind, 34.0D);
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND) {
            stageWind = Math.max(stageWind, 58.0D);
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL || subtype == StormSubtype.SUPERCELL) {
            stageWind = Math.max(stageWind, 46.0D);
        }
        Vec3 direction = horizontalDirection(storm.motion);
        if (direction == Vec3.ZERO) {
            direction = directionFromAngle(storm.getAdjustedAngle());
        }
        double stormMotionBoost = storm.motion == null ? 0.0D : Math.min(12.0D, storm.motion.horizontalDistance() * 40.0D);
        if (subtype == StormSubtype.HURRICANE || subtype == StormSubtype.CYCLONE) {
            double cycloneBase = subtype == StormSubtype.HURRICANE ? 92.0D : 64.0D;
            double stageBoost = Math.max(0, storm.levelCurIntensityStage - StormObject.STATE_FORMING) * 13.0D;
            double cap = subtype == StormSubtype.HURRICANE ? 170.0D : 128.0D;
            double speed = Mth.clamp(Math.max(stageWind, cycloneBase + stageBoost) + stormMotionBoost, cycloneBase, cap);
            Vec3 mphVector = direction.scale(speed);
            double radius = Math.max(subtype == StormSubtype.HURRICANE ? 420.0D : 180.0D, storm.getSize() * (subtype == StormSubtype.HURRICANE ? 4.2D : 3.0D));
            return new TornadoWindField.WindSample(mphVector, speed, speed, 0.0D, radius, 0.0D);
        }
        if (stageWind <= 0.0D) {
            return TornadoWindField.WindSample.CALM;
        }
        double speed = Math.min(72.0D, stageWind + stormMotionBoost);
        Vec3 mphVector = direction.scale(speed);
        double radius = Math.max(32.0D, storm.getSize() * 2.0D);
        return new TornadoWindField.WindSample(mphVector, speed, speed, 0.0D, radius, 0.0D);
    }

    private static Vec3 horizontalDirection(Vec3 vector) {
        if (vector == null || vector.horizontalDistanceSqr() < 1.0E-5D) {
            return Vec3.ZERO;
        }
        return new Vec3(vector.x, 0.0D, vector.z).normalize();
    }

    private static Vec3 directionFromAngle(float angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        Vec3 direction = new Vec3(Math.cos(radians), 0.0D, Math.sin(radians));
        return direction.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : direction.normalize();
    }

    private static void appendParticleStorm(List<WeatherPhysicalSnapshot> result, WeatherObjectParticleStorm storm) {
        StormSubtype subtype = StormClassifier.classify(storm);
        if (subtype == StormSubtype.NONE) {
            return;
        }
        Vec3 motion = storm.motion == null ? Vec3.ZERO : storm.motion;
        result.add(new WeatherPhysicalSnapshot(
                "particle:" + storm.ID,
                storm.ID,
                Long.MIN_VALUE,
                subtype,
                storm.pos,
                storm.pos,
                motion,
                motion.scale(20.0D),
                0.0D,
                Math.max(24.0F, storm.getSize()),
                Math.max(24.0F, storm.getSize()),
                0.0F,
                Math.max(1.0F, storm.getSize() * 0.5F),
                0,
                "",
                false,
                !storm.isDead,
                // Snow and blowing sand are what a radar would see in a particle storm; a flammagenitus is a
                // cloud, and nothing falls out of it.
                subtype != StormSubtype.FLAMMAGENITUS,
                Vec3.ZERO,
                Vec3.ZERO,
                Vec3.ZERO,
                Math.max(24.0F, storm.getSize()),
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                SupercellStructureService.SupercellMode.CLASSIC.ordinal()
        ));
    }

    private static void appendAddonEvent(List<WeatherPhysicalSnapshot> result, AddonWeatherEvent event) {
        Vec3 center = event.position();
        Vec3 motion = event.motion();
        double maxWindMph = event.type() == StormSubtype.LANDSPOUT ? Math.min(LandspoutEntity.MAX_EF2_WIND_MPH, event.maxWindMph()) : event.maxWindMph();
        float width = event.isLine() && event.lineStart() != null && event.lineEnd() != null
                ? (float) event.lineStart().distanceTo(event.lineEnd())
                : Math.max(3.0F, event.radius() * 2.0F);
        Vec3 wind = event.isLine()
                ? (motion == null ? Vec3.ZERO : new Vec3(motion.x, 0.0D, motion.z).normalize().scale(maxWindMph))
                : (motion == null || motion.horizontalDistanceSqr() < 1.0E-5D ? Vec3.ZERO : motion.normalize().scale(maxWindMph));
        boolean vortex = event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.FUNNEL_CLOUD || event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL || event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.FIRE_WHIRL || event.type() == StormSubtype.FLOATER_TORNADO || event.type() == StormSubtype.SATELLITE_TORNADO || event.type() == StormSubtype.TORNADO;
        result.add(new WeatherPhysicalSnapshot(
                TornadoTrackService.addonSourceKey(event.id()),
                event.id(),
                Long.MIN_VALUE,
                event.type(),
                center,
                center,
                motion,
                wind,
                maxWindMph,
                width,
                event.type() == StormSubtype.HURRICANE ? width * 0.30F : Math.max(2.0F, width * 0.58F),
                event.type() == StormSubtype.HURRICANE ? Math.max(18.0F, event.radius() * 0.16F) : Math.max(1.5F, event.radius() * 0.35F),
                event.type() == StormSubtype.HURRICANE ? Math.max(32.0F, event.radius() * 0.42F) : Math.max(1.5F, event.radius()),
                inferredStage((float) maxWindMph),
                inferredStageLabel((float) maxWindMph),
                vortex,
                true,
                precipitatingEvent(event.type()),
                Vec3.ZERO,
                Vec3.ZERO,
                Vec3.ZERO,
                Math.max(24.0F, event.radius()),
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                SupercellStructureService.SupercellMode.CLASSIC.ordinal()
        ));
    }

    /**
     * Whether a storm is putting anything in the air for a radar to see.
     *
     * <p>A weather radar returns precipitation and the debris a circulation lifts, not cloud: a tower that has
     * not started raining is invisible to it until its first echo appears. Weather2 marks a storm as
     * precipitating while it rains, and its stages run cloud, thunderstorm, high wind, hail - so a storm at or
     * beyond the thunderstorm stage is raining somewhere even if the flag has not caught up, a resolved
     * supercell is precipitating by definition, and a hurricane, cyclone or squall line is a rain system.
     */
    private static boolean precipitatingStorm(StormObject storm, StormSubtype subtype, boolean structured) {
        return storm.isPrecipitating()
                || storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                || structured
                || subtype == StormSubtype.HURRICANE
                || subtype == StormSubtype.CYCLONE
                || subtype == StormSubtype.SQUALL_LINE;
    }

    /** Whether an addon event's own nature puts precipitation in the air, as opposed to being a cloud. */
    private static boolean precipitatingEvent(StormSubtype type) {
        return switch (type) {
            case HURRICANE, CYCLONE, SQUALL_LINE, THUNDERSTORM, SUPERCELL, SNOWSTORM, SANDSTORM -> true;
            default -> false;
        };
    }

    private static int inferredStage(float windMph) {
        if (windMph >= 200.0F) {
            return 9;
        }
        if (windMph >= 166.0F) {
            return 8;
        }
        if (windMph >= 136.0F) {
            return 7;
        }
        if (windMph >= 111.0F) {
            return 6;
        }
        if (windMph >= 86.0F) {
            return 5;
        }
        if (windMph >= 65.0F) {
            return 4;
        }
        return 0;
    }

    private static String inferredStageLabel(float windMph) {
        String tag = StormStageLabel.tornadoTag(inferredStage(windMph));
        // An addon vortex (landspout, waterspout, gustnado, dust devil) never climbs Weather2's
        // ladder, so its label has always been inferred from wind - report that as a tornado stage
        // rather than a raw rung, which claimed "Stage 9" for a 200 mph whirl that is not one.
        return tag == null ? "" : tag;
    }
}
