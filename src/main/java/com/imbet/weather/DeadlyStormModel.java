package com.imbet.weather;

import java.util.Locale;

/**
 * Whether a day can carry a deadly storm, and how much of one the air is worth.
 *
 * <p>Weather2's deadly cycle is a timer, not a forecast, and it is worth stating exactly what it does because
 * everything here hangs off it. In {@code StormObject.tickProgression} a storm that is still at
 * {@code STATE_NORMAL} - a fresh cell, not one already grown - is promoted into a deadly storm once the
 * manager's last storm is older than {@code Server_Storm_Deadly_TimeBetweenInTicks}, which ships at 72000
 * ticks, three game days; where that timer is off, the land-based one at 240000 ticks, ten game days, is what
 * fires instead. That three-to-ten-day cadence is Weather2's and is not this mod's to move: the only question
 * it asks before promoting a storm is {@link weather2.weathersystem.storm.StormObject#canBeDeadly}, and that
 * flag belongs to whoever spawned the cell.
 *
 * <p>It used to be set unconditionally, which made the timer a dice roll that ignored the sky: a capped,
 * blown-out air mass produced its deadly storm on schedule exactly as a loaded one did, and the readout had
 * nothing honest to say about it but a flat per-day percentage - the same number on a lid as on a loaded
 * afternoon. So the flag is the air's now, and this class is the reading behind it. The percentage the
 * forecast prints is computed from the same method the flag is, which is the only way the two are kept from
 * disagreeing: a sky that cannot carry a deadly storm reads 0.00% and cannot produce one, and a sky that can
 * reads the odds Weather2 was already rolling.
 *
 * <p>Deliberately smaller than the mod's own thermodynamic profile. Instability gates it and shear shapes it
 * and nothing else does, because those are the two things the question is actually about: CAPE is the
 * necessary condition - a deadly storm is a deep moist updraft, and no amount of shear on a capped column
 * produces one - so the CAPE term multiplies, and shear is what decides how much of that instability a storm
 * can organise into one, so it scales the reading between a share of itself and all of it. The shear share is
 * not zero because a marginally unstable but strongly sheared day is exactly the day a low-topped supercell
 * comes out of, and not one because a sheared and capped day is nothing at all.
 *
 * <p>The two ends are the shipped odds rather than numbers invented here: a fully loaded air mass is worth
 * exactly the per-day chance Weather2's own odds describe, and a capped one is worth none of it, with every
 * sky between them landing somewhere between. Nothing in here knows about a world: the wiring reads CAPE and
 * shear off the profile and hands them in.
 */
public final class DeadlyStormModel {
    private DeadlyStormModel() {
    }

    /** No CAPE beyond this is asked for, matching the ceiling the air model itself holds CAPE to. */
    public static final double CAPE_LIMIT_JKG = BiomeClimate.CAPE_CEILING_JKG;

    /**
     * The air's reading of how deadly a day is: 0 for a sky that cannot carry a deadly storm at all, 1 for one
     * worth every bit of the shipped odds.
     */
    public static final double NO_AIR = 0.0D;
    public static final double FULL_AIR = 1.0D;

    /** Half the air's reading, and the line between a marginal and a loaded sky. */
    public static final double LOADED_LINE = 0.5D;

    /**
     * The knobs, with the values this mod ships.
     *
     * <p>A record rather than four static fields so that a world's own numbers can be handed in as one thing,
     * and validated in one place: a config file is a text file a player can edit, so a floor above its own
     * full mark, a negative share or a shear of zero all have to arrive at arithmetic that still means
     * something rather than at a division by nothing.
     */
    public record Settings(double capeFloorJkg, double capeFullJkg, double shearShare, double fullShearMps) {
        /** 1000 J/kg is the floor a severe updraft is conventionally said to need; 3000 is a loaded afternoon. */
        public static final Settings SHIPPED = new Settings(1000.0D, 3000.0D, 0.25D, 30.0D);

        public Settings {
            capeFloorJkg = clamp(finite(capeFloorJkg), 0.0D, CAPE_LIMIT_JKG);
            capeFullJkg = clamp(finite(capeFullJkg), 0.0D, CAPE_LIMIT_JKG);
            shearShare = clamp(finite(shearShare), 0.0D, 1.0D);
            // A shear of zero would make the shear term a division by nothing, and a floor of anything at all
            // is nearer to the truth than that: the calmest sky still has some.
            fullShearMps = Math.max(1.0D, finite(fullShearMps));
        }

        /**
         * Whether the full mark stands above the floor. A pack that writes them the other way round, or
         * writes them equal, has asked for a sky with no middle: every CAPE is either below the floor or at
         * the full mark, and the reading below says exactly that rather than dividing by the gap it was not
         * given.
         */
        public boolean hasRamp() {
            return capeFullJkg > capeFloorJkg;
        }
    }

