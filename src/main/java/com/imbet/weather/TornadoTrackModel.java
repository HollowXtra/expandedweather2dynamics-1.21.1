package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.weathersystem.storm.StormObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Real-world tornado track behaviour.
 *
 * Real damage paths do not snake symmetrically around the mean flow. They translate with the
 * ambient wind at 15-35 mph, veer to the RIGHT of the mean wind (deviant motion) - and the
 * stronger the tornado, the more it deviates and the slower it moves - they hold one curvature
 * direction for tens of minutes so the path reads as a long arc rather than a wiggle, and while
 * suction vortices are orbiting they superimpose a small periodic sway on the whole circulation
 * that scallops the edge of the damage swath.
 *
 * This class is the single source of truth for all of that so the steered and unsteered movement
 * paths cannot drift apart. Every value is derived from synced storm fields plus game time, so
 * client and server agree without any extra network state.
 */
public final class TornadoTrackModel {
    /** Minecraft blocks are metres: 1 mph = 0.44704 m/s, and a tick is 1/20 s. */
    private static final double MPH_TO_BLOCKS_PER_TICK = 0.44704D / 20.0D;
    /** Terrain roughness is resampled this often per storm; it only ever changes slowly. */
    private static final long ROUGHNESS_REFRESH_TICKS = 120L;
    /**
     * Below this speed (blocks per tick, about 2 mph) a storm has no established track direction,
     * so it is allowed to swing straight onto the steering heading instead of curving there.
     * The translation floor is well above this, so an established tornado can never snap.
     */
    private static final double NO_TRACK_SPEED = 0.05D;

    private static final Map<Long, TerrainSample> TERRAIN = new HashMap<>();

    private record TerrainSample(long gameTime, double roughness) {
    }

    private TornadoTrackModel() {
    }

    public static boolean enabled() {
        return Config.TRACK_REALISTIC_ENABLED.get();
    }

    /** Writes a speed config in the units a player can reason about (mph) as blocks per tick. */
    public static double mphToBlocksPerTick(double mph) {
        return mph * MPH_TO_BLOCKS_PER_TICK;
    }

    /** True for a tornado that actually makes ground contact; waterspouts and plain storms are untouched. */
    public static boolean appliesTo(StormObject storm) {
        return enabled()
                && storm != null
                && !storm.attrib_waterSpout
                && DynamicStormController.isDynamicVortex(storm)
                && storm.isTornadoFormingOrGreater();
    }

    /** 0 = weak rope tornado, 1 = violent EF4/EF5 wedge. */
    public static double strengthFraction(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        double maxWind = TornadoWindField.maxWindMph(storm);
        double width = DynamicStormController.stateFor(storm).damagePathWidth();
        double stage = Mth.clamp((storm.levelCurIntensityStage - StormObject.STATE_FORMING) / 5.0D, 0.0D, 1.0D);
        return Mth.clamp(0.18D + maxWind / 320.0D + width / 420.0D + stage * 0.15D, 0.05D, 1.0D);
    }

    public static double weaknessFraction(StormObject storm) {
        return 1.0D - strengthFraction(storm);
    }

    /**
     * Extra clockwise veer from deviant motion. Deviant motion grows with intensity, so a weak
     * tornado tracks almost along the flow while a violent one runs well to the right of it.
     */
    public static double devianceDegrees(StormObject storm) {
        if (!appliesTo(storm)) {
            return 0.0D;
        }
        return Config.TRACK_DEVIANT_MOTION_MAX_DEGREES.get() * Math.pow(strengthFraction(storm), 1.25D);
    }

    /**
     * Slow persistent curvature, in degrees clockwise (positive = right of motion).
     *
     * The dominant term has a ~90 minute period, which is far longer than a tornado lifetime, so a
     * storm only ever experiences part of one swing: the path arcs instead of oscillating. It is
     * deliberately asymmetric - {@code 0.35 + 0.65 * sin} never drops below -0.30 - so a track
     * mostly curves right and only rarely backs left, which is what real paths do. A weaker
     * ~25 minute component adds a gentle undulation on top.
     */
    public static double arcDegrees(StormObject storm, long gameTime) {
        if (!appliesTo(storm)) {
            return 0.0D;
        }
        double strength = strengthFraction(storm);
        double amplitude = Config.TRACK_CURVATURE_DEGREES.get() * (0.55D + 0.45D * strength);
        double phase = (storm.ID % 89L) * 0.317D;
        double arc = Math.sin(gameTime * 0.000055D + phase);
        double drift = Math.sin(gameTime * 0.00021D + phase * 1.71D);
        double veer = 0.35D + 0.65D * arc;
        return amplitude * (veer + drift * 0.28D);
    }

