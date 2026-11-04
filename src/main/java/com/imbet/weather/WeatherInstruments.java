package com.imbet.weather;

import com.imbet.network.LowPressureSyncPayload.LowPressureSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.Locale;

/**
 * The readings every instrument block shares.
 *
 * <p>One place decides what the pressure is, what the rain rate is, which way the wind blows and
 * which storm is nearest, so a barometer, a rain gauge, a vane, a probe and the weather radio can
 * never disagree with each other about the same patch of air. Everything here is a pure read of the
 * mod's own synced state; nothing is randomised and nothing is stored, so two instruments a block
 * apart report the same weather.
 *
 * <p>The pressure model is the interesting one. A real station pressure is sea-level pressure scaled
 * for how high you are, which is why a mountain reads low no matter what the weather is doing. On top
 * of that base, a low-pressure system subtracts a deficit that grows as it gets closer and deeper,
 * and a vortex core subtracts a much larger one - a violent tornado genuinely drops pressure by
 * around 100 hPa at its centre. That is what gives a barometer a tendency worth reading and gives the
 * tornado probe a headline number.
 */
public final class WeatherInstruments {
    private static final double SEA_LEVEL_HPA = 1013.25D;
    /** Minecraft sea level, which is what the barometric formula is measured from. */
    private static final double SEA_LEVEL_Y = 63.0D;
    private static final double MAX_LOW_DEFICIT_HPA = 38.0D;
    private static final double MAX_CORE_DEFICIT_HPA = 96.0D;
    /** Distance from a low's centre at which its deficit has decayed to nothing. */
    private static final double LOW_INFLUENCE_BLOCKS = 2600.0D;
    /** Inside this radius a vortex subtracts its full deficit; beyond it, the drop falls off as 1/r^2. */
    private static final double CORE_PRESSURE_RADIUS_BLOCKS = 45.0D;
    private static final double CORE_INFLUENCE_BLOCKS = 150.0D;
    /** A torrential HP core is roughly this heavy, in millimetres per hour. */
    private static final double MAX_RAIN_MM_PER_HOUR = 55.0D;
    /** Wind at which a vortex's pressure deficit has fully developed. */
    private static final double FULL_DEFICIT_WIND_MPH = 200.0D;

    private static final String[] COMPASS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    private WeatherInstruments() {
    }

    // ------------------------------------------------------------------ pressure

    /** The full pressure picture at a position: what it is, and what contributed to it. */
    public record PressureReading(
            double hpa,
            double altitudeHpa,
            double lowDeficitHpa,
            double coreDeficitHpa,
            String lowLabel,
            double lowDistanceBlocks,
            String lowBearing
    ) {
    }

    public static PressureReading pressure(ServerLevel level, Vec3 position) {
        return pressure(level, position, CORE_INFLUENCE_BLOCKS);
    }

    public static PressureReading pressure(ServerLevel level, Vec3 position, double coreInfluenceBlocks) {
        double altitudeHpa = altitudePressureHpa(position.y);
        double lowDeficit = 0.0D;
        double coreDeficit = 0.0D;
        String lowLabel = "";
        double lowDistance = -1.0D;
        String lowBearing = "";

        if (level != null) {
            for (LowPressureSnapshot low : LowPressureService.active(level)) {
                double dx = low.x() - position.x;
                double dz = low.z() - position.z;
                double distance = Math.sqrt(dx * dx + dz * dz);
                double influence = Math.max(LOW_INFLUENCE_BLOCKS, low.radius());
                if (distance > influence) {
                    continue;
                }
                // A deeper low subtracts more, and the deficit fades smoothly with distance.
                double falloff = Math.pow(1.0D - distance / influence, 1.4D);
                lowDeficit = Math.max(lowDeficit, MAX_LOW_DEFICIT_HPA * low.intensity() * falloff);
                if (lowDistance < 0.0D || distance < lowDistance) {
                    lowDistance = distance;
                    lowLabel = low.label();
                    lowBearing = bearingToward(position, new Vec3(low.x(), position.y, low.z()));
                }
            }
            coreDeficit = coreDeficitHpa(level, position, coreInfluenceBlocks);
        }

        double hpa = altitudeHpa - lowDeficit - coreDeficit;
        return new PressureReading(hpa, altitudeHpa, lowDeficit, coreDeficit, lowLabel, lowDistance, lowBearing);
    }

    /** Sea-level pressure scaled for altitude by the standard barometric formula. */
    /**
     * The belt an instrument is standing in, as one line, from the one source every readout shares.
     *
     * <p>It is here rather than in each instrument because latitude is a property of the place, not of
     * the device: the barometer, the computer and the forecast readout standing in the same belt must
     * not be able to disagree about which one it is, and nothing that reads this can name a belt that
     * the genesis code does not use.
     */
    public static String beltLine(double z) {
        return ThermalCirculation.beltLabel(z) + " - " + ThermalCirculation.beltNote(z);
    }

