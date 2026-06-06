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
