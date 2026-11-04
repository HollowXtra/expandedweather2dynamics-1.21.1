package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.LowPressureSyncPayload;
import com.imbet.network.LowPressureSyncPayload.LowPressureSnapshot;
import com.imbet.network.NetworkSafety;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The level's synoptic lows: where they come from, what they do with their lives, and what the
 * client is told about them.
 *
 * <p>A low is born from the atmosphere, and only from the atmosphere. Warm air rising in the belt
 * around the world's equator line returns poleward, sinks, and the boundary where it meets the cold
 * air is a baroclinic zone - the corridor in which real lows form - so genesis puts its candidate
 * inside that corridor and asks the sky whether it has the gradient to organise there. A cluster of
 * storms can no longer father a low: storms are what a low organises once it exists, and a busy warm
 * sector deepens the system it belongs to, which is the feedback the old cluster path was reaching
 * for and the wrong way round.
 *
 * <p>Every low then runs its own life: deepening, maturity, occlusion and decay, drifting on the
 * same atmospheric steering flow the storms ride. The snapshot published to clients carries the
 * low's own front lengths and angles, so what the forecaster plots is the system the server is
 * actually running.
 */
public final class LowPressureService {
    private static final Map<ResourceKey<Level>, List<LowPressureSnapshot>> SYSTEMS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_ADVANCE_TICK = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_ENVIRONMENT_TICK = new HashMap<>();
    /** Environment sampling is far more expensive than a step, so lows re-sample on this cadence. */
    private static final long ENVIRONMENT_INTERVAL_TICKS = 1200L;
    /** Lows published to clients in one payload; the wire format carries at most this many. */
    private static final int MAX_SYNCED_LOWS = 8;

    private LowPressureService() {
    }

    public static void tick(ServerLevel level) {
        if (!Config.LOW_PRESSURE_ENABLED.get()) {
            SYSTEMS.remove(level.dimension());
            LAST_ADVANCE_TICK.remove(level.dimension());
            LAST_ENVIRONMENT_TICK.remove(level.dimension());
            return;
        }
        int interval = Math.max(20, Config.LOW_PRESSURE_SCAN_INTERVAL_TICKS.get());
        long gameTime = level.getGameTime();
        if (gameTime % interval != 0L) {
            return;
        }

        SynopticLowStore store = SynopticLowStore.get(level);
        long previous = LAST_ADVANCE_TICK.getOrDefault(level.dimension(), gameTime - interval);
        int elapsed = (int) Mth.clamp(gameTime - previous, 1L, interval * 4L);
        LAST_ADVANCE_TICK.put(level.dimension(), gameTime);

        Vec3 steering = WindSteeringManager.globalWindVector(level);
        // Tracked rather than derived from the clock, so any scan interval the user sets still
        // resamples the environment on this cadence instead of silently never doing it.
        Long lastEnvironment = LAST_ENVIRONMENT_TICK.get(level.dimension());
        boolean resample = lastEnvironment == null || gameTime - lastEnvironment >= ENVIRONMENT_INTERVAL_TICKS;
        if (resample) {
            LAST_ENVIRONMENT_TICK.put(level.dimension(), gameTime);
        }
        for (SynopticLow low : store.lows()) {
            if (resample) {
                low.setEnvironmentFactor(environmentFactor(ThermalCirculation.genesisScore(level, low.x(), low.z())));
            }
            // The belts push as well as supply: a low drifting through the corridor is nudged into it,
            // and one out over the sinking ridge is nudged away. Bounded, so this steers and never drives.
            Vec3 belt = ThermalCirculation.steeringBias(low.z());
            low.tick(gameTime, belt.horizontalDistanceSqr() <= 1.0E-9D ? steering : steering.add(belt), elapsed);
        }
        store.pruneFinished();
        capLows(store);

        deepenFromStormActivity(level, store, resample);
        maybeSpawnNaturally(level, store, gameTime);

        List<LowPressureSnapshot> systems = store.lows().stream()
                .sorted(Comparator.comparingDouble(SynopticLow::intensity).reversed())
                .limit(MAX_SYNCED_LOWS)
                .map(SynopticLow::snapshot)
                .toList();
        SYSTEMS.put(level.dimension(), systems);
        LowPressureSyncPayload payload = new LowPressureSyncPayload(systems);
        for (ServerPlayer player : level.players()) {
            NetworkSafety.sendToPlayer(player, payload);
        }
    }

