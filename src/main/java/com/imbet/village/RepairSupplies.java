package com.imbet.village;

import java.util.List;
import java.util.Map;

/**
 * What a builder carries, what a chest holds, and how much of it is worth walking over for.
 *
 * <p>Free of Minecraft types and generic over the material key, so the arithmetic can be measured
 * without the game - the goal uses it keyed by {@code Item}, the harness keyed by strings, and both run
 * the same code. Everything here is a pure function of its arguments: no villager, no level, no state.
 *
 * <p>The rule the whole feature rests on is deliberately plain: the material a repair costs is the
 * block the repair puts back. A builder taking the bark off a stripped trunk takes an oak log out of a
 * chest and places an oak log; re-sodding scoured ground takes the very block it will put down. That
 * makes the accounting honest rather than approximate - what leaves the chest is what appears in the
 * world - and it is why a job the village cannot pay for is refused instead of improvised.
 */
public final class RepairSupplies {
    /** A container of materials: where it stands, and what is in it. */
    public record Stash<K>(double x, double y, double z, Map<K, Integer> contents) {
        public int count(K material) {
            Integer held = contents.get(material);
            return held == null ? 0 : held;
        }

        public boolean has(K material) {
            return count(material) > 0;
        }
    }

    private RepairSupplies() {
    }

    /** How many of this material the builder is carrying. */
    public static <K> int carried(Map<K, Integer> purse, K material) {
        Integer held = purse.get(material);
        return held == null ? 0 : held;
    }

    /** Whether one repair can be paid for right now. */
    public static <K> boolean covers(Map<K, Integer> purse, K material) {
        return carried(purse, material) > 0;
    }

    /**
     * The nearest stash within reach that actually holds what is needed, or -1. A nearer chest full of
     * cobblestone is not a source of dirt, and a chest with the right block two hundred blocks away is
     * not a source either: a builder walks to a chest it can reach and use.
     */
    public static <K> int pickStash(List<Stash<K>> stashes, K material, double fromX, double fromY, double fromZ, double maxDistance) {
        int best = -1;
        double bestDistance = 0.0D;
        for (int i = 0; i < stashes.size(); i++) {
            Stash<K> stash = stashes.get(i);
            if (!stash.has(material)) {
                continue;
            }
            double dx = stash.x() - fromX;
            double dy = stash.y() - fromY;
            double dz = stash.z() - fromZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > maxDistance) {
                continue;
            }
            // Strictly nearer keeps the first of two equal chests, so the choice does not wander.
            if (best < 0 || distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * How many to take out of a stash now: enough to finish the carry, never more than the chest holds,
     * and nothing at all once the builder is already carrying its limit.
     */
    public static int withdrawAmount(int inStash, int carriedAlready, int carryLimit) {
        int room = Math.max(0, carryLimit - Math.max(0, carriedAlready));
        return Math.max(0, Math.min(Math.max(0, inStash), room));
    }

    /** The first target in a work order the builder can pay for, or -1. */
    public static <K> int firstAffordable(int[] order, List<K> materials, Map<K, Integer> purse) {
        for (int index : order) {
            if (index >= 0 && index < materials.size() && covers(purse, materials.get(index))) {
                return index;
            }
        }
        return -1;
    }
}
