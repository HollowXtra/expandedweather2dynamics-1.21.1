package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.world.level.Level;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObjectParticleStorm;

public final class StormClassifier {
    private StormClassifier() {
    }

    public static StormSubtype classify(StormObject storm, DynamicStormState state) {
        if (storm == null) {
            return StormSubtype.NONE;
        }
        if (AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return StormSubtype.THUNDERSTORM;
        }
        // A water storm is a tropical system for its whole life, not only once it reaches hurricane
        // strength: the 1.12.2 ladder - tropical disturbance, depression, tropical storm, then a named
        // hurricane with a category - is one family, and which half of it the storm belongs to is the
        // direction it turns, which is exactly what an anticyclonic one is.
        //
        // Decided BEFORE the waterspout tag, deliberately. Weather2's own waterspout pass sets that tag on
        // water storms, and reading it first relabelled hurricanes as waterspouts - which took them down the
        // tornado branch of every consumer, funnel and all.
        if (DynamicStormController.isTropicalSystem(storm)
                && (Config.HURRICANES_ENABLED.get() || Config.CYCLONES_ENABLED.get())) {
            return state != null && state.isAntiCyclonic() ? StormSubtype.CYCLONE : StormSubtype.HURRICANE;
        }
        if (Config.WATERSPOUTS_ENABLED.get() && storm.attrib_waterSpout) {
            return StormSubtype.WATERSPOUT;
        }
        if (storm.isTornadoFormingOrGreater()) {
            if (Config.FLOATER_TORNADOES_ENABLED.get()
                    && state != null
                    && (state.targetFunnelMorphology() == FunnelMorphology.FLOATER
                    || state.funnelMorphology() == FunnelMorphology.FLOATER)) {
                return StormSubtype.FLOATER_TORNADO;
            }
            return StormSubtype.TORNADO;
        }
        if (AddonWeatherEventService.isSquallLineHelperStorm(storm)) {
            return StormSubtype.THUNDERSTORM;
        }
        if (SupercellStructureService.isEligible(storm) && state != null && state.supercellStructureStrength() >= 0.20F) {
            return StormSubtype.SUPERCELL;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_THUNDER || storm.isPrecipitating()) {
            return StormSubtype.THUNDERSTORM;
        }
        return StormSubtype.NONE;
    }

    public static StormSubtype classify(WeatherObjectParticleStorm storm) {
        if (storm.getType() == WeatherObjectParticleStorm.StormType.SNOWSTORM) {
            return StormSubtype.SNOWSTORM;
        }
        if (storm.getType() == WeatherObjectParticleStorm.StormType.SANDSTORM) {
            return StormSubtype.SANDSTORM;
        }
        return StormSubtype.NONE;
    }

    public static SpcRisk riskFor(StormObject storm) {
        if (storm == null) {
            return SpcRisk.NONE;
        }
        if (AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return SpcRisk.SLIGHT;
        }
        // Tropical first, for the same reason the subtype is: a tagged hurricane is still a hurricane.
        if (DynamicStormController.isTropicalSystem(storm)) {
            int category = TropicalCyclone.categoryForWind(TornadoWindField.maxWindMph(storm));
            if (category >= 3) {
                return SpcRisk.HIGH;
            }
            return category >= 1 ? SpcRisk.MODERATE : SpcRisk.ENHANCED;
        }
        if (storm.attrib_waterSpout) {
            return SpcRisk.MARGINAL;
        }
        // A tropical system is ranked by the category its live wind earns, the same way a tornado is
        // ranked by its EF class: a weak hurricane parked on the top rung does not get to be a high
        // risk, and a violent one does not get to hide under a moderate one.
        if (DynamicStormController.isNativeTropicalSystem(storm)) {
            int category = TropicalCyclone.categoryForWind(TornadoWindField.maxWindMph(storm));
            if (category >= 3) {
                return SpcRisk.HIGH;
            }
            return category >= 1 ? SpcRisk.MODERATE : SpcRisk.ENHANCED;
        }
        if (storm.isTornadoFormingOrGreater() && storm.levelCurIntensityStage >= StormObject.STATE_STAGE5) {
            return SpcRisk.HIGH;
        }
        if (storm.isTornadoFormingOrGreater() && storm.levelCurIntensityStage >= StormObject.STATE_STAGE4) {
            return SpcRisk.MODERATE;
        }
        if (storm.isTornadoFormingOrGreater() || storm.levelCurIntensityStage >= StormObject.STATE_STAGE2) {
            return SpcRisk.ENHANCED;
        }
        if (AddonWeatherEventService.isSquallLineHelperStorm(storm)) {
            return SpcRisk.MARGINAL;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL || storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND) {
            return SpcRisk.SLIGHT;
        }
        if (storm.isPrecipitating()) {
            return storm.levelCurIntensityStage >= StormObject.STATE_THUNDER ? SpcRisk.MARGINAL : SpcRisk.TSTM;
        }
        return SpcRisk.NONE;
    }

    public static SpcRisk riskFor(WeatherObjectParticleStorm storm) {
        return SpcRisk.NONE;
    }

    public static String labelFor(StormObject storm, StormSubtype subtype) {
        if (subtype == StormSubtype.TORNADO && DynamicStormController.isAntiCyclonicTornado(storm)) {
            return "Anti-Cyclonic Tornado";
        }
        if (storm != null && (subtype == StormSubtype.SUPERCELL || subtype == StormSubtype.TORNADO)) {
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (SupercellStructureService.isHighPrecipitation(storm, state)) {
                // HP storms hide their tornado in the rain shield - and a rain-wrapped tornado hides an
                // arrangement too, so this name wins over the multi-vortex one: you cannot see vortices
                // through the rain, and a warning that described them would be describing nothing.
                return subtype == StormSubtype.TORNADO ? "Rain-Wrapped Tornado" : "HP Supercell";
            }
            if (SupercellStructureService.isLowPrecipitation(storm, state)) {
                return subtype == StormSubtype.TORNADO ? "LP Supercell Tornado" : "LP Supercell";
            }
        }
        // A multi-vortex arrangement is not a different storm - it is the same tornado wearing its
        // vortices a particular way, so it is named by what it is at the moment rather than by a
        // subtype of its own. Nothing about the storm's identity, save data or severity changes when
        // an episode starts. A lifted parent keeps the plain name: its base has pulled up off the
        // surface, but the column above it is still the tornado, so naming the arrangement would be
        // naming the vortices under it as if they were the storm.
        if (subtype == StormSubtype.TORNADO && storm != null) {
            MultiVortexFlavour flavour = DynamicStormController.stateFor(storm).multiVortexFlavour();
            if (flavour != MultiVortexFlavour.LIFTED) {
                return flavour.label();
            }
        }
        if (subtype == StormSubtype.HURRICANE || subtype == StormSubtype.CYCLONE) {
            // The 1.12.2 identity, name and all: "Hurricane Karl - Category 3", or the ladder rung it
            // has climbed to before it is a hurricane, or "Tropical Cyclone ..." for the anticyclonic
            // twin. Radar, alerts, the balloon, the probe and the map all read this one label.
            if (storm != null && DynamicStormController.isNativeTropicalSystem(storm)) {
                return TropicalCyclone.label(storm.ID, storm.levelCurIntensityStage,
                        TornadoWindField.maxWindMph(storm),
                        DynamicStormController.stateFor(storm).isAntiCyclonic());
            }
        }
        return subtype.label();
    }

    public static boolean isColdEnoughForWinter(Level level, net.minecraft.core.BlockPos pos) {
        return level.getBiome(pos).value().coldEnoughToSnow(pos);
    }
}
