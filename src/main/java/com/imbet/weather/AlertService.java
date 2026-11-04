package com.imbet.weather;

import com.imbet.Config;
import com.imbet.spotter.SpotterService;
import com.imbet.volcano.VolcanoService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class AlertService {
    public static final AlertResult NONE = new AlertResult(AlertLevel.NONE, "No Alert", "NONE", "None", Vec3.ZERO, Vec3.ZERO, Long.MIN_VALUE, 0L);

    private AlertService() {
    }

    /**
     * The strongest product in force at a position: the one product a siren, a radio or a banner shows.
     *
     * <p>It is a fold over {@link #collect}, so the single strongest alert and the full list of them are one
     * walk of the same sources rather than two that could disagree about what is in force.
     */
    public static AlertResult highestAlert(ServerLevel level, Vec3 origin, boolean warningsOnly) {
        AlertResult[] best = { NONE };
        collect(level, origin, candidate -> {
            if (warningsOnly && !candidate.level().isWarning()) {
                return;
            }
            if (candidate.level().redstonePower() > best[0].level().redstonePower()) {
                best[0] = candidate;
            }
        });
        return best[0];
    }

    /**
     * Every distinct product in force at a position, strongest first.
     *
     * <p>This is what a weather computer's warnings tab lists, and the reason it exists separately from
     * {@link #highestAlert}: a station that can only name one product is a station that hides a tornado warning
     * behind a severe thunderstorm warning for the same ground, which is exactly the thing a list of warnings is
     * supposed to prevent. Products are made distinct by code, source and label, so a storm warned about twice in
     * the same refresh appears once.
     */
    public static List<AlertResult> activeAlerts(ServerLevel level, Vec3 origin) {
        Map<String, AlertResult> distinct = new LinkedHashMap<>();
        collect(level, origin, alert -> distinct.putIfAbsent(
                alert.productCode() + "|" + alert.source() + "|" + alert.label(), alert));
        List<AlertResult> alerts = new ArrayList<>(distinct.values());
        alerts.sort(Comparator.comparingInt((AlertResult alert) -> alert.level().redstonePower()).reversed());
        return List.copyOf(alerts);
    }

    /**
     * Every source of alerts, offered to a sink one at a time.
     *
     * <p>The sources and their order are the ones this class has always used: storms and their satellites, the
     * addon events, an eruption, the day's outbreak, and a spotter's report last so that anything the mod
     * observed itself keeps its own product. Handing each candidate to a caller rather than ranking them here is
     * what lets one walk answer both "what is the strongest" and "what is all of it".
     */
    private static void collect(ServerLevel level, Vec3 origin, Consumer<AlertResult> sink) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            sink.accept(alertForStorm(storm, origin));
            DynamicStormState state = DynamicStormController.stateFor(storm);
            // Read once for the whole storm: the lead a warning is projected down the path with is the same
            // knob for the distance it is measured at and for the polygon that measurement goes into.
            int pathLeadTicks = Config.ALERT_WARNING_PATH_LEAD_TICKS.get();
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                double distance = distanceToPath(origin, center, storm.motion, pathLeadTicks);
                if (distance <= Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
                    sink.accept(new AlertResult(AlertLevel.TORNADO_WARNING, "Satellite Tornado", "TOR", "OBSERVED", center, projected(center, storm.motion, pathLeadTicks), satellite.id(), expiresAt(level)));
                }
            }
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            sink.accept(alertForAddonEvent(level, event, origin));
        }
        // An eruption is ranked alongside the storms rather than as a separate authority. Everything a siren
        // station, a radio or the banner already does - pick the strongest product in range, sound it, drive
        // redstone - then applies to a volcano without a second system, which is the point of a common ladder.
        sink.accept(VolcanoService.alertFor(level, origin));
        // An outbreak is ranked alongside the storms rather than above them. It is a product about the
        // day over this ground rather than about something with a position, so it never outranks a real
        // tornado warning - and it travels through the same ladder, which is what keeps the rule that a
        // player only ever sees an alert they have a station or a radio to hear.
        sink.accept(OutbreakService.alertFor(level, origin));
        // A spotter's report is folded in last and on purpose: it is a tornado warning and nothing more, so
        // anything the mod observed itself - including a PDS or an emergency, which share the same redstone
        // strength - keeps its own product. Reports fill the gap between what the scan knows and what a person
        // can see; they never speak over it.
        sink.accept(SpotterService.alertFor(level, origin));
    }

    /**
     * The position of the closest storm worth pointing an instrument at, or null when there is nothing
     * within {@code range}.
     *
     * <p>This is deliberately not the same question as {@link #highestAlert}: an alert is ranked by how
     * dangerous a storm is to the siren's own position, while a dish has to point at something physical,
     * so proximity is what decides. Absorbed and decaying circulations are skipped for the same reason
     * they are skipped everywhere else - they are not storms any more.
     */
    public static Vec3 nearestStormPosition(ServerLevel level, Vec3 origin, double range) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null || origin == null || !(range > 0.0D)) {
            return null;
        }
        Vec3 closest = null;
        double closestDistance = range;
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            double distance = origin.distanceTo(storm.pos);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = storm.pos;
            }
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            double distance = distanceToAddonEvent(origin, event);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = event.position();
            }
        }
        return closest;
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
            if (subtype == StormSubtype.HURRICANE || subtype == StormSubtype.CYCLONE) {
                // A tropical system gets its own product, named and categorised the way 1.12.2 named
                // them: "Hurricane Karl - Category 3". Hurricane strength makes it a hurricane warning;
                // a depression or tropical storm is still a severe-weather product, not a thunderstorm.
                AlertLevel level = isHurricaneWarningStrength(storm)
                        ? AlertLevel.HURRICANE_WARNING
                        : AlertLevel.SEVERE_STORM_WARNING;
                return alert(level, StormClassifier.labelFor(storm, subtype), storm, "RADAR INDICATED");
            }
            if (subtype != StormSubtype.WATERSPOUT && isSevereThunderstorm(storm)) {
                return alert(AlertLevel.SEVERE_STORM_WARNING, StormClassifier.labelFor(storm, subtype), storm, "RADAR INDICATED");
            }
        }
        SpcRisk risk = StormClassifier.riskFor(storm);
        double currentDistance = origin.distanceTo(storm.pos);
        // A tropical system is not tornado-favorable air: a watch product would be describing the wrong
        // storm, and its own ladder already warns for it.
        if (risk.severity() >= SpcRisk.SLIGHT.severity()
                && subtype != StormSubtype.WATERSPOUT
                && subtype != StormSubtype.CYCLONE
                && subtype != StormSubtype.HURRICANE
                && currentDistance <= Config.ALERT_WATCH_RANGE_BLOCKS.get()) {
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
        // A line the classification has named is warned about by name and rung, so a player reading the
        // banner, the siren's product code or the alert log all see the same thing the damage swath does.
        DerechoModel.Verdict verdict = DerechoService.verdictOf(event);
        if (verdict.derecho()) {
            return new AlertResult(
                    AlertLevel.DERECHO_WARNING,
                    verdict.label(),
                    productCode(AlertLevel.DERECHO_WARNING),
                    "RADAR INDICATED",
                    event.position(),
                    projected(event.position(), event.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get()),
                    event.id(),
                    expiresAt(level)
            );
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

    /** Whether a tropical system has earned hurricane strength, by wind or by rung. */
    private static boolean isHurricaneWarningStrength(StormObject storm) {
        return TropicalCyclone.isHurricane(storm.levelCurIntensityStage)
                || TropicalCyclone.categoryForWind(TornadoWindField.maxWindMph(storm)) > 0;
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
        if (level.isVolcanic()) {
            return level.productCode();
        }
        return "NONE";
    }

    public record AlertResult(AlertLevel level, String label, String productCode, String source, Vec3 position, Vec3 projectedPosition, long sourceId, long expiresAt) {
    }
}