    public static List<LowPressureSnapshot> active(ServerLevel level) {
        return SYSTEMS.getOrDefault(level.dimension(), List.of());
    }

    /**
     * The natural genesis path: the belt decides where a low may be born, and the sky decides whether
     * one is.
     *
     * <p>The candidate is no longer a random bearing from a random player. Latitude is the axis that
     * matters, so the candidate is placed at the baroclinic corridor's own latitude - inside the band,
     * jittered across its width - on whichever side of the equator line the players are, then along X
     * wherever they are, because a belt runs east-west across the world and has no ends. The score still
     * comes from sampling the sky; the belt decides which sky is eligible, and it is also where a share
     * of the score comes from, so a boundary fed by a strong rising belt beats a sharper one with
     * nothing feeding it.
     */
    private static void maybeSpawnNaturally(ServerLevel level, SynopticLowStore store, long gameTime) {
        if (!Config.LOW_PRESSURE_NATURAL_SPAWN_ENABLED.get() || !ThermalCirculation.enabled()) {
            return;
        }
        if (store.lows().size() >= Config.LOW_PRESSURE_MAX_NATURAL_LOWS.get()) {
            return;
        }
        long interval = Math.max(200, Config.LOW_PRESSURE_GENESIS_INTERVAL_TICKS.get());
        long lastGenesis = store.lastGenesisTick();
        if (lastGenesis != Long.MIN_VALUE && gameTime - lastGenesis < interval) {
            return;
        }
        store.setLastGenesisTick(gameTime);
        // A world with nobody in it is mostly unloaded, and an unloaded sky cannot be sampled.
        if (level.players().isEmpty()) {
            return;
        }
        RandomSource random = level.getRandom();
        if (random.nextDouble() > Config.LOW_PRESSURE_NATURAL_SPAWN_CHANCE.get()) {
            return;
        }

        double equatorZ = Config.TROPICAL_HEMISPHERE_EQUATOR_Z.get();
        double offset = Config.THERMAL_GENESIS_BELT_OFFSET_BLOCKS.get();
        double transition = Config.THERMAL_BELT_TRANSITION_BLOCKS.get();
        double poleward = averagePlayerZ(level) >= equatorZ ? 1.0D : -1.0D;
        double latitude = offset + (random.nextDouble() - 0.5D) * transition;
        double candidateZ = equatorZ + poleward * Math.max(1.0D, latitude);
        double candidateX = averagePlayerX(level)
                + (random.nextDouble() - 0.5D) * Config.LOW_PRESSURE_GENESIS_WINDOW_BLOCKS.get() * 2.0D;

        double score = ThermalCirculation.genesisScore(level, candidateX, candidateZ);
        double corridor = ThermalCirculation.genesisWeight(equatorZ, candidateZ, offset, transition);
        if (score * corridor < Config.LOW_PRESSURE_MIN_FRONTOGENESIS.get()) {
            return;
        }
        if (tooClose(store, candidateX, candidateZ)) {
            return;
        }
        announce(level, spawn(level, store, candidateX, candidateZ, score, random));
    }

    /**
     * Storms can deepen a low, and can never create one.
     *
     * <p>This is the half of the old cluster path worth keeping, and the old behaviour had it the wrong
     * way round. A low is born from the atmosphere - the rising belt's supply meeting a baroclinic zone
     * - and once it exists, the convection it organises on its own fronts feeds back into it: a busy
     * warm sector is a deepening system, which is why a low looks strongest on the day it produces most.
     * What a cluster of storms cannot do is father a system, because a low that has never been born has
     * no fronts for those storms to be on.
     */
    private static void deepenFromStormActivity(ServerLevel level, SynopticLowStore store, boolean resample) {
        if (!resample || store.lows().isEmpty()) {
            return;
        }
        int needed = Config.LOW_PRESSURE_MIN_STORMS.get();
        for (SynopticLow low : store.lows()) {
            int storms = organisedStormCount(level, low.x(), low.z(), low.radius() * 1.35D);
            if (storms < needed) {
                continue;
            }
            // Bounded on purpose: a nudge on a low the air already made, not a second way for storms to
            // build one. The factor itself is clamped, so repeated feedback saturates rather than runs away.
            double feedback = Mth.clamp((storms - needed + 1) * 0.06D, 0.0D, 0.25D);
            low.setEnvironmentFactor(low.environmentFactor() + (float) feedback);
        }
    }

