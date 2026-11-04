package com.imbet.client;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.CompatHooks;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * How far the level-of-detail terrain renderer draws, in blocks.
 *
 * <p>Weather2 sizes its particle cutoffs as {@code GameRenderer.getRenderDistance() * 4}, which is
 * the same formula as vanilla's far clip plane ({@code getDepthFar}). With a LOD mod that pairing
 * breaks: LOD users normally run a LOW vanilla render distance and let the LOD mod draw the
 * distance, so a tornado can be culled while the terrain it stands in is still rendered thousands
 * of blocks further. {@link com.imbet.mixin.GameRendererMixin} hands that value through this class,
 * so every renderer that asks "how far out do I draw" - Weather2's own funnel and debris, our
 * satellite and sub-vortex particles, anything else that culls by render distance - gets an answer
 * that matches what the client is actually drawing.
 *
 * <p>Nothing here is a compile-time dependency: Distant Horizons is read through its public API by
 * reflection, Voxy through its config class. Every failure path returns "no LOD renderer", and the
 * probe disables itself for the session after repeated failures so a version mismatch can never
 * turn into a per-frame exception in the render loop.
 */
public final class LodViewDistance {

    /** How often the probe re-reads the LOD renderer, in ticks. A second is plenty: these settings change by hand. */
    private static final int REFRESH_INTERVAL_TICKS = 20;

    private static final String DH_API_DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";
    private static final String DH_API_CONFIG = "com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig";
    private static final String DH_API_GRAPHICS = "com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig";
    private static final String DH_API_VALUE = "com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue";
    private static final String VOXY_CONFIG = "me.cortex.voxy.client.config.VoxyConfig";

    private static boolean dhResolved;
    private static Field dhConfigsField;
    private static Method dhGraphicsMethod;
    private static Method dhRenderingEnabledMethod;
    private static Method dhChunkRenderDistanceMethod;
    private static Method dhValueGetMethod;

    private static boolean voxyResolved;
    private static Field voxyConfigField;
    private static Method voxyRenderingEnabledMethod;

    private static long lastProbeTick = Long.MIN_VALUE;
    private static float cachedBlocks;
    private static boolean cachedActive;
    private static String cachedSource = "";
    private static boolean loggedState;

    private LodViewDistance() {
    }

    /**
     * Extends a vanilla render distance (in blocks) to the LOD renderer's, when one is drawing
     * further than vanilla does. Returns the input unchanged in every other case, so with no LOD mod
     * this is a no-op.
     */
    public static float extend(float vanillaBlocks) {
        try {
            float lod = activeDistanceBlocks();
            return lod > vanillaBlocks ? lod : vanillaBlocks;
        } catch (Throwable ignored) {
            return vanillaBlocks;
        }
    }

    /**
     * The same extension for a distance that gates <em>spawning</em> rather than drawing: the
     * tornado's own debris cloud, sub-vortex swirls, horizontal vortices and stick debris are all
     * spawned only within a configured range, so with an LOD mod raising the funnel's draw distance
     * alone the funnel would arrive without the debris column underneath it. Returns the input
     * unchanged when no LOD renderer is drawing, so without one this is exactly the configured value.
     */
    public static int extendSpawnRange(int configuredBlocks) {
        try {
            float lod = activeDistanceBlocks();
            return lod > configuredBlocks ? Math.round(lod) : configuredBlocks;
        } catch (Throwable ignored) {
            return configuredBlocks;
        }
    }

    /** The LOD renderer's draw distance in blocks, or 0 when none is drawing. */
    public static float activeDistanceBlocks() {
        refresh();
        return cachedActive ? cachedBlocks : 0.0F;
    }

    private static void refresh() {
        if (!Config.LOD_COMPAT_ENABLED.get()) {
            cachedActive = false;
            cachedBlocks = 0.0F;
            cachedSource = "";
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            cachedActive = false;
            cachedBlocks = 0.0F;
            return;
        }
        long tick = minecraft.level.getGameTime();
        // Absolute difference, not subtraction: game time restarts per level, so leaving and rejoining
        // a world (or changing dimension) can move it backwards, which would otherwise leave the probe
        // convinced it refreshed a moment ago and pinned to the previous world's answer.
        if (lastProbeTick != Long.MIN_VALUE && Math.abs(tick - lastProbeTick) < REFRESH_INTERVAL_TICKS) {
            return;
        }
        lastProbeTick = tick;

        int manual = Config.LOD_COMPAT_VIEW_DISTANCE_BLOCKS.get();
        if (manual > 0) {
            cachedBlocks = manual;
            cachedActive = true;
            cachedSource = "manual";
        } else if (Config.LOD_COMPAT_AUTO_DISTANCE.get()) {
            probeAuto();
        } else {
            cachedActive = false;
            cachedBlocks = 0.0F;
            cachedSource = "";
            return;
        }
        clamp();
        maybeLog();
    }

    /**
     * One line, once per session, saying what the probe decided. Both LOD mods are read by reflection,
     * so when someone reports that the compat is not doing anything, this is what says whether the API
     * shape was recognised at all versus whether the renderer simply is not drawing.
     */
    private static void maybeLog() {
        if (loggedState || !Config.LOG_STARTUP.getAsBoolean()) {
            return;
        }
        if (cachedActive) {
            loggedState = true;
            ExpandedWeather2Dynamics.LOGGER.info(
                    "LOD compat: extending the weather draw distance to {} blocks ({}).",
                    Math.round(cachedBlocks),
                    cachedSource
            );
        } else if (CompatHooks.lodRendererLoaded()) {
            loggedState = true;
            ExpandedWeather2Dynamics.LOGGER.info(
                    "LOD compat: a level-of-detail renderer is installed but none is drawing (or its view distance could not be read), so the vanilla draw distance is unchanged."
            );
        }
    }

