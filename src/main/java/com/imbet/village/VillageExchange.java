package com.imbet.village;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the village pays for what it is short of.
 *
 * <p>A village does not conjure what it cannot find - it buys it, at a published price - and the price is
 * written down once, here, so that every way of buying from a village agrees with every other way: the
 * counter at a builder's bench, the trade list its villagers offer a player, and the builder walking up to
 * the villager who happens to be holding what the village needs. Two of those interfaces quoting different
 * numbers is the classic way an economy stops making sense.
 *
 * <p>Prices are quoted per kind of goods rather than per block, because that is what a village means by
 * a material: every plank is the same plank to a village that builds in whatever it has. A {@link Lot} is
 * the unit a price is quoted in - sixteen cobblestone, one door - and a partial lot costs a whole one,
 * which is the honest way to price a small order and the reason the bench says "1 emerald per 24".
 *
 * <p>Minecraft-free on purpose: what a village wants and what it pays are the two things a player argues
 * with, so both are measured rather than trusted.
 */
public final class VillageExchange {
    /** The kinds of goods a village buys, and therefore the things a price is quoted for. */
    public enum Goods {
        STONE,
        /** The ground a scour is put back to: dirt, grass, sand - the blocks a repair spends. */
        GROUND,
        PLANKS,
        LOG,
        GLASS,
        TORCH,
        CHEST,
        DOOR,
        BED,
        STATION,
        BENCH,
        /** The lumberjack's own workstation: what makes a timber yard a timber yard. */
        SAWMILL,
        /** The meteorologist's own workstation: what makes a weather station a weather station. */
        WEATHER_STATION
    }

    /** A quoted price: {@code items} of some goods for {@code emeralds} of the village's money. */
    public record Lot(int items, int emeralds) {
        public Lot {
            if (items <= 0 || emeralds <= 0) {
                throw new IllegalArgumentException("a price quotes a positive lot for a positive price");
            }
        }
    }

    /** One thing the village is short of, as the kind of goods it is bought by and how many it needs. */
    public record Need(Goods goods, int missing) {
        public Need {
            if (missing < 0) {
                throw new IllegalArgumentException("a village cannot be short of a negative amount");
            }
        }
    }

    /** How many needs the bench names before it stops: a shopping list is a hint, not a ledger. */
    private static final int NAMED_NEEDS = 2;

    private static final Map<Goods, Lot> PRICES = prices();
    private static final Map<Goods, String> NAMES = names();

    private VillageExchange() {
    }

    private static Map<Goods, Lot> prices() {
        Map<Goods, Lot> prices = new LinkedHashMap<>();
        prices.put(Goods.STONE, new Lot(16, 1));
        prices.put(Goods.GROUND, new Lot(16, 1));
        prices.put(Goods.PLANKS, new Lot(24, 1));
        prices.put(Goods.LOG, new Lot(16, 1));
        prices.put(Goods.GLASS, new Lot(8, 1));
        prices.put(Goods.TORCH, new Lot(32, 1));
        prices.put(Goods.CHEST, new Lot(2, 1));
        prices.put(Goods.DOOR, new Lot(1, 1));
        prices.put(Goods.BED, new Lot(1, 1));
        // The two workstations are the dearest things a village buys, and both are deliberately below what
        // its own villagers charge for them: a village that buys cheap and sells dear is a shop, and one
        // that does it the other way round is a hole in the economy.
        prices.put(Goods.STATION, new Lot(1, 8));
        prices.put(Goods.BENCH, new Lot(1, 4));
        prices.put(Goods.SAWMILL, new Lot(1, 3));
        // The weather station is priced above the other three workstations and still below what its own
        // villager charges: it is the one job site a village cannot use to build the next thing, so buying one
        // has to be worth the same as any other purchase to the village's own accounts.
        prices.put(Goods.WEATHER_STATION, new Lot(1, 10));
        return Map.copyOf(prices);
    }

    private static Map<Goods, String> names() {
        Map<Goods, String> names = new LinkedHashMap<>();
        names.put(Goods.STONE, "stone");
        names.put(Goods.GROUND, "dirt");
        names.put(Goods.PLANKS, "planks");
        names.put(Goods.LOG, "logs");
        names.put(Goods.GLASS, "glass");
        names.put(Goods.TORCH, "torches");
        names.put(Goods.CHEST, "chests");
        names.put(Goods.DOOR, "doors");
        names.put(Goods.BED, "beds");
        names.put(Goods.STATION, "survey stations");
        names.put(Goods.BENCH, "builders' benches");
        names.put(Goods.SAWMILL, "sawmills");
        names.put(Goods.WEATHER_STATION, "weather stations");
        return Map.copyOf(names);
    }

