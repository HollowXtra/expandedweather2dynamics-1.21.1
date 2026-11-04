package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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
        Vec3 direction = steeringDirectionFrom(globalWind, storm.motion, gameTime, storm.ID, eneBias, variability);
        // Real-track behaviour: deviant veer, the slow asymmetric arc and the multi-vortex scallop.
        // All three are angles, so they compose with whatever meander the flow already produced.
        double trackOffset = TornadoTrackModel.headingOffsetDegrees(storm, gameTime);
        return trackOffset == 0.0D ? direction : rotateHorizontal(direction, trackOffset);
    }

    public static Vec3 steeringVector(StormObject storm) {
        if (!shouldSteer(storm)) {
            return storm == null || storm.motion == null ? Vec3.ZERO : storm.motion;
        }
        long gameTime = storm.manager.getWorld().getGameTime();
        double windMagnitude = globalWindVector(storm).horizontalDistance();
        double targetSpeed = windMagnitude * Config.ATMOSPHERIC_STEERING_COEFFICIENT.get();
        if (TornadoTrackModel.appliesTo(storm)) {
            // The surge goes in BEFORE the miles-per-hour clamp on purpose: a tornado may crawl or lunge,
            // but it may never leave the honest 6-34 mph band real tornadoes travel at. A storm already at
            // the ceiling can then only ease back, which is exactly what a fast mover does.
            targetSpeed *= TornadoTrackModel.speedSurge(storm, gameTime);
            // Honest miles-per-hour translation for tornadoes: the legacy block-per-tick clamp is
            // deliberately bypassed, since its 0.54 ceiling is only ~24 mph.
            targetSpeed = TornadoTrackModel.targetSpeedBlocksPerTick(storm, targetSpeed);
        } else {
            // Non-tornadic storms keep the old global pulse; tornadoes get the richer surge above, so the
            // two are deliberately exclusive rather than stacking into double the speed variation.
            targetSpeed *= speedPulse(gameTime, storm.ID, isTropicalSteeringStorm(storm));
            targetSpeed = Mth.clamp(targetSpeed, Config.ATMOSPHERIC_STEERING_MIN_SPEED.get(), Config.ATMOSPHERIC_STEERING_MAX_SPEED.get());
        }
        Vec3 flow = steeringDirection(storm).scale(targetSpeed);
        // The belts are part of the flow rather than a second system on top of it: air converges on the
        // rising belt and on the genesis corridor that feeds off it, and diverges over the sinking
        // ridge, so a storm drifting through either carries that component too. Bounded, so it biases a
        // track and never drives one.
        return flow.add(ThermalCirculation.steeringBias(storm.pos.z));
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
        if (storm == null || previousPos == null || storm.manager == null || storm.manager.getWorld() == null) {
            return;
        }
        if (storm.manager.getWorld().isClientSide()) {
            applyClientPositionInterpolation(storm);
            return;
        }
        if (!shouldSteer(storm)) {
            return;
        }
        Vec3 steering = steeringVector(storm);
        if (steering.horizontalDistanceSqr() <= 1.0E-8D) {
            return;
        }
        Vec3 smoothed = smoothMotion(storm.motion == null ? Vec3.ZERO : storm.motion, steering, isTropicalSteeringStorm(storm));
        if (TornadoTrackModel.appliesTo(storm)) {
            // Cap the curvature so the track can only bend, never kink. A merge orbit is already
            // steering the storm deliberately, so it is allowed to curve freely.
            DynamicStormState state = DynamicStormController.stateFor(storm);
            long gameTime = storm.manager.getWorld().getGameTime();
            if (!state.mergeOrbitActive(gameTime)) {
                boolean newborn = storm.levelCurIntensityStage <= StormObject.STATE_FORMING;
                smoothed = TornadoTrackModel.limitTurn(storm.motion, smoothed,
                        TornadoTrackModel.maxTurnDegreesPerTick(storm), newborn);
            }
        }
        storm.motion = smoothed;
        storm.pos = previousPos.add(smoothed);
        if (storm.posGround != null) {
            storm.posGround = new Vec3(storm.pos.x, storm.posGround.y, storm.pos.z);
        }
    }

    // ------------------------------------------------------------------
    // Client-side position smoothing between server NBT syncs.
    //
    // Weather2 client storms only receive a new position when an NBT sync
    // packet arrives (every ~20 ticks). Steering those storms locally is
    // wrong: the client's wind data is stale (synced every 60 ticks), so a
    // locally steered path diverges from the server and the whole tornado
    // (funnel, deck, dust, satellites) snaps back to the server position at
    // every sync - the jitter. Instead the client lerps the storm position
    // from the previous synced position to the newest one over the sync
    // interval, so the funnel glides smoothly and meets the next synced
    // position exactly when it arrives.
    // ------------------------------------------------------------------

    private record ClientSyncState(Vec3 prevPos, Vec3 targetPos, long startTime, long interval) {
    }

    private static final Map<Long, ClientSyncState> CLIENT_SYNC = new HashMap<>();
    private static final long DEFAULT_SYNC_INTERVAL = 20L;

    /** Called at the HEAD of StormObject.read() on the client, before the synced NBT is applied. */
    public static void onClientSyncStart(StormObject storm, long gameTime, long lastSyncGameTime) {
        if (storm == null || storm.pos == null) {
            return;
        }
        long interval = lastSyncGameTime == Long.MIN_VALUE ? DEFAULT_SYNC_INTERVAL : Math.max(1L, gameTime - lastSyncGameTime);
        CLIENT_SYNC.put(storm.ID, new ClientSyncState(storm.pos, storm.pos, gameTime, interval));
    }

    /** Called at the RETURN of StormObject.read() on the client, after the synced NBT applied. */
    public static void onClientSyncComplete(StormObject storm, long gameTime) {
        if (storm == null || storm.pos == null) {
            return;
        }
        ClientSyncState state = CLIENT_SYNC.get(storm.ID);
        if (state == null) {
            return;
        }
        Vec3 prev = state.prevPos();
        Vec3 target = storm.pos;
        // Fresh storm (client storms start at 0,0,0): snap directly instead of
        // dragging the funnel across the map from the origin.
        if (prev.distanceToSqr(Vec3.ZERO) < 1.0D && target.distanceToSqr(prev) > 64.0D) {
            CLIENT_SYNC.put(storm.ID, new ClientSyncState(target, target, gameTime, state.interval()));
            return;
        }
        CLIENT_SYNC.put(storm.ID, new ClientSyncState(prev, target, gameTime, state.interval()));
    }

    public static void applyClientPositionInterpolation(StormObject storm) {
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null || !storm.manager.getWorld().isClientSide()) {
            return;
        }
        // Weather2 dead-reckons weather-machine storms itself in tickMovementClient.
        if (storm.weatherMachineControlled) {
            CLIENT_SYNC.remove(storm.ID);
            return;
        }
        ClientSyncState state = CLIENT_SYNC.get(storm.ID);
        if (state == null || state.prevPos() == null || state.targetPos() == null) {
            return;
        }
        long now = storm.manager.getWorld().getGameTime();
        if (now - state.startTime() > 600L) {
            CLIENT_SYNC.remove(storm.ID);
            return;
        }
        float t = Mth.clamp((now - state.startTime()) / (float) state.interval(), 0.0F, 1.0F);
        Vec3 interpolated = state.prevPos().lerp(state.targetPos(), t);
        storm.pos = interpolated;
        if (storm.posGround != null) {
            storm.posGround = new Vec3(interpolated.x, storm.posGround.y, interpolated.z);
        }
        if (t >= 1.0F) {
            // Arrived at the synced target; hold until the next sync refreshes it.
            CLIENT_SYNC.put(storm.ID, new ClientSyncState(state.targetPos(), state.targetPos(), now, state.interval()));
        }
    }

    public static void clearClientSyncState() {
        Iterator<Map.Entry<Long, ClientSyncState>> iterator = CLIENT_SYNC.entrySet().iterator();
        while (iterator.hasNext()) {
            iterator.remove();
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

    /**
     * A tropical system is steered like a tropical system for its whole life, not only once it is a
     * hurricane: a depression already drifts on the deep-layer flow, and this is what tells the steering
     * to smooth it hard and pulse it slowly instead of letting a developing water storm wobble like a
     * supercell.
     */
    private static boolean isTropicalSteeringStorm(StormObject storm) {
        return storm != null
                && (DynamicStormController.isNativeTropicalSystem(storm)
                || AddonWeatherEventService.isHurricaneHelperStorm(storm));
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
