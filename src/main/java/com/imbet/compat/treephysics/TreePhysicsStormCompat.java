package com.imbet.compat.treephysics;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.BlockGrabSafety;
import com.imbet.weather.CompatHooks;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.StructureCrusher;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherDataStreamService;
import com.imbet.weather.WeatherPhysicalSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TreePhysicsStormCompat {
    private static final String MANAGER_CLASS = "com.farcr.treephysics.api.manager.ServerTreeManager";
    private static final String SYSTEM_CLASS = "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem";
    private static final String SUBLEVEL_CLASS = "dev.ryanhcode.sable.sublevel.SubLevel";
    private static final Map<UUID, Long> ACTIVE_TREE_TICKS = new HashMap<>();
    private static Reflection reflection;
    private static boolean disabledForSession;
    private static boolean loggedInactive;

    private TreePhysicsStormCompat() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide() || disabledForSession || !Config.TREE_PHYSICS_COMPAT_ENABLED.get() || !CompatHooks.treePhysicsLoaded()) {
            return;
        }
        if (level.getGameTime() % Math.max(1, Config.TREE_PHYSICS_SCAN_INTERVAL_TICKS.get()) != 0L) {
            return;
        }
        if (estimatedTps(level) < Config.TREE_PHYSICS_MIN_TPS.get()) {
            return;
        }
        Reflection refs = resolve();
        if (refs == null) {
            return;
        }
        try {
            Object manager = refs.getManager.invoke(null, level);
            Object system = refs.getSystem.invoke(null, level);
            if (manager == null || system == null) {
                return;
            }
            Object treesObject = refs.treesField.get(manager);
            if (!(treesObject instanceof Map<?, ?> trees) || trees.isEmpty()) {
                pruneTracking(level.getGameTime());
                return;
            }
            List<?> treeValues = new ArrayList<>(trees.values());
            int processed = 0;
            for (Object treeData : treeValues) {
                if (processed >= Config.TREE_PHYSICS_MAX_FALLING_TREE_ENTITIES.get()) {
                    break;
                }
                if (treeData == null) {
                    continue;
                }
                Object subLevel = refs.getSubLevel.invoke(treeData, level);
                if (subLevel == null) {
                    continue;
                }
                Object handle = refs.getPhysicsHandle.invoke(system, subLevel);
                if (handle == null) {
                    continue;
                }
                Vec3 position = subLevelPosition(subLevel);
                if (position == null) {
                    continue;
                }
                WeatherPhysicalSnapshot threat = strongestThreat(level, position);
                if (threat == null) {
                    continue;
                }
                processed++;
                applyStormForces(level, refs, manager, treeData, subLevel, handle, threat, position);
            }
            pruneTracking(level.getGameTime());
        } catch (ReflectiveOperationException | ClassCastException | LinkageError ex) {
            disabledForSession = true;
            ExpandedWeather2Dynamics.LOGGER.warn("Tree Physics storm compat disabled for this session after API mismatch.", ex);
        }
    }

    private static void applyStormForces(ServerLevel level, Reflection refs, Object manager, Object treeData, Object subLevel, Object handle, WeatherPhysicalSnapshot threat, Vec3 position) throws ReflectiveOperationException {
        TornadoWindField.WindSample sample = threat.sampleAt(position);
        double localWindMph = Math.max(sample.speedMph(), sample.strongestLocalMph());
        if (localWindMph < 45.0D) {
            return;
        }
        Vector3d currentVelocity = (Vector3d) refs.getLinearVelocity.invoke(handle, new Vector3d());
        int logs = Math.max(1, refs.logsField == null ? 8 : refs.logsField.getInt(treeData));
        double massFactor = Math.max(1.0D, logs / 6.0D);
        Vec3 wind = sample.mphVector();
        Vec3 horizontal = new Vec3(wind.x, 0.0D, wind.z);
        if (horizontal.horizontalDistanceSqr() <= 1.0E-5D) {
            horizontal = new Vec3(threat.windVectorMph().x, 0.0D, threat.windVectorMph().z);
        }
        if (horizontal.horizontalDistanceSqr() <= 1.0E-5D) {
            return;
        }
        Vec3 direction = horizontal.normalize();
        Vec3 fromCore = position.subtract(threat.circulationPosition());
        double distance = Math.max(0.7D, Math.sqrt(fromCore.x * fromCore.x + fromCore.z * fromCore.z));
        Vec3 radial = new Vec3(fromCore.x / distance, 0.0D, fromCore.z / distance);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double outerRadius = Math.max(Math.max(24.0D, threat.damagePathWidth() * 0.75D), Math.max(threat.radiusOfMaxWind(), threat.coreRadius()) * 4.0D);
        double proximity = Mth.clamp(1.0D - distance / outerRadius, 0.0D, 1.0D);
        double windMph = Math.max(localWindMph, threat.maxWindMph() * Math.pow(proximity, 0.85D));
        boolean violent = windMph >= 166.0D;
        boolean strong = windMph >= 111.0D;
        double forceScale = strong ? Config.TREE_PHYSICS_STRONG_LIFT_SCALE.get() * 7.0D : Config.TREE_PHYSICS_WEAK_ORBIT_SCALE.get() * 6.0D;
        if (violent) {
            forceScale = Config.TREE_PHYSICS_VIOLENT_LIFT_SCALE.get() * 9.0D;
            stripLeaves(refs, manager, subLevel);
        }
        double speedNorm = Mth.clamp((windMph - 45.0D) / 180.0D, 0.0D, 1.0D);
        double massDampen = Mth.clamp(1.25D / Math.sqrt(massFactor), 0.42D, 1.10D);
        double threatScale = (0.35D + proximity * 0.95D) * (0.55D + speedNorm * 0.85D) * massDampen;
        forceScale *= threatScale;
        Vec3 orbital = tangent.scale(forceScale * (strong ? 1.45D : 0.85D))
                .add(radial.scale(violent ? forceScale * 0.95D : -forceScale * (strong ? 0.70D : 0.35D)));
        if (!strong) {
            orbital = orbital.add(direction.scale(forceScale * 0.90D));
        }
        double lift = 0.0D;
        if (strong) {
            lift = forceScale * (0.85D + speedNorm * 0.80D);
            if (currentVelocity.y < 0.0D) {
                lift += Math.min(0.65D, -currentVelocity.y * 0.85D);
            }
        }
        if (violent) {
            lift += forceScale * 1.35D;
            orbital = orbital.add(radial.scale(forceScale * 1.15D)).add(direction.scale(forceScale * 0.55D));
            spawnStressParticles(level, position, windMph);
        }
        if (!strong && lift > 0.0D) {
            lift = 0.0D;
        }
        Vector3d linearDelta = clamp(new Vector3d(orbital.x, lift, orbital.z), violent ? 1.85D : strong ? 1.10D : 0.55D);
        Vector3d angularDelta = clamp(new Vector3d(radial.z * forceScale * 0.95D, forceScale * (0.55D + speedNorm), -radial.x * forceScale * 0.95D), violent ? 1.35D : strong ? 0.85D : 0.38D);
        refs.addLinearAndAngularVelocity.invoke(handle, linearDelta, angularDelta);
        UUID id = subLevelId(refs, treeData, subLevel);
        if (id != null) {
            ACTIVE_TREE_TICKS.put(id, level.getGameTime());
        }
        Vector3d impactVelocity = new Vector3d(currentVelocity).add(linearDelta);
        maybeCrush(level, refs, manager, treeData, subLevel, handle, position, impactVelocity, massFactor, violent);
    }

    private static void maybeCrush(ServerLevel level, Reflection refs, Object manager, Object treeData, Object subLevel, Object handle, Vec3 position, Vector3d velocity, double massFactor, boolean violent) throws ReflectiveOperationException {
        Vec3 velocityVec = new Vec3(velocity.x, velocity.y, velocity.z);
        if (StructureCrusher.kineticEnergy(massFactor, velocityVec) < Config.STRUCTURE_CRUSHER_MIN_KINETIC_ENERGY.get()) {
            return;
        }
        BlockPos impact = firstSolidAhead(level, position, velocityVec);
        if (impact == null) {
            BlockPos surface = BlockGrabSafety.safeSurface(level, BlockPos.containing(position), Heightmap.Types.MOTION_BLOCKING);
            if (surface == null || position.y > surface.getY() + 5.0D) {
                return;
            }
            impact = surface;
        }
        if (!level.isLoaded(impact)) {
            return;
        }
        StructureCrusher.CrushResult result = StructureCrusher.crush(level, impact, velocityVec, massFactor, violent);
        if (result.destroyedBlocks() <= 0) {
            return;
        }
        double retained = Math.max(0.15D, 1.0D - result.hardnessCost() / Math.max(2.0D, massFactor * (violent ? 14.0D : 8.0D)));
        Vector3d loss = new Vector3d(velocity.x * (retained - 1.0D), velocity.y * (retained - 1.0D), velocity.z * (retained - 1.0D));
        refs.addLinearAndAngularVelocity.invoke(handle, loss, new Vector3d(0.0D, -0.025D * result.destroyedBlocks(), 0.0D));
        if (violent && retained <= 0.20D && position.y <= impact.getY() + 2.0D) {
            markRemoved(refs, manager, subLevel);
        }
    }

    private static BlockPos firstSolidAhead(ServerLevel level, Vec3 position, Vec3 velocity) {
        if (velocity.lengthSqr() < 0.04D) {
            return null;
        }
        Vec3 step = velocity.normalize();
        double reach = Mth.clamp(velocity.length() * 3.0D, 1.0D, 6.0D);
        for (double traveled = 0.5D; traveled <= reach; traveled += 0.75D) {
            BlockPos pos = BlockPos.containing(position.add(step.scale(traveled)));
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, pos, null);
            if (sample.state() != null && !sample.state().isAir() && !sample.fluid()) {
                return pos;
            }
        }
        return null;
    }

    private static WeatherPhysicalSnapshot strongestThreat(ServerLevel level, Vec3 position) {
        WeatherPhysicalSnapshot strongest = null;
        double strongestWind = 0.0D;
        for (WeatherPhysicalSnapshot snapshot : WeatherDataStreamService.snapshots(level)) {
            if (snapshot == null || !snapshot.active()) {
                continue;
            }
            StormSubtype subtype = snapshot.subtype();
            if (subtype != StormSubtype.TORNADO
                    && subtype != StormSubtype.SATELLITE_TORNADO
                    && subtype != StormSubtype.LANDSPOUT
                    && subtype != StormSubtype.WATERSPOUT
                    && subtype != StormSubtype.GUSTNADO
                    && subtype != StormSubtype.SQUALL_LINE
                    && subtype != StormSubtype.HURRICANE) {
                continue;
            }
            TornadoWindField.WindSample sample = snapshot.sampleAt(position);
            if (sample.speedMph() > strongestWind) {
                strongestWind = sample.speedMph();
                strongest = snapshot;
            }
        }
        return strongestWind >= 45.0D ? strongest : null;
    }

    private static void stripLeaves(Reflection refs, Object manager, Object subLevel) {
        try {
            refs.startBreakingLeaves.invoke(manager, subLevel);
        } catch (ReflectiveOperationException ignored) {
            // Visual/decay aid only.
        }
    }

    private static void spawnStressParticles(ServerLevel level, Vec3 position, double windMph) {
        if (level.getGameTime() % 4L != 0L) {
            return;
        }
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LOG.defaultBlockState()),
                position.x,
                position.y + 0.9D,
                position.z,
                windMph >= 200.0D ? 10 : 5,
                0.75D,
                0.45D,
                0.75D,
                0.12D);
    }

    private static void markRemoved(Reflection refs, Object manager, Object subLevel) {
        try {
            if (refs.markRemoved != null) {
                refs.markRemoved.invoke(subLevel);
            }
            if (refs.unsetTree != null) {
                refs.unsetTree.invoke(manager, subLevel);
            }
        } catch (ReflectiveOperationException ignored) {
            // Cleanup is best-effort; Tree Physics will still despawn normally.
        }
    }

    private static Vec3 subLevelPosition(Object subLevel) throws ReflectiveOperationException {
        Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
        Object position = pose.getClass().getMethod("position").invoke(pose);
        return new Vec3(coordinate(position, "x"), coordinate(position, "y"), coordinate(position, "z"));
    }

    private static double coordinate(Object vector, String name) throws ReflectiveOperationException {
        try {
            Method method = vector.getClass().getMethod(name);
            Object value = method.invoke(vector);
            return value instanceof Number number ? number.doubleValue() : 0.0D;
        } catch (NoSuchMethodException ignored) {
            try {
                Field field = vector.getClass().getField(name);
                Object value = field.get(vector);
                return value instanceof Number number ? number.doubleValue() : 0.0D;
            } catch (NoSuchFieldException second) {
                return 0.0D;
            }
        }
    }

    private static UUID subLevelId(Reflection refs, Object treeData, Object subLevel) {
        try {
            Object id = refs.subLevelIdField == null && refs.getUniqueId != null ? refs.getUniqueId.invoke(subLevel) : refs.subLevelIdField == null ? null : refs.subLevelIdField.get(treeData);
            return id instanceof UUID uuid ? uuid : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Vector3d clamp(Vector3d vector, double maxLength) {
        double length = vector.length();
        if (length > maxLength && length > 1.0E-6D) {
            vector.mul(maxLength / length);
        }
        return vector;
    }

    private static double estimatedTps(ServerLevel level) {
        try {
            Method method = level.getServer().getClass().getMethod("getAverageTickTime");
            Object value = method.invoke(level.getServer());
            double averageTickTime = value instanceof Number number ? number.doubleValue() : 50.0D;
            if (averageTickTime <= 0.0D) {
                return 20.0D;
            }
            return Math.min(20.0D, 1000.0D / Math.max(50.0D, averageTickTime));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 20.0D;
        }
    }

    private static void pruneTracking(long gameTime) {
        ACTIVE_TREE_TICKS.entrySet().removeIf(entry -> entry.getValue() + 200L < gameTime);
    }

    private static Reflection resolve() {
        if (reflection != null) {
            return reflection;
        }
        if (loggedInactive) {
            return null;
        }
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Class<?> systemClass = Class.forName(SYSTEM_CLASS);
            Class<?> subLevelClass = Class.forName(SUBLEVEL_CLASS);
            Method getManager = managerClass.getMethod("get", ServerLevel.class);
            Method getSystem = findStaticSystemGet(systemClass);
            Field treesField = managerClass.getDeclaredField("trees");
            treesField.setAccessible(true);
            Class<?> treeDataClass = Class.forName("com.farcr.treephysics.api.manager.TreeData");
            Method getSubLevel = treeDataClass.getMethod("getSubLevel", Level.class);
            Method startBreakingLeaves = managerClass.getMethod("startBreakingLeaves", subLevelClass);
            Method unsetTree = findMethod(managerClass, "unsetTree", subLevelClass);
            Field logsField = optionalField(treeDataClass, "logs");
            Field subLevelIdField = optionalField(treeDataClass, "subLevelId");
            Method getPhysicsHandle = findOneArgMethod(systemClass, "getPhysicsHandle");
            Class<?> handleClass = getPhysicsHandle.getReturnType();
            Method getLinearVelocity = handleClass.getMethod("getLinearVelocity", Vector3d.class);
            Method addLinearAndAngularVelocity = handleClass.getMethod("addLinearAndAngularVelocity", Vector3d.class, Vector3d.class);
            Method getUniqueId = findMethod(subLevelClass, "getUniqueId");
            Method markRemoved = findMethod(subLevelClass, "markRemoved");
            reflection = new Reflection(getManager, getSystem, treesField, getSubLevel, getPhysicsHandle, getLinearVelocity, addLinearAndAngularVelocity, startBreakingLeaves, unsetTree, logsField, subLevelIdField, getUniqueId, markRemoved);
            return reflection;
        } catch (ReflectiveOperationException | LinkageError ex) {
            loggedInactive = true;
            ExpandedWeather2Dynamics.LOGGER.info("Tree Physics storm compat inactive: Tree Physics/Sable API was not found or did not match this version.");
            return null;
        }
    }

    private static Method findStaticSystemGet(Class<?> systemClass) throws NoSuchMethodException {
        for (Method method : systemClass.getMethods()) {
            if (method.getName().equals("get") && Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1) {
                Class<?> parameter = method.getParameterTypes()[0];
                if (parameter.isAssignableFrom(Level.class) || parameter.isAssignableFrom(ServerLevel.class) || Level.class.isAssignableFrom(parameter)) {
                    return method;
                }
            }
        }
        for (Method method : systemClass.getDeclaredMethods()) {
            if (method.getName().equals("get") && Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(systemClass.getName() + "#get(Level)");
    }

    private static Method findOneArgMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters.length) {
                    return method;
                }
            }
            return null;
        }
    }

    private static Field optionalField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private record Reflection(
            Method getManager,
            Method getSystem,
            Field treesField,
            Method getSubLevel,
            Method getPhysicsHandle,
            Method getLinearVelocity,
            Method addLinearAndAngularVelocity,
            Method startBreakingLeaves,
            Method unsetTree,
            Field logsField,
            Field subLevelIdField,
            Method getUniqueId,
            Method markRemoved
    ) {
    }
}