    /**
     * Periodic sway of the whole circulation while suction vortices and satellites are orbiting it.
     * This is the parent-vortex wobble that scallops the edge of a multi-vortex damage path; it is
     * scaled by current orbiting activity, so a single vortex tornado tracks smoothly.
     */
    public static double scallopDegrees(StormObject storm, long gameTime) {
        if (!appliesTo(storm)) {
            return 0.0D;
        }
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double activity = Mth.clamp((state.subVortices().size() + state.satelliteTornadoes().size() * 0.6D) / 2.4D, 0.0D, 1.0D);
        if (activity <= 0.001D) {
            return 0.0D;
        }
        double amplitude = Config.TRACK_SCALLOP_DEGREES.get() * activity * (0.45D + 0.55D * weaknessFraction(storm));
        if (amplitude <= 0.0D) {
            return 0.0D;
        }
        double phase = (storm.ID % 71L) * 0.211D;
        double fast = Math.sin(gameTime * 0.0628D + phase);
        double slow = Math.sin(gameTime * 0.0231D + phase * 1.93D);
        return amplitude * (fast * 0.65D + slow * 0.35D);
    }

    /** Everything the heading should be rotated by because of the track model, in degrees clockwise. */
    public static double headingOffsetDegrees(StormObject storm, long gameTime) {
        if (!appliesTo(storm)) {
            return 0.0D;
        }
        return devianceDegrees(storm) + arcDegrees(storm, gameTime) + scallopDegrees(storm, gameTime);
    }

    /**
     * Fastest heading change the track is allowed to make, in degrees per tick. Violent tornadoes
     * carve the straightest, slowest-curving paths; ropes and weak funnels whip around far more.
     */
    public static double maxTurnDegreesPerTick(StormObject storm) {
        double base = Math.max(0.01D, Config.TRACK_MAX_TURN_DEGREES_PER_TICK.get());
        double weakness = storm == null ? 0.5D : weaknessFraction(storm);
        return base * (0.55D + 1.05D * weakness);
    }

    /**
     * Rotates {@code desiredMotion} back toward {@code currentMotion} so the track can only curve
     * at {@code maxTurnDegrees} per tick. Speed is preserved, so this bends a heading without
     * changing how fast the tornado is travelling. A null or near-zero current heading (a brand new
     * tornado) snaps straight to the desired heading instead of drifting sideways for minutes.
     */
    public static Vec3 limitTurn(Vec3 currentMotion, Vec3 desiredMotion, double maxTurnDegrees, boolean allowSnap) {
        if (desiredMotion == null) {
            return currentMotion == null ? Vec3.ZERO : currentMotion;
        }
        Vec3 desired = new Vec3(desiredMotion.x, 0.0D, desiredMotion.z);
        double desiredSpeed = desired.horizontalDistance();
        if (desiredSpeed <= 1.0E-7D) {
            return currentMotion == null ? desiredMotion : currentMotion;
        }
        if (currentMotion == null) {
            return desiredMotion;
        }
        Vec3 current = new Vec3(currentMotion.x, 0.0D, currentMotion.z);
        if (allowSnap || current.horizontalDistance() <= NO_TRACK_SPEED) {
            return desiredMotion;
        }
        if (maxTurnDegrees <= 0.0D) {
            maxTurnDegrees = 0.01D;
        }
        double currentYaw = yawDegrees(current);
        double desiredYaw = yawDegrees(desired);
        double delta = Mth.wrapDegrees(desiredYaw - currentYaw);
        double turn = Mth.clamp(delta, -maxTurnDegrees, maxTurnDegrees);
        double yaw = Math.toRadians(currentYaw + turn);
        return new Vec3(-Math.sin(yaw) * desiredSpeed, currentMotion.y, Math.cos(yaw) * desiredSpeed);
    }

