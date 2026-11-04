package com.imbet.chase;

import com.imbet.Config;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.DynamicStormState;
import com.imbet.weather.StormClassifier;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.TornadoWindField;
import com.imbet.weather.WeatherDataStreamService;
import com.imbet.weather.WeatherPhysicalSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The chase log's tick: for every player, what vortex is near them, what it is doing, and what they are
 * standing in.
 *
 * <p>The storm side reads the same published snapshots the radar and the damage survey read, and the wind is
 * the vortex's own field sampled at the player - {@code WeatherPhysicalSnapshot.sampleAt} - which is the same
 * model the anemometer and the survey use, so a gust in the log is a gust the instruments would have agreed
 * with. That also removes a whole class of special cases: a funnel cloud returns calm because it has no
 * surface circulation, a storm out of range returns calm because its own falloff says so, and a decaying
 * storm is not in the list to begin with. The log therefore does not decide what the wind is; it decides only
 * what is worth remembering.
 *
 * <p>Everything that is a judgement lives in the configuration: the radius that counts as chasing, the
 * strength a storm needs before it is worth starting a log entry, how long a storm may be missing before the
 * entry closes, and whether creative players count. Everything that is arithmetic lives in
 * {@link ChaseTracker}, which is why this class is small.
 *
 * <p>Only a tornado is logged. The published snapshots carry dust devils, fire whirls, snow devils,
 * gustnados, waterspouts and funnel clouds as well, because a radar should draw them and a camera should
 * photograph them - but a chase log makes two claims the wider list cannot support: it rates what it files on
 * the published EF bands, which rate tornadoes, and it calls the encounter a chase of a storm. A whirl is not
 * a tornado, a waterspout is not over anybody's land, and a funnel cloud puts nothing on the ground at all,
 * so none of them is a chase however strong it is. The entry list is therefore the tornadic circulations -
 * the supercell tornado, its satellites, the floater and the landspout - and the notes the spotter net and the
 * storm camera already carry about the chase log excluding waterspouts are true of it rather than aspirational.
 *
 * <p>Open encounters are kept in memory rather than saved. A chase is only a chase while it is happening,
 * and the thing that must survive a reload is the finished record; a draft reloaded from disk would resume
 * counting a storm that may have died in the meantime.
 */
public final class ChaseLogService {
    /** Open encounters, keyed by player. A player is in one level at a time, so the level is not part of the key. */
    private static final Map<UUID, ChaseTracker> TRACKERS = new HashMap<>();

