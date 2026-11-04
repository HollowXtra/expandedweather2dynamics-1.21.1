package com.imbet.village;

import com.imbet.registry.ModContent;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;
import java.util.Map;

/**
 * The blocks a blueprint's materials become, and what the village pays for them.
 *
 * <p>A plan says "planks", not "oak planks", because a village builds out of what it has: the same plan
 * builds in spruce or cherry, and it builds in whatever is in the chest rather than waiting for a tree
 * the plan happened to name. So each material is a list of the blocks that would do, the village's stock
 * picks between them, and the cost of a piece is the block it becomes - the same rule repairs already
 * live by, one item out of the chest and one block into the world.
 *
 * <p>The orientation states are here rather than in the goal because they are part of what a piece *is*: a
 * door is two cells with a hinge on one side, a bed is two cells with a head, and a chest faces the room
 * it is in so it can be opened from inside. The three workstations are the village's own blocks rather
 * than vanilla ones, so their names are the only thing the plan knows about them - which is the whole
 * reason a material is a kind of thing and not a block.
 */
public final class VillageMaterials {
    /** What one piece costs and what it puts in the world. */
    public record Placement(Item item, BlockState first, BlockState second) {
        public boolean isTall() {
            return second != null;
        }
    }

    /** The woods a stripped trunk is put back to, in the order they are listed. */
    private static final List<Item> WOODS = List.of(Items.OAK_WOOD, Items.SPRUCE_WOOD, Items.BIRCH_WOOD,
            Items.JUNGLE_WOOD, Items.ACACIA_WOOD, Items.DARK_OAK_WOOD, Items.MANGROVE_WOOD, Items.CHERRY_WOOD,
            Items.CRIMSON_HYPHAE, Items.WARPED_HYPHAE);

    /**
     * The blocks a village buys that no building is made of: the ground a scour is re-sodded with and the
     * trunk a stripped log goes back to. Repair materials are not in the material lists because a repair
     * costs whatever the block beside it was, so they are named here instead.
     */
    private static final List<Item> REPAIR_GROUND = List.of(Items.GRASS_BLOCK, Items.DIRT, Items.COARSE_DIRT,
            Items.ROOTED_DIRT, Items.PODZOL, Items.MYCELIUM, Items.SAND, Items.RED_SAND, Items.SNOW_BLOCK,
            Items.DIRT_PATH);

    /** Which goods each block the village buys is: what a price is quoted for, built once. */
    private static final Map<Item, VillageExchange.Goods> GOODS = goods();

    private VillageMaterials() {
    }

    /**
     * The goods a material is bought and sold as. A village prices what it builds from, and it prices by
     * kind rather than by block: oak and spruce planks are one price because a village that builds in
     * whatever it has cannot tell them apart.
     */
    public static VillageExchange.Goods goods(VillageBlueprint.Material material) {
        return switch (material) {
            case STONE -> VillageExchange.Goods.STONE;
            case PLANKS -> VillageExchange.Goods.PLANKS;
            case LOG -> VillageExchange.Goods.LOG;
            case GLASS -> VillageExchange.Goods.GLASS;
            case TORCH -> VillageExchange.Goods.TORCH;
            case DOOR -> VillageExchange.Goods.DOOR;
            case BED -> VillageExchange.Goods.BED;
            case CHEST -> VillageExchange.Goods.CHEST;
            case STATION -> VillageExchange.Goods.STATION;
            case BENCH -> VillageExchange.Goods.BENCH;
            case SAWMILL -> VillageExchange.Goods.SAWMILL;
            case WEATHER_STATION -> VillageExchange.Goods.WEATHER_STATION;
        };
    }

    private static Map<Item, VillageExchange.Goods> goods() {
        Map<Item, VillageExchange.Goods> goods = new java.util.LinkedHashMap<>();
        for (VillageBlueprint.Material material : VillageBlueprint.Material.values()) {
            for (Item item : candidates(material)) {
                goods.put(item, goods(material));
            }
        }
        for (Item item : REPAIR_GROUND) {
            goods.put(item, VillageExchange.Goods.GROUND);
        }
        for (Item item : WOODS) {
            goods.put(item, VillageExchange.Goods.LOG);
        }
        return Map.copyOf(goods);
    }

    /**
     * The goods this block is, or null when the village does not buy it at all. Null matters: a village's
     * purse should not be emptied by whoever brings a chest of wool to the bench, so what it does not
     * price it does not buy.
     */
    public static VillageExchange.Goods goodsOf(Item item) {
        return GOODS.get(item);
    }

