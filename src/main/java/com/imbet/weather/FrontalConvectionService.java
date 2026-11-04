package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The convection a synoptic low makes on its own fronts.
 *
 * <p>Before this the fronts were a forecast and nothing else: they changed the day's probabilities,
 * its intensity groups and how readily storms spawned, but no storm in the world was ever actually
 * theirs. A front is not a modifier on a chart, it is where air is being forced up, so this is the
 * piece that puts the low's weather in the sky - and it is what makes a low something a player can
 * walk out and watch rather than read about.
 *
 * <p>The cell is placed first and classified second, which is the order the atmosphere works in. A
 * candidate point is chosen on one of the low's own front arms - the cold front's line, the warm
 * front's line, or the wedge between them - and then the front geometry is asked which regime that
 * place actually is. Only a point that really is on a front, or really is in the wrap of the warm
 * sector, can seed anything, so a front makes cells where a front is and not wherever the low's
 * centre happens to be. The regime it lands in then decides the cell: a line's forced cells, the
 * warm sector's discrete supercells, or a warm front's elevated ones (see {@link FrontalCellKind}).
 *
 * <p>A low has to have organised its fronts to force anything - a system that has just been born, or
 * one that is filling, has no front yet - and it can only keep a few cells of its own in the air at
 * once, which is what stops a single low from flooding the map.
 */
public final class FrontalConvectionService {
    /** A low shallower than this has not organised its fronts yet, so there is nothing forcing air up. */
    private static final double MIN_SEED_INTENSITY = 0.35D;
    /** How much of the front's influence a place needs before a cell can be seeded on it. */
    private static final double MIN_FRONT_INFLUENCE = 0.20D;
    /**
     * Storms outrun the system that made them: a low drifts at 2 to 12 mph while its cells ride the
     * same flow faster, so a seeded cell is given the low's heading at a multiple of its speed.
     */
    private static final double CELL_MOTION_MULTIPLIER = 2.4D;
    private static final double MIN_CELL_MOTION_BLOCKS_PER_TICK = 0.030D;
    private static final double MAX_CELL_MOTION_BLOCKS_PER_TICK = 0.120D;
    /** How far around the wedge's midline a warm-sector cell may be seeded, in degrees of bearing. */
    private static final double WARM_SECTOR_SPREAD_DEGREES = 40.0D;
    /** The fallback heading when a low has no motion to speak of, matching how the chart draws it. */
    private static final double FALLBACK_HEADING_X = 1.0D;
    private static final double FALLBACK_HEADING_Z = -0.35D;

    private FrontalConvectionService() {
    }

