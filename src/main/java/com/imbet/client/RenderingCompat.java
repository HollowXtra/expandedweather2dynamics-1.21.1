package com.imbet.client;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

/**
 * Runtime detection for rendering mods (Sodium, Iris) that change how the vanilla
 * shader pipeline and chunk rendering behave. Class-based checks keep these soft
 * dependencies off the compile classpath and out of the load order.
 */
public final class RenderingCompat {
    private static final boolean SODIUM_LOADED = classPresent("net.caffeinemc.mods.sodium.client.SodiumClientMod");

    /**
     * The Iris API class, which is what the shader mod actually exposes. It lives under {@code api.v0} - the
     * version is part of the package - and looking for it under {@code api} finds nothing at all, which is a
     * silent failure: every check below would be skipped and the mod would run as though Iris were absent.
     * The older, pre-v0 name is kept as a fallback so an older Iris or an Oculus build still answers.
     */
    private static final String IRIS_API = findIrisApi();
    private static final boolean IRIS_LOADED = IRIS_API != null;

    private static boolean cachedPackActive;
    private static long lastPackCheckTick = Long.MIN_VALUE;

    // The API is a singleton and its methods never change, so they are resolved once and kept.
    private static boolean irisResolved;
    private static Object irisApi;
    private static Method irisPackActive;
    private static Method irisShadowPass;

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
        cachedPackActive = queryIris(irisPackActive);
        return cachedPackActive;
    }

    /**
     * True while Iris is drawing its shadow map.
     *
     * <p>A pack renders the world a second time, from the sun's angle, into a depth map, and it does that by
     * running the level again - so every {@code RenderLevelStageEvent} stage fires twice per frame, once for
     * the visible pass and once for the shadow pass. Anything a stage handler draws is therefore drawn into
     * the shadow map as well, which is at best wasted work and at worst visible artefacts. This is asked live
     * rather than cached, because the two passes alternate within a single frame.
     *
     * <p>Without a pack there is no shadow pass at all, so this is only ever true under Iris.
     */
    public static boolean isRenderingShadowPass() {
        if (!IRIS_LOADED) {
            return false;
        }
        ensureIris();
        return queryIris(irisShadowPass);
    }

    private static boolean queryIris(Method method) {
        ensureIris();
        if (irisApi == null || method == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(irisApi));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    /** Resolves the API singleton and its methods on first use; failure is remembered, not retried. */
    private static void ensureIris() {
        if (irisResolved || !IRIS_LOADED) {
            return;
        }
        irisResolved = true;
        try {
            Class<?> apiClass = Class.forName(IRIS_API);
            irisApi = apiClass.getMethod("getInstance").invoke(null);
            irisPackActive = apiClass.getMethod("isShaderPackInUse");
            irisShadowPass = apiClass.getMethod("isRenderingShadowPass");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            irisApi = null;
        }
    }

    /** The first Iris API class that is actually on this runtime, or null if the mod is absent. */
    private static String findIrisApi() {
        // Iris itself, and Oculus, which is its Forge port: the version is part of the package.
        if (classPresent("net.irisshaders.iris.api.v0.IrisApi")) {
            return "net.irisshaders.iris.api.v0.IrisApi";
        }
        // The pre-v0 name, kept only so an older build still answers.
        if (classPresent("net.irisshaders.iris.api.IrisApi")) {
            return "net.irisshaders.iris.api.IrisApi";
        }
        return null;
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
