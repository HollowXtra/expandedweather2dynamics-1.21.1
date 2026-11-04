package com.imbet.weather;

public enum StormSubtype {
    NONE("Storm Cell"),
    SUPERCELL("Supercell"),
    TORNADO("Tornado"),
    SATELLITE_TORNADO("Satellite Tornado"),
    FLOATER_TORNADO("Floater Tornado"),
    LANDSPOUT("Landspout"),
    WATERSPOUT("Waterspout"),
    FUNNEL_CLOUD("Funnel Cloud"),
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
    THUNDERSTORM("Thunderstorm"),
    // Volcanic, and appended rather than inserted: this enum travels as its ordinal, so a new member goes on
    // the end and nothing that is already in a world or on the wire is renamed.
    LAVA_WHIRL("Lava Whirl"),
    STEAM_DEVIL("Steam Devil");

    private final String label;

    StormSubtype(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
