package com.imbet.survey;

import com.imbet.Config;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.TornadoWindField;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;

public final class TornadoTrackService {
    private TornadoTrackService() {
    }

    public static void tick(ServerLevel level) {
        if (!Config.SURVEYS_ENABLED.get()) {
            return;
        }
        int interval = Math.max(5, Config.SURVEY_TRACK_RECORD_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        TornadoSurveySavedData data = TornadoSurveySavedData.get(level);
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                    continue;
                }
                DynamicStormState state = DynamicStormController.stateFor(storm);
                if (storm.isTornadoFormingOrGreater() && !storm.attrib_waterSpout) {
                    data.recordPoint(
                            stormSourceKey(storm.ID),
                            storm.ID,
                            Long.MIN_VALUE,
                            level.getGameTime(),
                            storm.pos,
                            Math.max(8.0F, state.damagePathWidth()),
                            TornadoWindField.maxWindMph(storm),
                            StormClassifier.labelFor(storm, StormSubtype.TORNADO)
                    );
                }
                for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                    if (!satellite.touchedDown() || satellite.absorbed()) {
                        continue;
                    }
                    Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                    data.recordPoint(
                            satelliteSourceKey(storm.ID, satellite.id()),
                            storm.ID,
                            satellite.id(),
                            level.getGameTime(),
                            center,
                            Math.max(6.0F, satellite.damagePathWidth()),
                            DynamicStormController.satelliteWindMph(storm, satellite),
                            StormSubtype.SATELLITE_TORNADO.label()
                    );
                }
            }
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.type() != StormSubtype.LANDSPOUT && event.type() != StormSubtype.DUST_DEVIL) {
                continue;
            }
            data.recordPoint(
                    addonSourceKey(event.id()),
                    Long.MIN_VALUE,
                    event.id(),
                    level.getGameTime(),
                    event.position(),
                    Math.max(4.0F, event.radius() * 2.0F),
                    event.maxWindMph(),
                    event.type().label()
            );
        }
    }

    public static void recordStormMerge(ServerLevel level, long absorberStormId, long absorbedStormId) {
        if (!Config.SURVEYS_ENABLED.get()) {
            return;
        }
        TornadoSurveySavedData.get(level).recordMerge(stormSourceKey(absorberStormId), stormSourceKey(absorbedStormId));
    }

    public static void recordSatelliteMerge(ServerLevel level, long parentStormId, long satelliteId) {
        if (!Config.SURVEYS_ENABLED.get()) {
            return;
        }
        TornadoSurveySavedData.get(level).recordMerge(stormSourceKey(parentStormId), satelliteSourceKey(parentStormId, satelliteId));
    }

    public static String stormSourceKey(long stormId) {
        return "storm:" + stormId;
    }

    public static String satelliteSourceKey(long parentStormId, long satelliteId) {
        return "satellite:" + parentStormId + ":" + satelliteId;
    }

    public static String addonSourceKey(long eventId) {
        return "addon:" + eventId;
    }
}
