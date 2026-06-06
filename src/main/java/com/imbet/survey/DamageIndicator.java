package com.imbet.survey;

public enum DamageIndicator {
    ROOF_DAMAGED("Roof damaged", 75, 105),
    ROOF_MISSING("Roof missing", 96, 135),
    WALLS_COLLAPSED("Exterior walls collapsed", 136, 165),
    FOUNDATION_CLEARED("Foundation cleared", 200, 321),
    LARGE_BRANCHES_BROKEN("Large branches broken", 65, 95),
    SOFTWOOD_UPROOTED("Softwood uprooted", 86, 125),
    HARDWOOD_UPROOTED("Hardwood uprooted", 111, 165),
    TOTAL_DEBARKING("Total debarking", 166, 321),
    WEATHER_BLOCK_DAMAGED("Weather blocks torn down", 86, 135),
    WEATHER_BLOCK_DESTROYED("Weather blocks destroyed", 136, 200);

    private final String label;
    private final int minMph;
    private final int maxMph;

    DamageIndicator(String label, int minMph, int maxMph) {
        this.label = label;
        this.minMph = minMph;
        this.maxMph = maxMph;
    }

    public String label() {
        return label;
    }

    public int minMph() {
        return minMph;
    }

    public int maxMph() {
        return maxMph;
    }
}
