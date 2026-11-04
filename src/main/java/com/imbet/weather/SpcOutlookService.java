package com.imbet.weather;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

/**
 * The outlook in force here: the current issuance, read against what is actually out there.
 *
 * <p>Every category and every issued number comes from the issuance and from nothing else. That is
 * what makes it an outlook: SPC derives its categorical risk from probability forecasts of the three
 * severe hazards, so the product forecasts the environment, and a tornado that deepens on the ground
 * does not upgrade it. Observed severe weather is what watches, mesoscale discussions and warnings
 * are for, and the addon keeps the same split - storms and addon events are counted and named here so
 * the panel still says what is happening within range, but they can no longer raise a probability, a
 * conditional intensity group or the category itself.
 *
 * <p>This used to be the other way round, with each storm proposing a category from its own intensity
 * stage and each addon event proposing one from its own measured wind. The effect was that the scale
 * lit up as a function of whatever had already spawned, which made the outlook a readout of the storms
 * rather than a forecast of them, and let a single violent vortex push the day onto the extension
 * tiers. Those tiers are still reachable; they are earned by the environment at issuance, through the
 * extension CIG columns built from CAPE, shear and storm mode.
 *
 * <p>Because the issued numbers are the issuance's own probability and CIG cells, the SPC decision
 * matrix converts them back to the same categories the panel lights, so a probability can never
 * disagree with the category printed beside it.
 */
public final class SpcOutlookService {
    /** Forecaster's severe-wind criterion; the observational counterpart of the 58 mph report. */
    private static final double SEVERE_WIND_MPH = 58.0D;

    private SpcOutlookService() {
    }

