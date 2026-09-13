package com.imbet.weather;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Issues the fictional Day 1 outlook the way SPC does: a fixed schedule of updates through the day
 * (0600Z, 1300Z, 1630Z, 2000Z, 0100Z, with the afternoon issue the important one), each one a fresh
 * look at the environment rather than a single daily number.
 *
 * <p>In game the world clock starts at dawn, which this maps to 0600Z, so the issue times fall where
 * they should: instability has not built at the morning issue, peaks through the afternoon, and
 * decays overnight. Environmental quantities are evolved per issue - CAPE most, shear barely - with a
 * small deterministic per-day jitter, which is what lets an outlook upgrade from marginal in the
 * morning to slight or enhanced by the 1630Z update, and downgrade again after dark, exactly as real
 * outlooks do.
 *
 * <p>Every outlook is issued as a probability plus conditional intensity group per hazard, chosen so
 * the SPC decision matrix converts back to the day's category. The continuous probability fields are
 * kept as the internal spawn drivers, so storm spawning behaves as before while the product shows
 * real SPC numbers.
 *
 * <p>Past high risk the ladder continues onto the addon's extreme and hyper extreme tiers, which need
 * a genuinely off-the-charts environment: the published table's top rungs plus the extension CIG
 * columns. Those days also get an outbreak bonus on top of the saturated severity factor, so an
 * extreme day spawns more and violent tornadoes more readily instead of merely wearing a bigger
 * label.
 */
public final class DailySpcOutlookService {
    private static final Map<ResourceKey<Level>, DailySpcOutlook> OUTLOOKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Integer> ISSUED_SLOT = new HashMap<>();

    /** Issue times, as {zulu hour, zulu minute, tick of the Minecraft day, CAPE factor, shear factor}. */
    private static final double[][] ISSUES = {
            {6.0D, 0.0D, 0.0D, 0.72D, 1.00D},
            {13.0D, 0.0D, 7000.0D, 0.92D, 0.98D},
            {16.5D, 0.0D, 10500.0D, 1.08D, 1.02D},
            {20.0D, 0.0D, 14000.0D, 1.00D, 1.00D},
            {1.0D, 0.0D, 19000.0D, 0.74D, 0.96D}
    };

