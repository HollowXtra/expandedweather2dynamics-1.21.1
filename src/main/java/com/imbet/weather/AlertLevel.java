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
    // A tropical system's own product, ranked between a severe thunderstorm warning and a tornado
    // warning: a hurricane is not a thunderstorm, and it is not a tornado either.
    HURRICANE_WARNING(15, "Hurricane Warning", 0xFFB4232B, "HUW"),
    TORNADO_WARNING(15, "Tornado Warning", 0xFFE01B1B, "TOR"),
    PDS_TORNADO_WARNING(15, "PDS Tornado Warning", 0xFFA80C0C, "TOR"),
    TORNADO_EMERGENCY(15, "Tornado Emergency", 0xFFFF1744, "TOR"),
    // The one product that is about a day rather than about a storm: an outbreak is running over this
    // ground - several tornado-capable cells, or a line of them. It is watch-strength on purpose: it
    // never sounds a siren and never drives redstone, because it does not know where the tornadoes are
    // and the storm products that do still say so separately.
    OUTBREAK_ADVISORY(0, "Tornado Outbreak Advisory", 0xFF7C4DFF, "OBX"),
    // Volcanic products. An ash advisory is issued on a schedule rather than for an event and is
    // watch-strength: it never sounds a siren and never drives redstone. An ash warning is a
    // genuine warning for the area under the plume. A pyroclastic flow warning is the loudest
    // thing either system can say, because a density current is not something to shelter from -
    // it is something to already have left.
    VOLCANIC_ASH_ADVISORY(0, "Volcanic Ash Advisory", 0xFF8D6E63, "VAA"),
    VOLCANIC_ASH_WARNING(13, "Volcanic Ash Warning", 0xFF9E4B2B, "VAW"),
    PYROCLASTIC_FLOW_WARNING(15, "Pyroclastic Flow Warning", 0xFF4E342E, "PDC"),
    // A squall line that has been recognised as a derecho. It is a severe thunderstorm warning with a
    // longer memory: the straight-line wind is the same hazard, but this one has a name, a rung on its
    // own ladder, and a swath a hundred kilometres long behind it, so it is issued as its own product
    // rather than folded into the ordinary wind warning it would otherwise be.
    DERECHO_WARNING(15, "Derecho Warning", 0xFFB4232B, "DSW");

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

    /** Whether this level is about a system rather than about one storm. */
    public boolean isOutbreak() {
        return this == OUTBREAK_ADVISORY;
    }

    public boolean activatesEas() {
        return this == TORNADO_WARNING
                || this == PDS_TORNADO_WARNING
                || this == TORNADO_EMERGENCY
                || this == SEVERE_STORM_WARNING
                || this == HURRICANE_WARNING
                || this == VOLCANIC_ASH_WARNING
                || this == PYROCLASTIC_FLOW_WARNING;
    }

    /** Whether this level is a volcanic product rather than a storm one. */
    public boolean isVolcanic() {
        return this == VOLCANIC_ASH_ADVISORY || this == VOLCANIC_ASH_WARNING || this == PYROCLASTIC_FLOW_WARNING;
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
