package com.imbet.weather;

import com.imbet.Config;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

public final class SpcOutlookService {
    private SpcOutlookService() {
    }

    public static Outlook compute(ServerLevel level, Vec3 origin, int rangeBlocks) {
        DailySpcOutlook daily = DailySpcOutlookService.current(level, net.minecraft.core.BlockPos.containing(origin));
        SpcRisk categorical = daily.risk();
        SpcRisk tornado = SpcRisk.NONE;
        SpcRisk wind = SpcRisk.NONE;
        SpcRisk hail = SpcRisk.NONE;
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
                    categorical = SpcRisk.max(categorical, SpcRisk.TSTM);
                }

                SpcRisk stormTornado = tornadoRisk(storm, state, subtype);
                SpcRisk stormWind = windRisk(storm);
                SpcRisk stormHail = hailRisk(storm);

                if (subtype == StormSubtype.WATERSPOUT) {
                    stormTornado = SpcRisk.cap(stormTornado, SpcRisk.MARGINAL);
                }
                if (subtype == StormSubtype.CYCLONE || subtype == StormSubtype.HURRICANE) {
                    stormTornado = SpcRisk.NONE;
                }

                tornado = SpcRisk.max(tornado, stormTornado);
                wind = SpcRisk.max(wind, stormWind);
                hail = SpcRisk.max(hail, stormHail);
                maxWindMph = Math.max(maxWindMph, TornadoWindField.maxWindMph(storm));

                if (stormTornado.severity() >= SpcRisk.SLIGHT.severity()) {
                    tornadoCount++;
                    hazard = state.satelliteTornadoes().isEmpty() ? "Tornado Potential" : "Tornado/Satellite Potential";
                }
                if (stormWind.severity() >= SpcRisk.SLIGHT.severity() || stormHail.severity() >= SpcRisk.SLIGHT.severity()) {
                    severeCount++;
                    if (hazard.equals("None")) {
                        hazard = stormWind.severity() >= stormHail.severity() ? "Damaging Wind" : "Hail";
                    }
                }
                if (state.recentlyReintensified() && stormTornado.severity() >= SpcRisk.SLIGHT.severity()) {
                    tornado = SpcRisk.max(tornado, bump(tornado));
                    hazard = "Reintensifying Tornado Potential";
                }
                if (Config.SUPERCELL_STRUCTURE_WALL_CLOUD_RISK_BOOST.get()
                        && state.wallCloudPersistenceTicks() >= Config.SUPERCELL_STRUCTURE_WALL_CLOUD_MIN_PERSISTENCE_TICKS.get()
                        && state.wallCloudRotationStrength() >= 0.50F
                        && subtype != StormSubtype.WATERSPOUT
                        && subtype != StormSubtype.CYCLONE) {
                    SpcRisk wallCloudRisk = state.wallCloudRotationStrength() >= 0.72F && storm.levelCurIntensityStage >= StormObject.STATE_HAIL ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
                    tornado = SpcRisk.max(tornado, wallCloudRisk);
                    categorical = SpcRisk.max(categorical, wallCloudRisk);
                    if (hazard.equals("None") || hazard.endsWith("Advisory")) {
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
                wind = SpcRisk.max(wind, lineRisk);
                categorical = SpcRisk.max(categorical, lineRisk);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Organized Squall-Line Wind";
                severeCount++;
            } else if (event.type() == StormSubtype.HURRICANE) {
                SpcRisk hurricaneRisk = event.maxWindMph() >= 130.0F ? SpcRisk.HIGH : event.maxWindMph() >= 96.0F ? SpcRisk.MODERATE : SpcRisk.ENHANCED;
                wind = SpcRisk.max(wind, hurricaneRisk);
                categorical = SpcRisk.max(categorical, hurricaneRisk);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Maritime Hurricane Wind";
                severeCount++;
            } else if (event.type() == StormSubtype.WILDFIRE || event.type() == StormSubtype.FIRE_WHIRL || event.type() == StormSubtype.FLAMMAGENITUS) {
                SpcRisk fireRisk = fireRisk(event);
                categorical = SpcRisk.max(categorical, fireRisk);
                if (event.type() == StormSubtype.FIRE_WHIRL) {
                    wind = SpcRisk.max(wind, fireRisk);
                    maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                }
                hazard = event.type() == StormSubtype.FIRE_WHIRL ? "Fire Whirl / Extreme Fire Weather" : event.type() == StormSubtype.FLAMMAGENITUS ? "Flammagenitus Fire-Plume Risk" : "Wildfire Spread Risk";
                severeCount++;
            } else if (event.type() == StormSubtype.FLOATER_TORNADO) {
                SpcRisk floaterRisk = event.maxWindMph() >= 96.0F ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
                tornado = SpcRisk.max(tornado, floaterRisk);
                categorical = SpcRisk.max(categorical, floaterRisk);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Elevated Floater Tornado";
            } else if (event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT) {
                tornado = SpcRisk.max(tornado, SpcRisk.MARGINAL);
                categorical = SpcRisk.max(categorical, SpcRisk.MARGINAL);
                hazard = event.type() == StormSubtype.LANDSPOUT ? "Landspout Advisory" : "Waterspout Advisory";
            } else if (event.type() == StormSubtype.SNOW_DEVIL) {
                categorical = SpcRisk.max(categorical, SpcRisk.MARGINAL);
                hazard = "Rare Snow Devil Advisory";
            } else if (event.type() == StormSubtype.DUST_DEVIL) {
                categorical = SpcRisk.max(categorical, SpcRisk.MARGINAL);
                hazard = "Dust Devil Advisory";
            } else if (event.type() == StormSubtype.GUSTNADO) {
                wind = SpcRisk.max(wind, SpcRisk.MARGINAL);
                categorical = SpcRisk.max(categorical, SpcRisk.MARGINAL);
                maxWindMph = Math.max(maxWindMph, event.maxWindMph());
                hazard = "Gustnado Advisory";
            }
        }

        if (stormCount >= 3 && severeCount > 0) {
            categorical = SpcRisk.max(categorical, SpcRisk.SLIGHT);
        }
        if (stormCount >= 5 && severeCount >= 3) {
            categorical = SpcRisk.max(categorical, SpcRisk.ENHANCED);
        }
        if (tornadoCount >= 2 && tornado.severity() >= SpcRisk.ENHANCED.severity()) {
            tornado = SpcRisk.max(tornado, SpcRisk.MODERATE);
        }
        if (squallLineCount > 0 && stormCount >= 3) {
            wind = SpcRisk.max(wind, SpcRisk.ENHANCED);
        }

        categorical = SpcRisk.max(categorical, tornado);
        categorical = SpcRisk.max(categorical, wind);
        categorical = SpcRisk.max(categorical, hail);
        return new Outlook(categorical, tornado, wind, hail, hazard, stormCount, severeCount, maxWindMph);
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

    private static SpcRisk bump(SpcRisk risk) {
        return SpcRisk.fromSeverity(risk.severity() + 1);
    }

    public record Outlook(SpcRisk categorical, SpcRisk tornado, SpcRisk wind, SpcRisk hail, String hazard, int stormCount, int severeCount, double maxWindMph) {
        public SpcRisk riskForMode(int mode) {
            return switch (mode) {
                case 1 -> tornado;
                case 2 -> wind;
                case 3 -> hail;
                default -> categorical;
            };
        }
    }
}