    public static double altitudePressureHpa(double y) {
        double metres = (y - SEA_LEVEL_Y);
        double term = 1.0D - 2.25577E-5D * metres;
        if (term <= 0.01D) {
            // Above the top of any atmosphere the formula breaks down; keep it finite and tiny.
            return 1.0D;
        }
        return SEA_LEVEL_HPA * Math.pow(term, 5.25588D);
    }

    /**
     * The pressure dropped by the nearest vortex, which is the number a tornado probe is really
     * there to record. Inside the core the full deficit applies; outside it decays as 1/r^2, so the
     * reading climbs back toward the ambient value as a storm recedes.
     */
    private static double coreDeficitHpa(ServerLevel level, Vec3 position, double influenceBlocks) {
        double influence = Math.max(CORE_PRESSURE_RADIUS_BLOCKS * 2.0D, influenceBlocks);
        return TornadoThreatService.nearestThreat(level, position, influence)
                .map(threat -> {
                    double distance = Math.max(threat.distanceBlocks(), 1.0D);
                    double geometry = distance <= CORE_PRESSURE_RADIUS_BLOCKS
                            ? 1.0D
                            : (CORE_PRESSURE_RADIUS_BLOCKS * CORE_PRESSURE_RADIUS_BLOCKS) / (distance * distance);
                    // Squared, because a pressure deficit scales with the square of the wind speed
                    // (Bernoulli). Linear scaling would have a 60 mph vortex dropping 29 hPa, which
                    // is violent-tornado territory for a circulation that weak; squaring puts it at
                    // under 9, and still gives a 200 mph core its full ~100 hPa drop.
                    double ratio = Mth.clamp((float) (threat.windMph() / FULL_DEFICIT_WIND_MPH), 0.0F, 1.0F);
                    double strength = ratio * ratio;
                    return MAX_CORE_DEFICIT_HPA * strength * Math.min(1.0D, geometry);
                })
                .orElse(0.0D);
    }

    /** How fast the pressure is changing, in hPa per hour. */
    public record Tendency(String label, double hPaPerHour, boolean falling, boolean rapidlyFalling) {
    }

    /**
     * Classifies a pressure change rate.
     *
     * <p>Real thresholds: about 1 hPa/hour is a barometer worth watching and 2.5 hPa/hour is a
     * genuinely rapid fall, which is the signature of a deep low or a storm core closing in.
     */
    public static Tendency tendency(double hPaPerHour) {
        double rate = Double.isFinite(hPaPerHour) ? hPaPerHour : 0.0D;
        if (rate <= -2.5D) {
            return new Tendency("FALLING RAPIDLY", rate, true, true);
        }
        if (rate <= -0.8D) {
            return new Tendency("FALLING", rate, true, false);
        }
        if (rate >= 0.8D) {
            return new Tendency("RISING", rate, false, false);
        }
        return new Tendency("STEADY", rate, false, false);
    }

    // ------------------------------------------------------------------ wind

    public static TornadoWindField.WindSample wind(Level level, Vec3 position) {
        return TornadoWindField.sampleStrongest(level, position);
    }

    /** The compass point the wind is blowing <em>from</em>, which is how wind direction is reported. */
    public static String windFromLabel(Vec3 mphVector) {
        if (mphVector == null || mphVector.lengthSqr() < 1.0E-6D) {
            return "CALM";
        }
        return compass(windFromDegrees(mphVector));
    }

    public static double windFromDegrees(Vec3 mphVector) {
        if (mphVector == null) {
            return 0.0D;
        }
        return (headingOfMotion(mphVector.x, mphVector.z) + 180.0D) % 360.0D;
    }

    public static double headingOfMotion(double x, double z) {
        if (Math.abs(x) < 1.0E-6D && Math.abs(z) < 1.0E-6D) {
            return 0.0D;
        }
        // Minecraft is +X east and +Z south, and compass headings run clockwise from north (-Z).
        double degrees = Math.toDegrees(Math.atan2(x, -z));
        return (degrees + 360.0D) % 360.0D;
    }

    public static String compass(double degrees) {
        double normalised = ((degrees % 360.0D) + 360.0D) % 360.0D;
        int index = (int) Math.round(normalised / 22.5D) % 16;
        return COMPASS[index];
    }

    /** The 16-point compass bearing from one position toward another. */
    public static String bearingToward(Vec3 from, Vec3 to) {
        return compass(headingOfMotion(to.x - from.x, to.z - from.z));
    }

    // ------------------------------------------------------------------ precipitation

