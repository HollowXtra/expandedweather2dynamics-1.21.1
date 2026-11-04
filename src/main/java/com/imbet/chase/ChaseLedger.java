package com.imbet.chase;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's lifetime chase record: how many storms they have been close to, how they were classed, and the
 * three extremes worth keeping - the strongest storm, the closest approach, and the longest stay inside a
 * core - plus the wind they have actually stood in.
 *
 * <p>The extremes are kept as records rather than as numbers because the number without the storm it came
 * from is not a memory: "168 mph" is a curiosity, "an EF4 at 118 blocks, three days ago" is the thing the
 * player is trying to beat.
 *
 * <p>The counts are kept beside the recent list rather than derived from it, because the list is capped: a
 * player with a hundred chases keeps the last twenty-five of them, and a lifetime count that fell when old
 * chases aged out would be a record that gets worse the more you play. That is also why {@link #restore}
 * exists as a separate door: a ledger read back from disk has its lifetime totals given to it, not
 * recomputed from whatever happened to survive the cap.
 */
public final class ChaseLedger {
    private int stormsLogged;
    private double worstFeltMph;
    private ChaseRecord worstFelt;
    private ChaseRecord best;
    private ChaseRecord closest;
    private ChaseRecord longest;
    private final int[] tierCounts = new int[6];
    private final List<ChaseRecord> recent = new ArrayList<>();

    /** Folds a finished encounter in: counts it, and lets it challenge every extreme it can. */
    public void note(ChaseRecord record) {
        if (record == null) {
            return;
        }
        stormsLogged++;
        int tier = record.efTier();
        if (tier >= 0 && tier < tierCounts.length) {
            tierCounts[tier]++;
        }
        if (record.feltPeakMph() > worstFeltMph || worstFelt == null) {
            worstFeltMph = record.feltPeakMph();
            worstFelt = record;
        }
        if (best == null || record.stormPeakMph() > best.stormPeakMph()) {
            best = record;
        }
        if (closest == null || record.closestBlocks() < closest.closestBlocks()) {
            closest = record;
        }
        if (longest == null || record.coreTicks() > longest.coreTicks()) {
            longest = record;
        }
        recent.add(record);
    }

    /** Drops the oldest encounters once the recent list is over its cap. */
    public void trim(int maxRecent) {
        int cap = Math.max(1, maxRecent);
        while (recent.size() > cap) {
            recent.remove(0);
        }
    }

    /** Reads a ledger back with its lifetime totals given to it, for the persistence layer. */
    public void restore(int stormsLogged, double worstFeltMph, int[] tierCounts, ChaseRecord worstFelt,
                        ChaseRecord best, ChaseRecord closest, ChaseRecord longest, List<ChaseRecord> recent) {
        this.stormsLogged = Math.max(0, stormsLogged);
        this.worstFeltMph = Math.max(0.0D, worstFeltMph);
        this.worstFelt = worstFelt;
        this.best = best;
        this.closest = closest;
        this.longest = longest;
        for (int index = 0; index < tierCounts.length && index < this.tierCounts.length; index++) {
            this.tierCounts[index] = Math.max(0, tierCounts[index]);
        }
        this.recent.clear();
        this.recent.addAll(recent);
    }

    public int stormsLogged() {
        return stormsLogged;
    }

    public double worstFeltMph() {
        return worstFeltMph;
    }

    public ChaseRecord worstFelt() {
        return worstFelt;
    }

    public ChaseRecord best() {
        return best;
    }

    public ChaseRecord closest() {
        return closest;
    }

    public ChaseRecord longest() {
        return longest;
    }

    public int countOfTier(int tier) {
        return tier >= 0 && tier < tierCounts.length ? tierCounts[tier] : 0;
    }

    /** How many chases were EF2 or stronger, which is the significant-severe line of the published scale. */
    public int significantCount() {
        int total = 0;
        for (int tier = 2; tier < tierCounts.length; tier++) {
            total += tierCounts[tier];
        }
        return total;
    }

    /** The recent chases, oldest first. */
    public List<ChaseRecord> recent() {
        return List.copyOf(recent);
    }

    public int[] tierCountsCopy() {
        return tierCounts.clone();
    }

    /** A debug/status line's worth of the ledger, without the storm-by-storm detail. */
    public String summary() {
        return stormsLogged + " logged, strongest " + (best == null ? "none" : best.efClass())
                + ", closest " + (closest == null ? "-" : ChaseLogReadout.round(closest.closestBlocks()) + " blocks");
    }
}
