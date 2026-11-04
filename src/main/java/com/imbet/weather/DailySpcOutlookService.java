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
 *
 * <p>What the environment is <em>going to do</em> with that instability is the synoptic low's
 * business, and a low's fronts are read here through {@link FrontalEnvironment}: the warm front puts
 * the tornado and hail rows above the wind row, the cold front the wind row above everything, the
 * warm sector between them raises the lot, and cool and post-frontal air damps it. Fronts scale the
 * hazard probabilities, sharpen a conditional intensity group the environment already earned, and
 * decide how much of the day's instability spawns as supercells rather than as a line. A day with no
 * low in range is issued exactly as it was before fronts mattered, which is the state the ladder was
 * calibrated in.
 *
 * <p>An outlook is issued for a <em>region</em> of the map rather than for the world, because the air
 * an outlook describes is the air over a place and there is no such thing as the air over a world.
 * With regional issuance on, every region this world is carrying is issued on the same schedule from
 * its own centre, so the alley can sit at moderate while the desert next door is marginal on the same
 * afternoon; with it off there is one region keyed at world spawn, which is exactly how this behaved
 * before any of it existed. A region nobody has asked about is issued anyway, so walking into one
 * finds a forecast waiting rather than a forecast being made on arrival.
 */
public final class DailySpcOutlookService {
    private static final Map<ResourceKey<Level>, Map<Long, DailySpcOutlook>> OUTLOOKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> ISSUED_SLOTS = new HashMap<>();

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
        Map<Long, DailySpcOutlook> regions = OUTLOOKS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        Map<Long, Integer> issued = ISSUED_SLOTS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        if (regions.isEmpty()) {
            issue(level, regionKey(level, null), day, slot);
            return;
        }
        // Every region this world is carrying is issued on the same schedule, so a region nobody has stood
        // in since dawn still has its afternoon outlook by the time someone walks into it. The key set is
        // copied because issuing writes to the map it came from.
        for (Long key : regions.keySet().toArray(new Long[0])) {
            DailySpcOutlook existing = regions.get(key);
            Integer prior = issued.get(key);
            if (existing == null || existing.day() != day || prior == null || prior != slot) {
                issue(level, key, day, slot);
            }
        }
        // A region that has been issued nothing since the day before last is a region nobody has been near
        // and no storm is in, so it is dropped rather than carried for the life of the world.
        regions.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().day() < day - 1L);
        issued.keySet().removeIf(key -> !regions.containsKey(key));
    }

    public static DailySpcOutlook current(ServerLevel level, BlockPos origin) {
        if (level == null) {
            return DailySpcOutlook.neutral(0L);
        }
        long dayTime = level.getDayTime();
        long day = Math.floorDiv(dayTime, 24000L);
        int slot = Config.DAILY_SPC_OUTLOOK_ISSUANCE_CYCLE.get() ? slotFor(dayTime % 24000L) : 0;
        long key = regionKey(level, origin);
        Map<Long, DailySpcOutlook> regions = OUTLOOKS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        DailySpcOutlook outlook = regions.get(key);
        Integer prior = ISSUED_SLOTS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>()).get(key);
        if (outlook == null || outlook.day() != day || prior == null || prior != slot) {
            outlook = issue(level, key, day, slot);
        }
        return outlook;
    }

    /**
     * Which region's air a position is asking about. With regional issuance off, the whole dimension is
     * one region keyed at zero and issued from world spawn, which is every build before this behaved.
     */
    private static long regionKey(ServerLevel level, BlockPos pos) {
        if (!Config.CLIMATE_REGIONAL_OUTLOOK.get()) {
            return 0L;
        }
        BlockPos at = pos == null ? level.getSharedSpawnPos() : pos;
        return BiomeClimate.cellKey(at.getX(), at.getZ(), Config.climateRegionSizeBlocks());
    }

    /**
     * Where a region's air is measured: its own centre. A region's outlook must not depend on who asked
     * or where they were standing when they did, or the same country would be issued two forecasts.
     */
    private static BlockPos regionOrigin(ServerLevel level, long key) {
        if (key == 0L && !Config.CLIMATE_REGIONAL_OUTLOOK.get()) {
            return level.getSharedSpawnPos();
        }
        int size = Config.climateRegionSizeBlocks();
        return new BlockPos(BiomeClimate.cellCentreX(key, size), level.getSeaLevel(), BiomeClimate.cellCentreZ(key, size));
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

    private static DailySpcOutlook issue(ServerLevel level, long key, long day, int slot) {
        BlockPos origin = regionOrigin(level, key);
        DailySpcOutlook outlook = compute(level, origin, day, slot, key);
        OUTLOOKS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>()).put(key, outlook);
        ISSUED_SLOTS.computeIfAbsent(level.dimension(), ignored -> new HashMap<>()).put(key, slot);
        if (Config.LOG_STARTUP.getAsBoolean()) {
            ExpandedWeather2Dynamics.LOGGER.info("SPC outlook {} issued{}: {} ({}) - {}",
                    outlook.issuance(),
                    Config.CLIMATE_REGIONAL_OUTLOOK.get()
                            ? " for " + BiomeClimateService.alley(level, origin).label().toLowerCase(Locale.ROOT)
                                    + " at " + origin.getX() + ", " + origin.getZ()
                            : "",
                    outlook.risk().label(), outlook.productLine(),
                    outlook.frontalRegime() == FrontalRegime.NONE
                            ? "no front in range"
                            : outlook.frontalLabel() + " of a synoptic low, "
                                    + outlook.frontalRegime().hazardSummary());
        }
        return outlook;
    }

    private static DailySpcOutlook compute(ServerLevel level, BlockPos origin, long day, int slot, long regionKey) {
        ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, origin);
        double[] issue = ISSUES[Math.max(0, Math.min(ISSUES.length - 1, slot))];
        // The wobble is per region as well as per day and issue. Two neighbouring regions are already
        // different air; this is what keeps their two forecasts from ending in the same digit, and it is
        // what stops a region boundary being visible as a step in the map.
        double cape = Mth.clamp(profile.capeJkg() * issue[3] * jitter(level, day, slot, 1, regionKey), 0.0D, 5500.0D);
        double shear = Mth.clamp(profile.deepLayerShearMps() * issue[4] * jitter(level, day, slot, 2, regionKey), 0.0D, 48.0D);
        double mode = profile.stormModeIndex();

        // Which air mass of which low this place is in. A low's fronts are what decides whether the same
        // instability turns into discrete supercells, a squall line, or elevated hail on a warm front, so
        // this is read before any hazard is forecast rather than applied afterwards as a modifier.
        FrontalEnvironment.Influence frontal = FrontalEnvironment.at(level, origin);

        // The conditional intensity comes first, because past the published ladder the category *is* the
        // intensity: an environment beyond the significant-severe thresholds is off the published scale
        // by definition. Both come from these same three numbers, so the category on the panel and the
        // CIG hatching beside it can never disagree. The front sharpens the group the environment earned
        // and can never carry it onto the extension columns, which stay the environment's own.
        int tornadoCig = frontal.biasCig(SpcHazard.TORNADO, SpcIntensity.tornadoCig(cape, shear, mode));
        int windCig = frontal.biasCig(SpcHazard.WIND, SpcIntensity.windCig(cape, shear));
        int hailCig = frontal.biasCig(SpcHazard.HAIL, SpcIntensity.hailCig(cape, shear));

        SpcRisk ladder = extensionTier(tierFor(cape, shear, mode), tornadoCig, windCig);
        double severity = Mth.clamp((ladder.severity() - SpcRisk.MARGINAL.severity()) / 4.0D, 0.0D, 1.0D);
        // Severity saturates at high risk, so the extension tiers carry their own extra push: an
        // extreme day is meant to spawn noticeably more than the strongest high-risk day.
        double outbreak = Math.max(0, ladder.severity() - SpcRisk.HIGH.severity()) * 0.30D;
        double tornadoProb = Mth.clamp((0.01D + severity * 0.34D + (shear >= 22.0D ? 0.06D : 0.0D))
                * frontal.scale(frontal.regime().tornadoFactor()), 0.0D, 0.55D);
        double windProb = Mth.clamp((0.03D + severity * 0.52D + Math.max(0.0D, shear - 18.0D) * 0.012D)
                * frontal.scale(frontal.regime().windFactor()), 0.0D, 0.75D);
        double hailProb = Mth.clamp((0.02D + severity * 0.38D + Math.max(0.0D, cape - 1600.0D) / 9000.0D)
                * frontal.scale(frontal.regime().hailFactor()), 0.0D, 0.70D);

        // Each hazard's own category, read straight off the SPC decision matrix with the probability and
        // the intensity group this issuance is carrying. This is where a front changes the day: the same
        // instability puts the tornado row above the wind row on a warm front and the wind row above the
        // tornado row on a cold front.
        SpcRisk tornadoTier = SpcRisk.fromHazard(SpcHazard.TORNADO, (int) Math.round(tornadoProb * 100.0D), tornadoCig);
        SpcRisk windTier = SpcRisk.fromHazard(SpcHazard.WIND, (int) Math.round(windProb * 100.0D), windCig);
        SpcRisk hailTier = SpcRisk.fromHazard(SpcHazard.HAIL, (int) Math.round(hailProb * 100.0D), hailCig);

        // A front is what lets the day's category follow its hazards. Without one the day is the
        // environment's own ladder and the product is issued to stand where the environment put it, which
        // is what the ladder was calibrated for; with one the day is the strongest of the three rows and
        // that ladder, so a front can sharpen the forecast and can never weaken it. Letting the raw rows
        // set the category whether or not a front is present was measured at tripling the high-risk days
        // on a sweep of environments (4.6% of them to 16.6%), which would have undone the ladder's own
        // calibration - the published decision matrix is more aggressive than this environment model
        // needs to be, so it is the front that is allowed to reach for it.
        boolean fronted = frontal.present();
        SpcRisk rowTier = SpcRisk.max(tornadoTier, SpcRisk.max(windTier, hailTier));
        SpcRisk risk = fronted ? SpcRisk.max(ladder, rowTier) : ladder;

        double supercellMultiplier = Mth.clamp((0.55D + severity * 2.65D + outbreak)
                * frontal.scale(frontal.regime().supercellFactor()), 0.35D, 4.0D);
        double qlcsMultiplier = risk.severity() >= SpcRisk.MODERATE.severity()
                ? Mth.clamp((0.8D + severity * 1.5D + outbreak)
                        * frontal.scale(frontal.regime().qlcsFactor()), 0.0D, 3.2D) : 0.0D;
        double hurricaneMultiplier = ThermodynamicProfileService.hurricaneWindow(level, origin)
                ? Mth.clamp((profile.moisture() + profile.temperatureModifier() + 0.25D) * 0.8D, 0.0D, 1.5D) : 0.0D;
        double dustMultiplier = risk.severity() <= SpcRisk.SLIGHT.severity() ? Mth.clamp(1.35D - severity * 0.72D, 0.25D, 1.35D) : 0.0D;
        double rotationThreshold = Mth.clamp(0.92D - severity * 0.34D - outbreak * 0.10D, 0.50D, 0.94D);

        // The issued product: probability level plus conditional intensity, per hazard.
        int[] tornadoCell = cellFor(SpcHazard.TORNADO, fronted ? tornadoTier : risk, tornadoProb * 100.0D, tornadoCig);
        int[] windCell = cellFor(SpcHazard.WIND, fronted ? windTier : risk, windProb * 100.0D, windCig);
        int[] hailCell = cellFor(SpcHazard.HAIL, fronted ? hailTier : risk, hailProb * 100.0D, hailCig);

        return new DailySpcOutlook(day, risk, profile,
                frontal.regime(), frontal.proximity(),
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
     * The thermodynamic thresholds that decide the day's forecast category: this is the published SPC
     * ladder and nothing else.
     *
     * <p>The high-risk rung was lowered from 4200 J/kg, 30 m/s and 0.78 mode because the extension CIG
     * gates now sit above it. At the old values every high-risk environment already qualified for an
     * extension, so the ladder jumped straight from moderate to extreme and high risk was never issued.
     * The rungs now fall away from each other by roughly a factor of three to four from enhanced
     * upward, which is close to how the real product's own categories are distributed.
     */
    private static SpcRisk tierFor(double cape, double shear, double mode) {
        if (cape >= 3800.0D && shear >= 28.0D && mode >= 0.74D) {
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

    /** Small deterministic per-day, per-issue, per-region wobble so two places and two days differ. */
    private static double jitter(ServerLevel level, long day, int slot, int channel, long regionKey) {
        long hash = level.getSeed() * 6364136223846793005L + day * 1442695040888963407L + slot * 97L
                + channel * 31L + regionKey * 0x9E3779B97F4A7C15L;
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        return 0.96D + Math.floorMod(hash, 1000L) / 1000.0D * 0.08D;
    }
}
