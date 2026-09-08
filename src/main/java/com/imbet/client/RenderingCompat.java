package com.imbet.client;

import net.minecraft.client.Minecraft;

/**
 * Runtime detection for rendering mods (Sodium, Iris) that change how the vanilla
 * shader pipeline and chunk rendering behave. Class-based checks keep these soft
 * dependencies off the compile classpath and out of the load order.
 */
public final class RenderingCompat {
    private static final boolean SODIUM_LOADED = classPresent("net.caffeinemc.mods.sodium.client.SodiumClientMod");
    private static final boolean IRIS_LOADED = classPresent("net.irisshaders.iris.api.IrisApi");

    private static boolean cachedPackActive;
    private static long lastPackCheckTick = Long.MIN_VALUE;

    private RenderingCompat() {
    }

    /**
     * Sodium renders chunks with its own shader programs, so vanilla shader overrides
     * (foliage wind) never apply to chunk-rendered blocks, and marker vertex data baked
     * into Sodium meshes is best avoided entirely.
     */
    public static boolean isSodiumLoaded() {
        return SODIUM_LOADED;
    }

    public static boolean isIrisLoaded() {
        return IRIS_LOADED;
    }

    /**
     * True while an Iris shaderpack is actively in use. With a pack active, Iris replaces
     * the core shader programs: vanilla shader overrides are bypassed, and custom mod
     * programs the pack does not provide fall back to a dummy program. Checked on the
     * client thread and cached once per game tick.
     */
    public static boolean isShaderPackActive() {
        if (!IRIS_LOADED) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long tick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (tick == lastPackCheckTick) {
            return cachedPackActive;
        }
        lastPackCheckTick = tick;
        cachedPackActive = queryIrisPackActive();
        return cachedPackActive;
    }

    private static boolean queryIrisPackActive() {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(apiClass.getMethod("isShaderPackInUse").invoke(api));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, RenderingCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}