    private DailySpcOutlookService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || !Config.DAILY_SPC_OUTLOOK_ENABLED.get()) {
            return;
        }
        long dayTime = level.getDayTime();
        long day = Math.floorDiv(dayTime, 24000L);
        int slot = Config.DAILY_SPC_OUTLOOK_ISSUANCE_CYCLE.get() ? slotFor(dayTime % 24000L) : 0;
        DailySpcOutlook existing = OUTLOOKS.get(level.dimension());
        Integer issued = ISSUED_SLOT.get(level.dimension());
        if (existing != null && existing.day() == day && issued != null && issued == slot) {
            return;
        }
        issue(level, level.getSharedSpawnPos(), day, slot);
    }

    public static DailySpcOutlook current(ServerLevel level, BlockPos origin) {
        if (level == null) {
            return DailySpcOutlook.neutral(0L);
        }
        long dayTime = level.getDayTime();
        long day = Math.floorDiv(dayTime, 24000L);
        DailySpcOutlook outlook = OUTLOOKS.get(level.dimension());
        if (outlook == null || outlook.day() != day) {
            int slot = Config.DAILY_SPC_OUTLOOK_ISSUANCE_CYCLE.get() ? slotFor(dayTime % 24000L) : 0;
            outlook = issue(level, origin == null ? level.getSharedSpawnPos() : origin, day, slot);
        }
        return outlook;
    }

    /** The issuance label currently in force, e.g. "D1 1630Z". */
    public static String issuanceLabel(ServerLevel level, BlockPos origin) {
        return current(level, origin).issuance();
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

    private static DailySpcOutlook issue(ServerLevel level, BlockPos origin, long day, int slot) {
        DailySpcOutlook outlook = compute(level, origin, day, slot);
        OUTLOOKS.put(level.dimension(), outlook);
        ISSUED_SLOT.put(level.dimension(), slot);
        if (Config.LOG_STARTUP.getAsBoolean()) {
            ExpandedWeather2Dynamics.LOGGER.info("SPC outlook {} issued: {} ({})",
                    outlook.issuance(), outlook.risk().label(), outlook.productLine());
        }
        return outlook;
    }

    private static DailySpcOutlook compute(ServerLevel level, BlockPos origin, long day, int slot) {
        ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, origin);
        double[] issue = ISSUES[Math.max(0, Math.min(ISSUES.length - 1, slot))];
        double cape = Mth.clamp(profile.capeJkg() * issue[3] * jitter(level, day, slot, 1), 0.0D, 5500.0D);
        double shear = Mth.clamp(profile.deepLayerShearMps() * issue[4] * jitter(level, day, slot, 2), 0.0D, 48.0D);
        double mode = profile.stormModeIndex();

        // The conditional intensity comes first, because past the published ladder the category *is* the
        // intensity: an environment beyond the significant-severe thresholds is off the published scale
        // by definition. Both come from these same three numbers, so the category on the panel and the
        // CIG hatching beside it can never disagree.
        int tornadoCig = SpcIntensity.tornadoCig(cape, shear, mode);
        int windCig = SpcIntensity.windCig(cape, shear);
        int hailCig = SpcIntensity.hailCig(cape, shear);

        SpcRisk risk = extensionTier(tierFor(cape, shear, mode), tornadoCig, windCig);
        double severity = Mth.clamp((risk.severity() - SpcRisk.MARGINAL.severity()) / 4.0D, 0.0D, 1.0D);
        // Severity saturates at high risk, so the extension tiers carry their own extra push: an
        // extreme day is meant to spawn noticeably more than the strongest high-risk day.
        double outbreak = Math.max(0, risk.severity() - SpcRisk.HIGH.severity()) * 0.30D;
        double tornadoProb = Mth.clamp(0.01D + severity * 0.34D + (shear >= 22.0D ? 0.06D : 0.0D), 0.0D, 0.55D);
        double windProb = Mth.clamp(0.03D + severity * 0.52D + Math.max(0.0D, shear - 18.0D) * 0.012D, 0.0D, 0.75D);
        double hailProb = Mth.clamp(0.02D + severity * 0.38D + Math.max(0.0D, cape - 1600.0D) / 9000.0D, 0.0D, 0.70D);
        double supercellMultiplier = Mth.clamp(0.55D + severity * 2.65D + outbreak, 0.35D, 4.0D);
        double qlcsMultiplier = risk.severity() >= SpcRisk.MODERATE.severity()
                ? Mth.clamp(0.8D + severity * 1.5D + outbreak, 0.0D, 3.2D) : 0.0D;
        double hurricaneMultiplier = ThermodynamicProfileService.hurricaneWindow(level, origin)
                ? Mth.clamp((profile.moisture() + profile.temperatureModifier() + 0.25D) * 0.8D, 0.0D, 1.5D) : 0.0D;
        double dustMultiplier = risk.severity() <= SpcRisk.SLIGHT.severity() ? Mth.clamp(1.35D - severity * 0.72D, 0.25D, 1.35D) : 0.0D;
        double rotationThreshold = Mth.clamp(0.92D - severity * 0.34D - outbreak * 0.10D, 0.50D, 0.94D);

        // The issued product: probability level plus conditional intensity, per hazard.
        int[] tornadoCell = cellFor(SpcHazard.TORNADO, risk, tornadoProb * 100.0D, tornadoCig);
        int[] windCell = cellFor(SpcHazard.WIND, risk, windProb * 100.0D, windCig);
        int[] hailCell = cellFor(SpcHazard.HAIL, risk, hailProb * 100.0D, hailCig);

        return new DailySpcOutlook(day, risk, profile,
                tornadoProb, windProb, hailProb,
                supercellMultiplier, qlcsMultiplier, hurricaneMultiplier, dustMultiplier, rotationThreshold,
                label(slot), level.getGameTime(),
                tornadoCell[0], windCell[0], hailCell[0],
                tornadoCell[1], windCell[1], hailCell[1]);
    }

    /**
     * The day's category as issued: the forecast ladder, extended onto the two tiers above the published
     * one when the environment's own conditional intensity is past the published CIG columns. The
     * strongest hazard decides, and since the extension levels step up from CIG 3, a day can only reach
     * them by beating the significant-severe thresholds outright - not by forecasting differently.
     */
    private static SpcRisk extensionTier(SpcRisk forecast, int tornadoCig, int windCig) {
        int cig = Math.max(tornadoCig, windCig);
        if (cig >= 5) {
            return SpcRisk.max(forecast, SpcRisk.HYPER);
        }
        if (cig >= 4) {
            return SpcRisk.max(forecast, SpcRisk.EXTREME);
        }
        return forecast;
    }

    /**
     * Picks the issued probability and intensity for a hazard: the cell that converts back to the
     * day's category, closest to the environment's own estimate. A hazard that cannot express the
     * category alone (hail never reaches high risk) is capped at the strongest cell it can issue, so
     * the product still carries a sensible number.
     */
    private static int[] cellFor(SpcHazard hazard, SpcRisk tier, double percentEstimate, int cig) {
        SpcRisk capped = SpcRisk.cap(tier, hazard.maxExpressible());
        return hazard.bestCellForTier(capped, percentEstimate, hazard.clampedCig(cig));
    }

    /**
     * The legacy thermodynamic thresholds that decide the day's forecast category, unchanged: this is
     * the published SPC ladder and nothing else.
     */
    private static SpcRisk tierFor(double cape, double shear, double mode) {
        if (cape >= 4200.0D && shear >= 30.0D && mode >= 0.78D) {
            return SpcRisk.HIGH;
        }
        if (cape >= 3000.0D && shear >= 23.0D && mode >= 0.62D) {
            return SpcRisk.MODERATE;
        }
        if (cape >= 1800.0D && shear >= 16.0D && mode >= 0.46D) {
            return SpcRisk.ENHANCED;
        }
        if (cape >= 900.0D && shear >= 10.0D) {
            return SpcRisk.SLIGHT;
        }
        return SpcRisk.MARGINAL;
    }

    private static int slotFor(long dayTime) {
        int slot = 0;
        for (int index = 0; index < ISSUES.length; index++) {
            if (dayTime >= ISSUES[index][2]) {
                slot = index;
            }
        }
        return slot;
    }

    private static String label(int slot) {
        double[] issue = ISSUES[Math.max(0, Math.min(ISSUES.length - 1, slot))];
        return String.format(Locale.ROOT, "D1 %02d%02dZ", (int) issue[0], (int) issue[1]);
    }

    /** Small deterministic per-day, per-issue wobble so consecutive days differ in detail. */
    private static double jitter(ServerLevel level, long day, int slot, int channel) {
        long hash = level.getSeed() * 6364136223846793005L + day * 1442695040888963407L + slot * 97L + channel * 31L;
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        return 0.96D + Math.floorMod(hash, 1000L) / 1000.0D * 0.08D;
    }
}
