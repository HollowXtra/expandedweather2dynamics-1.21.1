package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;

public final class WindSteeringManager {
    private static final Vec3 ENE_DIRECTION = new Vec3(0.9238795325D, 0.0D, -0.3826834324D);
    private static final double MIN_STEERING_VARIABILITY = 0.18D;
    private static final double TROPICAL_STEERING_VARIABILITY = 0.46D;

    private WindSteeringManager() {
    }

    public static boolean shouldSteer(StormObject storm) {
        return Config.ATMOSPHERIC_STEERING_ENABLED.get()
                && storm != null
                && storm.manager != null
                && storm.manager.getWorld() != null
                && storm.manager.getWindManager() != null
                && storm.pos != null
                && storm.isRealStorm()
                && (!isControlledStorm(storm) || DynamicStormController.isDynamicVortex(storm));
    }

    public static Vec3 globalWindVector(StormObject storm) {
        if (storm == null || storm.manager == null || storm.manager.getWindManager() == null) {
            return Vec3.ZERO;
        }
        return globalWindVector(storm.manager.getWindManager().getWindSpeedForClouds(), storm.manager.getWindManager().getWindAngleForClouds());
    }

    public static Vec3 globalWindVector(Level level) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return Vec3.ZERO;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(serverLevel);
        if (manager == null || manager.getWindManager() == null) {
            return Vec3.ZERO;
        }
        return globalWindVector(manager.getWindManager().getWindSpeedForClouds(), manager.getWindManager().getWindAngleForClouds());
    }

    private static Vec3 globalWindVector(double speedValue, double angleDegrees) {
        double speed = Math.max(0.0D, speedValue);
        double angle = Math.toRadians(angleDegrees);
        double windX = -Math.sin(angle) * speed;
        double windZ = Math.cos(angle) * speed;
        return new Vec3(windX, 0.0D, windZ);
    }

    public static Vec3 steeringDirection(StormObject storm) {
        Vec3 globalWind = globalWindVector(storm);
        double eneBias = Mth.clamp(Config.ATMOSPHERIC_STEERING_ENE_BIAS_STRENGTH.get(), 0.0D, 1.0D);
        double variability = steeringVariability(storm);
        if (isTropicalSteeringStorm(storm)) {
            eneBias *= 0.35D;
            variability = Math.max(variability, TROPICAL_STEERING_VARIABILITY);
        }
        long gameTime = storm.manager.getWorld().getGameTime();
        return steeringDirectionFrom(globalWind, storm.motion, gameTime, storm.ID, eneBias, variability);
    }

    public static Vec3 steeringVector(StormObject storm) {
        if (!shouldSteer(storm)) {
            return storm == null || storm.motion == null ? Vec3.ZERO : storm.motion;
        }
        double windMagnitude = globalWindVector(storm).horizontalDistance();
        double targetSpeed = windMagnitude * Config.ATMOSPHERIC_STEERING_COEFFICIENT.get();
        targetSpeed *= speedPulse(storm.manager.getWorld().getGameTime(), storm.ID, isTropicalSteeringStorm(storm));
        double weakness = tornadoWeakness(storm);
        if (weakness > 0.0D) {
            // Weak and rope tornadoes surge forward and nearly stall; violent tornadoes hold a steadier pace.
            long gameTime = storm.manager.getWorld().getGameTime();
            double erratic = weakness * (0.22D * Math.sin(gameTime * 0.0039D + storm.ID * 0.53D)
                    + 0.14D * Math.sin(gameTime * 0.00052D + storm.ID * 0.37D));
            targetSpeed *= Mth.clamp(1.0D + erratic, 0.45D, 1.55D);
        }
        targetSpeed = Mth.clamp(targetSpeed, Config.ATMOSPHERIC_STEERING_MIN_SPEED.get(), Config.ATMOSPHERIC_STEERING_MAX_SPEED.get());
        return steeringDirection(storm).scale(targetSpeed);
    }

    public static Vec3 addonSteeringVector(Level level, AddonWeatherEvent event) {
        if (!Config.ATMOSPHERIC_STEERING_ENABLED.get() || level == null || event == null) {
            return event == null || event.motion() == null ? Vec3.ZERO : event.motion();
        }
        Vec3 current = event.motion() == null ? Vec3.ZERO : event.motion();
        Vec3 wind = globalWindVector(level);
        long gameTime = level.getGameTime();
        boolean tropical = event.type() == StormSubtype.HURRICANE;
        double eneBias = Mth.clamp(Config.ATMOSPHERIC_STEERING_ENE_BIAS_STRENGTH.get(), 0.0D, 1.0D) * (tropical ? 0.30D : 0.75D);
        double variability = Math.max(steeringVariability(null), tropical ? 0.62D : MIN_STEERING_VARIABILITY);
        Vec3 direction = steeringDirectionFrom(wind, current, gameTime, event.id(), eneBias, variability);
        double speed = wind.horizontalDistance() * Config.ATMOSPHERIC_STEERING_COEFFICIENT.get() * (tropical ? 0.18D : 0.55D);
        speed *= speedPulse(gameTime, event.id(), tropical);
        if (tropical) {
            speed = Mth.clamp(speed, 0.006D, 0.048D);
        } else {
            speed = Mth.clamp(speed, Config.ATMOSPHERIC_STEERING_MIN_SPEED.get(), Config.ATMOSPHERIC_STEERING_MAX_SPEED.get());
        }
        return smoothMotion(current, direction.scale(speed), tropical);
    }

    public static float steeringAngleDegrees(StormObject storm, float fallbackAngle) {
        if (!shouldSteer(storm)) {
            return fallbackAngle;
        }
        return angleFromVector(steeringDirection(storm), fallbackAngle);
    }

    public static float steeringSpeed(StormObject storm, float fallbackSpeed) {
        if (!shouldSteer(storm)) {
            return fallbackSpeed;
        }
        return (float) steeringVector(storm).horizontalDistance();
    }

    public static void applyPositionCorrection(StormObject storm, Vec3 previousPos) {
        if (!shouldSteer(storm) || previousPos == null) {
            return;
        }
        Vec3 steering = steeringVector(storm);
        if (steering.horizontalDistanceSqr() <= 1.0E-8D) {
            return;
        }
        Vec3 smoothed = smoothMotion(storm.motion == null ? Vec3.ZERO : storm.motion, steering, isTropicalSteeringStorm(storm));
        storm.motion = smoothed;
        storm.pos = previousPos.add(smoothed);
        if (storm.posGround != null) {
            storm.posGround = new Vec3(storm.pos.x, storm.posGround.y, storm.pos.z);
        }
    }

    private static Vec3 steeringDirectionFrom(Vec3 globalWind, Vec3 fallbackMotion, long gameTime, long seed, double eneBias, double variability) {
        Vec3 fallback = fallbackMotion != null && fallbackMotion.horizontalDistanceSqr() > 1.0E-7D
                ? new Vec3(fallbackMotion.x, 0.0D, fallbackMotion.z).normalize()
                : ENE_DIRECTION;
        Vec3 base = globalWind != null && globalWind.horizontalDistanceSqr() > 1.0E-7D ? globalWind.normalize() : fallback;
        Vec3 rotated = rotateHorizontal(base, Config.ATMOSPHERIC_STEERING_RIGHT_BIAS_DEGREES.get());
        Vec3 blended = rotated.scale(1.0D - eneBias).add(ENE_DIRECTION.scale(eneBias));
        if (blended.horizontalDistanceSqr() <= 1.0E-7D) {
            blended = rotated;
        }
        return applySteeringMeander(blended.normalize(), gameTime, seed, variability);
    }

    private static Vec3 applySteeringMeander(Vec3 direction, long gameTime, long seed, double variability) {
        double amount = Math.max(MIN_STEERING_VARIABILITY, Mth.clamp(variability, 0.0D, 1.5D));
        double phase = seed * 0.173D;
        double slow = Math.sin(gameTime * 0.00135D + phase) * 0.50D;
        double medium = Math.sin(gameTime * 0.00310D + phase * 2.17D) * 0.28D;
        double pulse = Math.sin(gameTime * 0.00048D + phase * 0.41D) * 0.12D;
        // Very long-period track curvature: paths hook and curve with the parent storm over tens of minutes.
        double curve = Math.sin(gameTime * 0.00012D + phase * 0.73D) * 0.30D;
        double maxDegrees = Mth.clamp(46.0D * amount, 4.0D, 72.0D);
        return rotateHorizontal(direction, (slow + medium + pulse + curve) * maxDegrees).normalize();
    }

    private static Vec3 rotateHorizontal(Vec3 vector, double degrees) {
        Vec3 base = vector == null || vector.horizontalDistanceSqr() <= 1.0E-7D ? ENE_DIRECTION : new Vec3(vector.x, 0.0D, vector.z).normalize();
        double theta = Math.toRadians(degrees);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double steeringX = (base.x * cos) - (base.z * sin);
        double steeringZ = (base.x * sin) + (base.z * cos);
        Vec3 rotated = new Vec3(steeringX, 0.0D, steeringZ);
        return rotated.horizontalDistanceSqr() <= 1.0E-7D ? ENE_DIRECTION : rotated.normalize();
    }

    private static Vec3 smoothMotion(Vec3 current, Vec3 target, boolean tropical) {
        if (target == null || target.horizontalDistanceSqr() <= 1.0E-10D) {
            return current == null ? Vec3.ZERO : current;
        }
        Vec3 existing = current == null ? Vec3.ZERO : new Vec3(current.x, 0.0D, current.z);
        if (existing.horizontalDistanceSqr() <= 1.0E-8D) {
            return target;
        }
        double response = tropical ? 0.045D : 0.16D;
        Vec3 desired = existing.lerp(target, response);
        Vec3 delta = desired.subtract(existing);
        double deltaLength = delta.horizontalDistance();
        double maxDelta = tropical ? 0.0045D : 0.035D;
        if (deltaLength > maxDelta) {
            delta = delta.normalize().scale(maxDelta);
            desired = existing.add(delta);
        }
        return new Vec3(desired.x, current == null ? 0.0D : current.y, desired.z);
    }

    private static double steeringVariability(StormObject storm) {
        double base = Mth.clamp(Config.ATMOSPHERIC_STEERING_WOBBLE_FRACTION.get(), 0.0D, 1.5D);
        if (storm == null || isTropicalSteeringStorm(storm)) {
            return Math.max(MIN_STEERING_VARIABILITY, base);
        }
        double weakness = tornadoWeakness(storm);
        if (weakness <= 0.0D) {
            return Math.max(MIN_STEERING_VARIABILITY, base);
        }
        // Weak and rope tornadoes dance harder around the steering flow; violent wedges track nearly straight.
        return Math.max(MIN_STEERING_VARIABILITY, Mth.clamp(base * (0.38D + 1.15D * weakness), 0.0D, 1.5D));
    }

    private static double tornadoWeakness(StormObject storm) {
        if (storm == null || storm.attrib_waterSpout || !DynamicStormController.isDynamicVortex(storm) || !storm.isTornadoFormingOrGreater()) {
            return 0.0D;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        double width = DynamicStormController.stateFor(storm).damagePathWidth();
        double stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0F, 0.0F, 1.0F);
        double strength = Mth.clamp(0.18D + maxWind / 320.0D + width / 420.0D + stage * 0.15D, 0.05D, 1.0D);
        return 1.0D - strength;
    }

    private static double speedPulse(long gameTime, long seed, boolean tropical) {
        double phase = seed * 0.291D;
        double magnitude = tropical ? 0.28D : 0.16D;
        return Mth.clamp(1.0D + Math.sin(gameTime * (tropical ? 0.00095D : 0.0024D) + phase) * magnitude, 0.60D, 1.38D);
    }

    private static boolean isTropicalSteeringStorm(StormObject storm) {
        return storm != null && (DynamicStormController.isNativeTropicalCyclone(storm) || AddonWeatherEventService.isHurricaneHelperStorm(storm));
    }

    private static boolean isControlledStorm(StormObject storm) {
        return storm != null && (storm.weatherMachineControlled || storm.isPlayerControlled() || storm.isPet());
    }

    private static float angleFromVector(Vec3 vector, float fallbackAngle) {
        if (vector == null || vector.horizontalDistanceSqr() <= 1.0E-7D) {
            return fallbackAngle;
        }
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-vector.x, vector.z)));
    }
}
