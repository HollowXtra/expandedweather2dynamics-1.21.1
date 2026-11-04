package com.imbet.spotter;

import java.util.List;

/**
 * What a spotter has earned, as a ladder of confirmed reports.
 *
 * <p>A reward that is only money is a reward for doing nothing: the bounty can be paid by a village you
 * happened to be standing next to, and it is spent. The rank is the part that stays - it is a count of the
 * times the world proved you right, said in words, and it is what makes a spotter a thing you are rather
 * than a thing you did once.
 *
 * <p>Deliberately a short ladder on round numbers. A rank nobody can reach is a rank nobody cares about,
 * and twenty confirmed reports is already a world where a player has spent a long time watching the sky.
 */
public record SpotterRank(String label, int required) {
    public static final SpotterRank CIVILIAN = new SpotterRank("Civilian", 0);
    public static final SpotterRank SPOTTER = new SpotterRank("Spotter", 1);
    public static final SpotterRank SENIOR = new SpotterRank("Senior Spotter", 5);
    public static final SpotterRank LEAD = new SpotterRank("Lead Spotter", 20);
    /** The ladder, weakest first. */
    public static final List<SpotterRank> LADDER = List.of(CIVILIAN, SPOTTER, SENIOR, LEAD);

    /** The rank a number of confirmed reports has earned, never null. */
    public static SpotterRank forConfirmed(int confirmed) {
        SpotterRank best = CIVILIAN;
        for (SpotterRank rank : LADDER) {
            if (confirmed >= rank.required()) {
                best = rank;
            }
        }
        return best;
    }

    /** The next rank up, or null at the top of the ladder. */
    public static SpotterRank next(int confirmed) {
        for (SpotterRank rank : LADDER) {
            if (confirmed < rank.required()) {
                return rank;
            }
        }
        return null;
    }

    /** How far along the ladder a count is, as a line that says what it is and what is next. */
    public static String progressLine(int confirmed) {
        SpotterRank current = forConfirmed(confirmed);
        SpotterRank next = next(confirmed);
        if (next == null) {
            return current.label() + " - the top of the ladder, with " + confirmed + " confirmed reports";
        }
        int remaining = next.required() - confirmed;
        return current.label() + " - " + remaining + (remaining == 1 ? " more confirmed report" : " more confirmed reports")
                + " for " + next.label();
    }

    /** Whether this rank is the top of the ladder, which is what a promotion message asks. */
    public static boolean isTop(SpotterRank rank) {
        return rank != null && rank.equals(LADDER.get(LADDER.size() - 1));
    }
}
