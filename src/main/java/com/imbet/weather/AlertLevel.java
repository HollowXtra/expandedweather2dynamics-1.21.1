package com.imbet.weather;

public enum AlertLevel {
    NONE(0, "No Alert"),
    TORNADO_WATCH(0, "Tornado Watch"),
    SEVERE_THUNDERSTORM_WATCH(0, "Severe Thunderstorm Watch"),
    TORNADO_WARNING(15, "Tornado Warning"),
    PDS_TORNADO_WARNING(15, "PDS Tornado Warning"),
    TORNADO_EMERGENCY(15, "Tornado Emergency"),
    SEVERE_STORM_WARNING(12, "Severe Thunderstorm Warning");

    private final int redstonePower;
    private final String label;

    AlertLevel(int redstonePower, String label) {
        this.redstonePower = redstonePower;
        this.label = label;
    }

    public int redstonePower() {
        return redstonePower;
    }

    public String label() {
        return label;
    }

    public boolean isWarning() {
        return redstonePower >= 12;
    }

    public boolean activatesEas() {
        return this == TORNADO_WARNING
                || this == PDS_TORNADO_WARNING
                || this == TORNADO_EMERGENCY
                || this == SEVERE_STORM_WARNING;
    }
}
