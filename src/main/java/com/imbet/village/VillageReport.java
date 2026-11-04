package com.imbet.village;

import java.util.ArrayList;
import java.util.List;

/**
 * What a workstation tells a player who asks it why nothing is happening.
 *
 * <p>This exists because of how the builder failed: silently and for four different reasons at once. It
 * could be owed a building and short of the stone, owed one with nowhere flat to put it, waiting on damage
 * it cannot pay for, or simply finished - and every one of those looked identical from outside, which is
 * the same as looking broken. So the numbers are gathered by the goal's own helpers and turned into
 * sentences here, and the sentences are a plain function of the numbers so they can be checked without a
 * world: no world reads, no Minecraft types.
 *
 * <p>What the village would buy is part of the answer now, because "short of stone" has a second half: at
 * what price, and with what money. Those lines are handed in already written, since a price is the
 * exchange's business and this class only decides where a line goes in the reading order - which is the
 * order a player needs it in: what is next, what is in the way, what could be sold to fix that, and the
 * damage, last, because it is the one line that is about the past.
 */
public final class VillageReport {
    private VillageReport() {
    }

    /**
     * Everything the report needs, as numbers and names.
     *
     * <p>{@code next} is null when there is nothing owed - the village has built all it is allowed.
     * {@code missing} is the one material in the way, with the count needed and the count in reach, or null
     * when the materials are there and something else is holding it up. {@code siteRange} is how far from
     * the bench a site was looked for, so the sentence can say where.
     */
    public record Status(String next, int width, int depth, String missing, int missingNeeded, int missingHeld,
                         boolean siteFound, int siteRange, int built, int allowance, int damageNearby,
                         List<String> shopping) {
    }

    /**
     * What a sawmill has to report: the felled trees in reach, what the village has to work with, and the
     * three things that stop a lumberjack - no tree, no chest, or no room in one.
     */
    public record Timber(int trunks, int logsInStore, int planksInStore, boolean storeReachable, boolean roomToPut,
                         int perTrip) {
    }

    /**
     * The same reading order a bench uses: what there is to work on, what the village has, and what a trip
     * would come back with - because a lumberjack that has nothing to fell and one that has nowhere to put
     * what it felled look identical from outside.
     */
    public static List<String> timberLines(Timber timber) {
        List<String> lines = new ArrayList<>(3);
        if (timber.trunks() <= 0) {
            lines.add("  No felled trees on recent storm ground within range of the mill.");
        } else {
            lines.add("  " + timber.trunks() + (timber.trunks() == 1 ? " felled tree" : " felled trees")
                    + " on recent storm ground within range.");
        }
        if (!timber.storeReachable()) {
            lines.add("  No chest within reach: a lumberjack has nowhere to stock what it fells.");
        } else if (!timber.roomToPut()) {
            lines.add("  The chests within reach are full.");
        } else {
            lines.add("  The village holds " + timber.logsInStore() + " logs and " + timber.planksInStore()
                    + " planks within reach of the mill.");
        }
        if (timber.trunks() > 0 && timber.storeReachable() && timber.roomToPut()) {
            lines.add("  Ready: up to " + timber.perTrip()
                    + " logs a trip, sawed into planks where the village is short of them.");
        }
        return lines;
    }

    /** The lines, in the order they read: what is next, what is in the way, and what is being repaired. */
    public static List<String> lines(Status status) {
        List<String> lines = new ArrayList<>(4);
        if (status.next() == null) {
            if (status.allowance() <= 0) {
                lines.add("  This village is not allowed to build anything: villagerBuilderBuildings is 0.");
            } else {
                lines.add("  The village has built all " + status.built() + " buildings it is allowed.");
            }
        } else {
            lines.add("  Next: " + status.next() + " (" + status.width() + "x" + status.depth()
                    + "), and " + status.built() + " of " + status.allowance() + " buildings are up.");
            if (status.missing() != null) {
                lines.add("  Waiting on " + status.missing() + ": the village has " + status.missingHeld()
                        + " of the " + status.missingNeeded() + " it needs in reach of the bench.");
                lines.add("  Put " + status.missing() + " in a chest within range and it will start.");
            } else if (!status.siteFound()) {
                lines.add("  Waiting on ground: no clear, level site within " + status.siteRange()
                        + " blocks of the bench.");
            } else {
                lines.add("  Ready: the materials are in reach and the ground beside the village is clear.");
            }
        }
        // What it would buy, and with what money. Before the damage line, which stays last: a shopping list
        // is about what can still be done, and damage is about what has already happened.
        if (status.shopping() != null) {
            lines.addAll(status.shopping());
        }
        if (status.damageNearby() > 0) {
            lines.add("  First, " + status.damageNearby() + " blocks of storm damage on a recent track.");
        } else {
            lines.add("  No storm damage on a recent track nearby.");
        }
        return lines;
    }
}
