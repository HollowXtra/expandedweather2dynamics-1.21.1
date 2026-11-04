package com.imbet.spotter;

import java.util.UUID;

/**
 * One player's report of a tornado, and what became of it.
 *
 * <p>A report is a claim about the world that the mod cannot yet make itself. The alert ladder only knows
 * what its own scan found: a storm that has put a circulation on the ground. A player standing under a wall
 * cloud watching a funnel lower is watching the same storm at a stage before that - which is exactly the
 * lead a spotter exists to give, and exactly why a report has a life of its own rather than being a copy of
 * the storm's alert. It is filed at a place, it is believed for a while, and then either the world proves it
 * or it lapses.
 *
 * <p>Kept as plain numbers rather than as game vectors, like the rest of this mod's arithmetic, so the
 * question a report answers - is it still in force, does this observation confirm it, what does the player
 * get - can be checked without a running game.
 *
 * <p>The reported position is the circulation the player was pointing at, and the projected position is
 * where that circulation's own drift will have carried the report by the time a warning is worth issuing.
 * Both are kept because they answer different questions: a station asks whether the report threatens its own
 * ground, which is a question about the path; a confirmation asks whether something actually happened where
 * the report said, which is a question about the place.
 */
public record SpotterReport(
        long id,
        UUID reporterId,
        String reporterName,
        double x,
        double y,
        double z,
        double projectedX,
        double projectedZ,
        String label,
        int stormWindMph,
        double coreRadiusBlocks,
        double motionX,
        double motionZ,
        long filedTick,
        long expiresTick,
        long confirmedTick,
        int villagersWarned,
        int bountyEmeralds,
        boolean paid
) {
    /** The three things a report can be, in the order it can become them. */
    public enum Status {
        FILED,
        CONFIRMED,
        LAPSED
    }

    /** Whether this report is still warning anybody. A confirmed one is not: it has become a fact. */
    public boolean active(long nowTick) {
        return confirmedTick < 0L && nowTick <= expiresTick;
    }

    /** Whether something was observed where the report said, so it counts. */
    public boolean confirmed() {
        return confirmedTick >= 0L;
    }

    /** Whether the report ran out of time without ever being proven. */
    public boolean lapsed(long nowTick) {
        return confirmedTick < 0L && !active(nowTick);
    }

    public Status status(long nowTick) {
        if (confirmed()) {
            return Status.CONFIRMED;
        }
        return active(nowTick) ? Status.FILED : Status.LAPSED;
    }

    /** What the report is, said the way a player would say it. */
    public String statusLabel(long nowTick) {
        return switch (status(nowTick)) {
            case FILED -> "in force";
            case CONFIRMED -> "confirmed";
            case LAPSED -> "lapsed";
        };
    }

    /** How long the report has been in force, or how long it was in force before it was confirmed. */
    public long ageTicks(long nowTick) {
        return Math.max(0L, (confirmed() ? confirmedTick : nowTick) - filedTick);
    }

    /** The distance from a place to the reported circulation, in blocks. */
    public double distanceTo(double px, double pz) {
        double dx = x - px;
        double dz = z - pz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Whether an observation at this place is close enough to count as the same tornado. */
    public boolean matches(double px, double pz, double rangeBlocks) {
        return distanceTo(px, pz) <= Math.max(1.0D, rangeBlocks);
    }

    /** This report, confirmed at the given tick. */
    public SpotterReport confirmedAt(long tick) {
        if (confirmed()) {
            return this;
        }
        return new SpotterReport(id, reporterId, reporterName, x, y, z, projectedX, projectedZ, label,
                stormWindMph, coreRadiusBlocks, motionX, motionZ, filedTick, expiresTick, Math.max(0L, tick),
                villagersWarned, bountyEmeralds, paid);
    }

    /**
     * This report, paid. A village that could not afford the bounty pays nothing rather than going into debt,
     * and the report is marked paid anyway: the credit was earned whether or not there was coin for it, and a
     * report that kept trying to collect would pay out again if the village later came into money.
     */
    public SpotterReport withPaid(int emeralds) {
        return new SpotterReport(id, reporterId, reporterName, x, y, z, projectedX, projectedZ, label,
                stormWindMph, coreRadiusBlocks, motionX, motionZ, filedTick, expiresTick, confirmedTick,
                villagersWarned, Math.max(0, emeralds), true);
    }

    /** Whether this report is worth warning about at all, for a report that was filed about nothing much. */
    public boolean worthWarning() {
        return stormWindMph > 0;
    }
}
