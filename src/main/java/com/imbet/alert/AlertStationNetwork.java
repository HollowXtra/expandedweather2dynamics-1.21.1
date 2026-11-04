package com.imbet.alert;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.AlertBroadcaster;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The station network: which alert stations exist in a level, and which of them owes a player a
 * warning right now.
 *
 * <p>This is what makes alerts a thing the player builds rather than a global broadcast. Every
 * station - a Weather Alert Siren or a Weather Radio - registers itself here while its chunk is
 * loaded, and the registry outlives the chunk, because it is saved with the level. That is why a
 * siren still warns the area around it while the player is out in the field and the siren's block is
 * unloaded. The scan runs every level tick, asks each station that is due for the strongest product
 * in force at its own position, and sends a banner to every player inside that station's radius. A
 * station whose block is gone is dropped as soon as its chunk is loaded again, so the registry heals
 * itself.
 *
 * <p>Coverage, tones and the word the banner uses are stored per station rather than read from a
 * single config value, which is what lets a siren and a radio sit in the same registry with different
 * ranges and different behaviour. They are refreshed from the live block whenever its chunk is
 * loaded, so cycling a station takes effect at once.
 *
 * <p>A station only re-broadcasts an alert that is still in force once per refresh interval. That
 * repetition is deliberate: it is what holds the banner on screen for as long as the hazard lasts,
 * and the client never restarts its animation for a refresh of the same product.
 */