    private static float environmentFactor(double energy) {
        return (float) Mth.clamp(0.70D + energy * 0.58D, 0.60D, 1.30D);
    }

    private static SynopticLow spawn(ServerLevel level, SynopticLowStore store, double x, double z, double energy, RandomSource random) {
        Vec3 steering = WindSteeringManager.globalWindVector(level);
        Vec3 heading = steering.horizontalDistanceSqr() <= 1.0E-9D ? new Vec3(0.923D, 0.0D, -0.382D) : steering;
        SynopticLow low = SynopticLow.born(
                store.nextId(),
                new Vec3(x, 0.0D, z),
                heading,
                energy,
                Config.LOW_PRESSURE_LIFE_TICKS.get(),
                random
        );
        low.setEnvironmentFactor(environmentFactor(energy));
        low.setBornIn(ThermalCirculation.beltLabel(z));
        store.add(low);
        return low;
    }

    private static void announce(ServerLevel level, SynopticLow low) {
        String belt = low.bornIn().isEmpty() ? "baroclinic zone" : low.bornIn().toLowerCase(Locale.ROOT);
        String message = String.format(Locale.ROOT, "Synoptic: a %s is forming in the %s here, %d block circulation",
                low.label().toLowerCase(Locale.ROOT), belt, Math.round(low.radius()));
        for (ServerPlayer player : level.players()) {
            if (horizontalDistance(player.position(), low.position()) > Config.LOW_PRESSURE_MAX_DISPLAY_RANGE_BLOCKS.get()) {
                continue;
            }
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GRAY));
        }
    }

    private static boolean tooClose(SynopticLowStore store, double x, double z) {
        double minimum = Config.LOW_PRESSURE_MERGE_DISTANCE_BLOCKS.get();
        for (SynopticLow low : store.lows()) {
            double dx = low.x() - x;
            double dz = low.z() - z;
            if (Math.sqrt(dx * dx + dz * dz) < minimum) {
                return true;
            }
        }
        return false;
    }

    /** Keeps a pathological cluster run from filling the level: the shallowest lows are dropped first. */
    private static void capLows(SynopticLowStore store) {
        int maximum = Math.max(MAX_SYNCED_LOWS, Config.LOW_PRESSURE_MAX_NATURAL_LOWS.get() + 4);
        while (store.lows().size() > maximum) {
            SynopticLow weakest = store.lows().stream().min(Comparator.comparingDouble(SynopticLow::intensity)).orElse(null);
            if (weakest == null) {
                return;
            }
            store.lows().remove(weakest);
            store.setDirty();
        }
    }

    private static int organisedStormCount(ServerLevel level, double x, double z, double radius) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return 0;
        }
        int count = 0;
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            if (!isOrganizedCell(storm)) {
                continue;
            }
            double dx = storm.pos.x - x;
            double dz = storm.pos.z - z;
            if (Math.sqrt(dx * dx + dz * dz) <= radius) {
                count++;
            }
        }
        return count;
    }

    private static double horizontalDistance(Vec3 position, Vec3 other) {
        double dx = position.x - other.x;
        double dz = position.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The mean Z of the players, which is what decides which side of the equator line the belt is on. */
    private static double averagePlayerZ(ServerLevel level) {
        double total = 0.0D;
        for (ServerPlayer player : level.players()) {
            total += player.getZ();
        }
        return level.players().isEmpty() ? Config.TROPICAL_HEMISPHERE_EQUATOR_Z.get() : total / level.players().size();
    }

    /**
     * The mean X of the players. A belt runs the whole way across the world, so X carries no latitude:
     * a low may be born at any X, and putting it near the players is what keeps the system where
     * somebody can grow to care about it.
     */
    private static double averagePlayerX(ServerLevel level) {
        double total = 0.0D;
        for (ServerPlayer player : level.players()) {
            total += player.getX();
        }
        return level.players().isEmpty() ? 0.0D : total / level.players().size();
    }

    private static boolean isOrganizedCell(StormObject storm) {
        return storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                || storm.isPrecipitating()
                || storm.attrib_precipitation
                || DynamicStormController.isDynamicVortex(storm);
    }

}
