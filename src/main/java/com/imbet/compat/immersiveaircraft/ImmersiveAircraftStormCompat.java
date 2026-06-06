package com.imbet.compat.immersiveaircraft;

import com.imbet.Config;
import com.imbet.weather.CompatHooks;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ImmersiveAircraftStormCompat {
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.022352D;
    private static Class<?> aircraftEntityClass;
    private static boolean classResolved;

    private ImmersiveAircraftStormCompat() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide() || !Config.DAMAGE_ENABLED.get() || !CompatHooks.immersiveAircraftLoaded()) {
            return;
        }
        Class<?> aircraftClass = aircraftEntityClass();
        if (aircraftClass == null) {
            return;
        }
        long gameTime = level.getGameTime();
        int interval = Math.max(1, Config.IMMERSIVE_VEHICLES_SCAN_INTERVAL_TICKS.get());
        if (gameTime % interval != 0L) {
            return;
        }

        int processed = 0;
        int maxAircraft = Math.max(0, Config.IMMERSIVE_VEHICLES_MAX_VEHICLES_PER_TICK.get());
        for (Entity aircraft : scanAircraft(level, aircraftClass)) {
            applyStormWind(level, aircraft);
            if (++processed >= maxAircraft) {
                break;
            }
        }
    }

    public static boolean isAircraftEntity(Entity entity) {
        Class<?> aircraftClass = aircraftEntityClass();
        return entity != null && aircraftClass != null && aircraftClass.isInstance(entity);
    }

    private static List<Entity> scanAircraft(ServerLevel level, Class<?> aircraftClass) {
        double range = Math.max(32.0D, Config.IMMERSIVE_VEHICLES_SCAN_RANGE_BLOCKS.get());
        List<Entity> aircraft = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Player player : level.players()) {
            AABB box = new AABB(player.getX() - range, player.getY() - range * 0.45D, player.getZ() - range,
                    player.getX() + range, player.getY() + range * 0.45D, player.getZ() + range);
            for (Entity entity : level.getEntities((Entity) null, box, candidate -> candidate.isAlive() && aircraftClass.isInstance(candidate))) {
                if (seen.add(entity.getId())) {
                    aircraft.add(entity);
                }
            }
        }
        return aircraft;
    }

    private static void applyStormWind(ServerLevel level, Entity aircraft) {
        if (aircraft == null || !aircraft.isAlive()) {
            return;
        }
        StormInfluence influence = strongestStormWind(level, aircraft.position());
        double windMph = Math.max(influence.wind.speedMph(), influence.wind.strongestLocalMph());
        if (windMph < 12.0D || influence.wind.mphVector().lengthSqr() < 1.0E-4D) {
            return;
        }

        double maxDelta = Math.max(0.08D, Config.IMMERSIVE_VEHICLES_MAX_MOTION_DELTA_PER_TICK.get() * 1.15D);
        double severity = clamp(windMph / Math.max(1.0D, Config.WINDFIELD_STAGE5_MPH.get()), 0.0D, 1.35D);
        Vec3 windBlocksPerTick = safeVector(influence.wind.mphVector()).scale(MPH_TO_BLOCKS_PER_TICK);
        Vec3 relativeAir = windBlocksPerTick.subtract(aircraft.getDeltaMovement());
        double airborneFactor = aircraft.onGround() ? 0.72D : 1.0D;
        Vec3 force = capVector(relativeAir.scale(Config.IMMERSIVE_VEHICLES_WIND_FORCE_SCALE.get() * (0.10D + severity * 0.18D) * airborneFactor), maxDelta);

        if (influence.tornadic && influence.wind.distanceFromCore() <= influence.suctionRadius) {
            force = force.add(tornadoSuctionForce(influence, aircraft.position(), severity, maxDelta, aircraft.onGround()));
        }
        if (force.lengthSqr() <= 1.0E-8D) {
            return;
        }

        aircraft.setDeltaMovement(aircraft.getDeltaMovement().add(force));
        aircraft.hasImpulse = true;
        aircraft.hurtMarked = true;
        applyAttitudeKick(aircraft, influence, windMph);
    }

    private static StormInfluence strongestStormWind(ServerLevel level, Vec3 position) {
        StormInfluence best = StormInfluence.CALM;
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return best;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null) {
                continue;
            }
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (state.isAbsorbedOrDecaying()) {
                continue;
            }
            boolean tornadic = DynamicStormController.isDynamicVortex(storm);
            TornadoWindField.WindSample sample = tornadic
                    ? TornadoWindField.sample(storm, level, position)
                    : SupercellStructureService.sampleWind(storm, level, position);
            Vec3 center = new Vec3(storm.pos.x, position.y, storm.pos.z);
            double suctionRadius = Math.max(Math.max(sample.baseRadius() * 1.65D, sample.radiusMaxWind() * 4.2D), 18.0D);
            best = best.stronger(new StormInfluence(sample, center, tornadic, suctionRadius));
        }
        return best;
    }

    private static Vec3 tornadoSuctionForce(StormInfluence influence, Vec3 aircraftPos, double severity, double maxDelta, boolean onGround) {
        Vec3 fromCenter = new Vec3(aircraftPos.x - influence.center.x, 0.0D, aircraftPos.z - influence.center.z);
        double distance = Math.max(0.5D, fromCenter.length());
        Vec3 radialOut = fromCenter.scale(1.0D / distance);
        Vec3 inward = radialOut.scale(-1.0D);
        Vec3 orbital = horizontal(influence.wind.mphVector());
        orbital = orbital.lengthSqr() < 1.0E-5D ? new Vec3(-radialOut.z, 0.0D, radialOut.x) : orbital.normalize();
        double close = clamp(1.0D - influence.wind.distanceFromCore() / Math.max(1.0D, influence.suctionRadius), 0.0D, 1.0D);
        double groundLift = onGround ? 0.55D : 1.0D;
        Vec3 pull = inward.scale(maxDelta * 0.24D * close * severity);
        Vec3 spin = orbital.scale(maxDelta * 0.48D * close * severity);
        Vec3 lift = new Vec3(0.0D, Config.IMMERSIVE_VEHICLES_LIFT_FORCE_SCALE.get() * maxDelta * close * severity * groundLift, 0.0D);
        return capVector(pull.add(spin).add(lift), maxDelta * 1.35D);
    }

    private static void applyAttitudeKick(Entity aircraft, StormInfluence influence, double windMph) {
        Vec3 horizontalWind = horizontal(influence.wind.mphVector());
        if (horizontalWind.lengthSqr() < 1.0E-4D) {
            return;
        }
        double severity = clamp(windMph / Math.max(1.0D, Config.WINDFIELD_STAGE5_MPH.get()), 0.0D, 1.25D);
        double yawRad = Math.toRadians(aircraft.getYRot());
        Vec3 right = new Vec3(Math.cos(yawRad), 0.0D, -Math.sin(yawRad));
        double sideLoad = horizontalWind.normalize().dot(right);
        float yawKick = (float) clamp(sideLoad * severity * 3.2D, -5.5D, 5.5D);
        aircraft.setYRot(aircraft.getYRot() + yawKick);
        if (!aircraft.onGround()) {
            float pitchKick = (float) clamp(-influence.wind.mphVector().y * 0.008D * severity, -3.5D, 3.5D);
            aircraft.setXRot(Mth.clamp(aircraft.getXRot() + pitchKick, -85.0F, 85.0F));
        }
    }

    private static Class<?> aircraftEntityClass() {
        if (classResolved) {
            return aircraftEntityClass;
        }
        classResolved = true;
        aircraftEntityClass = firstClass("immersive_aircraft.entity.AircraftEntity", "immersive_aircraft.entity.VehicleEntity");
        return aircraftEntityClass;
    }

    private static Class<?> firstClass(String... classNames) {
        for (String className : classNames) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ignored) {
                // Optional compat only.
            }
        }
        return null;
    }

    private static Vec3 safeVector(Vec3 vector) {
        if (vector == null || !Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            return Vec3.ZERO;
        }
        return vector;
    }

    private static Vec3 horizontal(Vec3 vector) {
        Vec3 safe = safeVector(vector);
        return new Vec3(safe.x, 0.0D, safe.z);
    }

    private static Vec3 capVector(Vec3 vector, double maxLength) {
        Vec3 safe = safeVector(vector);
        if (maxLength <= 0.0D) {
            return Vec3.ZERO;
        }
        double length = safe.length();
        return length > maxLength ? safe.scale(maxLength / length) : safe;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record StormInfluence(TornadoWindField.WindSample wind, Vec3 center, boolean tornadic, double suctionRadius) {
        private static final StormInfluence CALM = new StormInfluence(TornadoWindField.WindSample.CALM, Vec3.ZERO, false, 0.0D);

        private StormInfluence stronger(StormInfluence other) {
            double thisStrength = Math.max(wind.speedMph(), wind.strongestLocalMph());
            double otherStrength = Math.max(other.wind.speedMph(), other.wind.strongestLocalMph());
            return otherStrength > thisStrength ? other : this;
        }
    }
}
