package com.imbet.weather;

public enum StormSubtype {
    NONE("Storm Cell"),
    SUPERCELL("Supercell"),
    TORNADO("Tornado"),
    SATELLITE_TORNADO("Satellite Tornado"),
    FLOATER_TORNADO("Floater Tornado"),
    LANDSPOUT("Landspout"),
    WATERSPOUT("Waterspout"),
    CYCLONE("Cyclone"),
    HURRICANE("Hurricane"),
    DUST_DEVIL("Dust Devil"),
    SNOW_DEVIL("Snow Devil"),
    GUSTNADO("Gustnado"),
    WILDFIRE("Wildfire"),
    FIRE_WHIRL("Fire Whirl"),
    FLAMMAGENITUS("Flammagenitus Cloud"),
    SANDSTORM("Sandstorm"),
    SNOWSTORM("Snowstorm"),
    SQUALL_LINE("Squall Line"),
    WEATHER2_PARTICLE_STORM("Particle Storm"),
    THUNDERSTORM("Thunderstorm");

    private final String label;

    StormSubtype(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
