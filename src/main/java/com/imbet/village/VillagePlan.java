package com.imbet.village;

import java.util.List;
import java.util.Map;

/**
 * What a village is owed next, and whether it can pay to start.
 *
 * <p>Two rules, both small enough to argue with. The village builds a house first, then the buildings it
 * works out of - an office, then a station for each job: a survey station for the surveyor, a workshop for
 * the builder and a timber yard for the lumberjack - and then houses again until it has as many as it is
 * allowed, so "they have a house and a station for each job" is the plan rather than a hope. And a village
 * starts a building only when it can lay the whole ground layer: a foundation half laid and then abandoned
 * is worse than no building, and a village that cannot afford a ring of stone has no business starting a
 * wall.
 *
 * <p>Minecraft-free, so both rules are measured rather than trusted.
 */
public final class VillagePlan {
    private VillagePlan() {
    }

    /**
     * What a village has standing, counted from the works it has recorded and checked - not what it once
     * built and has since lost. Six numbers rather than six arguments, because a plan is easier to read than
     * a call site is, and because the fifth and sixth things a village learns to build should not change the
     * shape of every caller.
     */
    public record Counts(int houses, int offices, int stations, int workshops, int yards, int weatherStations) {
        public Counts {
            if (houses < 0 || offices < 0 || stations < 0 || workshops < 0 || yards < 0 || weatherStations < 0) {
                throw new IllegalArgumentException("a village cannot have built a negative number of things");
            }
        }

        /** A village that has just been founded, with nothing standing. */
        public static Counts none() {
            return new Counts(0, 0, 0, 0, 0, 0);
        }

        public int total() {
            return houses + offices + stations + workshops + yards + weatherStations;
        }
    }

    /**
     * The next building, or null when the village already has as many as it is allowed.
     */
    public static VillageBlueprint.Building next(Counts built, int cap) {
        if (built.total() >= Math.max(0, cap)) {
            return null;
        }
        if (built.houses() == 0) {
            return VillageBlueprint.house();
        }
        if (built.offices() == 0) {
            return VillageBlueprint.office();
        }
        if (built.stations() == 0) {
            return VillageBlueprint.station();
        }
        if (built.workshops() == 0) {
            return VillageBlueprint.workshop();
        }
        if (built.yards() == 0) {
            return VillageBlueprint.timberYard();
        }
        // The weather station comes after the three job sites a village needs to keep building, because a village
        // can repair itself without ever looking at the sky: this is the first thing on the list that is a want
        // rather than a need, and it is still built before the second house.
        if (built.weatherStations() == 0) {
            return VillageBlueprint.weatherStation();
        }
        return VillageBlueprint.house();
    }

    /**
     * Whether the village's stock covers a cost, material by material. A material the village has none of
     * is not a material it has zero of by accident: it is the one that stops the building.
     */
    /**
     * What a village has, counted by material rather than by block: two stacks of spruce and a stack of
     * cherry are three stacks of planks to a builder that is going to build one wall out of them.
     *
     * <p>Generic over the key and the item so the goal can ask it with items and the harness with strings.
     */
    public static <K, I> Map<K, Integer> totalByMaterial(Map<K, List<I>> candidates, Map<I, Integer> stock) {
        Map<K, Integer> totals = new java.util.LinkedHashMap<>();
        for (Map.Entry<K, List<I>> entry : candidates.entrySet()) {
            int total = 0;
            for (I item : entry.getValue()) {
                Integer held = stock.get(item);
                if (held != null) {
                    total += Math.max(0, held);
                }
            }
            totals.put(entry.getKey(), total);
        }
        return totals;
    }

    public static boolean canStart(Map<VillageBlueprint.Material, Integer> cost, Map<VillageBlueprint.Material, Integer> stock) {
        for (Map.Entry<VillageBlueprint.Material, Integer> entry : cost.entrySet()) {
            Integer held = stock.get(entry.getKey());
            if (held == null || held < entry.getValue()) {
                return false;
            }
        }
        return true;
    }
}
