package com.imbet.chase;

/**
 * One encounter while it is still happening: the running worst-case of a chase, folded observation by
 * observation until the storm leaves or dies.
 *
 * <p>Everything here is arithmetic, and all of it is deliberately of one kind: a chase log's numbers are
 * <em>extremes</em>, so every field is a maximum, a minimum, or a count. That is also why a draft cannot be
 * reconstructed from the last observation - the peak gust happened in the middle of the encounter and would
 * be lost by remembering only where the storm is now.
 *
 * <p>Observations arrive on an interval rather than every tick, so a count of observations is not a count of
 * ticks: {@link #observe} is handed the span each observation stands for and spends it, which is what keeps
 * "time inside the core" a real duration instead of a function of how the interval is configured.
 */
public final class ChaseDraft {
    /**
     * The smallest a core radius can be, in blocks: a vortex whose damage width is under eight blocks is
     * still a thing you can be inside, and a radius of two would mean standing on the ground column itself.
     */
    public static final double CORE_FLOOR_BLOCKS = 4.0D;
    private final String key;
    private final long startTick;
    private String label;
    private long lastSeenTick;
    private double stormPeakMph;
    private double feltPeakMph;
    private double closestBlocks = Double.MAX_VALUE;
    private int coreTicks;
    private int observations;

    public ChaseDraft(String key, long startTick) {
        this.key = key;
        this.startTick = startTick;
        this.lastSeenTick = startTick;
    }

    /**
     * Folds one observation in. The core is half the vortex's damage width, floored - the width a track
     * records is a width, and standing "in the core" has to mean standing within it rather than within a
     * radius equal to the whole swath.
     */
    public void observe(String observedLabel, double stormMph, double feltMph, double distanceBlocks,
                        double damageWidthBlocks, long tick, int spanTicks) {
        this.lastSeenTick = Math.max(lastSeenTick, tick);
        this.observations++;
        if (stormMph > stormPeakMph) {
            this.stormPeakMph = stormMph;
            // The label is the storm as it was at its strongest: a chase deserves to be remembered as the
            // storm it was when it was worth chasing, not as the rope-out it became.
            if (observedLabel != null && !observedLabel.isBlank()) {
                this.label = observedLabel;
            }
        } else if (label == null) {
            label = observedLabel;
        }
        this.feltPeakMph = Math.max(feltPeakMph, feltMph);
        double coreRadius = Math.max(CORE_FLOOR_BLOCKS, damageWidthBlocks * 0.5D);
        if (distanceBlocks < closestBlocks) {
            this.closestBlocks = distanceBlocks;
        }
        if (distanceBlocks <= coreRadius) {
            this.coreTicks += Math.max(1, spanTicks);
        }
    }

    /** True when this vortex has not been observed for longer than the grace window, so it is over. */
    public boolean stale(long nowTick, long graceTicks) {
        return nowTick - lastSeenTick > Math.max(1L, graceTicks);
    }

    /**
     * Closes the encounter. It ends at the last observation, not at the moment the absence was noticed: the
     * grace window exists so a storm flickering out of the snapshot list is not split into two logs, and
     * giving the draft that window back as time beside the storm would credit a chase with the minutes it
     * spent watching nothing.
     */
    public ChaseRecord finish() {
        return new ChaseRecord(
                key,
                label == null || label.isBlank() ? "Vortex" : label,
                startTick,
                Math.max(startTick, lastSeenTick),
                stormPeakMph,
                feltPeakMph,
                closestBlocks == Double.MAX_VALUE ? 0.0D : closestBlocks,
                coreTicks,
                observations
        );
    }

    public String key() {
        return key;
    }

    public long startTick() {
        return startTick;
    }

    public long lastSeenTick() {
        return lastSeenTick;
    }

    public double stormPeakMph() {
        return stormPeakMph;
    }

    public double feltPeakMph() {
        return feltPeakMph;
    }

    public double closestBlocks() {
        return closestBlocks;
    }

    public int coreTicks() {
        return coreTicks;
    }

    public int observations() {
        return observations;
    }
}