    /**
     * The air's own reading at one place and time, out of the two numbers the profile carries for it.
     *
     * <p>0 whenever CAPE is at or below the floor, whatever the shear: a stable column is a stable column,
     * and the mod has no business calling a deadly storm out of one. Above the floor the CAPE term ramps to
     * one over the floor-to-full span and the shear term scales that between the shipped share and all of it.
     */
    public static double airShare(Settings settings, double capeJkg, double shearMps) {
        double cape = capeTerm(settings, capeJkg);
        if (cape <= 0.0D) {
            return NO_AIR;
        }
        double shear = clamp(finite(shearMps) / settings.fullShearMps(), 0.0D, 1.0D);
        double share = settings.shearShare();
        return clamp(cape * (1.0D - share + share * shear), NO_AIR, FULL_AIR);
    }

    /**
     * The chance of a deadly storm on a day like this one, out of the odds Weather2's own timer describes.
     *
     * <p>The ceiling is not something this class decides - it is the per-day chance the deadly timer works out
     * to, worked out in the readout from the same two config numbers Weather2 rolls against - and this only
     * says how much of it this sky is worth.
     */
    public static double oddsPerDay(Settings settings, double ceilingPerDay, double capeJkg, double shearMps) {
        double ceiling = Math.max(0.0D, finite(ceilingPerDay));
        return ceiling * airShare(settings, capeJkg, shearMps);
    }

    /** Whether a storm born in this air may be promoted into a deadly one, which is what the flag means. */
    public static boolean supports(Settings settings, double capeJkg, double shearMps) {
        return airShare(settings, capeJkg, shearMps) > NO_AIR;
    }

    /**
     * How much of a storm's own ladder this air is worth, as a fraction of what a fully loaded sky would give
     * it: the cell is capped, or grown, rather than being handed the same room in every sky.
     *
     * <p>It never falls to nothing, and that is deliberate: a sky above the floor has already been called able
     * to carry a deadly storm, so the cell it carries starts from a real storm and not from a stub.
     */
    public static double strengthScale(double airShare) {
        double air = clamp(finite(airShare), NO_AIR, FULL_AIR);
        return 0.55D + 0.45D * air;
    }

    /**
     * The rung of the ladder a cell is granted in this air, between the rung its kind is capped at and the
     * rung a loaded sky would give it.
     *
     * <p>Interpolated rather than switched at a threshold because the thing being described is a continuous
     * one - how much room a cell has - and a step would make every cell on one side of an invisible line
     * identical. The two ends are the only values the caller's ladder knows anything about, so those are the
     * two ends here, and the result is held between them whichever way round they are given.
     */
    public static int ladderRung(int cappedStage, int fullStage, double airShare) {
        int low = Math.min(cappedStage, fullStage);
        int high = Math.max(cappedStage, fullStage);
        double air = clamp(finite(airShare), NO_AIR, FULL_AIR);
        int middle = (int) Math.round(low + (high - low) * air);
        return Math.max(low, Math.min(high, middle));
    }

    /** The air in a word, for a readout that has one line to say it in. */
    public static String label(double airShare) {
        double air = clamp(finite(airShare), NO_AIR, FULL_AIR);
        if (air <= NO_AIR) {
            return "capped";
        }
        return air < LOADED_LINE ? "marginal" : "loaded";
    }

    /**
     * The clause the forecast readout prints, so that the number a player reads and the number the flag is
     * decided by are the same arithmetic rather than two descriptions of it.
     *
     * <p>It names the two readings the share came from and the air in a word, because a percentage on its own
     * says nothing about which way it is going to move: 12.4% from a rising 2000 J/kg is a different day from
     * 12.4% out of a lid that is about to break.
     */
    public static String oddsLine(Settings settings, double ceilingPerDay, double capeJkg, double shearMps) {
        double air = airShare(settings, capeJkg, shearMps);
        return String.format(Locale.ROOT,
                "deadly odds %.2f%% a day - CAPE %.0f J/kg, shear %.0f m/s, %s air",
                oddsPerDay(settings, ceilingPerDay, capeJkg, shearMps) * 100.0D,
                Math.max(0.0D, finite(capeJkg)),
                Math.max(0.0D, finite(shearMps)),
                label(air));
    }

    /** The CAPE term alone: 0 at or below the floor, 1 at or above the full mark, a step with no ramp. */
    private static double capeTerm(Settings settings, double capeJkg) {
        double cape = clamp(finite(capeJkg), 0.0D, CAPE_LIMIT_JKG);
        if (!settings.hasRamp()) {
            return cape > settings.capeFloorJkg() ? FULL_AIR : NO_AIR;
        }
        return clamp((cape - settings.capeFloorJkg()) / (settings.capeFullJkg() - settings.capeFloorJkg()),
                NO_AIR, FULL_AIR);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }
}
