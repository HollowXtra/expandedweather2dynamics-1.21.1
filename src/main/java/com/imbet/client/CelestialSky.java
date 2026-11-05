package com.imbet.client;

/**
 * The arithmetic of the sky the mod draws for itself: where the sun and moon are, when either kind of eclipse
 * happens and how much of the sun or moon it hides, which tile of the moon's atlas a phase is drawn from, and the
 * great circle the Milky Way band lies on.
 *
 * <p>Kept apart from the renderer on purpose. The renderer needs a level, a shader and a matrix stack to
 * do anything at all, so the numbers it turns into geometry - both eclipse schedules, the coverage curves,
 * the phase's tile, the axis the band is built around - live here where they can be read and tested without a
 * client. The only thing this class knows about Minecraft is the vector type, and that is a convenience for the
 * caller rather than a dependency.
 */
public final class CelestialSky {
    /** One Minecraft day, in ticks. */
    public static final long DAY_TICKS = 24000L;
    /** Noon on the Minecraft clock, where a solar eclipse is centred: the sun is highest and the moon can pass in front. */
    public static final long NOON_TICK = 6000L;
    /** Midnight on the Minecraft clock, where a lunar eclipse is centred: the moon is highest and can cross the Earth's shadow. */
    public static final long MIDNIGHT_TICK = 18000L;
    /** The phase number the game calls a full moon, which is the only one that can be eclipsed. */
    public static final int FULL_MOON_PHASE = 0;

    /**
     * The Earth's umbra at the Moon's distance, in moon radii.
     *
     * <p>Its angular radius is about two and a half times the moon's - which is the whole reason a lunar eclipse
     * lasts hours where a solar one lasts minutes, and the reason the moon can be swallowed whole: the shadow is
     * a wide disc the moon takes a long time to cross. This is the honest figure rather than a look, and the
     * renderer draws the shadow at exactly this size.
     */
    public static final double UMBRA_RADII = 2.6D;

    /**
     * How far apart the moon and the shadow's axis are at first and last contact, in moon radii: their two radii
     * added, which is the separation at which the moon's limb first touches the umbra's.
     */
    public static final double LUNAR_CONTACT_RADII = UMBRA_RADII + 1.0D;

    /**
     * The salt the lunar eclipse's roll is mixed with, so that a night and a day cannot be chosen by the same
     * number.
     *
     * <p>Both eclipses are decided from the day number, and without this both would read the same roll: a day
     * that happened to roll low enough for a solar eclipse would be a lunar eclipse night as well, and the two
     * would always arrive together. They are different events a fortnight apart, so they get different numbers.
     */
    private static final long LUNAR_SALT = 0x2545F4914F6CDD1DL;

    /**
     * The unit normal of the galactic band, in the sky's own rotating frame.
     *
     * <p>The band is a great circle, so all it needs is the axis it circles: the Milky Way is drawn as a
     * ribbon around this direction. Tilted rather than aligned with the world axes, because a band running
     * due east/west or straight overhead does not read as a galaxy - it reads as a seam. This angle puts
     * the band up and across the sky, which is how it is actually seen from the northern hemisphere.
     */
    private static final double GALACTIC_TILT_DEGREES = 62.0D;
    private static final double GALACTIC_YAW_DEGREES = 28.0D;

    private CelestialSky() {
    }

    /**
     * A solar eclipse, as the sky should draw it right now: when it is scheduled, how far through it is,
     * and how much of the sun the moon is covering.
     *
     * <p>An empty eclipse is the common case - a window that has not opened - and its coverage is zero, so
     * the renderer can ask every frame without a branch of its own.
     */
    public record Eclipse(boolean active, double progress, double coverage) {
        private static final Eclipse NONE = new Eclipse(false, 0.0D, 0.0D);
    }

    /**
     * A deterministic roll in {@code [0,1)} for a day, so every client agrees on which days eclipse without
     * anything being synced.
     *
     * <p>Splitmix-style integer mixing rather than {@code Random}: the same day must give the same number on
     * every machine and every time it is asked, and a shared random source would drift.
     */
    public static double roll(long day) {
        // The constant is what keeps day zero out of it. With a bare multiplication, zero stayed zero all the way
        // through the mix and rolled exactly 0.0, which is under any chance at all - so every new world opened with
        // a total eclipse at its very first noon, dimming the world to half for four minutes.
        long x = day * 0x9E3779B97F4A7C15L ^ 0x6A09E667F3BCC909L;
        x ^= x >>> 30;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 27;
        x *= 0x94D049BB133111EBL;
        x ^= x >>> 31;
        // Top 53 bits, so the result is uniform over the doubles a day can map to.
        return (x >>> 11) * 0x1.0p-53;
    }

