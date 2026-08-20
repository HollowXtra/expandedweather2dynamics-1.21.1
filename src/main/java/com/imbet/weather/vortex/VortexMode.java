package com.imbet.weather.vortex;

import com.imbet.weather.StormSubtype;

public enum VortexMode {
    LAND_TORNADO,
    WATERSPOUT,
    LANDSPOUT,
    DUST_DEVIL,
    FIRENADO,
    SATELLITE_TORNADO;

    public StormSubtype subtype() {
        return switch (this) {
            case WATERSPOUT -> StormSubtype.WATERSPOUT;
            case LANDSPOUT -> StormSubtype.LANDSPOUT;
            case DUST_DEVIL -> StormSubtype.DUST_DEVIL;
            case FIRENADO -> StormSubtype.FIRENADO;
            case SATELLITE_TORNADO -> StormSubtype.SATELLITE_TORNADO;
            case LAND_TORNADO -> StormSubtype.TORNADO;
        };
    }

    public boolean tornadoLike() {
        return this == LAND_TORNADO || this == FIRENADO || this == SATELLITE_TORNADO;
    }

    public boolean standalone() {
        return this == WATERSPOUT || this == LANDSPOUT || this == DUST_DEVIL;
    }

    public boolean fire() {
        return this == FIRENADO;
    }

    public boolean water() {
        return this == WATERSPOUT;
    }

    public boolean dustOnly() {
        return this == DUST_DEVIL;
    }
}