    public static Outlook compute(ServerLevel level, Vec3 origin, int rangeBlocks) {
        DailySpcOutlook daily = DailySpcOutlookService.current(level, BlockPos.containing(origin));

        SpcRisk tornado = daily.hazardRisk(SpcHazard.TORNADO);
        SpcRisk wind = daily.hazardRisk(SpcHazard.WIND);
        SpcRisk hail = daily.hazardRisk(SpcHazard.HAIL);
        // The issued category, widened to cover each hazard's own conversion. The matrix can
        // legitimately place a hazard below the day's category when that hazard cannot express it
        // alone - hail never reaches high risk - and the panel must never print a category weaker
        // than one of the rows underneath it.
        SpcRisk categorical = SpcRisk.max(daily.risk(), daily.categoricalFromProduct());

        int stormCount = 0;
        int severeCount = 0;
        double maxWindMph = 0.0D;
        Observed strongest = null;

        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
            for (WeatherObject object : manager.getStormObjects()) {
                if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || storm.pos.distanceTo(origin) > rangeBlocks) {
                    continue;
                }
                DynamicStormState state = DynamicStormController.stateFor(storm);
                if (state.isAbsorbedOrDecaying()) {
                    continue;
                }
                if (storm.levelCurIntensityStage < StormObject.STATE_THUNDER && !storm.isPrecipitating()) {
                    continue;
                }

                StormSubtype subtype = StormClassifier.classify(storm, state);
                stormCount++;

                double stormWindMph = TornadoWindField.maxWindMph(storm);
                maxWindMph = Math.max(maxWindMph, stormWindMph);

                // Severe by observation, not by stage: a 58 mph measured wind or large hail is what a
                // storm report would carry, and it is what the panel's severe counter means.
                if (stormWindMph >= SEVERE_WIND_MPH || storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
                    severeCount++;
                }

                Observed stormSignal = stormSignal(subtype, state, stormWindMph, storm.levelCurIntensityStage);
                if (stormSignal != null && (strongest == null || stormSignal.rank() > strongest.rank())) {
                    strongest = stormSignal;
                }
            }
        }

        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.position().distanceTo(origin) > rangeBlocks + event.radius()) {
                continue;
            }
            maxWindMph = Math.max(maxWindMph, event.maxWindMph());
            switch (event.type()) {
                // The cyclone is the anticyclonic counterpart of the hurricane and the same class of
                // system, so it counts here too; the original only named the hurricane.
                case SQUALL_LINE, HURRICANE, CYCLONE, WILDFIRE, FIRE_WHIRL, FLAMMAGENITUS -> severeCount++;
                default -> {
                }
            }
            // eventSignal is null for the types that are not a hazard of their own - a broad
            // rotating storm event among them, which is a tropical system rather than a null signal.
            Observed signal = eventSignal(event);
            if (signal != null && (strongest == null || signal.rank() > strongest.rank())) {
                strongest = signal;
            }
        }

        // A thunderstorm area is still an outlook statement, so any storm in range at least says
        // general thunder. It cannot lift the day above that on its own.
        if (stormCount > 0) {
            categorical = SpcRisk.max(categorical, SpcRisk.TSTM);
        }

        String hazard = strongest != null ? strongest.text() : forecastHazardText(tornado, wind, hail);

        return new Outlook(categorical, tornado, wind, hail,
                daily.tornadoPercent(), daily.windPercent(), daily.hailPercent(),
                daily.tornadoCig(), daily.windCig(), daily.hailCig(),
                daily.issuance(), hazard, stormCount, severeCount, maxWindMph);
    }

    /** The observed signal a storm in range presents, or null when it presents nothing worth naming. */
    private static Observed stormSignal(StormSubtype subtype, DynamicStormState state, double windMph, int intensityStage) {
        if (!state.satelliteTornadoes().isEmpty()) {
            return Observed.SATELLITE_TORNADO;
        }
        if (subtype == StormSubtype.TORNADO || subtype == StormSubtype.FLOATER_TORNADO) {
            return Observed.TORNADO;
        }
        if (subtype == StormSubtype.HURRICANE || subtype == StormSubtype.CYCLONE) {
            return Observed.TROPICAL_CYCLONE;
        }
        if (subtype == StormSubtype.LANDSPOUT) {
            return Observed.LANDSPOUT;
        }
        if (subtype == StormSubtype.WATERSPOUT) {
            return Observed.WATERSPOUT;
        }
        if (subtype == StormSubtype.GUSTNADO) {
            return Observed.GUSTNADO;
        }
        if (subtype == StormSubtype.SQUALL_LINE) {
            return Observed.SQUALL_LINE;
        }
        if (windMph >= SEVERE_WIND_MPH) {
            return Observed.DAMAGING_WIND;
        }
        if (intensityStage >= StormObject.STATE_HAIL) {
            return Observed.LARGE_HAIL;
        }
        return Observed.THUNDERSTORMS;
    }

    /**
     * The observed signal an addon event presents, or null for a type that is not a hazard of its own.
     *
     * <p>Every type named here is a hazard that would earn its own alert; none of them can move the
     * outlook's numbers any more. The severe counter is incremented separately at the call site, and
     * only for the synoptic types, which is where it has always counted.
     */
    private static Observed eventSignal(AddonWeatherEvent event) {
        return switch (event.type()) {
            case SQUALL_LINE -> Observed.SQUALL_LINE;
            case HURRICANE, CYCLONE -> Observed.TROPICAL_CYCLONE;
            case FLOATER_TORNADO -> Observed.FLOATER_TORNADO;
            case LANDSPOUT -> Observed.LANDSPOUT;
            case WATERSPOUT -> Observed.WATERSPOUT;
            case FUNNEL_CLOUD -> Observed.FUNNEL_CLOUD;
            case GUSTNADO -> Observed.GUSTNADO;
            case SNOW_DEVIL -> Observed.SNOW_DEVIL;
            case DUST_DEVIL -> Observed.DUST_DEVIL;
            case FIRE_WHIRL -> Observed.FIRE_WHIRL;
            case FLAMMAGENITUS -> Observed.FLAMMAGENITUS;
            case WILDFIRE -> Observed.WILDFIRE;
            default -> null;
        };
    }

    /**
     * What the forecast alone is calling for, used when nothing in range is worth naming.
     *
     * <p>Kept short on purpose: this is the panel's bottom row, which fits seventeen characters.
     */
    private static String forecastHazardText(SpcRisk tornado, SpcRisk wind, SpcRisk hail) {
        if (SpcRisk.max(tornado, SpcRisk.max(wind, hail)).severity() <= SpcRisk.TSTM.severity()) {
            return "No convective risk";
        }
        if (tornado.severity() >= wind.severity() && tornado.severity() >= hail.severity()) {
            return "Tornado forecast";
        }
        return wind.severity() >= hail.severity() ? "Damaging wind forecast" : "Large hail forecast";
    }

    /**
     * The observed signals the hazard line can carry, weakest first.
     *
     * <p>Ranks exist so the strongest thing actually in range names the line, instead of whichever
     * storm the weather manager happened to return last.
     */
    private enum Observed {
        THUNDERSTORMS(1, "Thunderstorms"),
        DUST_DEVIL(2, "Dust Devil Advisory"),
        LARGE_HAIL(3, "Large Hail"),
        SNOW_DEVIL(4, "Rare Snow Devil Advisory"),
        GUSTNADO(5, "Gustnado Advisory"),
        LANDSPOUT(6, "Landspout Advisory"),
        WATERSPOUT(6, "Waterspout Advisory"),
        FUNNEL_CLOUD(6, "Funnel Cloud Advisory"),
        WILDFIRE(7, "Wildfire Spread Risk"),
        DAMAGING_WIND(8, "Damaging Wind"),
        FIRE_WHIRL(9, "Fire Whirl / Extreme Fire Weather"),
        FLAMMAGENITUS(9, "Flammagenitus Fire-Plume Risk"),
        TROPICAL_CYCLONE(10, "Tropical Cyclone"),
        SQUALL_LINE(11, "Organized Squall-Line Wind"),
        TORNADO(12, "Tornado Potential"),
        FLOATER_TORNADO(12, "Elevated Floater Tornado"),
        SATELLITE_TORNADO(13, "Tornado/Satellite Potential");

        private final int rank;
        private final String text;

        Observed(int rank, String text) {
            this.rank = rank;
            this.text = text;
        }

        int rank() {
            return rank;
        }

        String text() {
            return text;
        }
    }

    /**
     * The outlook. Each hazard carries the probability level and conditional intensity group that
     * were issued for it, and every category here is the SPC decision-matrix conversion of exactly
     * those numbers. The storm and severe counts are observations within range, reported alongside
     * the forecast rather than folded into it.
     */
    public record Outlook(
            SpcRisk categorical,
            SpcRisk tornado,
            SpcRisk wind,
            SpcRisk hail,
            int tornadoProbability,
            int windProbability,
            int hailProbability,
            int tornadoCig,
            int windCig,
            int hailCig,
            String issuance,
            String hazard,
            int stormCount,
            int severeCount,
            double maxWindMph
    ) {
        public Outlook {
            issuance = issuance == null || issuance.isBlank() ? "D1 0600Z" : issuance;
            tornadoProbability = Math.max(0, tornadoProbability);
            windProbability = Math.max(0, windProbability);
            hailProbability = Math.max(0, hailProbability);
            tornadoCig = Math.max(0, tornadoCig);
            windCig = Math.max(0, windCig);
            hailCig = Math.max(0, hailCig);
        }

        public SpcRisk riskForMode(int mode) {
            return switch (mode) {
                case 1 -> tornado;
                case 2 -> wind;
                case 3 -> hail;
                default -> categorical;
            };
        }

        public int probabilityForMode(int mode) {
            return switch (mode) {
                case 1 -> tornadoProbability;
                case 2 -> windProbability;
                case 3 -> hailProbability;
                default -> categorical.severity();
            };
        }

        public int cigForMode(int mode) {
            return switch (mode) {
                case 1 -> tornadoCig;
                case 2 -> windCig;
                case 3 -> hailCig;
                default -> 0;
            };
        }

        /** The hazard a panel mode is showing, or null for the categorical view. */
        public SpcHazard hazardForMode(int mode) {
            return switch (mode) {
                case 1 -> SpcHazard.TORNADO;
                case 2 -> SpcHazard.WIND;
                case 3 -> SpcHazard.HAIL;
                default -> null;
            };
        }

        /** The full product line, e.g. "TOR 10% CIG1 / WND 30% / HAIL 15%". */
        public String productLine() {
            return SpcHazard.TORNADO.productLine(tornadoProbability, tornadoCig)
                    + " / " + SpcHazard.WIND.productLine(windProbability, windCig)
                    + " / " + SpcHazard.HAIL.productLine(hailProbability, hailCig);
        }
    }
}
