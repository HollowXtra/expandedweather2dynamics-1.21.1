package com.imbet.village;

import java.util.List;

/**
 * The order a builder works in, and how fast.
 *
 * <p>Free of Minecraft types so the policy can be measured: what gets repaired first, that a trip ends
 * when its budget is spent, and that a slow worker is slow because the clock says so rather than
 * because a random number did. The goal supplies candidates it read from the world and applies the
 * block edits.
 */
public final class RepairPolicy {
    /** The two kinds of storm damage a builder puts back. */
    public enum Damage {
        /** A stripped trunk: the small, satisfying job. */
        LOG(0.0D),
        /** Scoured ground: the bulk of a track. */
        GROUND(1.5D);

        private final double bias;

        Damage(double bias) {
            this.bias = bias;
        }

        public double bias() {
            return bias;
        }
    }

    /** A block the builder could repair. */
    public record Target(double x, double y, double z, Damage damage) {
    }

    private RepairPolicy() {
    }

    /**
     * Walk cost, lower first. Distance dominates, so a builder sweeps the damage around it instead of
     * crossing a village for a better kind of job; the bias only settles near-ties.
     */
    public static double cost(Target target, double fromX, double fromY, double fromZ) {
        double dx = target.x() - fromX;
        double dy = target.y() - fromY;
        double dz = target.z() - fromZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + target.damage().bias();
    }

    /**
     * The work order, as indices into {@code targets}, nearest first. Ties keep their original order,
     * so two builders standing in the same place work the same block rather than disagreeing - and the
     * same target list is always worked in the same sequence.
     */
    public static int[] order(List<Target> targets, double fromX, double fromY, double fromZ) {
        int[] indices = new int[targets.size()];
        double[] costs = new double[targets.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
            costs[i] = cost(targets.get(i), fromX, fromY, fromZ);
        }
        // Insertion sort: these lists are a village's worth of damage, not thousands of blocks, and it
        // is stable, which is what keeps equal costs in world order.
        for (int i = 1; i < indices.length; i++) {
            int index = indices[i];
            double value = costs[index];
            int j = i - 1;
            while (j >= 0 && costs[indices[j]] > value) {
                indices[j + 1] = indices[j];
                j--;
            }
            indices[j + 1] = index;
        }
        return indices;
    }

    /** Blocks still allowed this trip. A trip never runs past its budget, however much damage is left. */
    public static int remaining(int done, int perTrip) {
        return Math.max(0, perTrip - Math.max(0, done));
    }

    /** Whether the next block is due: one every {@code ticksPerBlock}, no closer. */
    public static boolean due(long gameTime, long lastWorkTick, int ticksPerBlock) {
        return gameTime - lastWorkTick >= Math.max(1, ticksPerBlock);
    }
}
