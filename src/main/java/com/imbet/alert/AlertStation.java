package com.imbet.alert;

/**
 * A block that broadcasts alerts to the players around it.
 *
 * <p>Implemented by the Weather Alert Siren and the Weather Radio. Everything about <em>how</em> an
 * alert is decided, re-broadcast, and held on screen lives in {@link AlertStationNetwork}; a station
 * only has to say which channel it is on, how far it carries, and whether it makes its own noise.
 *
 * <p>This is what lets a radio take part in the network for free instead of duplicating it: the
 * registry sweeps stations, not sirens, and reads the mode and coverage off whichever block is
 * standing at that position.
 */
public interface AlertStation {
    /** All Alerts. */
    int MODE_ALL = 0;
    /** Warnings only - watches are filtered out. */
    int MODE_WARNINGS_ONLY = 1;
    /** Muted: the station stops broadcasting and drops its redstone. */
    int MODE_MUTED = 2;

    /** 0 = all alerts, 1 = warnings only, 2 = muted. */
    int alertMode();

    /** How far this station carries, in blocks. Read from config, so it can differ per block type. */
    double alertRangeBlocks();

    /**
     * Whether this station should ring the faux alert tones into the players it reaches.
     *
     * <p>A siren rings from its own block position and answers false here, so a player standing next
     * to their own siren does not hear every alert twice. A radio answers true, because its whole
     * point is to be heard indoors where the siren block is not.
     */
    boolean ringsTones();

    /**
     * True when the station is switched on and may warn.
     *
     * <p>A siren with no live control panel behind it is disarmed and stays silent - which is what makes
     * a warning system something you build. Every other station is armed by definition, so this defaults
     * to true and a radio needs no wiring.
     */
    default boolean armed() {
        return true;
    }

    /** The word the alert banner uses for this station, e.g. "SIREN" or "RADIO". */
    default String stationKind() {
        return "STATION";
    }

    /** Appends the station kind so the network can label a banner without knowing the block type. */
    static String kindOf(AlertStation station) {
        return station == null ? "" : station.stationKind();
    }
}