    /**
     * The rain rate on the ground at a position, in millimetres per hour.
     *
     * <p>Gated by the storm's own virga strength, which is the point: rain that evaporates under the
     * cloud base never reaches a gauge on the ground, so a dry storm with a visibly raining shaft
     * leaves the gauge empty. The storm's precipitation character sets how heavy the rain is, so an
     * HP core soaks the gauge and an LP cell barely wets it.
     */
    public static double rainfallRateMmPerHour(ServerLevel level, Vec3 position) {
        WeatherManagerServer manager = level == null ? null : ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return 0.0D;
        }
        double heaviest = 0.0D;
        for (WeatherObject object : new java.util.ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            if (DynamicStormController.isAbsorbedOrDecaying(storm) || !isPrecipitating(storm)) {
                continue;
            }
            double reach = rainReachBlocks(storm);
            double dx = storm.pos.x - position.x;
            double dz = storm.pos.z - position.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > reach) {
                continue;
            }
            double falloff = Math.pow(1.0D - distance / reach, 0.6D);
            double character = precipitationCharacter(storm);
            double virga = virgaReachFactor(storm);
            heaviest = Math.max(heaviest, MAX_RAIN_MM_PER_HOUR * character * falloff * virga);
        }
        return heaviest;
    }

    public static boolean isPrecipitating(StormObject storm) {
        return storm.attrib_precipitation
                || storm.isPrecipitating()
                || storm.levelCurIntensityStage >= StormObject.STATE_THUNDER;
    }

    /** How far from the storm centre its rain is still falling. */
    public static double rainReachBlocks(StormObject storm) {
        return Math.max(48.0D, storm.getSize() * 1.6D);
    }

    /** 0 = dry LP cell, 0.5 = classic, 1 = torrential HP. */
    public static double precipitationCharacter(StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        float character = SupercellStructureService.precipitationCharacter(storm, state);
        if (character >= 0.0F) {
            return Mth.clamp(character, 0.0F, 1.0F);
        }
        // Before the structure service has scored the storm, fall back to its intensity stage.
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            return 0.72D;
        }
        return storm.levelCurIntensityStage >= StormObject.STATE_THUNDER ? 0.5D : 0.32D;
    }

    /**
     * How much of a storm's rain actually lands, 0 to 1. A storm whose precipitation evaporates on
     * the way down contributes nothing to a gauge on the ground.
     */
    public static double virgaReachFactor(StormObject storm) {
        float virga = DynamicStormController.stateFor(storm).virgaStrength();
        if (!Float.isFinite(virga) || virga <= 0.0F) {
            // Negative means it has not been scored yet, which only happens before first read.
            return 1.0D;
        }
        return Mth.clamp(1.0F - virga, 0.0F, 1.0F);
    }

    // ------------------------------------------------------------------ nearest storm

    /** The nearest significant storm, described the way a chaser would report it. */
    public record StormReading(String label, String bearing, double distanceBlocks, int stage, double windMph) {
    }

    public static StormReading nearestStorm(ServerLevel level, Vec3 position, double rangeBlocks) {
        WeatherManagerServer manager = level == null ? null : ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return null;
        }
        StormObject nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (WeatherObject object : new java.util.ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            if (DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            double dx = storm.pos.x - position.x;
            double dz = storm.pos.z - position.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > rangeBlocks || distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            nearest = storm;
        }
        if (nearest == null) {
            return null;
        }
        String label;
        try {
            StormSubtype subtype = StormClassifier.classify(nearest, DynamicStormController.stateFor(nearest));
            label = StormClassifier.labelFor(nearest, subtype);
        } catch (RuntimeException ignored) {
            label = "Storm";
        }
        return new StormReading(
                label,
                bearingToward(position, nearest.pos),
                bestDistance,
                nearest.levelCurIntensityStage,
                TornadoWindField.maxWindMph(nearest)
        );
    }

    /**
     * How much debris the nearest storm is likely to be throwing, as a multiplier around 1.0.
     *
     * <p>There is no per-particle debris stream to count, so a probe's debris figure is derived: it
     * scales with how wide the storm's damage path is, because a broad violent circulation sweeps far
     * more material past a fixed point than a narrow rope. Honest as an estimate, not as a measurement.
     */
    public static double debrisLoadFactor(ServerLevel level, Vec3 position, double rangeBlocks) {
        WeatherManagerServer manager = level == null ? null : ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return 1.0D;
        }
        DynamicStormState nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (WeatherObject object : new java.util.ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            if (DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            double dx = storm.pos.x - position.x;
            double dz = storm.pos.z - position.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > rangeBlocks || distance >= bestDistance) {
                continue;
            }
            bestDistance = distance;
            nearest = DynamicStormController.stateFor(storm);
        }
        if (nearest == null) {
            return 1.0D;
        }
        return Mth.clamp(nearest.damagePathWidth() / 70.0F, 0.6D, 1.6D);
    }

    /** A short upper-case phrase for a stage number, for panels that have no room for more. */
    public static String stageLabel(int stage) {
        // Tornado rungs are named for the tornado's own stage (TOR1..TOR5), not lumped into a single
        // "VORTEX": the panel's number comes from Weather2's ladder, where rung 5 is stage 1, and a
        // player comparing it with the map label must see the same word in both places.
        String tornado = StormStageLabel.tornadoTag(stage);
        if (tornado != null) {
            return tornado;
        }
        if (stage >= StormObject.STATE_HAIL) {
            return "HAIL CORE";
        }
        if (stage >= StormObject.STATE_HIGHWIND) {
            return "HIGH WIND";
        }
        if (stage >= StormObject.STATE_THUNDER) {
            return "THUNDER";
        }
        return "SHOWER";
    }

    /** Formats a wind speed the way the other instruments do, so the panels read consistently. */
    public static String mph(double windMph) {
        return String.format(Locale.ROOT, "%.0f MPH", Math.max(0.0D, windMph));
    }
}
