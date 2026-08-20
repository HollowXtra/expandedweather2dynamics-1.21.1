package com.imbet.weather;

import com.imbet.Config;
import com.imbet.weather.vortex.VortexEngine;
import com.imbet.weather.vortex.VortexSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class AlertService {
    public static final AlertResult NONE = new AlertResult(AlertLevel.NONE, "No Alert", "NONE", "None", Vec3.ZERO, Vec3.ZERO, Long.MIN_VALUE, 0L);

    private AlertService() {
    }

    public static AlertResult highestAlert(ServerLevel level, Vec3 origin, boolean warningsOnly) {
        AlertResult best = NONE;
        List<VortexSnapshot> snapshots = VortexEngine.activeSnapshots(level);
        for (VortexSnapshot snapshot : snapshots) {
            if (!snapshot.warningCapable()) {
                continue;
            }
            Vec3 position = snapshot.position();
            double distance = distanceToPath(origin, position, snapshot.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
            if (distance <= Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
                AlertLevel alertLevel = tornadoWarningLevel(snapshot);
                String label = snapshot.mode().subtype().label();
                if (snapshot.antiCyclonic()) {
                    label = "Anti-Cyclonic " + label;
                }
                best = stronger(best, new AlertResult(alertLevel, label, productCode(alertLevel), "OBSERVED", position, projected(position, snapshot.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get()), snapshot.id(), expiresAt(level)), warningsOnly);
            }
        }
        return best;
    }

    private static AlertResult stronger(AlertResult current, AlertResult candidate, boolean warningsOnly) {
        if (warningsOnly && !candidate.level().isWarning()) {
            return current;
        }
        return candidate.level().redstonePower() > current.level().redstonePower() ? candidate : current;
    }

    private static double distanceToPath(Vec3 origin, Vec3 stormPos, Vec3 motion, int leadTicks) {
        double current = origin.distanceTo(stormPos);
        if (motion == null || leadTicks <= 0 || motion.lengthSqr() < 1.0E-5D) {
            return current;
        }
        Vec3 future = stormPos.add(motion.scale(leadTicks));
        return Math.min(current, origin.distanceTo(future));
    }

    private static Vec3 projected(Vec3 start, Vec3 motion, int leadTicks) {
        if (start == null || motion == null || leadTicks <= 0 || motion.lengthSqr() < 1.0E-5D) {
            return start == null ? Vec3.ZERO : start;
        }
        return start.add(motion.scale(leadTicks));
    }

    private static AlertLevel tornadoWarningLevel(VortexSnapshot snapshot) {
        if (!Config.ALERTS_STRUCTURED_PRODUCTS.get()) {
            return AlertLevel.TORNADO_WARNING;
        }
        if (Config.ALERTS_TORNADO_EMERGENCY.get() && snapshot.maxWindMph() >= 190.0F && snapshot.damagePathWidth() >= 180.0F) {
            return AlertLevel.TORNADO_EMERGENCY;
        }
        if (snapshot.fire() || snapshot.maxWindMph() >= 150.0F || snapshot.damagePathWidth() >= 110.0F) {
            return AlertLevel.PDS_TORNADO_WARNING;
        }
        return AlertLevel.TORNADO_WARNING;
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
