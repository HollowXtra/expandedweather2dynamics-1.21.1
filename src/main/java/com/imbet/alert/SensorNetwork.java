package com.imbet.alert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a run of wire is actually for: sensors, wired back to one place that reads them all.
 *
 * <p>The mod already has wire. A siren is armed by running it to a control panel, and the walk that does it -
 * a bounded flood fill over the wire block - is the same walk a sensor network needs, because a wire does not
 * know what is on its ends. So this is the second thing to hang off that: the instruments a player has placed
 * out in the fields, each one wired back to the weather computer, which then reports the network rather than
 * only the air at its own four walls.
 *
 * <p>That is worth having for one reason and it is not decoration: every instrument in this mod is worth more
 * in a network than alone. One anemometer says what the wind is doing at one fencepost. Three of them strung
 * across a mile of road say what the gust front looks like as it arrives, which is the thing a chaser is
 * actually trying to find out - and the same is true of pressure (a barometer network shows a low's shape) and
 * of rain (a gauge network shows where the core is). This class decides the two things that make that real:
 * whether an instrument is actually attached to a run, and whether the run has everything a network needs.
 *
 * <p>Attachment is by adjacency rather than by a connection state, because the wire block has no facing and
 * does not draw a connection to an instrument: an instrument within one block of any wire on the run is on it,
 * which is also exactly how a player expects to wire something up. A network is complete when it carries all
 * three kinds, and what it is missing is reported as what it is missing, because "partial" is not a diagnosis.
 *
 * <p>Arithmetic only, so the whole of it is measured without a world.
 */
public final class SensorNetwork {

    private SensorNetwork() {
    }

    /** The kinds of reading a station can take, and therefore the kinds of instrument on a network. */
    public enum Kind {
        WIND("Anemometer"),
        PRESSURE("Barometer"),
        RAIN("Rain Gauge");

        private final String instrument;

        Kind(String instrument) {
            this.instrument = instrument;
        }

        /** What the instrument that supplies this reading is called. */
        public String instrument() {
            return instrument;
        }
    }

    /** One place on the wire, as a block position. */
    public record Node(int x, int y, int z) {
    }

    /** One instrument, and where it is standing. */
    public record Sensor(Kind kind, Node at) {
    }

    /** What a run of wire has on it. */
    public record Coverage(boolean wind, boolean pressure, boolean rain) {

        public static final Coverage NONE = new Coverage(false, false, false);

        public boolean complete() {
            return wind && pressure && rain;
        }

        public int count() {
            return (wind ? 1 : 0) + (pressure ? 1 : 0) + (rain ? 1 : 0);
        }

        /** What the network cannot report, as the names of the instruments it is missing. */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (!wind) {
                missing.add(Kind.WIND.instrument());
            }
            if (!pressure) {
                missing.add(Kind.PRESSURE.instrument());
            }
            if (!rain) {
                missing.add(Kind.RAIN.instrument());
            }
            return List.copyOf(missing);
        }

        /** How a station describes its own network in one line. */
        public String label() {
            if (count() == 0) {
                return "no sensors wired";
            }
            return complete()
                    ? "network complete (" + count() + " kinds)"
                    : "network partial - missing " + String.join(", ", missing());
        }
    }

    /** How far from a wire an instrument can stand and still be on it. */
    public static final int ATTACH_RADIUS_BLOCKS = 1;

    /**
     * Whether two points are on the same wire run: touching, which for a wire means within one block.
     *
     * <p>A missing point touches nothing. That is not defensive noise: an instrument with no position is what a
     * partially loaded chunk, a broken block entity or a caller that built a sensor by hand looks like, and the
     * network's whole job is to report what is on a cable - so the one thing it must never do is throw while a
     * player is reading it.
     */
    public static boolean touches(Node first, Node second) {
        if (first == null || second == null) {
            return false;
        }
        return Math.abs(first.x() - second.x()) <= ATTACH_RADIUS_BLOCKS
                && Math.abs(first.y() - second.y()) <= ATTACH_RADIUS_BLOCKS
                && Math.abs(first.z() - second.z()) <= ATTACH_RADIUS_BLOCKS;
    }

    /**
     * Whether an instrument is attached to a run: touching any part of it. The run is the wire the walk found
     * from the hub, so an instrument on a wire that goes nowhere is not on the network, which is the whole
     * reason this takes the walk's own result rather than the wire block's presence nearby.
     */
    public static boolean attached(Sensor sensor, Collection<Node> run) {
        if (sensor == null || sensor.at() == null || run == null || run.isEmpty()) {
            return false;
        }
        for (Node node : run) {
            if (touches(sensor.at(), node)) {
                return true;
            }
        }
        return false;
    }

    /** Which of a station's own instruments are on its network, in the order they were given. */
    public static List<Sensor> onNetwork(Collection<Sensor> sensors, Collection<Node> run) {
        List<Sensor> attachedSensors = new ArrayList<>();
        if (sensors == null) {
            return attachedSensors;
        }
        for (Sensor sensor : sensors) {
            if (attached(sensor, run)) {
                attachedSensors.add(sensor);
            }
        }
        return List.copyOf(attachedSensors);
    }

    /** What a set of sensors can report between them. */
    public static Coverage coverage(Collection<Sensor> sensors) {
        if (sensors == null || sensors.isEmpty()) {
            return Coverage.NONE;
        }
        boolean wind = false;
        boolean pressure = false;
        boolean rain = false;
        for (Sensor sensor : sensors) {
            if (sensor == null || sensor.kind() == null) {
                continue;
            }
            switch (sensor.kind()) {
                case WIND -> wind = true;
                case PRESSURE -> pressure = true;
                case RAIN -> rain = true;
            }
        }
        return new Coverage(wind, pressure, rain);
    }

    /**
     * How far apart the two outermost sensors of one kind are, in blocks. This is what a network buys over a
     * single instrument and it is the only number that measures it: three gauges in one garden are a garden,
     * and three gauges across 600 blocks are a line of storms crossing a county.
     */
    public static double spread(Collection<Sensor> sensors, Kind kind) {
        if (sensors == null) {
            return 0.0D;
        }
        double largest = 0.0D;
        for (Sensor first : sensors) {
            for (Sensor second : sensors) {
                if (first == null || second == null || first.kind() != kind || second.kind() != kind) {
                    continue;
                }
                largest = Math.max(largest, distance(first.at(), second.at()));
            }
        }
        return largest;
    }

    /**
     * How much more a network is worth than one instrument, as a multiplier: the spread of the network against
     * the 160 blocks a single instrument can see, capped at four. It is the same reading either way - what
     * changes is how much country it describes.
     */
    public static final double SINGLE_INSTRUMENT_SPAN_BLOCKS = 160.0D;

    public static double networkReach(Collection<Sensor> sensors, Kind kind) {
        double span = spread(sensors, kind);
        if (span <= 0.0D) {
            return 0.0D;
        }
        return Math.min(4.0D, 1.0D + span / SINGLE_INSTRUMENT_SPAN_BLOCKS);
    }

    private static double distance(Node first, Node second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
