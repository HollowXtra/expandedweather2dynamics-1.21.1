package com.imbet.weather;

/**
 * The slow variation in a funnel's visible width: a train of surges, not a breath.
 *
 * <p>A tornado's width does not oscillate around an average. It widens through an episode, holds the
 * new width for a while, and later narrows again - and that difference is visible. Any wave built
 * symmetrically about the target, whatever its period, reads to the eye as the funnel inhaling and
 * exhaling on a beat, which is not weather but animation. The previous implementation was exactly
 * that: three summed sines folded through one more sine, which swings the width up and back down
 * every minute or two and returns to the same width each time.
 *
 * <p>This is the same variation built the other way round, as a value-noise lattice. Each stretch of
 * time has a width LEVEL hashed from the storm's own id and the stretch's index, and the width is
 * held flat at that level for all but the middle sixth of the stretch, where it crosses to the next
 * level. So the funnel arrives somewhere, sits there, and later moves somewhere else. Because
 * every level is an independent hash of its index, the sequence never repeats: there is no period to
 * lock onto, and two storms surge on unrelated schedules rather than in lockstep.
 *
 * <p>It is a pure function of (storm id, world time), which is what lets the server and every client
 * compute the identical number on the same tick with nothing extra to sync and nothing to lose when
 * a world is reloaded - the same property the sine version was chosen for.
 */
public final class TornadoWidthSurge {

    /**
     * Octave periods in ticks, longest first. The slow one is the width the funnel keeps for a couple
     * of minutes at a time; the fast one is the smaller correction inside it. 2600 ticks is 130
     * seconds and 1500 is 75: the fastest thing this can do is slower than the funnel's own spin, the
     * gust train and the width retargets, so it reads as the storm changing its mind rather than as
     * jitter.
     */
    private static final double[] PERIODS = {2600.0D, 1500.0D};

    /** What each octave weighs on the total. They sum to one, so the surge stays inside [-1, 1]. */
    private static final double[] WEIGHTS = {0.78D, 0.22D};

    /**
     * Fraction of each stretch spent parked at each end of it. At 0.42 the level is held for five
     * sixths of every stretch and crossed in the middle sixth - about twenty seconds of movement in
     * every two minutes - which is what makes the width sit still long enough to read as a width
     * rather than as movement.
     */
    private static final double HOLD = 0.42D;

    /**
     * How far a storm's own periods may be stretched or compressed. Two storms already differ
     * completely in which levels they drew; this keeps them from sharing a rhythm even when they
     * happened to draw similar ones.
     */
    private static final double PERIOD_SPREAD = 0.15D;

    /**
     * Output gain. Summed hash levels cluster around zero (two independent draws, and the loudest
     * octave only weighs 0.7), so the raw sum rarely reaches the edge of its range. This lifts the
     * typical excursion back to the amplitude the knob asks for; the clamp then means the widest
     * surges sit still at full amplitude for a moment, which is what a storm doing its widest does.
     */
    private static final double GAIN = 1.25D;

    private static final long ODD_CONSTANT = 0x9E3779B97F4A7C15L;
    private static final long STEP = 0xD6E8FEB86659FD93L;
    private static final long OCTAVE_SALT = 0x2545F4914F6CDD1DL;

    private TornadoWidthSurge() {
    }

    /**
     * How far the visible funnel is from its calm target right now, as a fraction of the configured
     * surge amplitude: -1 is the narrowest the storm will get, +1 the widest, and the value holds at
     * a level between moves instead of sweeping through every width in between.
     */
    public static double value(long stormId, long gameTime) {
        if (gameTime == Long.MIN_VALUE) {
            return 0.0D;
        }
        long seed = stormId * ODD_CONSTANT;
        double speed = periodScale(seed);
        double sum = 0.0D;
        for (int i = 0; i < PERIODS.length; i++) {
            sum += WEIGHTS[i] * octave(seed + i * OCTAVE_SALT, gameTime, PERIODS[i] * speed);
        }
        return clamp(sum * GAIN, -1.0D, 1.0D);
    }

    /** One octave: the level for this stretch of time, crossed to the next one in the middle. */
    private static double octave(long seed, long gameTime, double period) {
        double position = gameTime / period;
        long index = (long) Math.floor(position);
        double within = position - index;
        double moved = smoothstep(clamp((within - HOLD) / (1.0D - 2.0D * HOLD), 0.0D, 1.0D));
        double from = level(seed, index);
        if (moved <= 0.0D) {
            return from;
        }
        if (moved >= 1.0D) {
            return level(seed, index + 1);
        }
        return from + (level(seed, index + 1) - from) * moved;
    }

    /** The width level this storm drew for one stretch of time, in [-1, 1], independent of the rest. */
    private static double level(long seed, long index) {
        long hashed = mix(seed ^ (index * STEP));
        return ((hashed >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
    }

    /** This storm's own stretch of the periods: nominally the same shape, never the same rhythm. */
    private static double periodScale(long seed) {
        double spread = ((seed >>> 41) & 0x3FFL) / 1023.0D;
        return 1.0D - PERIOD_SPREAD + spread * 2.0D * PERIOD_SPREAD;
    }

    /** Murmur3's finalizer: adjacent stretch indices have to hash to unrelated levels. */
    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }

    private static double smoothstep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
