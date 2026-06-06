package com.imbet.compat.mts;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.AddonWeatherEventService;
import com.imbet.weather.CompatHooks;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherDataStreamService;
import com.imbet.weather.WeatherPhysicalSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ImmersiveVehiclesStormCompat {
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.022352D;
    private static final Map<UUID, VehicleStormState> VEHICLE_STATES = new HashMap<>();
    private static MtsApi api;
    private static boolean disabledForSession;
    private static boolean loggedDisabled;

    private ImmersiveVehiclesStormCompat() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide() || disabledForSession || !Config.IMMERSIVE_VEHICLES_ENABLED.get() || !CompatHooks.immersiveVehiclesLoaded()) {
            return;
        }
        int interval = Math.max(1, Config.IMMERSIVE_VEHICLES_SCAN_INTERVAL_TICKS.get());
        long gameTime = level.getGameTime();
        if (gameTime % interval != 0L) {
            return;
        }
        MtsApi resolved = api();
        if (resolved == null) {
            return;
        }

        List<Entity> candidates = scanVehicleWrappers(level, resolved);
        int maxVehicles = Math.max(0, Config.IMMERSIVE_VEHICLES_MAX_VEHICLES_PER_TICK.get());
        int processed = 0;
        Set<UUID> seen = new HashSet<>();
        for (Entity entity : candidates) {
            if (entity == null || !seen.add(entity.getUUID())) {
                continue;
            }
            Object vehicle = resolved.vehicleFromBuilder(entity);
            if (vehicle == null) {
                continue;
            }
            try {
                processVehicle(level, gameTime, resolved, entity, vehicle);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                disableCompat("Immersive Vehicles storm compat failed while applying forces; disabling compat for this session.", ex);
                return;
            }
            if (++processed >= maxVehicles) {
                break;
            }
        }

        if (gameTime % 200L == 0L) {
            VEHICLE_STATES.entrySet().removeIf(entry -> entry.getValue().lastSeenTick + 400L < gameTime);
        }
    }

    private static List<Entity> scanVehicleWrappers(ServerLevel level, MtsApi resolved) {
        double range = Math.max(16.0D, Config.IMMERSIVE_VEHICLES_SCAN_RANGE_BLOCKS.get());
        List<Entity> candidates = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Player player : level.players()) {
            AABB box = new AABB(player.getX() - range, player.getY() - range * 0.35D, player.getZ() - range,
                    player.getX() + range, player.getY() + range * 0.35D, player.getZ() + range);
            for (Entity entity : level.getEntities((Entity) null, box, resolved::isBuilderEntity)) {
                if (seen.add(entity.getId())) {
                    candidates.add(entity);
                }
            }
        }
        return candidates;
    }

    private static void processVehicle(ServerLevel level, long gameTime, MtsApi resolved, Entity wrapper, Object vehicle) throws ReflectiveOperationException {
        Vec3 vehiclePos = resolved.readVehiclePosition(vehicle, wrapper.position());
        StormInfluence influence = strongestStormWind(level, vehiclePos);
        double windMph = Math.max(influence.wind.speedMph(), influence.wind.strongestLocalMph());
        VehicleStormState state = VEHICLE_STATES.computeIfAbsent(wrapper.getUUID(), ignored -> new VehicleStormState());
        state.lastSeenTick = gameTime;
        if (windMph < 3.0D || influence.wind.mphVector().lengthSqr() < 1.0E-4D) {
            state.stress = Math.max(0.0D, state.stress - 0.04D);
            return;
        }

        Vec3 currentMotion = resolved.readVehicleMotion(vehicle);
        Vec3 windBlocksPerTick = safeVector(influence.wind.mphVector()).scale(MPH_TO_BLOCKS_PER_TICK);
        Vec3 relativeAir = windBlocksPerTick.subtract(currentMotion);
        Vec3 windHorizontal = horizontal(influence.wind.mphVector());
        Vec3 windDir = windHorizontal.lengthSqr() < 1.0E-5D ? Vec3.ZERO : windHorizontal.normalize();
        double yawDegrees = resolved.vehicleYawDegrees(vehicle);
        Vec3 forward = new Vec3(Math.sin(Math.toRadians(yawDegrees)), 0.0D, Math.cos(Math.toRadians(yawDegrees)));
        Vec3 right = new Vec3(forward.z, 0.0D, -forward.x);
        double sideExposure = windDir == Vec3.ZERO ? 0.35D : Math.abs(windDir.dot(right)) + Math.abs(windDir.dot(forward)) * 0.45D;
        double crossSection = clamp(resolved.crossSection(vehicle), 0.6D, 24.0D);
        double mass = Math.max(120.0D, resolved.mass(vehicle));
        double massFactor = clamp(1200.0D / mass, 0.15D, 2.35D);
        double exposure = clamp(sideExposure * Math.sqrt(crossSection / 3.0D), 0.20D, 1.75D);
        double severity = clamp(windMph / Math.max(1.0D, Config.WINDFIELD_STAGE5_MPH.get()), 0.0D, 1.2D);
        double maxDelta = Math.max(0.0D, Config.IMMERSIVE_VEHICLES_MAX_MOTION_DELTA_PER_TICK.get());
        double forceScale = Config.IMMERSIVE_VEHICLES_WIND_FORCE_SCALE.get() * severity * severity * exposure * massFactor * 0.09D;
        Vec3 force = capVector(relativeAir.scale(forceScale), maxDelta);

        if (influence.tornadic && influence.wind.distanceFromCore() <= influence.suctionRadius) {
            force = force.add(tornadoSuctionForce(influence, vehiclePos, massFactor, severity, maxDelta));
            double close = clamp(1.0D - influence.wind.distanceFromCore() / Math.max(1.0D, influence.suctionRadius), 0.0D, 1.0D);
            if (close > 0.48D) {
                resolved.reduceGroundContact(vehicle, close);
            }
        }
        if (force.lengthSqr() > 1.0E-8D) {
            resolved.addMotion(vehicle, capVector(force, maxDelta));
        }

        applyRollStress(resolved, vehicle, windDir, forward, right, severity, exposure, massFactor, maxDelta);
        applyStressAndDamage(resolved, vehicle, state, gameTime, influence, windMph, severity, exposure);
        applyStall(resolved, vehicle, influence, windMph);
        applyOpenCockpitEjection(resolved, vehicle, influence, windMph);
    }

    private static Vec3 tornadoSuctionForce(StormInfluence influence, Vec3 vehiclePos, double massFactor, double severity, double maxDelta) {
        Vec3 center = influence.center;
        Vec3 fromCenter = new Vec3(vehiclePos.x - center.x, 0.0D, vehiclePos.z - center.z);
        double distance = Math.max(0.5D, fromCenter.length());
        Vec3 radialOut = fromCenter.scale(1.0D / distance);
        Vec3 inward = radialOut.scale(-1.0D);
        Vec3 orbital = horizontal(influence.wind.mphVector());
        orbital = orbital.lengthSqr() < 1.0E-5D ? new Vec3(-radialOut.z, 0.0D, radialOut.x) : orbital.normalize();
        double close = clamp(1.0D - influence.wind.distanceFromCore() / Math.max(1.0D, influence.suctionRadius), 0.0D, 1.0D);
        double lift = Config.IMMERSIVE_VEHICLES_LIFT_FORCE_SCALE.get() * maxDelta * close * severity * massFactor;
        Vec3 pull = inward.scale(maxDelta * 0.22D * close * severity * massFactor);
        Vec3 spin = orbital.scale(maxDelta * 0.42D * close * severity);
        return capVector(pull.add(spin).add(0.0D, lift, 0.0D), maxDelta);
    }

    private static void applyRollStress(MtsApi resolved, Object vehicle, Vec3 windDir, Vec3 forward, Vec3 right, double severity, double exposure, double massFactor, double maxDelta) throws ReflectiveOperationException {
        if (windDir == Vec3.ZERO || maxDelta <= 0.0D) {
            return;
        }
        double side = clamp(windDir.dot(right), -1.0D, 1.0D);
        double head = clamp(windDir.dot(forward), -1.0D, 1.0D);
        double torque = Config.IMMERSIVE_VEHICLES_FLIP_TORQUE_SCALE.get() * severity * exposure * massFactor;
        if (Math.abs(side) > 0.18D || Math.abs(head) > 0.55D) {
            resolved.addRotation(vehicle, -head * torque * 0.18D, side * torque * 0.34D);
        }
    }

    private static void applyStressAndDamage(MtsApi resolved, Object vehicle, VehicleStormState state, long gameTime, StormInfluence influence, double windMph, double severity, double exposure) throws ReflectiveOperationException {
        double stressGain = 0.0D;
        if (windMph >= 80.0D) {
            stressGain += Math.pow((windMph - 70.0D) / 120.0D, 1.35D) * exposure * 0.08D;
        }
        if (influence.tornadic && influence.wind.distanceFromCore() <= influence.suctionRadius) {
            double close = clamp(1.0D - influence.wind.distanceFromCore() / Math.max(1.0D, influence.suctionRadius), 0.0D, 1.0D);
            stressGain += close * close * severity * 0.18D;
        }
        state.stress = clamp(state.stress + stressGain - 0.018D, 0.0D, 6.0D);
        if (Config.IMMERSIVE_VEHICLES_STRUCTURAL_STRESS_DAMAGE.get() && state.stress > 1.0D && gameTime >= state.nextDamageTick) {
            resolved.applyStressDamage(vehicle, clamp(state.stress * 0.28D, 0.15D, 2.5D));
            state.nextDamageTick = gameTime + 20L;
            state.stress *= 0.72D;
        }
    }

    private static void applyStall(MtsApi resolved, Object vehicle, StormInfluence influence, double windMph) throws ReflectiveOperationException {
        if (!Config.IMMERSIVE_VEHICLES_STALLING_ENABLED.get()) {
            return;
        }
        double stallThreshold = Math.max(1.0D, Config.IMMERSIVE_VEHICLES_STALL_WIND_MPH.get());
        boolean denseCore = influence.tornadic && influence.wind.distanceFromCore() <= Math.max(influence.wind.radiusMaxWind() * 2.2D, 10.0D);
        if (windMph >= stallThreshold || denseCore) {
            double intensity = clamp(windMph / stallThreshold, 0.35D, 1.75D);
            resolved.stallEngines(vehicle, intensity);
        }
    }

    private static void applyOpenCockpitEjection(MtsApi resolved, Object vehicle, StormInfluence influence, double windMph) throws ReflectiveOperationException {
        if (!Config.IMMERSIVE_VEHICLES_OPEN_COCKPIT_EJECT.get() || !influence.tornadic || windMph < Config.IMMERSIVE_VEHICLES_OPEN_COCKPIT_EJECT_MPH.get() || !resolved.isOpenTop(vehicle)) {
            return;
        }
        Vec3 impulse = capVector(safeVector(influence.wind.mphVector()).scale(MPH_TO_BLOCKS_PER_TICK * 0.36D), 1.8D).add(0.0D, 0.42D, 0.0D);
        resolved.ejectOpenSeats(vehicle, impulse);
    }

    private static StormInfluence strongestStormWind(ServerLevel level, Vec3 position) {
        StormInfluence best = StormInfluence.CALM;
        for (WeatherPhysicalSnapshot snapshot : WeatherDataStreamService.snapshots(level)) {
            TornadoWindField.WindSample sample = snapshot.sampleAt(position);
            boolean tornadic = snapshot.subtype() == StormSubtype.TORNADO
                    || snapshot.subtype() == StormSubtype.SATELLITE_TORNADO
                    || snapshot.subtype() == StormSubtype.LANDSPOUT
                    || snapshot.subtype() == StormSubtype.WATERSPOUT
                    || snapshot.subtype() == StormSubtype.DUST_DEVIL
                    || snapshot.subtype() == StormSubtype.GUSTNADO;
            double suctionRadius = Math.max(Math.max(sample.baseRadius() * 1.65D, sample.radiusMaxWind() * 4.2D), 18.0D);
            best = best.stronger(new StormInfluence(sample, snapshot.circulationPosition(), tornadic, suctionRadius));
        }
        if (best != StormInfluence.CALM) {
            return best;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
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
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            TornadoWindField.WindSample sample = sampleAddonEventWind(event, position);
            Vec3 center = event.isLine() ? closestPointOnSegment(position, event.lineStart(), event.lineEnd()) : new Vec3(event.position().x, position.y, event.position().z);
            boolean tornadic = event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.SATELLITE_TORNADO || event.type() == StormSubtype.TORNADO;
            best = best.stronger(new StormInfluence(sample, center, tornadic, Math.max(event.radius() * 4.0D, 16.0D)));
        }
        return best;
    }

    private static TornadoWindField.WindSample sampleAddonEventWind(AddonWeatherEvent event, Vec3 position) {
        if (event == null) {
            return TornadoWindField.WindSample.CALM;
        }
        if (event.isLine()) {
            return sampleLineWind(event, position);
        }
        Vec3 center = new Vec3(event.position().x, position.y, event.position().z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.4D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double rmw = Math.max(1.2D, event.radius());
        if (radius > rmw * 4.0D) {
            return TornadoWindField.WindSample.CALM;
        }
        double max = event.maxWindMph();
        double tangential = radius <= rmw ? max * (radius / rmw) : max * Math.pow(rmw / radius, 0.86D);
        Vec3 radial = fromCenter.scale(1.0D / radius);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double inflow = event.type() == StormSubtype.DUST_DEVIL ? 0.10D : event.type() == StormSubtype.GUSTNADO ? 0.055D : event.type() == StormSubtype.WATERSPOUT ? 0.24D : 0.18D;
        Vec3 vector = tangent.scale(tangential).add(radial.scale(-tangential * inflow));
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), radius, rmw * 2.0D, rmw);
    }

    private static TornadoWindField.WindSample sampleLineWind(AddonWeatherEvent event, Vec3 position) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 closest = closestPointOnSegment(position, start, end);
        double distance = Math.sqrt(horizontalDistanceSqr(position, closest));
        if (distance > event.radius()) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 line = end.subtract(start);
        Vec3 along = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(line.x, 0.0D, line.z).normalize();
        Vec3 motion = event.motion();
        Vec3 forward = motion != null && motion.horizontalDistanceSqr() > 1.0E-5D ? new Vec3(motion.x, 0.0D, motion.z).normalize() : new Vec3(-along.z, 0.0D, along.x);
        double falloff = clamp(1.0D - distance / Math.max(1.0D, event.radius()), 0.0D, 1.0D);
        Vec3 vector = forward.scale(event.maxWindMph() * falloff);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, event.radius(), event.radius() * 0.5D);
    }

    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 start, Vec3 end) {
        if (point == null || start == null || end == null) {
            return point == null ? Vec3.ZERO : point;
        }
        Vec3 ab = end.subtract(start);
        double lengthSqr = ab.x * ab.x + ab.z * ab.z;
        if (lengthSqr <= 1.0E-5D) {
            return new Vec3(start.x, point.y, start.z);
        }
        double t = ((point.x - start.x) * ab.x + (point.z - start.z) * ab.z) / lengthSqr;
        t = clamp(t, 0.0D, 1.0D);
        return new Vec3(start.x + ab.x * t, point.y, start.z + ab.z * t);
    }

    private static double horizontalDistanceSqr(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static MtsApi api() {
        if (api != null) {
            return api;
        }
        try {
            api = MtsApi.resolve();
            if (api == null) {
                disableCompat("Immersive Vehicles/MTS is loaded, but the expected 1.21.1 wrapper classes were not found. Storm vehicle compat is disabled.", null);
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            disableCompat("Immersive Vehicles storm compat could not resolve MTS internals; disabling compat for this session.", ex);
        }
        return api;
    }

    private static void disableCompat(String message, Throwable throwable) {
        disabledForSession = true;
        if (!loggedDisabled) {
            loggedDisabled = true;
            if (throwable == null) {
                ExpandedWeather2Dynamics.LOGGER.warn(message);
            } else {
                ExpandedWeather2Dynamics.LOGGER.warn(message, throwable);
            }
        }
    }

    private static Vec3 horizontal(Vec3 vector) {
        Vec3 safe = safeVector(vector);
        return new Vec3(safe.x, 0.0D, safe.z);
    }

    private static Vec3 safeVector(Vec3 vector) {
        if (vector == null || !Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            return Vec3.ZERO;
        }
        return vector;
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

    private static final class VehicleStormState {
        private long lastSeenTick;
        private long nextDamageTick;
        private double stress;
    }

    private static final class MtsApi {
        private final Class<?> builderClass;
        private final Class<?> vehicleClass;
        private final Class<?> pointClass;
        private final Class<?> partSeatClass;
        private final Class<?> partEngineClass;
        private final Field builderEntityField;
        private final Field positionField;
        private final Field motionField;
        private final Field orientationField;
        private final Field rotationField;
        private final Field currentMassField;
        private final Field velocityField;
        private final Field groundDeviceCollectiveField;
        private final Field openTopVarField;
        private final Field allPartsField;
        private final Field definitionField;
        private final Field pointXField;
        private final Field pointYField;
        private final Field pointZField;
        private final Field rotationAnglesField;
        private final Field computedValueField;
        private final Field computedActiveField;
        private final Field seatRiderField;
        private final Method seatRemoveRiderMethod;
        private final Field engineRunningField;
        private final Field engineRpmField;
        private final Field engineFuelFlowField;
        private final Method vehicleAttackMethod;
        private final Constructor<?> damageConstructor;

        private MtsApi(Class<?> builderClass, Class<?> vehicleClass, Class<?> pointClass, Class<?> partSeatClass, Class<?> partEngineClass,
                       Field builderEntityField, Field positionField, Field motionField, Field orientationField, Field rotationField,
                       Field currentMassField, Field velocityField, Field groundDeviceCollectiveField, Field openTopVarField, Field allPartsField,
                       Field definitionField, Field pointXField, Field pointYField, Field pointZField, Field rotationAnglesField,
                       Field computedValueField, Field computedActiveField, Field seatRiderField, Method seatRemoveRiderMethod,
                       Field engineRunningField, Field engineRpmField, Field engineFuelFlowField, Method vehicleAttackMethod,
                       Constructor<?> damageConstructor) {
            this.builderClass = builderClass;
            this.vehicleClass = vehicleClass;
            this.pointClass = pointClass;
            this.partSeatClass = partSeatClass;
            this.partEngineClass = partEngineClass;
            this.builderEntityField = builderEntityField;
            this.positionField = positionField;
            this.motionField = motionField;
            this.orientationField = orientationField;
            this.rotationField = rotationField;
            this.currentMassField = currentMassField;
            this.velocityField = velocityField;
            this.groundDeviceCollectiveField = groundDeviceCollectiveField;
            this.openTopVarField = openTopVarField;
            this.allPartsField = allPartsField;
            this.definitionField = definitionField;
            this.pointXField = pointXField;
            this.pointYField = pointYField;
            this.pointZField = pointZField;
            this.rotationAnglesField = rotationAnglesField;
            this.computedValueField = computedValueField;
            this.computedActiveField = computedActiveField;
            this.seatRiderField = seatRiderField;
            this.seatRemoveRiderMethod = seatRemoveRiderMethod;
            this.engineRunningField = engineRunningField;
            this.engineRpmField = engineRpmField;
            this.engineFuelFlowField = engineFuelFlowField;
            this.vehicleAttackMethod = vehicleAttackMethod;
            this.damageConstructor = damageConstructor;
        }

        private static MtsApi resolve() throws ReflectiveOperationException {
            Class<?> builder = firstClass("mcinterface1211.BuilderEntityExisting", "mcinterfaceforge1211.BuilderEntityExisting");
            Class<?> vehicle = Class.forName("minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics");
            Class<?> point = Class.forName("minecrafttransportsimulator.baseclasses.Point3D");
            Class<?> seat = Class.forName("minecrafttransportsimulator.entities.instances.PartSeat");
            Class<?> engine = Class.forName("minecrafttransportsimulator.entities.instances.PartEngine");
            Class<?> rotationMatrix = Class.forName("minecrafttransportsimulator.baseclasses.RotationMatrix");
            Class<?> computedVariable = Class.forName("minecrafttransportsimulator.baseclasses.ComputedVariable");
            Class<?> damage = Class.forName("minecrafttransportsimulator.baseclasses.Damage");

            Field builderEntity = requireField(builder, "entity");
            Field position = requireField(vehicle, "position");
            Field motion = requireField(vehicle, "motion");
            Field orientation = requireField(vehicle, "orientation");
            Field rotation = requireField(vehicle, "rotation");
            Field currentMass = requireField(vehicle, "currentMass");
            Field velocity = requireField(vehicle, "velocity");
            Field groundDeviceCollective = optionalField(vehicle, "groundDeviceCollective");
            Field openTopVar = optionalField(vehicle, "openTopVar");
            Field allParts = requireField(vehicle, "allParts");
            Field definition = optionalField(vehicle, "definition");
            Field pointX = requireField(point, "x");
            Field pointY = requireField(point, "y");
            Field pointZ = requireField(point, "z");
            Field angles = requireField(rotationMatrix, "angles");
            Field currentValue = optionalField(computedVariable, "currentValue");
            Field isActive = optionalField(computedVariable, "isActive");
            Field seatRider = optionalField(seat, "rider");
            Method removeRider = optionalMethod(seat, "removeRider");
            Field engineRunning = optionalField(engine, "running");
            Field engineRpm = optionalField(engine, "rpm");
            Field engineFuelFlow = optionalField(engine, "fuelFlow");
            Method attack = optionalAttackMethod(vehicle, damage);
            Constructor<?> damageCtor = optionalDamageConstructor(damage);
            return new MtsApi(builder, vehicle, point, seat, engine, builderEntity, position, motion, orientation, rotation, currentMass, velocity,
                    groundDeviceCollective, openTopVar, allParts, definition, pointX, pointY, pointZ, angles, currentValue, isActive,
                    seatRider, removeRider, engineRunning, engineRpm, engineFuelFlow, attack, damageCtor);
        }

        private boolean isBuilderEntity(Entity entity) {
            return entity != null && builderClass.isInstance(entity);
        }

        private Object vehicleFromBuilder(Entity entity) {
            if (!isBuilderEntity(entity)) {
                return null;
            }
            try {
                Object wrapped = builderEntityField.get(entity);
                return vehicleClass.isInstance(wrapped) ? wrapped : null;
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }

        private Vec3 readVehiclePosition(Object vehicle, Vec3 fallback) throws ReflectiveOperationException {
            Object point = positionField.get(vehicle);
            return point == null ? fallback : readPoint(point);
        }

        private Vec3 readVehicleMotion(Object vehicle) throws ReflectiveOperationException {
            Object point = motionField.get(vehicle);
            return point == null ? Vec3.ZERO : readPoint(point);
        }

        private void addMotion(Object vehicle, Vec3 delta) throws ReflectiveOperationException {
            Object point = motionField.get(vehicle);
            if (point == null) {
                return;
            }
            addToPoint(point, delta);
        }

        private void addRotation(Object vehicle, double pitchDelta, double rollDelta) throws ReflectiveOperationException {
            Object rotation = rotationField.get(vehicle);
            if (rotation == null) {
                return;
            }
            Object angles = rotationAnglesField.get(rotation);
            if (angles == null) {
                return;
            }
            double x = pointXField.getDouble(angles);
            double z = pointZField.getDouble(angles);
            pointXField.setDouble(angles, x + clamp(pitchDelta, -0.45D, 0.45D));
            pointZField.setDouble(angles, z + clamp(rollDelta, -0.75D, 0.75D));
        }

        private double vehicleYawDegrees(Object vehicle) throws ReflectiveOperationException {
            Object orientation = orientationField.get(vehicle);
            if (orientation == null) {
                return 0.0D;
            }
            Object angles = rotationAnglesField.get(orientation);
            if (angles == null) {
                return 0.0D;
            }
            return pointYField.getDouble(angles);
        }

        private double mass(Object vehicle) throws ReflectiveOperationException {
            Object mass = currentMassField.get(vehicle);
            if (mass instanceof Number number && Double.isFinite(number.doubleValue())) {
                return number.doubleValue();
            }
            return 1200.0D;
        }

        private double crossSection(Object vehicle) {
            try {
                Object definition = definitionField == null ? null : definitionField.get(vehicle);
                Object motorized = definition == null ? null : fieldValue(definition, "motorized");
                Object cross = motorized == null ? null : fieldValue(motorized, "crossSectionalArea", "crossSection");
                if (cross instanceof Number number && Double.isFinite(number.doubleValue())) {
                    return number.doubleValue();
                }
                Object width = motorized == null ? null : fieldValue(motorized, "width");
                Object height = motorized == null ? null : fieldValue(motorized, "height");
                if (width instanceof Number widthNumber && height instanceof Number heightNumber) {
                    return widthNumber.doubleValue() * heightNumber.doubleValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // Fallback below.
            }
            return 3.0D;
        }

        private boolean isOpenTop(Object vehicle) {
            try {
                if (openTopVarField != null) {
                    Object openTop = openTopVarField.get(vehicle);
                    if (openTop != null) {
                        boolean active = computedActiveField == null || computedActiveField.getBoolean(openTop);
                        double value = computedValueField == null ? 0.0D : computedValueField.getDouble(openTop);
                        if (active && value > 0.5D) {
                            return true;
                        }
                    }
                }
                Object definition = definitionField == null ? null : definitionField.get(vehicle);
                Object motorized = definition == null ? null : fieldValue(definition, "motorized");
                Object hasOpenTop = motorized == null ? null : fieldValue(motorized, "hasOpenTop", "openTop");
                return hasOpenTop instanceof Boolean bool && bool;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }

        private void reduceGroundContact(Object vehicle, double amount) {
            if (groundDeviceCollectiveField == null || amount < 0.66D) {
                return;
            }
            try {
                Object collective = groundDeviceCollectiveField.get(vehicle);
                Object grounded = collective == null ? null : fieldValue(collective, "groundedGroundDevices");
                if (grounded instanceof Set<?> set) {
                    set.clear();
                }
            } catch (ReflectiveOperationException ignored) {
                // Wheel contact override is optional.
            }
        }

        private void stallEngines(Object vehicle, double intensity) throws ReflectiveOperationException {
            for (Object part : allParts(vehicle)) {
                if (!partEngineClass.isInstance(part)) {
                    continue;
                }
                if (engineRunningField != null && intensity >= 0.85D) {
                    engineRunningField.setBoolean(part, false);
                }
                if (engineRpmField != null) {
                    double rpm = engineRpmField.getDouble(part);
                    engineRpmField.setDouble(part, Math.max(0.0D, rpm * (1.0D - clamp(intensity * 0.22D, 0.08D, 0.75D))));
                }
                if (engineFuelFlowField != null && intensity >= 1.0D) {
                    engineFuelFlowField.setDouble(part, 0.0D);
                }
            }
        }

        private void ejectOpenSeats(Object vehicle, Vec3 impulse) throws ReflectiveOperationException {
            for (Object part : allParts(vehicle)) {
                if (!partSeatClass.isInstance(part)) {
                    continue;
                }
                Object rider = seatRiderField == null ? null : seatRiderField.get(part);
                if (rider == null) {
                    continue;
                }
                Entity minecraftRider = unwrapMinecraftEntity(rider);
                if (seatRemoveRiderMethod != null) {
                    seatRemoveRiderMethod.invoke(part);
                }
                if (minecraftRider != null) {
                    minecraftRider.push(impulse.x, impulse.y, impulse.z);
                }
            }
        }

        private void applyStressDamage(Object vehicle, double amount) throws ReflectiveOperationException {
            if (vehicleAttackMethod == null || damageConstructor == null) {
                return;
            }
            Object damage = damageConstructor.newInstance(amount, null, null, null, null);
            vehicleAttackMethod.invoke(vehicle, damage);
        }

        private Iterable<?> allParts(Object vehicle) throws ReflectiveOperationException {
            Object parts = allPartsField.get(vehicle);
            return parts instanceof Iterable<?> iterable ? iterable : List.of();
        }

        private Vec3 readPoint(Object point) throws IllegalAccessException {
            if (!pointClass.isInstance(point)) {
                return Vec3.ZERO;
            }
            return new Vec3(pointXField.getDouble(point), pointYField.getDouble(point), pointZField.getDouble(point));
        }

        private void addToPoint(Object point, Vec3 delta) throws IllegalAccessException {
            if (!pointClass.isInstance(point)) {
                return;
            }
            Vec3 safe = safeVector(delta);
            pointXField.setDouble(point, pointXField.getDouble(point) + safe.x);
            pointYField.setDouble(point, pointYField.getDouble(point) + safe.y);
            pointZField.setDouble(point, pointZField.getDouble(point) + safe.z);
        }

        private Entity unwrapMinecraftEntity(Object wrapper) {
            if (wrapper instanceof Entity entity) {
                return entity;
            }
            try {
                Object value = fieldValue(wrapper, "entity", "entityLiving", "mcEntity", "internal");
                return value instanceof Entity entity ? entity : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private static Class<?> firstClass(String... names) throws ClassNotFoundException {
            ClassNotFoundException last = null;
            for (String name : names) {
                try {
                    return Class.forName(name);
                } catch (ClassNotFoundException ex) {
                    last = ex;
                }
            }
            throw last == null ? new ClassNotFoundException("No class names supplied") : last;
        }

        private static Field requireField(Class<?> type, String name) throws NoSuchFieldException {
            Field field = optionalField(type, name);
            if (field == null) {
                throw new NoSuchFieldException(type.getName() + "#" + name);
            }
            return field;
        }

        private static Field optionalField(Class<?> type, String name) {
            Class<?> cursor = type;
            while (cursor != null) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            return null;
        }

        private static Method optionalMethod(Class<?> type, String name, Class<?>... parameterTypes) {
            Class<?> cursor = type;
            while (cursor != null) {
                try {
                    Method method = cursor.getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            return null;
        }

        private static Method optionalAttackMethod(Class<?> vehicleClass, Class<?> damageClass) {
            Class<?> cursor = vehicleClass;
            while (cursor != null) {
                for (Method method : cursor.getDeclaredMethods()) {
                    if (method.getName().equals("attack") && method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(damageClass)) {
                        method.setAccessible(true);
                        return method;
                    }
                }
                cursor = cursor.getSuperclass();
            }
            return null;
        }

        private static Constructor<?> optionalDamageConstructor(Class<?> damageClass) {
            for (Constructor<?> constructor : damageClass.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 5 && (parameters[0] == double.class || parameters[0] == Double.class)) {
                    constructor.setAccessible(true);
                    return constructor;
                }
            }
            return null;
        }

        private static Object fieldValue(Object instance, String... names) throws ReflectiveOperationException {
            if (instance == null) {
                return null;
            }
            for (String name : names) {
                Field field = optionalField(instance.getClass(), name);
                if (field != null) {
                    return field.get(instance);
                }
            }
            return null;
        }
    }
}
