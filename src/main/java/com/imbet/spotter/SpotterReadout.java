package com.imbet.spotter;

import com.imbet.chase.ChaseLogReadout;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns spotter records into the lines and messages a player reads. Pure, so the wording and the arithmetic
 * behind it are checkable without a running game - and, like the chase log, it borrows that log's clock
 * rather than inventing a second one: two systems on the same world reporting ages in different units is a
 * small lie that both of them tell.
 */
public final class SpotterReadout {
    private SpotterReadout() {
    }

    /** The lines the weather computer prints for this player. */
    public static List<String> lines(SpotterLedger ledger, long nowTick) {
        List<String> lines = new ArrayList<>();
        if (ledger == null || ledger.empty()) {
            lines.add("Spotter log: nothing filed - fire a flare at a funnel to warn the villages under it");
            return lines;
        }
        lines.add("Spotter log: " + ledger.filed() + " filed, " + ledger.confirmed() + " confirmed, "
                + ledger.lapsed() + " lapsed");
        lines.add(SpotterRank.progressLine(ledger.confirmed()));
        if (ledger.villagersWarned() > 0) {
            lines.add("Warned: " + ledger.villagersWarned()
                    + (ledger.villagersWarned() == 1
                            ? " villager has been in range of your reports"
                            : " villagers have been in range of your reports"));
        }
        if (ledger.bountyEmeralds() > 0) {
            lines.add("Bounty: " + ledger.bountyEmeralds() + " emeralds paid by the villages you warned");
        }
        if (ledger.strongestConfirmedMph() > 0) {
            lines.add("Strongest confirmed: " + ChaseLogReadout.round(ledger.strongestConfirmedMph()) + " mph ("
                    + windClass(ledger.strongestConfirmedMph()) + ")");
        }
        if (ledger.last() != null) {
            lines.add("Last report: " + reportLine(ledger.last(), nowTick));
        }
        return lines;
    }

    /** One report in one line: what it was, what became of it, and how long ago. */
    public static String reportLine(SpotterReport report, long nowTick) {
        if (report == null) {
            return "no report";
        }
        String what = report.label() + " at " + ChaseLogReadout.round(report.stormWindMph()) + " mph";
        return switch (report.status(nowTick)) {
            case FILED -> what + ", in force, " + ChaseLogReadout.duration(report.ageTicks(nowTick)) + " old";
            case CONFIRMED -> what + ", confirmed " + ChaseLogReadout.ago(nowTick - report.confirmedTick())
                    + (report.bountyEmeralds() > 0 ? ", " + report.bountyEmeralds() + " emeralds" : ", unpaid");
            case LAPSED -> what + ", lapsed after " + ChaseLogReadout.duration(report.ageTicks(nowTick));
        };
    }

    /** What the report is called when it is filed, so the player knows what the net heard. */
    public static String filedLine(SpotterReport report, int distanceBlocks) {
        int villagers = Math.max(0, report.villagersWarned());
        return "Report filed: " + report.label() + " at " + ChaseLogReadout.round(report.stormWindMph())
                + " mph, " + distanceBlocks + " blocks away"
                + (villagers > 0
                        ? " - " + villagers + (villagers == 1 ? " villager is" : " villagers are") + " in range of the warning"
                        : " - nobody is close enough to hear it");
    }

    /** What the player is told when the world proves the report, and what it paid. */
    public static String confirmedLine(SpotterReport report, int distanceBlocks, int emeralds) {
        String paid = emeralds > 0
                ? " The village paid " + emeralds + (emeralds == 1 ? " emerald" : " emeralds") + "."
                : " No village was close enough to pay a bounty.";
        return "Report confirmed: " + report.label() + " was observed " + distanceBlocks + " blocks from where you "
                + "reported it. " + Math.max(0, report.villagersWarned()) + " villagers were warned." + paid;
    }

    /** What the player is told when nothing was ever observed where they reported. */
    public static String lapsedLine(SpotterReport report) {
        return "Report lapsed: nothing was observed where you reported the " + report.label()
                + ". It stood for " + ChaseLogReadout.duration(report.ageTicks(report.expiresTick())) + ".";
    }

    /** Nothing worth reporting is nearby, which is a message and not a failure. */
    public static String nothingVisibleLine(int rangeBlocks) {
        return "Nothing to report - no circulation within " + rangeBlocks + " blocks.";
    }

    /** A player can only file so often, and the wait is reported in the same units as everything else. */
    public static String tooSoonLine(long remainingTicks) {
        return "You have already filed a report - another in " + ChaseLogReadout.duration(remainingTicks) + ".";
    }

    /** The net has enough in force already. */
    public static String tooManyLine(int activeReports) {
        return "The spotter net is busy: " + activeReports + " reports are already in force.";
    }

    /** A promotion, said once, when it happens. */
    public static String promotionLine(SpotterRank rank) {
        return "You are now a " + rank.label() + ".";
    }

    /** The class name for a wind, read off the one ladder rather than restated here. */
    static String windClass(int mph) {
        return com.imbet.chase.ChaseScale.efClass(mph);
    }
}