    private ChaseLogService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || !Config.CHASE_LOG_ENABLED.get()) {
            return;
        }
        int interval = Math.max(1, Config.CHASE_LOG_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        long now = level.getGameTime();
        long grace = Config.CHASE_LOG_END_GRACE_TICKS.get();
        double radius = Config.CHASE_LOG_RADIUS_BLOCKS.get();
        double minimumStormMph = Config.CHASE_LOG_MIN_STORM_MPH.get();
        int maxRecent = Config.CHASE_LOG_MAX_ENCOUNTERS.get();
        List<Observation> observations = observations(level);
        ChaseLogSavedData data = ChaseLogSavedData.get(level);
        for (ServerPlayer player : level.players()) {
            if (!counts(player)) {
                continue;
            }
            ChaseTracker tracker = TRACKERS.computeIfAbsent(player.getUUID(), id -> new ChaseTracker());
            Vec3 position = player.position();
            Set<String> seen = new HashSet<>();
            for (Observation observation : observations) {
                WindReading reading = observation.readAt(level, position);
                if (!reading.withinRange(radius)) {
                    continue;
                }
                // A storm starts a log entry only if it is worth logging. One that is already being chased
                // keeps its entry however much it weakens - riding a tornado down is a chase, and it is how a
                // player learns what a rope-out is; requiring the floor every pass would delete the second
                // half of every chase.
                if (!tracker.tracking(observation.key()) && observation.stormMph() < minimumStormMph) {
                    continue;
                }
                tracker.observe(observation.key(), observation.label(), observation.stormMph(), reading.speedMph(),
                        reading.distanceBlocks(), observation.widthBlocks(), now, interval);
                seen.add(observation.key());
            }
            tracker.closeUnseen(seen, now, grace);
            for (ChaseRecord record : tracker.drainFinished()) {
                data.record(player, record, maxRecent);
                if (Config.CHASE_LOG_ANNOUNCE.get()) {
                    player.sendSystemMessage(Component.literal("Chase logged: " + ChaseLogReadout.recordLine(record))
                            .withStyle(ChatFormatting.GOLD));
                }
            }
        }
    }

    /** A player leaving the level takes their open encounters with them, logged as they stood. */
    public static void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        ChaseTracker tracker = TRACKERS.remove(playerId);
        if (tracker != null) {
            tracker.closeAll();
        }
    }

    /** The lines the weather computer prints for this player. */
    public static List<String> readoutLines(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        ChaseLedger ledger = ChaseLogSavedData.get(level).ledger(player.getUUID());
        return ChaseLogReadout.lines(ledger, level.getGameTime(), Config.CHASE_LOG_RECENT_LINES.get());
    }

    /**
     * Whether this player's chases are worth recording. Spectators are not there at all, and a creative player
     * can be inside a tornado with a full inventory and no consequence, so by default neither counts - the log
     * is a record of chases, and a chase implies having been in danger.
     */
    private static boolean counts(ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return false;
        }
        return !player.isCreative() || Config.CHASE_LOG_COUNT_CREATIVE.get();
    }

    /**
     * The vortices worth considering, from the level's own snapshots. When the snapshot stream has nothing to
     * say - the first tick of a level, a level whose weather scan has not run yet - the storms are read
     * directly instead, the same fallback the damage survey uses, so the log does not go blind on a reload.
     */
    private static List<Observation> observations(ServerLevel level) {
        List<WeatherPhysicalSnapshot> snapshots = WeatherDataStreamService.snapshots(level);
        List<Observation> observations = new ArrayList<>();
        if (!snapshots.isEmpty()) {
            for (WeatherPhysicalSnapshot snapshot : snapshots) {
                // The narrower question, not trackableVortex: a radar draws a fire whirl and a log of chases
                // must not file one. See the class comment for why the two lists differ.
                if (!snapshot.chaseableVortex() || snapshot.circulationPosition() == null) {
                    continue;
                }
                observations.add(new Observation(snapshot));
            }
            return observations;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return observations;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead
                    || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            DynamicStormState state = DynamicStormController.stateFor(storm);
            // The same rule as the snapshot path, said in the terms this path has: a storm at a tornado stage
            // on the ground, never a waterspout. A whirl here is not a StormObject at all, so nothing else
            // has to be excluded.
            if (storm.isTornadoFormingOrGreater() && !storm.attrib_waterSpout) {
                observations.add(new Observation(
                        "storm:" + storm.ID,
                        StormClassifier.labelFor(storm, StormSubtype.TORNADO),
                        storm.pos,
                        TornadoWindField.maxWindMph(storm),
                        Math.max(8.0D, state.damagePathWidth()),
                        null,
                        storm,
                        StormSubtype.TORNADO
                ));
            }
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                if (!satellite.touchedDown() || satellite.absorbed()) {
                    continue;
                }
                observations.add(new Observation(
                        "storm:" + storm.ID + ":" + satellite.id(),
                        StormSubtype.SATELLITE_TORNADO.label(),
                        DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y),
                        DynamicStormController.satelliteWindMph(storm, satellite),
                        Math.max(6.0D, satellite.damagePathWidth()),
                        null,
                        storm,
                        StormSubtype.SATELLITE_TORNADO
                ));
            }
        }
        return observations;
    }

    /** One vortex in play: its identity, what it is called, and what it is worth. */
    private record Observation(String key, String label, Vec3 position, double stormMph, double widthBlocks,
                               WeatherPhysicalSnapshot snapshot, StormObject storm, StormSubtype subtype) {
        /** The snapshot path, which is the one that carries the vortex's own sampled field. */
        Observation(WeatherPhysicalSnapshot snapshot) {
            this(snapshot.sourceKey(), snapshot.stageLabel() == null || snapshot.stageLabel().isBlank()
                            ? snapshot.subtype().label()
                            : snapshot.stageLabel() + " " + snapshot.subtype().label(),
                    snapshot.circulationPosition(),
                    snapshot.maxWindMph(),
                    Math.max(4.0D, snapshot.damagePathWidth()),
                    snapshot,
                    null,
                    snapshot.subtype());
        }

        /**
         * What this vortex is doing where the player stands. The snapshot answers with the vortex's own field;
         * the fallback path asks the wind field for the storm directly. Both return calm - which reads as "out
         * of range" because a calm sample carries an infinite distance - when the player is beyond the vortex
         * or the vortex puts nothing on the ground.
         */
        WindReading readAt(ServerLevel level, Vec3 position) {
            TornadoWindField.WindSample sample = snapshot != null
                    ? snapshot.sampleAt(position)
                    : (storm == null ? TornadoWindField.WindSample.CALM
                            : TornadoWindField.sample(storm, level, position));
            if (sample.distanceFromCore() == Double.MAX_VALUE) {
                return WindReading.NONE;
            }
            double distance = Math.max(0.0D, sample.distanceFromCore());
            return new WindReading(sample.speedMph(), distance);
        }
    }

    /** What a player's own position measured for one vortex, and how far away it was. */
    private record WindReading(double speedMph, double distanceBlocks) {
        static final WindReading NONE = new WindReading(0.0D, Double.MAX_VALUE);

        boolean withinRange(double radius) {
            return distanceBlocks <= radius;
        }
    }
}
