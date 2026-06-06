package com.imbet.weather;

import java.util.Locale;

public enum FunnelMorphology {
    DEFAULT,
    WEDGE,
    ELEPHANT_TRUNK,
    DRILL_BIT,
    FLOATER;

    public boolean shaped() {
        return this != DEFAULT;
    }

    public static FunnelMorphology byName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        try {
            return FunnelMorphology.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
