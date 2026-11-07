package com.imbet.weather;

import java.util.Locale;

public enum FunnelMorphology {
    DEFAULT,
    WEDGE,
    ELEPHANT_TRUNK,
    DRILL_BIT,
    FLOATER,
    CONE,
    STOVEPIPE,
    ROPE,
    CORKSCREW,
    BOWL,
    /** A thin, dead-straight column: the slender tornado that stands like a needle under the cloud. */
    NEEDLE,
    /** A long rope thrown into big S-curves across the sky. */
    SNAKE,
    /** A wide column with near-parallel sides and a slight bulge, broader than a stovepipe and straighter than a wedge. */
    BARREL,
    /** Wide at the ground and at the cloud, pinched to a narrow waist between. */
    HOURGLASS;

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