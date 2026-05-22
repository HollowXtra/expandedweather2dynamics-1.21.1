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
        if (Config.WATERSPOUTS_ENABLED.get() && storm.attrib_waterSpout) {
            return StormSubtype.WATERSPOUT;
        }
        if (storm.isTornadoFormingOrGreater()) {
            return StormSubtype.TORNADO;
        }
        if (Config.CYCLONES_ENABLED.get() && (storm.isTropicalCyclone() || storm.isHurricane() || storm.isCycloneFormingOrGreater())) {
            return StormSubtype.CYCLONE;
        }
        if (SupercellStructureService.isEligible(storm) && state != null && state.supercellStructureStrength() >= 0.20F) {
            return StormSubtype.SUPERCELL;
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
        if (storm.attrib_waterSpout) {
            return SpcRisk.MARGINAL;
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
        if (subtype == StormSubtype.CYCLONE && storm.isHurricane()) {
            return "Hurricane";
        }
        if (subtype == StormSubtype.CYCLONE && storm.levelCurIntensityStage >= StormObject.STATE_STAGE1) {
            return "Tropical Cyclone";
        }
        return subtype.label();
    }

    public static boolean isColdEnoughForWinter(Level level, net.minecraft.core.BlockPos pos) {
        return level.getBiome(pos).value().coldEnoughToSnow(pos);
    }
}
