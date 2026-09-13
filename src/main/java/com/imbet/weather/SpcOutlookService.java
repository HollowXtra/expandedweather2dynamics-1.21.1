package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

/**
 * The live outlook: the day's issued forecast refined by what is actually out there.
 *
 * <p>It works the way SPC works. Every source of risk - each storm, each eyewitness addon vortex -
 * proposes a category for the hazard it threatens, and a conditional intensity group; the category is
 * then expressed as the issued probability and CIG pair whose SPC decision-matrix conversion is that
 * same category, and the categorical risk is the strongest hazard. So the panel, the chat readout and
 * the redstone output all agree with the probability numbers shown beside them, instead of each
 * carrying its own hand-written mapping.
 */
public final class SpcOutlookService {
    private SpcOutlookService() {
    }

    public static Outlook compute(ServerLevel level, Vec3 origin, int rangeBlocks) {
        DailySpcOutlook daily = DailySpcOutlookService.current(level, BlockPos.containing(origin));

        SpcRisk tornadoTier = daily.hazardRisk(SpcHazard.TORNADO);
        SpcRisk windTier = daily.hazardRisk(SpcHazard.WIND);
        SpcRisk hailTier = daily.hazardRisk(SpcHazard.HAIL);
        double tornadoEstimate = daily.tornadoPercent();
        double windEstimate = daily.windPercent();
        double hailEstimate = daily.hailPercent();
        int tornadoCig = daily.tornadoCig();
        int windCig = daily.windCig();
        int hailCig = daily.hailCig();
        SpcRisk categoricalFloor = daily.risk();
        String hazard = "Daily " + daily.risk().label() + " thermodynamics";
        int stormCount = 0;
        int severeCount = 0;
        int tornadoCount = 0;
        int squallLineCount = 0;
        double maxWindMph = 0.0D;

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
                StormSubtype subtype = StormClassifier.classify(storm, state);
                if (storm.levelCurIntensityStage >= StormObject.STATE_THUNDER || storm.isPrecipitating()) {
                    stormCount++;
                }

                double stormWindMph = TornadoWindField.maxWindMph(storm);
                maxWindMph = Math.max(maxWindMph, stormWindMph);

                SpcRisk stormTornado = tornadoRisk(storm, state, subtype);
                SpcRisk stormWind = windRisk(storm);
                SpcRisk stormHail = hailRisk(storm);

                if (subtype == StormSubtype.WATERSPOUT) {
                    stormTornado = SpcRisk.cap(stormTornado, SpcRisk.MARGINAL);
                }
                if (subtype == StormSubtype.CYCLONE || subtype == StormSubtype.HURRICANE) {
                    stormTornado = SpcRisk.NONE;
                }

                if (stormTornado.severity() > tornadoTier.severity()) {
                    tornadoTier = stormTornado;
                }
                if (stormWind.severity() > windTier.severity()) {
                    windTier = stormWind;
                }
                if (stormHail.severity() > hailTier.severity()) {
                    hailTier = stormHail;
                }
                if (stormTornado.severity() > SpcRisk.NONE.severity()) {
                    tornadoCig = Math.max(tornadoCig, SpcIntensity.tornadoCigForWind(stormWindMph));
                    tornadoEstimate = Math.max(tornadoEstimate, tornadoPercentForStage(storm, state));
                }
                if (stormWind.severity() > SpcRisk.NONE.severity()) {
                    windCig = Math.max(windCig, SpcIntensity.windCigForWind(stormWindMph));
                    windEstimate = Math.max(windEstimate, windPercentForStage(storm));
                }
                if (stormHail.severity() > SpcRisk.NONE.severity()) {
                    hailCig = Math.max(hailCig, SpcIntensity.hailCigForStage(storm.levelCurIntensityStage));
                    hailEstimate = Math.max(hailEstimate, hailPercentForStage(storm));
                }

                if (stormTornado.severity() >= SpcRisk.SLIGHT.severity()) {
                    tornadoCount++;
                    hazard = state.satelliteTornadoes().isEmpty() ? "Tornado Potential" : "Tornado/Satellite Potential";
                }
                if (stormWind.severity() >= SpcRisk.SLIGHT.severity() || stormHail.severity() >= SpcRisk.SLIGHT.severity()) {
                    severeCount++;
                    if ("None".equals(hazard)) {
                        hazard = stormWind.severity() >= stormHail.severity() ? "Damaging Wind" : "Hail";
                    }
                }
                if (state.recentlyReintensified() && stormTornado.severity() >= SpcRisk.SLIGHT.severity()) {
                    tornadoTier = SpcRisk.max(tornadoTier, bump(tornadoTier));
                    tornadoEstimate = Math.max(tornadoEstimate, SpcHazard.TORNADO.lowestProbability() * 5.0D);
                    hazard = "Reintensifying Tornado Potential";
                }
                if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST.get()
                        && state.wallCloudPersistenceTicks() >= Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS.get()
                        && state.wallCloudRotationStrength() >= 0.50F
                        && subtype != StormSubtype.WATERSPOUT
                        && subtype != StormSubtype.CYCLONE) {
                    SpcRisk wallCloudRisk = state.wallCloudRotationStrength() >= 0.72F && storm.levelCurIntensityStage >= StormObject.STATE_HAIL
                            ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
                    tornadoTier = SpcRisk.max(tornadoTier, wallCloudRisk);
                    categoricalFloor = SpcRisk.max(categoricalFloor, wallCloudRisk);
                    tornadoEstimate = Math.max(tornadoEstimate,
                            wallCloudRisk == SpcRisk.SLIGHT ? SpcHazard.TORNADO.probabilityAt(1) : SpcHazard.TORNADO.lowestProbability());
                    if ("None".equals(hazard) || hazard.endsWith("Advisory")) {
                        hazard = "Persistent Rotating Wall Cloud";
                    }
                }
            }
        }

        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.position().distanceTo(origin) > rangeBlocks + event.radius()) {
                continue;
            }
            if (event.type() == StormSubtype.SQUALL_LINE) {
                squallLineCount++;
                SpcRisk lineRisk = squallLineWindRisk(event.maxWindMph());
                windTier = SpcRisk.max(windTier, lineRisk);
                windCig = Math.max(windCig, SpcIntensity.windCigForWind(event.maxWindMph()));
                windEstimate = Math.max(windEstimate, event.maxWindMph() >= 58.0F ? 45.0D : 30.0D);
                categoricalFloor = SpcRisk.max(categoricalFloor, lineRisk);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Organized Squall-Line Wind";
                severeCount++;
            } else if (event.type() == StormSubtype.HURRICANE) {
                SpcRisk hurricaneRisk = hurricaneWindRisk(event.maxWindMph());
                windTier = SpcRisk.max(windTier, hurricaneRisk);
                windCig = Math.max(windCig, SpcIntensity.windCigForWind(event.maxWindMph()));
                windEstimate = Math.max(windEstimate, event.maxWindMph() >= 96.0F ? 60.0D : 45.0D);
                categoricalFloor = SpcRisk.max(categoricalFloor, hurricaneRisk);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Maritime Hurricane Wind";
                severeCount++;
            } else if (event.type() == StormSubtype.WILDFIRE || event.type() == StormSubtype.FIRE_WHIRL || event.type() == StormSubtype.FLAMMAGENITUS) {
                SpcRisk fireRisk = fireRisk(event);
                categoricalFloor = SpcRisk.max(categoricalFloor, fireRisk);
                if (event.type() == StormSubtype.FIRE_WHIRL) {
                    windTier = SpcRisk.max(windTier, fireRisk);
                    windEstimate = Math.max(windEstimate, 45.0D);
                    maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                }
                hazard = event.type() == StormSubtype.FIRE_WHIRL ? "Fire Whirl / Extreme Fire Weather"
                        : event.type() == StormSubtype.FLAMMAGENITUS ? "Flammagenitus Fire-Plume Risk" : "Wildfire Spread Risk";
                severeCount++;
            } else if (event.type() == StormSubtype.FLOATER_TORNADO) {
                SpcRisk floaterRisk = event.maxWindMph() >= 96.0F ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
                tornadoTier = SpcRisk.max(tornadoTier, floaterRisk);
                tornadoEstimate = Math.max(tornadoEstimate, floaterRisk == SpcRisk.SLIGHT ? SpcHazard.TORNADO.probabilityAt(1) : SpcHazard.TORNADO.lowestProbability());
                categoricalFloor = SpcRisk.max(categoricalFloor, floaterRisk);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Elevated Floater Tornado";
            } else if (event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT) {
                tornadoTier = SpcRisk.max(tornadoTier, SpcRisk.MARGINAL);
                tornadoEstimate = Math.max(tornadoEstimate, SpcHazard.TORNADO.lowestProbability());
                categoricalFloor = SpcRisk.max(categoricalFloor, SpcRisk.MARGINAL);
                hazard = event.type() == StormSubtype.LANDSPOUT ? "Landspout Advisory" : "Waterspout Advisory";
            } else if (event.type() == StormSubtype.SNOW_DEVIL) {
                categoricalFloor = SpcRisk.max(categoricalFloor, SpcRisk.MARGINAL);
                hazard = "Rare Snow Devil Advisory";
            } else if (event.type() == StormSubtype.DUST_DEVIL) {
                categoricalFloor = SpcRisk.max(categoricalFloor, SpcRisk.MARGINAL);
                hazard = "Dust Devil Advisory";
            } else if (event.type() == StormSubtype.GUSTNADO) {
                windTier = SpcRisk.max(windTier, SpcRisk.MARGINAL);
                windEstimate = Math.max(windEstimate, SpcHazard.WIND.lowestProbability());
                categoricalFloor = SpcRisk.max(categoricalFloor, SpcRisk.MARGINAL);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Gustnado Advisory";
            }
        }

        if (stormCount >= 3 && severeCount > 0) {
            categoricalFloor = SpcRisk.max(categoricalFloor, SpcRisk.SLIGHT);
            windEstimate = Math.max(windEstimate, 15.0D);
        }
        if (stormCount >= 5 && severeCount >= 3) {
            categoricalFloor = SpcRisk.max(categoricalFloor, SpcRisk.ENHANCED);
            windTier = SpcRisk.max(windTier, SpcRisk.ENHANCED);
            windEstimate = Math.max(windEstimate, 30.0D);
        }
        if (tornadoCount >= 2 && tornadoTier.severity() >= SpcRisk.ENHANCED.severity()) {
            tornadoTier = SpcRisk.max(tornadoTier, SpcRisk.MODERATE);
        }
        if (squallLineCount > 0 && stormCount >= 3) {
            windTier = SpcRisk.max(windTier, SpcRisk.ENHANCED);
            windEstimate = Math.max(windEstimate, 30.0D);
        }

        // The published ladder stops at high risk. An environment observed past CIG 3 - a 200 mph
        // tornado, a 130 mph derecho gust, a major hurricane - is off that ladder whatever the
        // forecast said, so it carries its hazard onto the addon's extension tiers. This is the path
        // that matters in practice: it is earned by a vortex that actually exists, so a violent
        // tornado lights the top of the scale on the panel and the radar the moment it forms.
        tornadoTier = SpcRisk.escalateObserved(tornadoTier, tornadoCig);
        windTier = SpcRisk.escalateObserved(windTier, windCig);

        int[] tornadoCell = SpcHazard.TORNADO.bestCellForTier(SpcRisk.cap(tornadoTier, SpcHazard.TORNADO.maxExpressible()), tornadoEstimate, tornadoCig);
        int[] windCell = SpcHazard.WIND.bestCellForTier(SpcRisk.cap(windTier, SpcHazard.WIND.maxExpressible()), windEstimate, windCig);
        int[] hailCell = SpcHazard.HAIL.bestCellForTier(SpcRisk.cap(hailTier, SpcHazard.HAIL.maxExpressible()), hailEstimate, hailCig);

        SpcRisk tornado = SpcRisk.fromHazard(SpcHazard.TORNADO, tornadoCell[0], tornadoCell[1]);
        SpcRisk wind = SpcRisk.fromHazard(SpcHazard.WIND, windCell[0], windCell[1]);
        SpcRisk hail = SpcRisk.fromHazard(SpcHazard.HAIL, hailCell[0], hailCell[1]);
        SpcRisk categorical = SpcRisk.max(categoricalFloor, SpcRisk.max(tornado, SpcRisk.max(wind, hail)));
        if (stormCount > 0) {
            categorical = SpcRisk.max(categorical, SpcRisk.TSTM);
        }

        return new Outlook(categorical, tornado, wind, hail,
                tornadoCell[0], windCell[0], hailCell[0],
                tornadoCell[1], windCell[1], hailCell[1],
                daily.issuance(), hazard, stormCount, severeCount, maxWindMph);
    }

    private static SpcRisk fireRisk(AddonWeatherEvent event) {
        if (!Config.FIRE_WEATHER_RISK_ENABLED.get()) {
            return SpcRisk.NONE;
        }
        if (event.type() == StormSubtype.FIRE_WHIRL) {
            return event.maxWindMph() >= 105.0F ? SpcRisk.MODERATE : event.maxWindMph() >= 82.0F ? SpcRisk.ENHANCED : SpcRisk.SLIGHT;
        }
        if (event.type() == StormSubtype.FLAMMAGENITUS) {
            return event.radius() >= 160.0F ? SpcRisk.ENHANCED : SpcRisk.SLIGHT;
        }
        if (event.radius() >= 110.0F) {
            return SpcRisk.MODERATE;
        }
        if (event.radius() >= 75.0F) {
            return SpcRisk.ENHANCED;
        }
        if (event.radius() >= 45.0F) {
            return SpcRisk.SLIGHT;
        }
        return SpcRisk.MARGINAL;
    }

    private static SpcRisk tornadoRisk(StormObject storm, DynamicStormState state, StormSubtype subtype) {
        if (subtype != StormSubtype.TORNADO && state.satelliteTornadoes().isEmpty()) {
            return SpcRisk.NONE;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return SpcRisk.HIGH;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return SpcRisk.MODERATE;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2 || !state.satelliteTornadoes().isEmpty()) {
            return SpcRisk.ENHANCED;
        }
        return SpcRisk.SLIGHT;
    }

    /** The probability contour an observed tornado is behaving like, used to pick the issued cell. */
    private static double tornadoPercentForStage(StormObject storm, DynamicStormState state) {
        int stage = storm.levelCurIntensityStage;
        if (stage >= StormObject.STATE_STAGE5) {
            return 45.0D;
        }
        if (stage >= StormObject.STATE_STAGE4) {
            return 30.0D;
        }
        if (stage >= StormObject.STATE_STAGE3) {
            return 15.0D;
        }
        if (stage >= StormObject.STATE_STAGE2) {
            return 10.0D;
        }
        return state.satelliteTornadoes().isEmpty() ? 5.0D : 10.0D;
    }

    private static SpcRisk windRisk(StormObject storm) {
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE5 || DynamicStormController.isNativeHurricane(storm)) {
            return SpcRisk.HIGH;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4 || DynamicStormController.isNativeTropicalCyclone(storm)) {
            return SpcRisk.MODERATE;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return SpcRisk.ENHANCED;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND) {
            return SpcRisk.SLIGHT;
        }
        return SpcRisk.NONE;
    }

    private static double windPercentForStage(StormObject storm) {
        int stage = storm.levelCurIntensityStage;
        if (stage >= StormObject.STATE_STAGE5 || DynamicStormController.isNativeHurricane(storm)) {
            return 75.0D;
        }
        if (stage >= StormObject.STATE_STAGE4 || DynamicStormController.isNativeTropicalCyclone(storm)) {
            return 60.0D;
        }
        if (stage >= StormObject.STATE_STAGE3) {
            return 45.0D;
        }
        if (stage >= StormObject.STATE_STAGE2) {
            return 30.0D;
        }
        return 15.0D;
    }

    private static SpcRisk hailRisk(StormObject storm) {
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return SpcRisk.MODERATE;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return SpcRisk.ENHANCED;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            return SpcRisk.SLIGHT;
        }
        return SpcRisk.NONE;
    }

    private static double hailPercentForStage(StormObject storm) {
        int stage = storm.levelCurIntensityStage;
        if (stage >= StormObject.STATE_STAGE4) {
            return 45.0D;
        }
        if (stage >= StormObject.STATE_STAGE2) {
            return 30.0D;
        }
        return 15.0D;
    }

    private static SpcRisk squallLineWindRisk(float maxWindMph) {
        if (maxWindMph >= 90.0F) {
            return SpcRisk.HIGH;
        }
        if (maxWindMph >= 75.0F) {
            return SpcRisk.MODERATE;
        }
        if (maxWindMph >= 58.0F) {
            return SpcRisk.ENHANCED;
        }
        return SpcRisk.SLIGHT;
    }

    private static SpcRisk hurricaneWindRisk(float maxWindMph) {
        if (maxWindMph >= 130.0F) {
            return SpcRisk.HIGH;
        }
        if (maxWindMph >= 96.0F) {
            return SpcRisk.MODERATE;
        }
        return SpcRisk.ENHANCED;
    }

    /** A one-step escalation within the published ladder; the extension tiers are earned, not bumped. */
    private static SpcRisk bump(SpcRisk risk) {
        return SpcRisk.cap(SpcRisk.fromSeverity(risk.severity() + 1), SpcRisk.HIGH);
    }

    /**
     * The live outlook. Each hazard carries the probability level and conditional intensity group
     * that were issued for it, and every category here is the SPC decision-matrix conversion of
     * exactly those numbers.
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
