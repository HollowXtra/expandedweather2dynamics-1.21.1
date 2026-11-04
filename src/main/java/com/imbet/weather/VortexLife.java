package com.imbet.weather;

/**
 * How long an orbiting vortex is allowed to live, and how it is allowed to end.
 *
 * <p>The sub-vortex rails and the satellite spawner each drew a life and an orbit rate independently, and
 * nothing tied the two together. Measured on the shipped bands, the worst combinations did not survive
 * one turn of their parent: a suction vortex given the shortest life in its band and a slow episode rate
 * covered 1.15 turns, a dust whirl 1.03, a core spot 1.11, and a satellite tornado 1.6 - and a satellite
 * whose parent's own wind dipped for a moment was cleared on the spot, mid-orbit, whatever its remaining
 * life was. A vortex that is gone before it has been once around its parent reads as a flicker rather than
 * as a vortex, which is exactly what "it dies instantly after one orbit" looks like from the ground.
 *
 * <p>Two rules live here, and they are the same idea from both ends. A vortex is BORN with enough life to
 * go round a few times at the rate it is actually going to circle at, with the drawn life kept whenever it
 * was already long enough. And a vortex that its parent can no longer keep is RETIRED rather than deleted:
 * its own expiry is pulled in to a short fade, so it leaves the way it would have left anyway - thinning,
 * weakening and spiralling inward - instead of vanishing between two frames.
 */
public final class VortexLife {

    /**
     * Turns a vortex must be able to complete to be worth drawing at all. Under two it can be gone before
     * a player watching one spot on the ground has seen it come round even once.
     */
    public static final float MIN_ORBITS = 2.5F;

    /** The fade a vortex is given when its parent can no longer keep it. */
    public static final long RETIRE_FADE_TICKS = 100L;

    /**
     * How long a parent may stay too weak for the children it already has before they are retired.
     *
     * <p>The wind that decides this is the storm's own dynamic wind, which passes below a satellite's rung
     * for seconds at a time as part of being dynamic. Culling on the first weak tick is what used to empty
     * a sky of satellites mid-orbit; ten seconds of continuous weakness is a parent that really has given
     * them up, and it is longer than a full turn of any satellite the mod draws.
     */
    public static final long CULL_GRACE_TICKS = 200L;

    private VortexLife() {
    }

    /**
     * What one pass of a parent's decision does: how many of the children it already has to take back, and
     * the tick its weakness dates from.
     */
    public record Cull(int retireCount, long weakSinceTick) {
    }

    /**
     * Whether a parent that can keep {@code limit} children should take any of the {@code liveCount} it has
     * back, and how many.
     *
     * <p>The grace is the whole point of this being a function rather than three lines in the storm: a
     * momentary dip must retire nothing, and a dip that lasts must retire everything over the limit. Keeping
     * the decision here is also what makes it checkable - a parent that flickers below its own rung for one
     * tick and a parent that has genuinely weakened are the same numbers apart from the clock.
     *
     * <p>{@code weakSinceTick} of 0 means the parent was strong; the returned one is that same memory, so a
     * caller that keeps it sees a continuous weakness rather than a fresh one every tick. A parent that is
     * gone altogether has no grace and gives up every child at once.
     */
    public static Cull cull(int liveCount, int limit, boolean parentGone, long weakSinceTick, long gameTime,
                            long graceTicks) {
        boolean overLimit = liveCount > Math.max(0, limit);
        if (!overLimit && !parentGone) {
            return new Cull(0, 0L);
        }
        long since = weakSinceTick <= 0L ? gameTime : weakSinceTick;
        long grace = parentGone ? 0L : Math.max(0L, graceTicks);
        if (gameTime - since < grace) {
            return new Cull(0, since);
        }
        int excess = parentGone ? liveCount : liveCount - Math.max(0, limit);
        return new Cull(Math.max(0, excess), since);
    }

    /** The ticks one turn takes at this rate, or 0 for a vortex that does not orbit. */
    public static double orbitTicks(float orbitSpeedDegrees) {
        if (!Float.isFinite(orbitSpeedDegrees) || orbitSpeedDegrees <= 0.0F) {
            return 0.0D;
        }
        return 360.0D / orbitSpeedDegrees;
    }

    /** How many turns a vortex of this life and rate actually manages. */
    public static double orbitsIn(int lifeTicks, float orbitSpeedDegrees) {
        double period = orbitTicks(orbitSpeedDegrees);
        return period <= 0.0D ? 0.0D : Math.max(0, lifeTicks) / period;
    }

    /**
     * The life a vortex is really given: the drawn life, raised to {@link #MIN_ORBITS} turns for a vortex
     * that orbits, and never shortened by anything.
     *
     * <p>{@code capTicks} bounds only the raise - a vortex whose drawn life is already longer than the cap
     * keeps it - so this can make a vortex live as long as it needs to be readable, and cannot make one
     * live longer than its own type permits.
     */
    public static int lifeFor(int drawnLife, float orbitSpeedDegrees, int capTicks) {
        int life = Math.max(1, drawnLife);
        double period = orbitTicks(orbitSpeedDegrees);
        if (period <= 0.0D) {
            return life;
        }
        long needed = (long) Math.ceil(period * MIN_ORBITS);
        long ceiling = Math.max(life, capTicks);
        return (int) Math.max(life, Math.min(needed, ceiling));
    }

    /**
     * When a vortex that is no longer wanted should be gone: the earlier of its own expiry and a short fade
     * from now, so a retirement is never a cut. A vortex already closer to its end than the fade keeps the
     * end it had.
     */
    public static long retireTick(long gameTime, long expireTick, long fadeTicks) {
        return Math.min(expireTick, gameTime + Math.max(1L, fadeTicks));
    }
}
