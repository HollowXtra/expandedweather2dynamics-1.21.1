package com.imbet.weather;

public enum StormSubtype {
    NONE("Storm Cell"),
    SUPERCELL("Supercell"),
    TORNADO("Tornado"),
    SATELLITE_TORNADO("Satellite Tornado"),
    LANDSPOUT("Landspout"),
    WATERSPOUT("Waterspout"),
    CYCLONE("Cyclone"),
    DUST_DEVIL("Dust Devil"),
    SANDSTORM("Sandstorm"),
    SNOWSTORM("Snowstorm"),
    SQUALL_LINE("Squall Line"),
    WEATHER2_PARTICLE_STORM("Particle Storm"),
    FIRENADO("Firenado");

    private final String label;

    StormSubtype(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
