package com.imbet.chase;

/**
 * One finished close encounter between a player and one vortex: what the storm was doing, what the player
 * was standing in, and how long they stayed.
 *
 * <p>Two wind numbers are kept because they answer different questions. {@code stormPeakMph} is the vortex's
 * own strength at its strongest while the player was beside it - the number that earns the EF class, and the
 * one comparable between chases. {@code feltPeakMph} is what the player's own position measured, which is
 * lower than the vortex's strength unless they were standing in the core, and is the number a chaser would
 * actually quote about "the worst wind I have been in".
 *
 * @param key          the vortex's stable identity, so a storm that leaves and returns is one encounter
 * @param label        what the storm was called at its strongest, e.g. "EF3 Tornado"
 * @param startTick    game time of the first observation
 * @param endTick      game time the encounter was closed
 * @param stormPeakMph the vortex's own peak strength over the encounter
 * @param feltPeakMph  the peak wind measured at the player's position
 * @param closestBlocks the smallest distance from the player to the vortex's core, in blocks
 * @param coreTicks    how many ticks the player spent inside the core radius, not merely nearby
 * @param observations how many samples made up the encounter, so a three second pass is legible as one
 */
public record ChaseRecord(
        String key,
        String label,
        long startTick,
        long endTick,
        double stormPeakMph,
        double feltPeakMph,
        double closestBlocks,
        int coreTicks,
        int observations
) {
    /** How long the encounter lasted, in ticks, never negative. */
    public long durationTicks() {
        return Math.max(0L, endTick - startTick);
    }

    /** What the storm's own peak earned on the published scale. */
    public String efClass() {
        return ChaseScale.efClass(stormPeakMph);
    }

    /** The same as a tier index, for counting. */
    public int efTier() {
        return ChaseScale.efTier(stormPeakMph);
    }

    /** What the wind the player actually measured would be called if it were a tornado. */
    public String feltEfClass() {
        return ChaseScale.efClass(feltPeakMph);
    }

    /**
     * Whether this encounter is the better chase than another, which is what makes a personal best a single
     * record rather than a table the player has to read: the stronger storm wins, then the closer approach,
     * then the longer stay inside the core, then the earlier chase - so a tie is resolved by what happened
     * first rather than by whatever order the records happen to be in.
     */
    public boolean outranks(ChaseRecord other) {
        if (other == null) {
            return true;
        }
        if (stormPeakMph != other.stormPeakMph) {
            return stormPeakMph > other.stormPeakMph;
        }
        if (closestBlocks != other.closestBlocks) {
            return closestBlocks < other.closestBlocks;
        }
        if (coreTicks != other.coreTicks) {
            return coreTicks > other.coreTicks;
        }
        return startTick < other.startTick;
    }
}
