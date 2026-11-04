package com.imbet.chase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a chase ledger into the lines a player reads. Pure: no game types, no components, no colours, so the
 * wording and the arithmetic behind it are checkable without a running game - which matters more than usual
 * here, because a readout that rounds a distance into a lie or a duration into "0s" is the whole feature
 * failing quietly.
 */
public final class ChaseLogReadout {
    /**
     * The clock this readout uses for ages, in ticks of real time. Deliberately the same one
     * {@link #duration} uses: a chase that lasted two minutes and ended two minutes ago must not read as one
     * number in seconds and another in in-game hours, because the player experienced both of them at the same
     * rate. A Minecraft day is twenty real minutes, which is why a day here is a day of play rather than of
     * world time.
     */
    public static final long TICKS_PER_SECOND = 20L;
    public static final long TICKS_PER_MINUTE = TICKS_PER_SECOND * 60L;
    public static final long TICKS_PER_HOUR = TICKS_PER_MINUTE * 60L;
    public static final long TICKS_PER_DAY = TICKS_PER_HOUR * 24L;

    private ChaseLogReadout() {
    }

    /**
     * The whole log as chat lines: a summary, the worst wind the player has stood in, the closest approach
     * and its time inside the core, the best chase, and then the last few storms beside them.
     */
    public static List<String> lines(ChaseLedger ledger, long nowTick, int maxRecent) {
        List<String> lines = new ArrayList<>();
        if (ledger == null || ledger.stormsLogged() <= 0) {
            lines.add("Chase log: empty - get close to a tornado and it starts counting");
            return lines;
        }
        lines.add("Chase log: " + ledger.stormsLogged() + " storms logged"
                + (ledger.significantCount() > 0 ? ", " + ledger.significantCount() + " of them EF2 or stronger" : ""));
        if (ledger.worstFelt() != null) {
            lines.add("Worst wind stood in: " + round(ledger.worstFeltMph()) + " mph ("
                    + ledger.worstFelt().feltEfClass() + " class)");
        }
        if (ledger.closest() != null) {
            lines.add("Closest approach: " + round(ledger.closest().closestBlocks()) + " blocks from a "
                    + ledger.closest().efClass() + ", " + duration(ledger.closest().coreTicks()) + " inside the core");
        }
        if (ledger.best() != null) {
            lines.add("Best chase: " + recordLine(ledger.best()) + ", " + ago(nowTick - ledger.best().endTick()));
        }
        List<ChaseRecord> recent = ledger.recent();
        int first = Math.max(0, recent.size() - Math.max(1, maxRecent));
        for (int index = recent.size() - 1; index >= first; index--) {
            ChaseRecord record = recent.get(index);
            lines.add("  " + recordLine(record) + " (" + ago(nowTick - record.endTick()) + ")");
        }
        return lines;
    }

    /**
     * One encounter in one line: the class the storm earned, its peak wind, how close the player got, and how
     * long they spent inside the core. Deliberately the same four numbers everywhere, so two chases can be
     * compared by reading two lines.
     */
    public static String recordLine(ChaseRecord record) {
        if (record == null) {
            return "no chase recorded";
        }
        return record.efClass() + " " + round(record.stormPeakMph()) + " mph, closest "
                + round(record.closestBlocks()) + " blocks, " + duration(record.coreTicks()) + " in the core";
    }

    /** A tick count as a duration: seconds under a minute, then minutes, then hours. */
    public static String duration(long ticks) {
        long seconds = Math.round(ticks / (double) TICKS_PER_SECOND);
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        long restSeconds = seconds % 60L;
        if (minutes < 60L) {
            return restSeconds == 0L ? minutes + "m" : minutes + "m " + restSeconds + "s";
        }
        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        return restMinutes == 0L ? hours + "h" : hours + "h " + restMinutes + "m";
    }

    /** A tick delta as a readable age, on the same clock {@link #duration} reports. */
    public static String ago(long deltaTicks) {
        if (deltaTicks <= TICKS_PER_SECOND) {
            return "just now";
        }
        if (deltaTicks < TICKS_PER_MINUTE) {
            return (deltaTicks / TICKS_PER_SECOND) + "s ago";
        }
        if (deltaTicks < TICKS_PER_HOUR) {
            return (deltaTicks / TICKS_PER_MINUTE) + "m ago";
        }
        if (deltaTicks < TICKS_PER_DAY) {
            long hours = deltaTicks / TICKS_PER_HOUR;
            long minutes = (deltaTicks % TICKS_PER_HOUR) / TICKS_PER_MINUTE;
            return minutes == 0L ? hours + "h ago" : hours + "h " + minutes + "m ago";
        }
        long days = deltaTicks / TICKS_PER_DAY;
        long hours = (deltaTicks % TICKS_PER_DAY) / TICKS_PER_HOUR;
        return hours == 0L ? days + "d ago" : days + "d " + hours + "h ago";
    }

    /** A wind speed or distance as a whole number, without a locale that would print a comma for a decimal. */
    public static String round(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