    /**
     * Real translation speed. A tornado moves at roughly the mean cloud-layer wind speed, capped
     * into the honest 6-34 mph band from the config; violent tornadoes run a little below the flow
     * because they are deviant, and broken terrain bleeds off a little more.
     */
    public static double targetSpeedBlocksPerTick(StormObject storm, double flowSpeed) {
        double minMph = Config.TRACK_SPEED_MIN_MPH.get();
        double maxMph = Config.TRACK_SPEED_MAX_MPH.get();
        double min = mphToBlocksPerTick(Math.min(minMph, maxMph));
        double max = mphToBlocksPerTick(Math.max(minMph, maxMph));
        double speed = flowSpeed;
        if (appliesTo(storm)) {
            speed *= 1.0D - Config.TRACK_INTENSITY_SPEED_SLOWDOWN.get() * strengthFraction(storm);
            speed *= 1.0D - Config.TRACK_TERRAIN_SPEED_SLOWDOWN.get() * terrainRoughness(storm);
        }
        return Mth.clamp(speed, min, max);
    }

    /**
     * Forward-speed surge, as a multiplier on the track's translation speed.
     *
     * <p>Real tornadoes do not hold one pace. They crawl while the mesocyclone cycles and then lunge
     * forward as the circulation reorganises, and a long-track storm can run fast for minutes and then
     * nearly stall. This is a slow deterministic envelope - three octaves whose fastest cycle is still
     * about ninety seconds - so the track visibly gathers and eases rather than twitching. It is a pure
     * function of world time and storm id, so the server and every client agree and it cannot reset on a
     * reload.
     *
     * <p>Phase and rate are hashed per storm rather than offset by {@code storm.ID}, because a small
     * linear offset leaves two nearby tornadoes surging on almost the same beat, which reads as a global
     * pulse rather than two storms. Weak and rope funnels lurch hardest and a violent wedge is steadier -
     * but nothing is allowed to hold a perfectly flat pace, because a constant speed is what looks fake.
     */
    public static double speedSurge(StormObject storm, long gameTime) {
        if (!appliesTo(storm)) {
            return 1.0D;
        }
        double amplitude = Config.TRACK_SPEED_SURGE.get();
        if (amplitude <= 0.0D) {
            return 1.0D;
        }
        long hash = storm.ID * 0x9E3779B97F4A7C15L;
        double phase = ((hash >>> 11) * 0x1.0p-53) * Math.PI * 2.0D;
        double rate = 0.88D + ((hash >>> 7) & 0x1FL) * 0.015D;
        double surge = Math.sin(gameTime * 0.00042D * rate + phase) * 0.50D
                + Math.sin(gameTime * 0.00130D * rate + phase * 1.93D) * 0.32D
                + Math.sin(gameTime * 0.00340D * rate + phase * 2.71D) * 0.18D;
        return 1.0D + surge * amplitude * (0.75D + 0.55D * weaknessFraction(storm));
    }

    public static void clearCaches() {
        TERRAIN.clear();
    }

    /** Minecraft's own clockwise yaw convention: 0 = +Z (south), 90 = -X (west). */
    private static double yawDegrees(Vec3 horizontal) {
        return Math.toDegrees(Math.atan2(-horizontal.x, horizontal.z));
    }

    /**
     * Height spread over a coarse ring around the tornado, 0 (flat) to 0.30 (broken). Only the
     * server can read terrain; clients report flat, which is harmless because a client never
     * steers - it interpolates between synced server positions.
     */
    private static double terrainRoughness(StormObject storm) {
        if (storm == null || storm.manager == null || storm.pos == null) {
            return 0.0D;
        }
        Level level = storm.manager.getWorld();
        if (!(level instanceof net.minecraft.server.level.ServerLevel)) {
            return 0.0D;
        }
        long gameTime = level.getGameTime();
        TerrainSample cached = TERRAIN.get(storm.ID);
        if (cached != null && gameTime - cached.gameTime() < ROUGHNESS_REFRESH_TICKS) {
            return cached.roughness();
        }
        double roughness = sampleTerrainRoughness(level, BlockPos.containing(storm.pos.x, storm.pos.y, storm.pos.z));
        TERRAIN.put(storm.ID, new TerrainSample(gameTime, roughness));
        if (TERRAIN.size() > 512) {
            TERRAIN.clear();
        }
        return roughness;
    }

    private static double sampleTerrainRoughness(Level level, BlockPos center) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = -24; x <= 24; x += 24) {
            for (int z = -24; z <= 24; z += 24) {
                BlockPos column = center.offset(x, 0, z);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING);
                if (surface == null) {
                    continue;
                }
                min = Math.min(min, surface.getY());
                max = Math.max(max, surface.getY());
            }
        }
        if (min == Integer.MAX_VALUE || max == Integer.MIN_VALUE) {
            return 0.0D;
        }
        return Mth.clamp((max - min) / 90.0D, 0.0D, 0.30D);
    }
}
