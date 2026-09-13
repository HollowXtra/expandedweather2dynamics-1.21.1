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
        if (Config.WATERSPOUTS_ENABLED.get() && storm.attrib_waterSpout) {
            return StormSubtype.WATERSPOUT;
        }
        if (Config.HURRICANES_ENABLED.get() && DynamicStormController.isNativeHurricane(storm)) {
            return StormSubtype.HURRICANE;
        }
        if (Config.CYCLONES_ENABLED.get() && DynamicStormController.isNativeCyclone(storm)) {
            return StormSubtype.CYCLONE;
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
        if (storm.attrib_waterSpout) {
            return SpcRisk.MARGINAL;
        }
        if (DynamicStormController.isNativeHurricane(storm)) {
            return SpcRisk.HIGH;
        }
        if (DynamicStormController.isNativeTropicalCyclone(storm)) {
            return SpcRisk.MODERATE;
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
        // an episode starts. A lifted parent keeps the plain name: there, the vortices are the whole
        // tornado and calling it anything else would describe what you see as a separate storm.
        if (subtype == StormSubtype.TORNADO && storm != null) {
            MultiVortexFlavour flavour = DynamicStormController.stateFor(storm).multiVortexFlavour();
            if (flavour != MultiVortexFlavour.LIFTED) {
                return flavour.label();
            }
        }
        if (subtype == StormSubtype.HURRICANE) {
            return "Hurricane";
        }
        if (subtype == StormSubtype.CYCLONE && DynamicStormController.isNativeTropicalCyclone(storm)) {
            return "Tropical Cyclone";
        }
        return subtype.label();
    }

    public static boolean isColdEnoughForWinter(Level level, net.minecraft.core.BlockPos pos) {
        return level.getBiome(pos).value().coldEnoughToSnow(pos);
    }
}