public final class AlertStationNetwork extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_alert_stations";
    private static final Factory<AlertStationNetwork> FACTORY = new Factory<>(AlertStationNetwork::new, AlertStationNetwork::load);

    private final Map<Long, Station> stations = new LinkedHashMap<>();

    private static final class Station {
        private final BlockPos pos;
        private int mode;
        private double range;
        private boolean tones;
        private String kind;
        private long lastBroadcastTick = -1_000_000L;
        private String lastAlertKey = "";

        private Station(BlockPos pos, int mode, double range, boolean tones, String kind) {
            this.pos = pos;
            this.mode = mode;
            this.range = range;
            this.tones = tones;
            this.kind = kind;
        }
    }

    public static AlertStationNetwork get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * Records a station, or refreshes it when anything about it has changed. Called when a station
     * loads and periodically while it ticks, so the registry always reflects the live blocks.
     */
    /**
     * Drops a station from the registry.
     *
     * <p>This is what a disarmed siren calls: a siren with no switched-on panel behind it is not a
     * station, so it must stop warning the area around it immediately, including while its chunk is
     * unloaded. Re-arming registers it again, so nothing has to be remembered.
     */
    public static void removeStation(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        AlertStationNetwork network = get(level);
        if (network.stations.remove(pos.asLong()) != null) {
            network.setDirty();
        }
    }

    public static void recordStation(ServerLevel level, BlockPos pos, AlertStation station) {
        if (level == null || pos == null || station == null) {
            return;
        }
        if (!station.armed()) {
            // A station that is switched off is not registered at all, so there is exactly one rule:
            // stations in the registry warn, and nothing else does.
            removeStation(level, pos);
            return;
        }
        AlertStationNetwork network = get(level);
        double range = Math.max(32.0D, station.alertRangeBlocks());
        boolean tones = station.ringsTones();
        String kind = AlertStation.kindOf(station);
        Station existing = network.stations.get(pos.asLong());
        if (existing == null) {
            network.stations.put(pos.asLong(), new Station(pos.immutable(), station.alertMode(), range, tones, kind));
            network.setDirty();
        } else if (existing.mode != station.alertMode()
                || existing.range != range
                || existing.tones != tones
                || !existing.kind.equals(kind)) {
            existing.mode = station.alertMode();
            existing.range = range;
            existing.tones = tones;
            existing.kind = kind;
            network.setDirty();
        }
    }

    /** The number of registered stations, for diagnostics. */
    public int stationCount() {
        return stations.size();
    }

    /**
     * Runs the network: every station that is due warns whoever its own coverage radius reaches.
     * Called from the level tick, so it keeps working with no station chunk loaded.
     */
    public static void broadcast(ServerLevel level) {
        if (level == null || !Config.ALERTS_ENABLED.get() || !Config.ALERTS_REQUIRE_SIREN.get()) {
            return;
        }
        AlertStationNetwork network = get(level);
        if (network.stations.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        long refresh = Math.max(20L, Config.ALERTS_BANNER_REFRESH_TICKS.get());
        boolean changed = false;
        Iterator<Station> iterator = network.stations.values().iterator();
        while (iterator.hasNext()) {
            Station station = iterator.next();
            if (!refreshFromWorld(level, station, iterator)) {
                changed = true;
                continue;
            }
            if (station.mode == AlertStation.MODE_MUTED) {
                station.lastAlertKey = "";
                continue;
            }
            if (gameTime - station.lastBroadcastTick < refresh) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(station.pos);
            AlertService.AlertResult result = AlertService.highestAlert(
                    level, center, station.mode == AlertStation.MODE_WARNINGS_ONLY);
            if (result.level() == AlertLevel.NONE) {
                station.lastAlertKey = "";
                continue;
            }
            int sent = 0;
            for (ServerPlayer player : level.players()) {
                double distance = player.position().distanceTo(center);
                if (distance > station.range) {
                    continue;
                }
                if (AlertBroadcaster.send(player, result, (int) Math.round(distance), station.kind, station.tones)) {
                    sent++;
                }
            }
            station.lastBroadcastTick = gameTime;
            station.lastAlertKey = result.level().name() + "|" + result.sourceId();
            changed = true;
            if (sent == 0 && Config.LOG_STARTUP.getAsBoolean()) {
                ExpandedWeather2Dynamics.LOGGER.debug("Alert station at {} held a {} with nobody in range.",
                        station.pos, result.productCode());
            }
        }
        if (changed) {
            network.setDirty();
        }
    }

    /**
     * Brings a station up to date with the live block, and drops it when the block is gone.
     *
     * <p>A station whose chunk is not loaded keeps the values it was last recorded with, so a siren
     * left behind at base goes on warning the area around it with the right coverage. Returns false
     * only when the station has been removed.
     */
    private static boolean refreshFromWorld(ServerLevel level, Station station, Iterator<Station> iterator) {
        if (!level.isLoaded(station.pos)) {
            return true;
        }
        if (level.getBlockEntity(station.pos) instanceof AlertStation live) {
            if (!live.armed()) {
                // Switched off since it was recorded: drop it rather than leave it warning.
                iterator.remove();
                return false;
            }
            station.mode = live.alertMode();
            station.range = Math.max(32.0D, live.alertRangeBlocks());
            station.tones = live.ringsTones();
            station.kind = AlertStation.kindOf(live);
            return true;
        }
        iterator.remove();
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Station station : stations.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", station.pos.asLong());
            entry.putInt("Mode", station.mode);
            entry.putDouble("Range", station.range);
            entry.putBoolean("Tones", station.tones);
            entry.putString("Kind", station.kind);
            entry.putLong("LastBroadcast", station.lastBroadcastTick);
            entry.putString("LastAlertKey", station.lastAlertKey);
            list.add(entry);
        }
        tag.put("Stations", list);
        return tag;
    }

    private static AlertStationNetwork load(CompoundTag tag, HolderLookup.Provider registries) {
        AlertStationNetwork network = new AlertStationNetwork();
        ListTag list = tag.getList("Stations", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (!entry.contains("Pos")) {
                continue;
            }
            Station station = new Station(
                    BlockPos.of(entry.getLong("Pos")),
                    entry.getInt("Mode"),
                    entry.contains("Range") ? entry.getDouble("Range") : 256.0D,
                    // Absent Tones means a station saved by the siren-only registry, and a siren must
                    // never ring tones into players: it already rings out of its own block.
                    entry.contains("Tones") && entry.getBoolean("Tones"),
                    entry.contains("Kind") ? entry.getString("Kind") : "SIREN");
            station.lastBroadcastTick = entry.getLong("LastBroadcast");
            station.lastAlertKey = entry.contains("LastAlertKey") ? entry.getString("LastAlertKey") : "";
            network.stations.put(station.pos.asLong(), station);
        }
        return network;
    }
}