    /** The blocks each material can be built from, in the order a village should prefer them. */
    public static List<Item> candidates(VillageBlueprint.Material material) {
        return switch (material) {
            case STONE -> List.of(Items.COBBLESTONE, Items.STONE, Items.STONE_BRICKS, Items.DEEPSLATE,
                    Items.ANDESITE, Items.GRANITE, Items.DIORITE, Items.TUFF);
            case PLANKS -> List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
                    Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
                    Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS);
            case LOG -> List.of(Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
                    Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
                    Items.CRIMSON_STEM, Items.WARPED_STEM);
            case GLASS -> List.of(Items.GLASS);
            case DOOR -> List.of(Items.OAK_DOOR, Items.SPRUCE_DOOR, Items.BIRCH_DOOR, Items.JUNGLE_DOOR,
                    Items.ACACIA_DOOR, Items.DARK_OAK_DOOR, Items.MANGROVE_DOOR, Items.CHERRY_DOOR,
                    Items.BAMBOO_DOOR, Items.CRIMSON_DOOR, Items.WARPED_DOOR);
            case TORCH -> List.of(Items.TORCH);
            case BED -> List.of(Items.WHITE_BED, Items.LIGHT_GRAY_BED, Items.GRAY_BED, Items.BLACK_BED,
                    Items.BROWN_BED, Items.RED_BED, Items.ORANGE_BED, Items.YELLOW_BED, Items.LIME_BED,
                    Items.GREEN_BED, Items.CYAN_BED, Items.LIGHT_BLUE_BED, Items.BLUE_BED, Items.PURPLE_BED,
                    Items.MAGENTA_BED, Items.PINK_BED);
            case CHEST -> List.of(Items.CHEST, Items.BARREL);
            case STATION -> List.of(ModContent.SURVEY_STATION_ITEM.get());
            case BENCH -> List.of(ModContent.BUILDERS_BENCH_ITEM.get());
            case SAWMILL -> List.of(ModContent.SAWMILL_ITEM.get());
            case WEATHER_STATION -> List.of(ModContent.WEATHER_STATION_ITEM.get());
        };
    }

    /**
     * The item a piece of this material costs, preferring one the village actually has: a village with a
     * chest of spruce builds in spruce and never notices the plan was written in oak.
     */
    public static Item choose(VillageBlueprint.Material material, Map<Item, Integer> stock) {
        List<Item> candidates = candidates(material);
        for (Item candidate : candidates) {
            Integer count = stock.get(candidate);
            if (count != null && count > 0) {
                // The first of the list that the village actually has, so two builds in a row are made of the
                // same thing rather than of whatever the chest happens to be sorted like.
                return candidate;
            }
        }
        return candidates.get(0);
    }

    /**
     * What one piece puts in the world. The second block is the rest of a piece that is two cells - a
     * door's top half, a bed's head - and is placed with the first for the price of one.
     */
    public static Placement placementFor(Item item, VillageBlueprint.Material material, int facing) {
        Block block = Block.byItem(item);
        Direction outward = direction(VillageBlueprint.outward(facing));
        Direction inward = direction(VillageBlueprint.inward(facing));
        BlockState state = block.defaultBlockState();
        return switch (material) {
            case DOOR -> new Placement(item,
                    state.setValue(DoorBlock.FACING, outward).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER),
                    state.setValue(DoorBlock.FACING, outward).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            case BED -> new Placement(item,
                    state.setValue(BedBlock.FACING, inward).setValue(BedBlock.PART, BedPart.FOOT),
                    state.setValue(BedBlock.FACING, inward).setValue(BedBlock.PART, BedPart.HEAD));
            case CHEST -> new Placement(item, state.setValue(ChestBlock.FACING, inward), null);
            default -> new Placement(item, state, null);
        };
    }

    /** Whether a piece occupies the cell above its own as well. */
    public static boolean isTall(VillageBlueprint.Material material) {
        return material == VillageBlueprint.Material.DOOR;
    }

    /** Whether a piece occupies the next cell along the building's own inward direction. */
    public static boolean isLong(VillageBlueprint.Material material) {
        return material == VillageBlueprint.Material.BED;
    }

    /** One of the four horizontal directions, from the blueprint's own numbering. */
    public static Direction direction(int[] unit) {
        if (unit[1] > 0) {
            return Direction.SOUTH;
        }
        if (unit[1] < 0) {
            return Direction.NORTH;
        }
        return unit[0] > 0 ? Direction.EAST : Direction.WEST;
    }
}