    public static void tick(ServerLevel level) {
        if (!Config.LOW_PRESSURE_ENABLED.get() || !Config.FRONTAL_CONVECTION_ENABLED.get()) {
            return;
        }
        long interval = Math.max(100, Config.FRONTAL_CONVECTION_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        List<SynopticLow> lows = SynopticLowStore.get(level).lows();
        if (lows.isEmpty()) {
            return;
        }
        int maxPerLow = Config.FRONTAL_CONVECTION_MAX_CELLS_PER_LOW.get();
        for (SynopticLow low : lows) {
            if (low.intensity() < MIN_SEED_INTENSITY) {
                continue;
            }
            if (AddonWeatherEventService.frontalCellCount(level, low) >= maxPerLow) {
                continue;
            }
            // A front forces air up in any season, but a winter front does it far more readily than a
            // summer one, and a summer one is often a heat low with hardly a shower on it: the season's
            // own frontal scale is what makes the same low a November gale line and a July warm sector.
            double cellChance = Mth.clamp(Config.FRONTAL_CONVECTION_CELL_CHANCE.get()
                    * SeasonCalendarService.effect(level).frontal(), 0.0D, 1.0D);
            if (level.random.nextDouble() > cellChance) {
                continue;
            }
            seedOne(level, low);
        }
    }

    /**
     * One cell, on one of the low's own fronts. The candidate is drawn from the front arms more often
     * than from the wedge between them, because that is where the forcing is: a warm sector is wide and
     * a front is a line, so sampling the circle uniformly would almost never land on the line itself.
     */
    private static void seedOne(ServerLevel level, SynopticLow low) {
        double heading = headingDegrees(low);
        double bearing;
        double distance;
        double roll = level.random.nextDouble();
        if (roll < 0.40D) {
            bearing = heading + low.coldFrontAngleDegrees();
            distance = low.coldFrontLengthBlocks() * (0.35D + level.random.nextDouble() * 0.55D);
        } else if (roll < 0.65D) {
            bearing = heading + low.warmFrontAngleDegrees();
            distance = low.warmFrontLengthBlocks() * (0.30D + level.random.nextDouble() * 0.55D);
        } else {
            bearing = heading + (low.warmFrontAngleDegrees() + low.coldFrontAngleDegrees()) * 0.5D
                    + (level.random.nextDouble() - 0.5D) * WARM_SECTOR_SPREAD_DEGREES;
            distance = low.radius() * (0.30D + level.random.nextDouble() * 0.55D);
        }
        double radians = Math.toRadians(bearing);
        double x = low.x() + Math.cos(radians) * distance;
        double z = low.z() + Math.sin(radians) * distance;

        FrontalEnvironment.Influence influence = FrontalEnvironment.at(level, x, z);
        if (!influence.present() || influence.proximity() < MIN_FRONT_INFLUENCE) {
            return;
        }
        FrontalCellKind kind = kindFor(influence.regime());
        if (kind == null) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level,
                BlockPos.containing(x, level.getSeaLevel() + 64.0D, z));
        if (!allowed(outlook, kind)) {
            return;
        }
        AddonWeatherEventService.seedFrontalCell(level, new Vec3(x, level.getSeaLevel(), z), cellMotion(low), kind);
    }

    /** The regime at a place decides what the front there is making. Cool and post-frontal air makes nothing. */
    private static FrontalCellKind kindFor(FrontalRegime regime) {
        return switch (regime) {
            case COLD_FRONT -> FrontalCellKind.COLD_FRONT_LINE;
            case WARM_FRONT -> FrontalCellKind.WARM_FRONT_ELEVATED;
            case WARM_SECTOR -> FrontalCellKind.WARM_SECTOR_SUPERCELL;
            default -> null;
        };
    }

    /**
     * Whether the day can support this kind of convection at all. A front sharpens what the air already
     * supports and never invents it, which is the same rule the outlook itself is read through - so a
     * quiet day's front seeds nothing, and an extreme day's front seeds the supercells an outbreak is
     * made of.
     */
    private static boolean allowed(DailySpcOutlook outlook, FrontalCellKind kind) {
        return switch (kind) {
            case WARM_SECTOR_SUPERCELL -> outlook.allowsSupercells();
            case COLD_FRONT_LINE -> outlook.allowsQlcs();
            case WARM_FRONT_ELEVATED -> outlook.risk().severity() >= SpcRisk.SLIGHT.severity();
        };
    }

    /** Public because an outbreak's cells ride the same flow their parent low is riding. */
    public static Vec3 cellMotion(SynopticLow low) {
        Vec3 motion = low.motion();
        Vec3 forward = motion.horizontalDistanceSqr() > 1.0E-9D
                ? new Vec3(motion.x, 0.0D, motion.z).normalize()
                : new Vec3(FALLBACK_HEADING_X, 0.0D, FALLBACK_HEADING_Z).normalize();
        double speed = Mth.clamp(motion.horizontalDistance() * CELL_MOTION_MULTIPLIER,
                MIN_CELL_MOTION_BLOCKS_PER_TICK, MAX_CELL_MOTION_BLOCKS_PER_TICK);
        return forward.scale(speed);
    }

    /** The low's heading in degrees, in the same x-to-z sense the front angles are measured in. */
    private static double headingDegrees(SynopticLow low) {
        Vec3 motion = low.motion();
        double mx = motion.x;
        double mz = motion.z;
        if (Math.sqrt(mx * mx + mz * mz) < 1.0E-4D) {
            mx = FALLBACK_HEADING_X;
            mz = FALLBACK_HEADING_Z;
        }
        return Math.toDegrees(Math.atan2(mz, mx));
    }
}
