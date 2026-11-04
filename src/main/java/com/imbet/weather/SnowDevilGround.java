package com.imbet.weather;

import java.util.List;

/**
 * Where a snow devil can stand, and what it takes to make one leave.
 *
 * <p>A snow devil is the dust devil's cold-country twin: bare frozen ground, cold air over it, and no
 * rain - which is drawn out in the sky, not on it, so the ground under the player can carry a whirl
 * while a shower two biomes over has nothing to do with it. This class is that decision, arithmetic
 * only, so the whole table can be measured without a world.
 *
 * <p>Two things about the real thing are what the table has to get right, and both of them were wrong
 * before it existed. The first is that <b>a snow devil stands on a snow layer</b>. What most snowy
 * ground actually is in this game is a grass block under a thin layer of snow, and a test that only
 * accepted a full snow block, packed ice or blue ice was therefore a test that rejected nearly every
 * place a snow devil would really form. The second is that <b>snowfall is not disqualifying weather</b>.
 * A whole-dimension rain flag is true whenever it snows anywhere - vanilla's snow is rain as far as
 * that flag is concerned - so a snow devil could neither form nor survive in the very biome it belongs
 * to. What disqualifies it is water: rain, thunder, open water under it, and ground that is neither
 * frozen nor cold enough to be frozen at all.
 *
 * <p>The last rule is the one that stops a whirl dying the moment it moves. A vortex wanders: over a
 * snow layer, off it onto a bare patch, across a frozen lake and back. Killing it the first tick its
 * centre left snow is what made them vanish as soon as they appeared, so the verdict is taken over a
 * <em>footprint</em> - the centre and the four corners of its own radius - and any one of those keeping
 * its footing is enough. On top of that a devil has a minimum life, so nothing can be erased before it
 * has been on screen long enough to be seen at all.
 */
public final class SnowDevilGround {

    /**
     * The temperature at or below which <em>bare</em> ground can read as cold country.
     *
     * <p>Frozen ground does not need it. A snow layer or a sheet of ice is its own evidence that the
     * country is cold, which is the only reading that lets a snow devil form on a frozen ocean: that
     * biome reports the sea's own temperature and nothing else, and the ice on top of it is the truth
     * about the ground. This constant is therefore what separates a bare cold field from a bare warm one,
     * not a gate in front of the frozen ground itself.
     */
    public static final double MAX_SURFACE_TEMPERATURE = 0.28D;

    /** How often a living devil re-checks the ground under it, in ticks. Once a second is plenty. */
    public static final int CHECK_INTERVAL_TICKS = 20;

    /** The life a devil is guaranteed regardless of the ground, in ticks. Ten seconds at the default. */
    public static final int DEFAULT_MIN_LIFE_TICKS = 200;

    private SnowDevilGround() {
    }

    /**
     * The ground under one point, in the model's own terms. Every flag is what is actually there rather
     * than what the biome says should be: a snow layer is a snow layer, and ice is ice.
     */
    public record Surface(double baseTemperature, boolean snowAtFeet, boolean snowLayerBelow,
                          boolean snowBlockBelow, boolean powderSnowBelow, boolean iceBelow,
                          boolean openWaterBelow) {
    }

    /** Why a point is or is not ground a snow devil can stand on. */
    public enum Verdict {
        GROUND,
        SNOWING,
        TOO_WARM,
        NO_SNOW,
        OPEN_WATER,
        RAINING,
        THUNDERING
    }

    /**
     * The verdict for one point.
     *
     * @param liquidRainHere    rain, as against snow, falling on this point right now
     * @param precipitatingHere anything falling on it at all, which only matters when fair weather is
     *                          being required
     * @param fairWeatherOnly   whether snowfall is disqualifying, which is the behaviour this mod had
     *                          before snow devils were allowed in their own weather
     */
    public static Verdict verdict(boolean liquidRainHere, boolean precipitatingHere, boolean fairWeatherOnly,
                                  boolean thundering, Surface surface) {
        if (thundering) {
            return Verdict.THUNDERING;
        }
        if (liquidRainHere) {
            return Verdict.RAINING;
        }
        if (fairWeatherOnly && precipitatingHere) {
            return Verdict.SNOWING;
        }
        if (surface == null) {
            return Verdict.NO_SNOW;
        }
        // Frozen evidence first, because water under ice is frozen ground: it is the surface that decides,
        // and a caller that reports both is reporting ice with a lake under it.
        if (frozen(surface)) {
            return Verdict.GROUND;
        }
        if (surface.openWaterBelow()) {
            return Verdict.OPEN_WATER;
        }
        return surface.baseTemperature() > MAX_SURFACE_TEMPERATURE ? Verdict.TOO_WARM : Verdict.NO_SNOW;
    }

    /** Whether one point is ground a devil may form on. */
    public static boolean canSpawn(boolean liquidRainHere, boolean precipitatingHere, boolean fairWeatherOnly,
                                   boolean thundering, Surface surface) {
        return verdict(liquidRainHere, precipitatingHere, fairWeatherOnly, thundering, surface) == Verdict.GROUND;
    }

    /**
     * The verdict over the points under a vortex. Any one of them that keeps its footing keeps the
     * vortex up - that is the whole rule - and if none of them does, the centre is what says why.
     */
    public static Verdict verdictOver(List<Surface> footprint, boolean liquidRainHere, boolean precipitatingHere,
                                      boolean fairWeatherOnly, boolean thundering) {
        if (thundering) {
            return Verdict.THUNDERING;
        }
        if (liquidRainHere) {
            return Verdict.RAINING;
        }
        if (fairWeatherOnly && precipitatingHere) {
            return Verdict.SNOWING;
        }
        if (footprint == null || footprint.isEmpty()) {
            return Verdict.NO_SNOW;
        }
        for (Surface surface : footprint) {
            if (verdict(false, false, false, false, surface) == Verdict.GROUND) {
                return Verdict.GROUND;
            }
        }
        return verdict(false, false, false, false, footprint.get(0));
    }

    /**
     * Whether a devil that has already formed keeps going. A verdict of ground keeps it alive outright;
     * anything else only ends it once it has lived out its guaranteed life, so a whirl can never be
     * erased in the second it appeared in.
     */
    public static boolean survives(Verdict verdict, long ageTicks, long minLifeTicks) {
        return ageTicks < Math.max(0L, minLifeTicks) || verdict == Verdict.GROUND;
    }

    /** Whether a vortex in this verdict is about to be taken off the map, for a readout or a harness. */
    public static boolean doomed(Verdict verdict, long ageTicks, long minLifeTicks) {
        return !survives(verdict, ageTicks, minLifeTicks);
    }

    /**
     * The points a vortex is sampled over: its centre first, then the four corners of its own radius.
     * The centre is first because it is the point that explains a death when none of them is ground.
     */
    public static int[][] footprintOffsets(int radius) {
        int r = Math.max(0, radius);
        return new int[][]{{0, 0}, {-r, -r}, {r, -r}, {-r, r}, {r, r}};
    }

    /** Whether a surface reports any kind of frozen ground on it at all. */
    public static boolean frozen(Surface surface) {
        return surface != null && (surface.snowAtFeet() || surface.snowLayerBelow() || surface.snowBlockBelow()
                || surface.powderSnowBelow() || surface.iceBelow());
    }
}
