package com.imbet.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The village's store: the containers a village owns, and where its wealth physically is.
 *
 * <p>A village's money is not a number in save data. It is the emeralds in its chests - put there by the
 * players who paid for its villagers' wares - and its materials are the blocks in the same chests. So
 * there is exactly one walk that finds them: the one the builder prices a building with, the one a trade
 * payment is put into, and the one a bounty is paid out of. Three scans that disagree about which chests
 * a village owns would be three different villages.
 *
 * <p>The walk is over columns of ground rather than over a container list, because vanilla has no point of
 * interest for a chest and a village keeps its stores indoors as often as in the open; it is coarse, so it
 * costs a bounded number of block reads however big the village is.
 */
public final class VillageStore {
    /** The widest radius a walk will cover, so a bad config cannot turn a chest search into a chunk tour. */
    public static final int MAX_RADIUS = 20;

    /** How far below a column's surface a container is looked for. */
    private static final int DEPTH = 8;

    /** Blocks between sampled columns. Two, the same stride the damage sweep uses. */
    private static final int STRIDE = 2;

    /** A container a village owns, and where it stands. */
    public record Chest(BlockPos pos, Container container) {
    }

    private VillageStore() {
    }

    /** Every container within reach of a point, found by walking columns of ground around it. */
    public static List<Chest> near(ServerLevel level, BlockPos center, int range) {
        int radius = Math.min(MAX_RADIUS, Math.max(0, range));
        List<Chest> found = new ArrayList<>();
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx += STRIDE) {
            for (int dz = -radius; dz <= radius; dz += STRIDE) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                BlockPos column = center.offset(dx, 0, dz);
                if (!level.isLoaded(column)) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
                for (int dy = 0; dy >= -DEPTH; dy--) {
                    BlockPos cursor = surface.offset(0, dy, 0);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (level.getBlockEntity(cursor) instanceof Container container) {
                        found.add(new Chest(cursor.immutable(), container));
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** What one container holds, summed by item. */
    public static Map<Item, Integer> contentsOf(Container container) {
        Map<Item, Integer> contents = new HashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                contents.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return contents;
    }

    /** Everything the village owns within reach, summed by item. */
    public static Map<Item, Integer> contents(ServerLevel level, BlockPos center, int range) {
        Map<Item, Integer> contents = new HashMap<>();
        for (Chest chest : near(level, center, range)) {
            for (Map.Entry<Item, Integer> entry : contentsOf(chest.container()).entrySet()) {
                contents.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return contents;
    }

    /**
     * Puts a stack into the store, and returns whatever would not fit. The remainder is returned rather
     * than dropped because the caller knows what the goods are for: a trade payment that a full chest
     * cannot take is a payment that belongs in the hands of the villager who took it, not on the floor.
     */
    public static ItemStack deposit(ServerLevel level, BlockPos center, int range, ItemStack stack) {
        ItemStack left = stack.copy();
        for (Chest chest : near(level, center, range)) {
            left = put(chest.container(), left);
            if (left.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return left;
    }

    /**
     * Whether the store has room for this stack. Asked before a sale is agreed rather than found out
     * afterwards, because goods taken from a player and then dropped on the floor because the chests were
     * full is a village that cannot be trusted to hold up its end of a deal.
     */
    public static boolean fits(ServerLevel level, BlockPos center, int range, ItemStack stack) {
        int room = 0;
        for (Chest chest : near(level, center, range)) {
            Container container = chest.container();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()) {
                    room += Math.min(stack.getMaxStackSize(), container.getMaxStackSize(stack));
                } else if (ItemStack.isSameItemSameComponents(existing, stack)
                        && container.canPlaceItem(slot, stack)) {
                    room += Math.max(0, container.getMaxStackSize(stack) - existing.getCount());
                }
                if (room >= stack.getCount()) {
                    return true;
                }
            }
        }
        return room >= stack.getCount();
    }

    /**
     * Takes items out of the store, up to a count and no more: the village paying for what it buys. The
     * count is read off the stack that comes back, so a caller that asks for more than a village has pays
     * for what it got rather than for what it asked for.
     */
    public static ItemStack withdraw(ServerLevel level, BlockPos center, int range, Item item, int count) {
        ItemStack taken = new ItemStack(item, 0);
        if (count <= 0) {
            return taken;
        }
        for (Chest chest : near(level, center, range)) {
            for (int slot = 0; slot < chest.container().getContainerSize() && taken.getCount() < count; slot++) {
                ItemStack stack = chest.container().getItem(slot);
                if (stack.isEmpty() || !stack.is(item)) {
                    continue;
                }
                ItemStack removed = chest.container().removeItem(slot,
                        Math.min(count - taken.getCount(), stack.getCount()));
                taken.grow(removed.getCount());
            }
            if (taken.getCount() >= count) {
                break;
            }
        }
        return taken;
    }

    /**
     * One container's worth of stacking: into matching stacks first, then into empty slots, and never
     * past what the container will take. Written out rather than called on {@code Container}, because the
     * stacking a player expects of a chest is a method of {@code SimpleContainer} and the village's store
     * is whatever a block entity happens to be.
     */
    private static ItemStack put(Container container, ItemStack stack) {
        ItemStack left = stack.copy();
        for (int slot = 0; slot < container.getContainerSize() && !left.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, left)
                    || !container.canPlaceItem(slot, left)) {
                continue;
            }
            int room = container.getMaxStackSize(left) - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, left.getCount());
            container.setItem(slot, existing.copyWithCount(existing.getCount() + moved));
            left.shrink(moved);
            container.setChanged();
        }
        for (int slot = 0; slot < container.getContainerSize() && !left.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, left)) {
                continue;
            }
            ItemStack placed = left.split(Math.min(left.getMaxStackSize(), left.getCount()));
            container.setItem(slot, placed);
            container.setChanged();
        }
        return left;
    }
}