    private static void probeAuto() {
        float dh = probeDistantHorizons();
        if (dh > 0.0F) {
            cachedBlocks = dh;
            cachedActive = true;
            cachedSource = "Distant Horizons";
            return;
        }
        if (probeVoxy()) {
            // Voxy publishes no view distance we can trust across versions - its own setting is
            // stored in top-level-LOD units, not blocks - so when it is the one rendering we extend
            // to the configured ceiling instead of guessing a formula from its config.
            cachedBlocks = Config.LOD_COMPAT_MAX_VIEW_DISTANCE_BLOCKS.get();
            cachedActive = true;
            cachedSource = "Voxy (distance capped, set viewDistanceBlocks to match it)";
            return;
        }
        cachedActive = false;
        cachedBlocks = 0.0F;
        cachedSource = "";
    }

    private static void clamp() {
        float max = Config.LOD_COMPAT_MAX_VIEW_DISTANCE_BLOCKS.get();
        if (cachedBlocks > max) {
            cachedBlocks = max;
        }
        if (cachedBlocks < 64.0F) {
            cachedActive = false;
            cachedBlocks = 0.0F;
        }
    }

    /**
     * Distant Horizons' LOD distance in blocks, or 0 when it is absent, not initialised yet (its API
     * statics are null until DH finishes loading) or not drawing. Read through DH's own interfaces
     * rather than the implementation classes, because those are package-private.
     */
    private static float probeDistantHorizons() {
        if (!CompatHooks.distantHorizonsLoaded()) {
            return 0.0F;
        }
        try {
            if (!resolveDistantHorizons()) {
                return 0.0F;
            }
            Object configs = dhConfigsField.get(null);
            if (configs == null) {
                return 0.0F;
            }
            if (dhRenderingEnabledMethod != null) {
                Object enabled = dhRenderingEnabledMethod.invoke(dhGraphicsMethod.invoke(configs));
                Object value = enabled == null ? null : dhValueGetMethod.invoke(enabled);
                if (value instanceof Boolean drawing && !drawing) {
                    return 0.0F;
                }
            }
            Object distance = dhChunkRenderDistanceMethod.invoke(dhGraphicsMethod.invoke(configs));
            Object chunks = distance == null ? null : dhValueGetMethod.invoke(distance);
            if (chunks instanceof Number number) {
                // DH reports chunks; the rest of the mod works in blocks.
                return Math.max(0.0F, number.floatValue() * 16.0F);
            }
        } catch (Throwable ignored) {
            dhChunkRenderDistanceMethod = null;
            dhResolved = false;
        }
        return 0.0F;
    }

    private static boolean resolveDistantHorizons() {
        if (dhResolved) {
            return dhConfigsField != null;
        }
        dhResolved = true;
        try {
            Class<?> delayed = Class.forName(DH_API_DELAYED);
            Class<?> config = Class.forName(DH_API_CONFIG);
            Class<?> graphics = Class.forName(DH_API_GRAPHICS);
            Class<?> value = Class.forName(DH_API_VALUE);
            dhConfigsField = delayed.getField("configs");
            dhGraphicsMethod = config.getMethod("graphics");
            dhRenderingEnabledMethod = findMethod(graphics, "renderingEnabled");
            // DH names this "chunkRenderDistance"; other API builds have used the longer forms, and the
            // probe is name-tolerant because we cannot test every DH version a player might have.
            dhChunkRenderDistanceMethod = findMethod(
                    graphics,
                    "chunkRenderDistance",
                    "chunkRenderDistanceRadius",
                    "renderDistanceRadius",
                    "renderDistance"
            );
            dhValueGetMethod = value.getMethod("getValue");
            return dhConfigsField != null && dhGraphicsMethod != null && dhChunkRenderDistanceMethod != null && dhValueGetMethod != null;
        } catch (Throwable ignored) {
            dhConfigsField = null;
            return false;
        }
    }

    /** Whether Voxy is installed AND drawing. Distance is deliberately not read - see {@link #probeAuto()}. */
    private static boolean probeVoxy() {
        if (!CompatHooks.voxyLoaded() && !resolveVoxyConfigClass()) {
            return false;
        }
        try {
            if (!resolveVoxy()) {
                // The mod id is present but its config class is not one we know: still compat, since
                // something is drawing LODs we cannot measure.
                return CompatHooks.voxyLoaded();
            }
            Object config = voxyConfigField.get(null);
            if (config == null) {
                return CompatHooks.voxyLoaded();
            }
            if (voxyRenderingEnabledMethod != null) {
                Object drawing = voxyRenderingEnabledMethod.invoke(config);
                if (drawing instanceof Boolean enabled) {
                    return enabled;
                }
            }
            return true;
        } catch (Throwable ignored) {
            voxyResolved = false;
            return CompatHooks.voxyLoaded();
        }
    }

    private static boolean resolveVoxyConfigClass() {
        try {
            Class.forName(VOXY_CONFIG);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean resolveVoxy() {
        if (voxyResolved) {
            return voxyConfigField != null;
        }
        voxyResolved = true;
        try {
            Class<?> type = Class.forName(VOXY_CONFIG);
            voxyConfigField = type.getField("CONFIG");
            voxyRenderingEnabledMethod = findMethod(type, "isRenderingEnabled");
            return voxyConfigField != null;
        } catch (Throwable ignored) {
            voxyConfigField = null;
            return false;
        }
    }

    private static Method findMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getMethod(name);
            } catch (Throwable ignored) {
                // try the next name; the caller treats null as "this build does not expose it"
            }
        }
        return null;
    }
}
