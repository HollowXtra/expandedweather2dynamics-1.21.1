package com.imbet.weather;

/**
 * The convective outlook risk categories: the official SPC palette (light green general thunder,
 * dark green marginal, yellow slight, orange enhanced, red moderate, magenta high) followed by the
 * addon's two extension tiers, extreme and hyper extreme, for the once-a-decade outbreaks the real
 * ladder has no name for.
 *
 * <p>The published ladder stops at high risk because SPC has no product above it; extreme and hyper
 * are the addon's, and they are reachable only through the extended conditional intensity columns in
 * {@link SpcHazard} - a 45% tornado area with CIG 4 is extreme, the same area with CIG 5 is hyper.
 * Everything below them is untouched, so a real-table outlook still converts exactly as SPC would
 * publish it.
 *
 * <p>Enum ordinal, {@code severity} and the position on {@link #scale()} are deliberately the same
 * number, so severity round-trips through {@link #fromSeverity(int)} and can be used directly as an
 * index. Redstone power rises with the category until it saturates at the vanilla signal limit of 15
 * on high risk; extreme and hyper are redstone-identical to high, so a circuit that trips on high
 * risk still trips on them.
 */
public enum SpcRisk {
    NONE(0, "None", "No convective outlook", 0xFF263238, 0),
    TSTM(1, "TSTM", "General thunderstorm area", 0xFFC1E9C1, 2),
    MARGINAL(2, "MRGL", "Marginal risk of severe", 0xFF1E7B24, 5),
    SLIGHT(3, "SLGT", "Slight risk of severe", 0xFFF2E33B, 8),
    ENHANCED(4, "ENH", "Enhanced risk of severe", 0xFFF08A00, 11),
    MODERATE(5, "MDT", "Moderate risk of severe", 0xFFE01414, 13),
    HIGH(6, "HIGH", "High risk of severe", 0xFFE817C9, 15),
    EXTREME(7, "EXTREME", "Extreme risk of severe - violent tornado outbreak expected", 0xFF9B1FE0, 15),
    HYPER(8, "HYPER", "Hyper extreme risk of severe - historic outbreak expected", 0xFFE9D9FF, 15);

    /** The ladder as it is drawn on a panel or cycled in a readout, weakest first. */
    private static final SpcRisk[] SCALE = {
            TSTM, MARGINAL, SLIGHT, ENHANCED, MODERATE, HIGH, EXTREME, HYPER
    };

    private final int severity;
    private final String label;
    private final String description;
    private final int color;
    private final int redstonePower;

    SpcRisk(int severity, String label, String description, int color, int redstonePower) {
        this.severity = severity;
        this.label = label;
        this.description = description;
        this.color = color;
        this.redstonePower = redstonePower;
    }

    /**
     * The ordered categories, excluding {@link #NONE}. Callers iterate this instead of hard-coding
     * their own copy, so a panel cannot fall out of step with the ladder.
     */
    public static SpcRisk[] scale() {
        return SCALE.clone();
    }

    /** The strongest category the addon can issue. */
    public static int maxSeverity() {
        return HYPER.severity;
    }

    public int severity() {
        return severity;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public int color() {
        return color;
    }

    public int redstonePower() {
        return redstonePower;
    }

    public static SpcRisk max(SpcRisk first, SpcRisk second) {
        return first.severity >= second.severity ? first : second;
    }

    public static SpcRisk cap(SpcRisk risk, SpcRisk cap) {
        return risk.severity > cap.severity ? cap : risk;
    }

    /** True past the published SPC ladder, i.e. for the addon's own extreme and hyper tiers. */
    public boolean isExtended() {
        return severity >= EXTREME.severity;
    }

    /**
     * The category an <em>observed</em> conditional intensity group implies, given whatever the
     * forecast said. CIG 0-3 is the published ladder and changes nothing; CIG 4 is extreme and CIG 5
     * hyper extreme, because an environment actually producing a 200 mph tornado or a 130 mph derecho
     * gust is off the published scale no matter what was forecast that morning.
     *
     * <p>This is the one place that rule lives, so the SPC panel's live outlook and the radar's storm
     * colours cannot disagree about the same vortex.
     */
    public static SpcRisk escalateObserved(SpcRisk tier, int cig) {
        if (cig >= 5) {
            return max(tier, HYPER);
        }
        if (cig >= 4) {
            return max(tier, EXTREME);
        }
        return tier;
    }

    public static SpcRisk fromSeverity(int severity) {
        SpcRisk[] values = values();
        return values[Math.max(0, Math.min(values.length - 1, severity))];
    }

    /**
     * The risk category a hazard's probability and conditional intensity group produce. This is the
     * SPC's probability-to-categorical conversion - extended past the published ladder by the CIG
     * columns above 3 - and it is now the single source of truth for every category the addon
     * reports: the daily outlook, the live outlook, the panel and the readouts all come through
     * here, so the numbers in the product can never disagree with the colour shown.
     */
    public static SpcRisk fromHazard(SpcHazard hazard, int probability, int cig) {
        return fromSeverity(hazard.matrixSeverity(probability, cig));
    }
}