    /** Whether the given day is an eclipse day: a roll below the chance, held for the whole day. */
    public static boolean eclipseDay(long day, double chance) {
        if (chance <= 0.0D) {
            return false;
        }
        return roll(day) < Math.min(chance, 1.0D);
    }

    /**
     * The eclipse state at a world time, given how long one lasts and how likely a day is to host one.
     *
     * <p>Centred on noon and symmetric about it, so the moon arrives from one side, covers the sun and
     * leaves the other. The coverage is a half-sine rather than a triangle: a real eclipse darkens fastest
     * near totality and barely at all at first contact, which is the shape that makes the middle of it
     * feel like the middle.
     */
    public static Eclipse eclipse(long dayTime, double durationSeconds, double chance) {
        long day = Math.floorDiv(dayTime, DAY_TICKS);
        if (!eclipseDay(day, chance)) {
            return Eclipse.NONE;
        }
        double durationTicks = Math.max(2.0D, durationSeconds * 20.0D);
        long inDay = dayTime - day * DAY_TICKS;
        double start = NOON_TICK - durationTicks * 0.5D;
        double progress = (inDay - start) / durationTicks;
        if (progress < 0.0D || progress > 1.0D) {
            return Eclipse.NONE;
        }
        double coverage = Math.sin(Math.PI * progress);
        return new Eclipse(true, progress, coverage);
    }

    /**
     * How far the moon is offset from the sun at a moment of the eclipse, in sun-radii.
     *
     * <p>One sun-radius apart at first contact, dead centre at totality, and back out again - so the moon
     * visibly slides across rather than simply appearing on the sun's face and growing.
     */
    public static double moonOffsetSunRadii(double coverage) {
        return Math.max(0.0D, 1.0D - coverage) * 2.0D;
    }

    /**
     * A lunar eclipse, as the sky should draw it right now: when it is scheduled, how far through it is, and how
     * much of the moon the Earth's shadow is covering.
     *
     * <p>The same shape of answer as a solar eclipse, because the renderer asks the same shape of question. What
     * differs is where the numbers come from: a solar eclipse's offset is read off its coverage, and a lunar
     * one's coverage is read off its offset - the shadow is not a body crossing the moon but a shadow the moon
     * crosses, so it is the moon's own position that sweeps linearly through it and the geometry that decides
     * how much is dark.
     */
    public record LunarEclipse(boolean active, double progress, double coverage) {
        private static final LunarEclipse NONE = new LunarEclipse(false, 0.0D, 0.0D);
    }

    /**
     * Whether the given full-moon night hosts a lunar eclipse: the same deterministic roll as the solar one, mixed
     * with a salt so the two events are not decided by the same number.
     */
    public static boolean lunarEclipseNight(long day, double chance) {
        if (chance <= 0.0D) {
            return false;
        }
        return roll(day ^ LUNAR_SALT) < Math.min(chance, 1.0D);
    }

    /**
     * The lunar eclipse at a world time, given how long one lasts, how likely a full-moon night is to host one,
     * and the phase the level reports.
     *
     * <p>Centred on midnight and symmetric about it. The chance is per full-moon night rather than per day,
     * because a lunar eclipse is only possible when the moon is full - the Earth's shadow is behind the world, so
     * the moon has to be on the far side of it - which also makes one predictable: a player who watches a full
     * moon rise knows that is the night it could happen.
     */
    public static LunarEclipse lunarEclipse(long dayTime, double durationSeconds, double chance, int moonPhase) {
        if (moonPhase != FULL_MOON_PHASE) {
            return LunarEclipse.NONE;
        }
        long day = Math.floorDiv(dayTime, DAY_TICKS);
        if (!lunarEclipseNight(day, chance)) {
            return LunarEclipse.NONE;
        }
        double durationTicks = Math.max(2.0D, durationSeconds * 20.0D);
        long inDay = dayTime - day * DAY_TICKS;
        double start = MIDNIGHT_TICK - durationTicks * 0.5D;
        double progress = (inDay - start) / durationTicks;
        if (progress < 0.0D || progress > 1.0D) {
            return LunarEclipse.NONE;
        }
        return new LunarEclipse(true, progress, lunarCoverage(lunarShadowOffsetMoonRadii(progress)));
    }

