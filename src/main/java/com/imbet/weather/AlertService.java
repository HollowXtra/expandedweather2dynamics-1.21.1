package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

public final class AlertService {
    public static final AlertResult NONE = new AlertResult(AlertLevel.NONE, "No Alert", "NONE", "None", Vec3.ZERO, Vec3.ZERO, Long.MIN_VALUE, 0L);

    private AlertService() {
    }

    public static AlertResult highestAlert(ServerLevel level, Vec3 origin, boolean warningsOnly) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return NONE;
        }
        AlertResult best = NONE;
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            AlertResult stormAlert = alertForStorm(storm, origin);
            best = stronger(best, stormAlert, warningsOnly);
            DynamicStormState state = DynamicStormController.stateFor(storm);
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                double distance = distanceToPath(origin, center, storm.motion, Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
                if (distance <= Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
                    best = stronger(best, new AlertResult(AlertLevel.TORNADO_WARNING, "Satellite Tornado", "TOR", "OBSERVED", center, projected(center, storm.motion, Config.ALERT_WARNING_PATH_LEAD_TICKS.get()), satellite.id(), expiresAt(level)), warningsOnly);
                }
            }
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            best = stronger(best, alertForAddonEvent(level, event, origin), warningsOnly);
        }
        return best;
    }

    public static AlertResult alertForStorm(StormObject storm, Vec3 origin) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        if (state.isAbsorbedOrDecaying()) {
            return NONE;
        }
        StormSubtype subtype = StormClassifier.classify(storm, state);
        double warningDistance = distanceToPath(origin, storm.pos, storm.motion, Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
        if (warningDistance <= Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
            if (subtype == StormSubtype.TORNADO) {
                return alert(tornadoWarningLevel(storm, state), StormClassifier.labelFor(storm, subtype), storm, "OBSERVED");
            }
            if (subtype != StormSubtype.WATERSPOUT && subtype != StormSubtype.CYCLONE && isSevereThunderstorm(storm)) {
                return alert(AlertLevel.SEVERE_STORM_WARNING, StormClassifier.labelFor(storm, subtype), storm, "RADAR INDICATED");
            }
        }
        SpcRisk risk = StormClassifier.riskFor(storm);
        double currentDistance = origin.distanceTo(storm.pos);
        if (risk.severity() >= SpcRisk.SLIGHT.severity() && subtype != StormSubtype.WATERSPOUT && subtype != StormSubtype.CYCLONE && currentDistance <= Config.ALERT_WATCH_RANGE_BLOCKS.get()) {
            return alert(AlertLevel.TORNADO_WATCH, "Tornado-favorable storm", storm, "CONDITIONS FAVORABLE");
        }
        return NONE;
    }

    public static AlertResult alertForAddonEvent(ServerLevel level, AddonWeatherEvent event, Vec3 origin) {
        double warningDistance = distanceToAddonEvent(origin, event);
        if (warningDistance > Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
            return NONE;
        }
        if (event.type() != StormSubtype.SQUALL_LINE || event.maxWindMph() < 58.0F) {
            return NONE;
        }
        return new AlertResult(
                AlertLevel.SEVERE_STORM_WARNING,
                "Damaging straight-line wind",
                productCode(AlertLevel.SEVERE_STORM_WARNING),
                "RADAR INDICATED",
                event.position(),
                projected(event.position(), event.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get()),
                event.id(),
                expiresAt(level)
        );
    }

    private static AlertResult stronger(AlertResult current, AlertResult candidate, boolean warningsOnly) {
        if (warningsOnly && !candidate.level().isWarning()) {
            return current;
        }
        return candidate.level().redstonePower() > current.level().redstonePower() ? candidate : current;
    }

    private static boolean isSevereThunderstorm(StormObject storm) {
        return storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND
                || storm.levelCurIntensityStage >= StormObject.STATE_HAIL;
    }

    private static double distanceToPath(Vec3 origin, Vec3 stormPos, Vec3 motion, int leadTicks) {
        double current = origin.distanceTo(stormPos);
        if (motion == null || leadTicks <= 0 || motion.lengthSqr() < 1.0E-5D) {
            return current;
        }
        Vec3 future = stormPos.add(motion.scale(leadTicks));
        return Math.min(current, origin.distanceTo(future));
    }

    private static double distanceToAddonEvent(Vec3 origin, AddonWeatherEvent event) {
        if (event.isLine() && event.lineStart() != null && event.lineEnd() != null) {
            Vec3 closest = closestPointOnSegment(origin, event.lineStart(), event.lineEnd());
            return Math.max(0.0D, origin.distanceTo(closest) - event.radius());
        }
        return distanceToPath(origin, event.position(), event.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
    }

    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lenSqr = segment.lengthSqr();
        if (lenSqr < 1.0E-5D) {
            return start;
        }
        double t = net.minecraft.util.Mth.clamp(point.subtract(start).dot(segment) / lenSqr, 0.0D, 1.0D);
        return start.add(segment.scale(t));
    }

    private static AlertResult alert(AlertLevel level, String label, StormObject storm, String source) {
        net.minecraft.world.level.Level world = storm.manager == null ? null : storm.manager.getWorld();
        return new AlertResult(level, label, productCode(level), source, storm.pos, projected(storm.pos, storm.motion, Config.ALERT_WARNING_PATH_LEAD_TICKS.get()), storm.ID, expiresAt(world));
    }

    private static AlertLevel tornadoWarningLevel(StormObject storm, DynamicStormState state) {
        if (!Config.ALERTS_STRUCTURED_PRODUCTS.get()) {
            return AlertLevel.TORNADO_WARNING;
        }
        double wind = TornadoWindField.maxWindMph(storm);
        if (Config.ALERTS_TORNADO_EMERGENCY.get() && wind >= 190.0D && state.damagePathWidth() >= 180.0F) {
            return AlertLevel.TORNADO_EMERGENCY;
        }
        if (wind >= 150.0D || state.damagePathWidth() >= 110.0F) {
            return AlertLevel.PDS_TORNADO_WARNING;
        }
        return AlertLevel.TORNADO_WARNING;
    }

    private static Vec3 projected(Vec3 start, Vec3 motion, int leadTicks) {
        if (start == null || motion == null || leadTicks <= 0 || motion.lengthSqr() < 1.0E-5D) {
            return start == null ? Vec3.ZERO : start;
        }
        return start.add(motion.scale(leadTicks));
    }

    private static long expiresAt(net.minecraft.world.level.Level level) {
        return level == null ? 0L : level.getGameTime() + Math.max(20L * 60L, Config.ALERT_COOLDOWN_TICKS.get());
    }

    private static String productCode(AlertLevel level) {
        if (level == AlertLevel.TORNADO_WATCH) {
            return "TOR WATCH";
        }
        if (level == AlertLevel.SEVERE_THUNDERSTORM_WATCH) {
            return "SVA";
        }
        if (level == AlertLevel.TORNADO_WARNING || level == AlertLevel.PDS_TORNADO_WARNING || level == AlertLevel.TORNADO_EMERGENCY) {
            return "TOR";
        }
        if (level == AlertLevel.SEVERE_STORM_WARNING) {
            return "SVR";
        }
        return "NONE";
    }

    public record AlertResult(AlertLevel level, String label, String productCode, String source, Vec3 position, Vec3 projectedPosition, long sourceId, long expiresAt) {
    }
}
