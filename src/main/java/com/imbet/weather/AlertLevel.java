package com.imbet.weather;

/**
 * The fictional EAS-style alert levels, strongest last.
 *
 * <p>Each level carries the colour its banner is drawn in, so the on-screen alert, the siren's
 * redstone output and the product code all describe the same severity. Watches are gold and low
 * urgency, warnings are red and drive redstone, and a tornado emergency is the loudest thing the
 * system can say.
 */
public enum AlertLevel {
    NONE(0, "No Alert", 0xFF607D8B, "NONE"),
    TORNADO_WATCH(0, "Tornado Watch", 0xFFE0B33A, "TOR WATCH"),
    SEVERE_THUNDERSTORM_WATCH(0, "Severe Thunderstorm Watch", 0xFFC9A227, "SVA"),
    SEVERE_STORM_WARNING(12, "Severe Thunderstorm Warning", 0xFFF08A00, "SVR"),
    TORNADO_WARNING(15, "Tornado Warning", 0xFFE01B1B, "TOR"),
    PDS_TORNADO_WARNING(15, "PDS Tornado Warning", 0xFFA80C0C, "TOR"),
    TORNADO_EMERGENCY(15, "Tornado Emergency", 0xFFFF1744, "TOR");

    private final int redstonePower;
    private final String label;
    private final int color;
    private final String productCode;

    AlertLevel(int redstonePower, String label, int color, String productCode) {
        this.redstonePower = redstonePower;
        this.label = label;
        this.color = color;
        this.productCode = productCode;
    }

    public int redstonePower() {
        return redstonePower;
    }

    public String label() {
        return label;
    }

    /** The accent colour this level's banner is drawn in. */
    public int color() {
        return color;
    }

    /** The compact product code, e.g. "TOR" or "SVR". */
    public String productCode() {
        return productCode;
    }

    public boolean isWarning() {
        return redstonePower >= 12;
    }

    /** Watches are advisory: gold, quiet, and never sounded. */
    public boolean isWatch() {
        return this == TORNADO_WATCH || this == SEVERE_THUNDERSTORM_WATCH;
    }

    public boolean activatesEas() {
        return this == TORNADO_WARNING
                || this == PDS_TORNADO_WARNING
                || this == TORNADO_EMERGENCY
                || this == SEVERE_STORM_WARNING;
    }

    /** The level a name refers to, falling back to {@link #NONE} for anything unrecognised. */
    public static AlertLevel byName(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        for (AlertLevel level : values()) {
            if (level.name().equals(name)) {
                return level;
            }
        }
        return NONE;
    }
}
