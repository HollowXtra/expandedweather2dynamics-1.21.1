package com.imbet.client;

import com.imbet.Config;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PhysicsModFoliageBridge {
    private static final boolean PHYSICS_MOD_LOADED = ModList.get().isLoaded("physicsmod");
    private static final Map<Block, Boolean> PHYSICS_BLOCK_CACHE = new ConcurrentHashMap<>();
    private static boolean windLookupAttempted;
    private static boolean windLookupAvailable;
    private static boolean vineLookupAttempted;
    private static boolean vineLookupAvailable;
    private static Method physicsModGetInstance;
    private static Method getPhysicsWorld;
    private static Method getWeatherDomain;
    private static Method getWindDirection;
    private static Method getWindStrength;
    private static Field vectorX;
    private static Field vectorZ;
    private static Method vineGetSetting;

    private PhysicsModFoliageBridge() {
    }

    public static boolean enabled() {
        return PHYSICS_MOD_LOADED && Config.FOLIAGE_WIND_USE_PHYSICS_MOD.get();
    }

    public static boolean physicsOwnsBlock(BlockState state) {
        if (!enabled() || state == null || !ensureVineLookup()) {
            return false;
        }
        return PHYSICS_BLOCK_CACHE.computeIfAbsent(state.getBlock(), block -> {
            try {
                return vineGetSetting.invoke(null, state) != null;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                vineLookupAvailable = false;
                return false;
            }
        });
    }

    public static PhysicsWindSample sample(ClientLevel level, Vec3 position, float thrashMph) {
        if (!enabled() || level == null || position == null || !ensureWindLookup()) {
            return PhysicsWindSample.CALM;
        }
        try {
            Object physicsMod = physicsModGetInstance.invoke(null, level);
            if (physicsMod == null) {
                return PhysicsWindSample.CALM;
            }
            Object physicsWorld = getPhysicsWorld.invoke(physicsMod);
            if (physicsWorld == null) {
                return PhysicsWindSample.CALM;
            }
            Object weatherDomain = getWeatherDomain.invoke(physicsWorld);
            if (weatherDomain == null) {
                return PhysicsWindSample.CALM;
            }
            int x = Mth.floor(position.x);
            int y = Mth.floor(position.y);
            int z = Mth.floor(position.z);
            Object direction = getWindDirection.invoke(weatherDomain, x, y, z);
            float strength = ((Number) getWindStrength.invoke(weatherDomain, x, y, z)).floatValue();
            if (direction == null || !Float.isFinite(strength) || strength <= 1.0E-4F) {
                return PhysicsWindSample.CALM;
            }
            double dirX = ((Number) vectorX.get(direction)).doubleValue();
            double dirZ = ((Number) vectorZ.get(direction)).doubleValue();
            Vec3 horizontal = new Vec3(dirX, 0.0D, dirZ);
            if (horizontal.horizontalDistanceSqr() <= 1.0E-6D) {
                return PhysicsWindSample.CALM;
            }
            float normalized = Mth.clamp(strength, 0.0F, 1.0F);
            float gust = Mth.clamp((strength - 0.18F) / 0.82F, 0.0F, 1.0F);
            double speedMph = Math.max(1.0F, thrashMph) * normalized;
            return new PhysicsWindSample(horizontal.normalize().scale(speedMph), normalized, gust);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            windLookupAvailable = false;
            return PhysicsWindSample.CALM;
        }
    }

    private static boolean ensureWindLookup() {
        if (windLookupAttempted) {
            return windLookupAvailable;
        }
        windLookupAttempted = true;
        try {
            Class<?> physicsModClass = Class.forName("net.diebuddies.physics.PhysicsMod");
            Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
            Class<?> physicsWorldClass = Class.forName("net.diebuddies.physics.PhysicsWorld");
            Class<?> weatherDomainClass = Class.forName("net.diebuddies.physics.wind.WeatherDomain");
            Class<?> vectorClass = Class.forName("org.joml.Vector3f");
            physicsModGetInstance = physicsModClass.getMethod("getInstance", levelClass);
            getPhysicsWorld = physicsModClass.getMethod("getPhysicsWorld");
            getWeatherDomain = physicsWorldClass.getMethod("getWeatherDomain");
            getWindDirection = weatherDomainClass.getMethod("getWindDirection", int.class, int.class, int.class);
            getWindStrength = weatherDomainClass.getMethod("getWindStrength", int.class, int.class, int.class);
            vectorX = vectorClass.getField("x");
            vectorZ = vectorClass.getField("z");
            windLookupAvailable = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            windLookupAvailable = false;
        }
        return windLookupAvailable;
    }

    private static boolean ensureVineLookup() {
        if (vineLookupAttempted) {
            return vineLookupAvailable;
        }
        vineLookupAttempted = true;
        try {
            Class<?> vineHelperClass = Class.forName("net.diebuddies.physics.vines.VineHelper");
            Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            vineGetSetting = vineHelperClass.getMethod("getSetting", blockStateClass);
            vineLookupAvailable = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            vineLookupAvailable = false;
        }
        return vineLookupAvailable;
    }

    public record PhysicsWindSample(Vec3 wind, float strength, float gust) {
        public static final PhysicsWindSample CALM = new PhysicsWindSample(Vec3.ZERO, 0.0F, 0.0F);
    }
}
