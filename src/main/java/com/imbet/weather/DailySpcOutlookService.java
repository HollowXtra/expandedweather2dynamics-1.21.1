package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

public final class DailySpcOutlookService {
    private static final Map<ResourceKey<Level>, DailySpcOutlook> OUTLOOKS = new HashMap<>();

    private DailySpcOutlookService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || !Config.DAILY_SPC_OUTLOOK_ENABLED.get()) {
            return;
        }
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        long dayTime = level.getDayTime() % 24000L;
        DailySpcOutlook existing = OUTLOOKS.get(level.dimension());
        boolean morningWindow = dayTime >= 900L && dayTime <= 1600L;
        if (existing == null || existing.day() != day && (morningWindow || level.getGameTime() < 80L)) {
            OUTLOOKS.put(level.dimension(), compute(level, level.getSharedSpawnPos(), day));
        }
    }

    public static DailySpcOutlook current(ServerLevel level, BlockPos origin) {
        if (level == null) {
            return DailySpcOutlook.neutral(0L);
        }
        long day = Math.floorDiv(level.getDayTime(), 24000L);
        DailySpcOutlook outlook = OUTLOOKS.get(level.dimension());
        if (outlook == null || outlook.day() != day) {
            outlook = compute(level, origin == null ? level.getSharedSpawnPos() : origin, day);
            OUTLOOKS.put(level.dimension(), outlook);
        }
        return outlook;
    }

    public static boolean allowsSquallLines(ServerLevel level, BlockPos origin) {
        return current(level, origin).allowsQlcs();
    }

    public static boolean allowsLandspouts(ServerLevel level, BlockPos origin) {
        return current(level, origin).allowsLandspouts();
    }

    public static boolean favorsDustDevils(ServerLevel level, BlockPos origin) {
        return current(level, origin).favorsDustDevils();
    }

    public static boolean hurricaneWindow(ServerLevel level, BlockPos origin) {
        DailySpcOutlook outlook = current(level, origin);
        return ThermodynamicProfileService.hurricaneWindow(level, origin) && outlook.hurricaneSpawnMultiplier() > 0.0D;
    }

    private static DailySpcOutlook compute(ServerLevel level, BlockPos origin, long day) {
        ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, origin);
        double cape = profile.capeJkg();
        double shear = profile.deepLayerShearMps();
        double mode = profile.stormModeIndex();
        SpcRisk risk;
        if (cape >= 4200.0D && shear >= 30.0D && mode >= 0.78D) {
            risk = SpcRisk.HIGH;
        } else if (cape >= 3000.0D && shear >= 23.0D && mode >= 0.62D) {
            risk = SpcRisk.MODERATE;
        } else if (cape >= 1800.0D && shear >= 16.0D && mode >= 0.46D) {
            risk = SpcRisk.ENHANCED;
        } else if (cape >= 900.0D && shear >= 10.0D) {
            risk = SpcRisk.SLIGHT;
        } else {
            risk = SpcRisk.MARGINAL;
        }

        double severity = Mth.clamp((risk.severity() - SpcRisk.MARGINAL.severity()) / 4.0D, 0.0D, 1.0D);
        double tornadoProb = Mth.clamp(0.01D + severity * 0.34D + (shear >= 22.0D ? 0.06D : 0.0D), 0.0D, 0.55D);
        double windProb = Mth.clamp(0.03D + severity * 0.52D + Math.max(0.0D, shear - 18.0D) * 0.012D, 0.0D, 0.75D);
        double hailProb = Mth.clamp(0.02D + severity * 0.38D + Math.max(0.0D, cape - 1600.0D) / 9000.0D, 0.0D, 0.70D);
        double supercellMultiplier = Mth.clamp(0.55D + severity * 2.65D, 0.35D, 4.0D);
        double qlcsMultiplier = risk.severity() >= SpcRisk.MODERATE.severity() ? Mth.clamp(0.8D + severity * 1.5D, 0.0D, 2.6D) : 0.0D;
        double hurricaneMultiplier = ThermodynamicProfileService.hurricaneWindow(level, origin) ? Mth.clamp((profile.moisture() + profile.temperatureModifier() + 0.25D) * 0.8D, 0.0D, 1.5D) : 0.0D;
        double dustMultiplier = risk.severity() <= SpcRisk.SLIGHT.severity() ? Mth.clamp(1.35D - severity * 0.72D, 0.25D, 1.35D) : 0.0D;
        double rotationThreshold = Mth.clamp(0.92D - severity * 0.34D, 0.54D, 0.94D);
        return new DailySpcOutlook(day, risk, profile, tornadoProb, windProb, hailProb, supercellMultiplier, qlcsMultiplier, hurricaneMultiplier, dustMultiplier, rotationThreshold);
    }
}
