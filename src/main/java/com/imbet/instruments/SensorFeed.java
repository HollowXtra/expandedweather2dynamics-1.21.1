package com.imbet.instruments;

import com.imbet.Config;
import com.imbet.alert.SensorNetwork;
import com.imbet.alert.SirenWiring;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The instruments wired to a weather computer, read as one network.
 *
 * <p>The mod's cable already exists and already does one job: a run of wire from a siren to a control panel is
 * what arms the siren. This is the same cable doing the other job a cable can do, which is to carry readings
 * back to the place a player actually sits - and the reason it is worth carrying them is that every instrument
 * in this mod is worth more in a network than alone. One anemometer says what the wind is doing at one
 * fencepost; three of them strung across a mile of road say what a gust front looks like as it arrives, which
 * is the thing a chaser is trying to find out.
 *
 * <p>The rules are the cable's own, unchanged: the run is walked from the computer, a run longer than the
 * configured limit counts as no connection at all, and no chunk is ever loaded to look at it. An instrument
 * within one block of any wire on the run is on the network, which is also exactly how a player expects to wire
 * something up, because the wire block has no facing and draws no connection to it.
 *
 * <p>Only the three instruments that take the three readings are sites. The weather vane is not one on purpose:
 * it reads direction, and a direction is not a wind speed, so folding it in would put degrees into the network's
 * spread arithmetic beside miles per hour. The vane is still worth wiring for what it does on its own, which is
 * to be understood from across a base.
 *
 * <p>This class reads the world, so it runs on the server and only when something asks: a player using the
 * computer, or an open computer screen on its refresh. It is never part of a per-tick world pass.
 */
public final class SensorFeed {

    private SensorFeed() {
    }

    /** One instrument on the network, and what it says. */
    public record Site(BlockPos pos, SensorNetwork.Kind kind, double value, String line) {
    }

    /** The whole network, as it stands right now. */
    public record Feed(List<Site> sites, SensorNetwork.Coverage coverage, boolean runComplete) {

        public boolean empty() {
            return sites.isEmpty();
        }

        /** Whether the computer has a network it can report at all. */
        public boolean wired() {
            return runComplete && !sites.isEmpty();
        }

        /** The spread of one kind across the network, in blocks. */
        public double spread(SensorNetwork.Kind kind) {
            double largest = 0.0D;
            for (Site first : sites) {
                for (Site second : sites) {
                    if (first.kind() != kind || second.kind() != kind) {
                        continue;
                    }
                    largest = Math.max(largest, Math.sqrt(first.pos().distSqr(second.pos())));
                }
            }
            return largest;
        }

        /** How much more country one kind's readings describe than a single instrument's would. */
        public double reach(SensorNetwork.Kind kind) {
            double span = spread(kind);
            if (span <= 0.0D) {
                return 0.0D;
            }
            return Math.min(4.0D, 1.0D + span / SensorNetwork.SINGLE_INSTRUMENT_SPAN_BLOCKS);
        }
    }