    public static Lot priceOf(Goods goods) {
        Lot lot = PRICES.get(goods);
        if (lot == null) {
            throw new IllegalArgumentException("no price is published for " + goods);
        }
        return lot;
    }

    /** What this kind of goods is called when a player is told what the village wants. */
    public static String name(Goods goods) {
        return NAMES.get(goods);
    }

    /**
     * What the village pays for {@code count} of something. Whole lots: a village asked for three planks
     * pays for a lot, because that is the price it published rather than one it worked out on the spot.
     */
    public static int emeraldsFor(Goods goods, int count) {
        if (count <= 0) {
            return 0;
        }
        Lot lot = priceOf(goods);
        return ((count + lot.items() - 1) / lot.items()) * lot.emeralds();
    }

    /**
     * The most of something a purse buys, never more than the village wants. A village with three emeralds
     * and a price of one emerald per door buys three doors, and a village with one emerald and a price of
     * eight per station buys nothing at all rather than a station it cannot afford.
     */
    public static int affordable(Goods goods, int wanted, int purse) {
        if (wanted <= 0 || purse <= 0) {
            return 0;
        }
        Lot lot = priceOf(goods);
        return Math.min(wanted, (purse / lot.emeralds()) * lot.items());
    }

    /**
     * What the village buys when it is offered goods it needs: never more than it is short of, never more
     * than it was offered, never more than it can pay for. This is the whole of a purchase, which is why
     * it is one function rather than three lines copied into a block, an event handler and a goal.
     */
    public static int buys(Goods goods, int missing, int offered, int purse) {
        return Math.min(Math.min(missing, offered), affordable(goods, missing, purse));
    }

    /** The price written the way the bench sign reads it. */
    public static String describe(Goods goods) {
        Lot lot = priceOf(goods);
        String money = lot.emeralds() == 1 ? "1 emerald" : lot.emeralds() + " emeralds";
        return lot.items() == 1 ? money + " each" : money + " per " + lot.items();
    }

    /**
     * What a cost is short of, in the order the plan states it. A material the village has none of is
     * short of all of it, and the order is the plan's own because "what it needs first" is a plan's
     * judgement rather than a HashMap's.
     */
    public static <K> Map<K, Integer> shortfall(Map<K, Integer> cost, Map<K, Integer> stock) {
        Map<K, Integer> shortfall = new LinkedHashMap<>();
        for (Map.Entry<K, Integer> entry : cost.entrySet()) {
            int held = stock.getOrDefault(entry.getKey(), 0);
            int missing = entry.getValue() - Math.max(0, held);
            if (missing > 0) {
                shortfall.put(entry.getKey(), missing);
            }
        }
        return shortfall;
    }

    /**
     * The village's shopping list, in the order it wants it: what its repairs need first, then what it is
     * building. Repairs lead because a hole in the ground is a thing that has already happened while a house
     * that is not there yet can wait - and because a village that cannot put its own streets back is being
     * asked to build in the middle of the mess it is standing in. A kind of goods both sides want is one
     * line, for the sum: a village short of planks is short of planks however it got that way.
     */
    public static List<Need> wants(Map<Goods, Integer> repairs, Map<Goods, Integer> building) {
        Map<Goods, Integer> missing = new LinkedHashMap<>(repairs.size() + building.size());
        missing.putAll(repairs);
        for (Map.Entry<Goods, Integer> entry : building.entrySet()) {
            missing.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        List<Need> wants = new ArrayList<>(missing.size());
        for (Map.Entry<Goods, Integer> entry : missing.entrySet()) {
            wants.add(new Need(entry.getKey(), entry.getValue()));
        }
        return wants;
    }

    /**
     * What a player standing at the bench should be told: what the village is short of, what it pays for
     * it, and how to sell to it. A village that wants something and cannot pay is the case worth saying
     * out loud, because the answer is a trade with its villagers rather than a walk to a chest.
     */
    public static List<String> offerLines(List<Need> needs, int purse) {
        List<String> lines = new ArrayList<>(3);
        for (Need need : needs) {
            if (lines.size() >= NAMED_NEEDS) {
                break;
            }
            if (need.missing() <= 0) {
                continue;
            }
            lines.add("  Short of " + need.missing() + " " + name(need.goods()) + ": it pays "
                    + describe(need.goods()) + ".");
        }
        if (lines.isEmpty()) {
            return lines;
        }
        if (purse <= 0) {
            lines.add("  It has no emeralds to pay with: what its villagers are paid in trades is its money.");
        } else {
            lines.add("  It has " + purse + (purse == 1 ? " emerald" : " emeralds") + " to spend.");
            lines.add("  Sell it the goods while holding them at this bench, or trade with its villagers.");
        }
        return lines;
    }
}