    /**
     * How far the moon's centre is from the Earth's shadow's axis at a moment of the eclipse, in moon radii, and
     * on which side: out on one limb at first contact, concentric at the middle, out on the other limb at last
     * contact.
     *
     * <p>A linear sweep, because that is what it is: the shadow is a wide disc and the moon is the thing moving.
     * At contact the centres are their two radii apart, so the moon's limb has just touched the umbra's, and at
     * the middle they are concentric. The sign is the side the shadow lies on, which is the one thing the
     * renderer needs that the coverage does not carry: the shadow is drawn as a distance from the moon's own
     * centre, and a distance alone cannot say which way the shadow is coming from.
     */
    public static double lunarShadowOffsetMoonRadii(double progress) {
        return LUNAR_CONTACT_RADII * (1.0D - progress * 2.0D);
    }

    /**
     * How much of the moon's disc the Earth's shadow covers at a given separation, as a fraction of the moon.
     *
     * <p>The real thing rather than a curve shaped to look right: for two circles this far apart, the dark part is
     * the area they share, over the moon's own area - the lens formula, in moon radii. That is what gives a lunar
     * eclipse the shape it actually has, including its flat top: the umbra is wider than the moon, so once the
     * moon's limb is inside it - within {@code UMBRA_RADII - 1} of the axis - the whole disc is dark and the
     * coverage sits at one for a good while, where a solar eclipse is total for an instant.
     */
    public static double lunarCoverage(double offsetMoonRadii) {
        double distance = Math.abs(offsetMoonRadii);
        if (distance >= LUNAR_CONTACT_RADII) {
            return 0.0D;
        }
        if (distance <= UMBRA_RADII - 1.0D) {
            return 1.0D;
        }
        double moon = 1.0D;
        double umbra = UMBRA_RADII;
        double moonAngle = Math.acos((distance * distance + moon * moon - umbra * umbra) / (2.0D * distance * moon));
        double umbraAngle = Math.acos((distance * distance + umbra * umbra - moon * moon) / (2.0D * distance * umbra));
        double triangle = 0.5D * Math.sqrt(Math.max(0.0D, (-distance + moon + umbra) * (distance + moon - umbra)
                * (distance - moon + umbra) * (distance + moon + umbra)));
        double shared = moon * moon * moonAngle + umbra * umbra * umbraAngle - triangle;
        double fraction = shared / (Math.PI * moon * moon);
        return Math.max(0.0D, Math.min(1.0D, fraction));
    }

    /**
     * How many phase tiles the moon's atlas has across, and how many rows of them it has.
     *
     * <p>The same four by two the game's own moon is drawn from, and for the same reason: eight phases is a
     * cycle, and a tile per phase in one row would be a strip eight wide. The order is the game's own, so the
     * phase number a level reports is the tile that gets drawn - full at zero, new at four, and the waxing and
     * waning halves either side of them.
     */
    public static final int MOON_ATLAS_COLUMNS = 4;
    public static final int MOON_ATLAS_ROWS = 2;

    /**
     * The column of the moon's atlas a phase is drawn from, counted from zero.
     *
     * <p>Kept here rather than in the renderer because it is arithmetic about the sky and nothing else -
     * which tile is which phase - and because it is the one thing about the atlas the renderer and the art have
     * to agree on, so it is worth being able to read and test without a client.
     */
    public static int moonPhaseColumn(int moonPhase) {
        return Math.floorMod(moonPhase, MOON_ATLAS_COLUMNS);
    }

    /** The row of the moon's atlas a phase is drawn from, counted from zero: the second four phases. */
    public static int moonPhaseRow(int moonPhase) {
        return Math.floorMod(moonPhase, MOON_ATLAS_COLUMNS * MOON_ATLAS_ROWS) / MOON_ATLAS_COLUMNS;
    }

    /** The band's tilt about the up axis, in radians, for a caller building the ribbon geometry. */
    public static double galacticYawRadians() {
        return Math.toRadians(GALACTIC_YAW_DEGREES);
    }

    /** The band's tilt away from the horizontal, in radians. */
    public static double galacticTiltRadians() {
        return Math.toRadians(GALACTIC_TILT_DEGREES);
    }
}