    /** Reads the network at a computer, or an empty feed when there is none. */
    public static Feed read(ServerLevel level, BlockPos computer) {
        if (level == null || computer == null || !Config.SENSOR_NETWORK_ENABLED.get()) {
            return new Feed(List.of(), SensorNetwork.Coverage.NONE, false);
        }
        SirenWiring.Run run = SirenWiring.wireRun(level, computer, Config.SENSOR_WIRE_MAX_LENGTH.get());
        if (run.wires().isEmpty()) {
            return new Feed(List.of(), SensorNetwork.Coverage.NONE, false);
        }
        // Keyed by position so an instrument beside two wires on the same run is one instrument, which is what a
        // player means by wiring a gauge to one cable that happens to pass on both sides of it.
        Map<Long, Site> found = new LinkedHashMap<>();
        for (BlockPos wire : run.wires()) {
            for (int dx = -SensorNetwork.ATTACH_RADIUS_BLOCKS; dx <= SensorNetwork.ATTACH_RADIUS_BLOCKS; dx++) {
                for (int dy = -SensorNetwork.ATTACH_RADIUS_BLOCKS; dy <= SensorNetwork.ATTACH_RADIUS_BLOCKS; dy++) {
                    for (int dz = -SensorNetwork.ATTACH_RADIUS_BLOCKS; dz <= SensorNetwork.ATTACH_RADIUS_BLOCKS; dz++) {
                        BlockPos pos = wire.offset(dx, dy, dz);
                        if (found.containsKey(pos.asLong()) || !level.isLoaded(pos)) {
                            continue;
                        }
                        BlockEntity entity = level.getBlockEntity(pos);
                        if (entity instanceof SensorSite site) {
                            found.put(pos.asLong(), new Site(pos.immutable(), site.sensorKind(),
                                    site.sensorValue(), site.sensorLine()));
                        }
                    }
                }
            }
        }
        List<Site> sites = List.copyOf(found.values());
        SensorNetwork.Coverage coverage = SensorNetwork.coverage(sites.stream()
                .map(site -> new SensorNetwork.Sensor(site.kind(), new SensorNetwork.Node(
                        site.pos().getX(), site.pos().getY(), site.pos().getZ())))
                .toList());
        return new Feed(sites, coverage, run.complete());
    }

    /**
     * The network as lines a player can read, ready for a chat message or a computer tab.
     *
     * <p>An unwired computer is not reported as an empty network: it says it has no network, because "no sensors
     * wired" and "sensors wired but no readings" are different situations and a player wiring a cable needs to
     * know which one they are in. In particular a run that stops short of the instrument - the usual mistake -
     * reports itself as an incomplete walk rather than as an absent instrument.
     */
    public static List<String> reportLines(ServerLevel level, BlockPos computer) {
        if (!Config.SENSOR_NETWORK_ENABLED.get()) {
            return List.of("Sensor network: switched off in the configuration.");
        }
        Feed feed = read(level, computer);
        if (feed.empty()) {
            boolean anyWire = !SirenWiring.wireRun(level, computer, 1).wires().isEmpty();
            return List.of(anyWire
                    ? "Sensor network: no instruments on this cable yet"
                    : "Sensor network: no cable - run wire from the computer to an instrument");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Sensor network: " + (feed.runComplete()
                ? feed.coverage().label()
                : "incomplete run - " + feed.sites().size() + " instrument(s), cable past the limit of "
                        + Config.SENSOR_WIRE_MAX_LENGTH.get() + " wire blocks"));
        for (Site site : feed.sites()) {
            lines.add("  " + site.kind().instrument() + " at " + describe(site.pos()) + ": " + site.line());
        }
        StringBuilder spans = new StringBuilder();
        for (SensorNetwork.Kind kind : SensorNetwork.Kind.values()) {
            double spread = feed.spread(kind);
            if (spread <= 0.0D) {
                continue;
            }
            if (spans.length() > 0) {
                spans.append(", ");
            }
            spans.append(kind.instrument().toLowerCase(Locale.ROOT)).append(' ')
                    .append(String.format(Locale.ROOT, "%.0f blocks (%.1fx reach)", spread, feed.reach(kind)));
        }
        if (spans.length() > 0) {
            lines.add("  Spread: " + spans);
        }
        return List.copyOf(lines);
    }

    /** The instrument nearest a position on the network, for a readout that points at something. */
    public static Site nearest(ServerLevel level, BlockPos computer, SensorNetwork.Kind kind) {
        Feed feed = read(level, computer);
        Site best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 origin = Vec3.atCenterOf(computer);
        for (Site site : feed.sites()) {
            if (kind != null && site.kind() != kind) {
                continue;
            }
            double distance = origin.distanceTo(Vec3.atCenterOf(site.pos()));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = site;
            }
        }
        return best;
    }

    /** A block position as a player reads one, short enough for a line of chat. */
    private static String describe(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
