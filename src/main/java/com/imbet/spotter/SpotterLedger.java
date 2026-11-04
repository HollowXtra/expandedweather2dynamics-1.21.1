package com.imbet.spotter;

/**
 * One player's spotting record: what they filed, what the world proved, and what it paid.
 *
 * <p>Filed and confirmed are kept apart rather than as a ratio, because the interesting number is not the
 * hit rate - a spotter who reports every lowering they see is doing the job right, and one who waits for a
 * touchdown before saying anything is useless - it is how many warnings turned out to be true, which is the
 * only thing that decides whether the sirens earned their noise.
 *
 * <p>{@code villagersWarned} is the sum of the crowds the reports stood in front of: the count of villagers
 * who were in range of the warning at the moment it was filed. It is the one number here that is not about
 * the player, which is the point - a report is a thing you do for other people.
 */
public final class SpotterLedger {
    private int filed;
    private int confirmed;
    private int lapsed;
    private int bountyEmeralds;
    private int villagersWarned;
    private int strongestConfirmedMph;
    private SpotterReport last;

    /** A report was filed. */
    public void noteFiled(SpotterReport report) {
        if (report == null) {
            return;
        }
        filed++;
        villagersWarned += Math.max(0, report.villagersWarned());
        last = report;
    }

    /** A report was proven, and the village paid what it could. */
    public void noteConfirmed(SpotterReport report, int emeralds) {
        if (report == null) {
            return;
        }
        confirmed++;
        bountyEmeralds += Math.max(0, emeralds);
        strongestConfirmedMph = Math.max(strongestConfirmedMph, report.stormWindMph());
        last = report;
    }

    /** A report ran out of time. */
    public void noteLapsed(SpotterReport report) {
        if (report == null) {
            return;
        }
        lapsed++;
        last = report;
    }

    /** Reads a ledger back with its totals given to it, for the persistence layer. */
    public void restore(int filed, int confirmed, int lapsed, int bountyEmeralds, int villagersWarned,
                        int strongestConfirmedMph, SpotterReport last) {
        this.filed = Math.max(0, filed);
        this.confirmed = Math.max(0, confirmed);
        this.lapsed = Math.max(0, lapsed);
        this.bountyEmeralds = Math.max(0, bountyEmeralds);
        this.villagersWarned = Math.max(0, villagersWarned);
        this.strongestConfirmedMph = Math.max(0, strongestConfirmedMph);
        this.last = last;
    }

    /** Whether this player has ever reported anything. */
    public boolean empty() {
        return filed <= 0;
    }

    public int filed() {
        return filed;
    }

    public int confirmed() {
        return confirmed;
    }

    public int lapsed() {
        return lapsed;
    }

    public int bountyEmeralds() {
        return bountyEmeralds;
    }

    public int villagersWarned() {
        return villagersWarned;
    }

    public int strongestConfirmedMph() {
        return strongestConfirmedMph;
    }

    public SpotterReport last() {
        return last;
    }

    /** What the counts have earned. */
    public SpotterRank rank() {
        return SpotterRank.forConfirmed(confirmed);
    }

    /** Whether a promotion happened between two counts, which is what a message is for. */
    public boolean promotedFrom(int previousConfirmed) {
        return !SpotterRank.forConfirmed(previousConfirmed).equals(rank());
    }
}
