package com.imbet.registry;

import com.google.common.collect.ImmutableSet;
import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.village.VillageExchange;
import com.imbet.village.VillageTradeIntake;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Predicate;

/**
 * The two new village jobs.
 *
 * <p>The three village jobs, and the workstations they claim. A profession in Minecraft is a pair: a
 * job-site block a villager claims, and a name. The claiming
 * is done by the villager's own brain, which looks for any point of interest whose type is in the
 * {@code minecraft:acquirable_job_site} tag and whose state matches the profession's predicate - so
 * registering a POI type for each workstation and putting both in that tag is what actually creates
 * the jobs. Nothing else claims a villager; there is no spawn table to edit.
 *
 * <p>Both professions are resolved by key rather than by holder, because the profession registry is
 * built before the point-of-interest registry and a holder lookup at that moment would fail.
 */
public final class ModVillagers {
    private static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, ExpandedWeather2Dynamics.MODID);
    private static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, ExpandedWeather2Dynamics.MODID);

    public static final ResourceKey<PoiType> SURVEY_STATION_POI = poiKey("survey_station");
    public static final ResourceKey<PoiType> BUILDERS_BENCH_POI = poiKey("builders_bench");
    public static final ResourceKey<PoiType> SAWMILL_POI = poiKey("sawmill");
    public static final ResourceKey<PoiType> WEATHER_STATION_POI = poiKey("weather_station");

    public static final DeferredHolder<PoiType, PoiType> SURVEY_STATION_POI_TYPE = POI_TYPES.register("survey_station",
            () -> new PoiType(Set.of(ModContent.SURVEY_STATION.get().defaultBlockState()), 1, 1));

    public static final DeferredHolder<PoiType, PoiType> BUILDERS_BENCH_POI_TYPE = POI_TYPES.register("builders_bench",
            () -> new PoiType(Set.of(ModContent.BUILDERS_BENCH.get().defaultBlockState()), 1, 1));

    public static final DeferredHolder<PoiType, PoiType> SAWMILL_POI_TYPE = POI_TYPES.register("sawmill",
            () -> new PoiType(Set.of(ModContent.SAWMILL.get().defaultBlockState()), 1, 1));

    public static final DeferredHolder<PoiType, PoiType> WEATHER_STATION_POI_TYPE = POI_TYPES.register("weather_station",
            () -> new PoiType(Set.of(ModContent.WEATHER_STATION.get().defaultBlockState()), 1, 1));

    /**
     * The surveyor claims a survey station and works a turbine-quiet trade: paper in, instrument out.
     * Its job is the track, not the counter.
     */
    public static final DeferredHolder<VillagerProfession, VillagerProfession> SURVEYOR = PROFESSIONS.register("surveyor",
            () -> new VillagerProfession(
                    "surveyor",
                    holdsJobSite(SURVEY_STATION_POI),
                    holdsJobSite(SURVEY_STATION_POI),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_CARTOGRAPHER));

    /** The builder claims a bench and trades in the plain materials a village rebuilds with. */
    public static final DeferredHolder<VillagerProfession, VillagerProfession> BUILDER = PROFESSIONS.register("builder",
            () -> new VillagerProfession(
                    "builder",
                    holdsJobSite(BUILDERS_BENCH_POI),
                    holdsJobSite(BUILDERS_BENCH_POI),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_MASON));

    /** The lumberjack claims a mill and trades in what a tree is worth: logs in, planks out. */
    public static final DeferredHolder<VillagerProfession, VillagerProfession> LUMBERJACK = PROFESSIONS.register("lumberjack",
            () -> new VillagerProfession(
                    "lumberjack",
                    holdsJobSite(SAWMILL_POI),
                    holdsJobSite(SAWMILL_POI),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_FLETCHER));

    /**
     * The meteorologist claims a weather station and trades in what the sky leaves behind.
     *
     * <p>It is the fourth job and the first one that is not needed to build the other three: a village can house,
     * feed and repair itself without ever looking at the sky, so a meteorologist only exists where a village has a
     * station and a reason to write things down. The profession is registered regardless of the configuration, and
     * the configuration only decides whether it has trades - a job site with no counter to stand behind is a
     * decoration, which is exactly what the switch is for.
     */
    public static final DeferredHolder<VillagerProfession, VillagerProfession> METEOROLOGIST = PROFESSIONS.register("meteorologist",
            () -> new VillagerProfession(
                    "meteorologist",
                    holdsJobSite(WEATHER_STATION_POI),
                    holdsJobSite(WEATHER_STATION_POI),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_CARTOGRAPHER));

    private ModVillagers() {
    }

    public static void register(IEventBus modEventBus) {
        POI_TYPES.register(modEventBus);
        PROFESSIONS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(ModVillagers::onVillagerTrades);
        // What a village is paid for its trades, which is where its money comes from: registered here
        // because it is the other half of the trades above - those hand things out, this keeps the change.
        NeoForge.EVENT_BUS.addListener(VillageTradeIntake::onTrade);
    }

    private static ResourceKey<PoiType> poiKey(String name) {
        return ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, name));
    }

    /** A profession's job site, named by key so it can be declared before the POI registry exists. */
    private static Predicate<Holder<PoiType>> holdsJobSite(ResourceKey<PoiType> key) {
        return holder -> holder.is(key);
    }

    /**
     * Two short trade lists, so a hired villager has something to do at the counter: the surveyor
     * eventually sells the instruments it uses, and the builder trades the blocks it works with.
     *
     * <p>Each of them sells its own workstation at the top of the list, and that is not decoration: both
     * the station and the workshop a village builds cost one of these blocks, which a village cannot make
     * for itself. Selling them is what turns "a village can build a station for each job" into something a
     * player can actually bring about - buy the block, put it in a chest, and the village builds the
     * building around it.
     */
    private static void onVillagerTrades(VillagerTradesEvent event) {
        if (event.getType() == SURVEYOR.get()) {
            event.getTrades().get(1).add(offer(new ItemCost(Items.PAPER, 20), new ItemStack(Items.EMERALD), 16, 2));
            event.getTrades().get(2).add(offer(new ItemCost(Items.EMERALD, 14), new ItemStack(ModContent.DAMAGE_SURVEYOR_ITEM.get()), 8, 8));
            event.getTrades().get(3).add(offer(new ItemCost(Items.EMERALD, 12), new ItemStack(ModContent.BAROMETER_BLOCK_ITEM.get()), 8, 12));
            event.getTrades().get(4).add(offer(new ItemCost(Items.EMERALD, 10), new ItemStack(ModContent.SURVEY_STATION_ITEM.get()), 8, 14));
        } else if (event.getType() == LUMBERJACK.get()) {
            // The mill buys the wood it works in at the village's own price and sells what it saws: logs
            // in, planks out, which is the same trade the lumberjack itself makes out in the fields.
            event.getTrades().get(1).add(buy(Items.OAK_LOG, VillageExchange.Goods.LOG, 16, 2));
            event.getTrades().get(1).add(buy(Items.OAK_PLANKS, VillageExchange.Goods.PLANKS, 16, 4));
            event.getTrades().get(2).add(offer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.OAK_PLANKS, 8), 16, 6));
            event.getTrades().get(3).add(offer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.OAK_LOG, 6), 12, 8));
            event.getTrades().get(4).add(offer(new ItemCost(Items.EMERALD, 5), new ItemStack(ModContent.SAWMILL_ITEM.get()), 8, 14));
        } else if (event.getType() == BUILDER.get()) {
            // The counter buys at the village's published price ({@link VillageExchange}), so what a player
            // is paid here is what the bench pays, and what the builder pays a villager it buys from. Four
            // kinds of goods, because those are the four a village building a house runs out of.
            event.getTrades().get(1).add(buy(Items.COBBLESTONE, VillageExchange.Goods.STONE, 16, 2));
            event.getTrades().get(1).add(buy(Items.OAK_PLANKS, VillageExchange.Goods.PLANKS, 16, 4));
            event.getTrades().get(1).add(buy(Items.OAK_LOG, VillageExchange.Goods.LOG, 16, 4));
            event.getTrades().get(1).add(buy(Items.GLASS, VillageExchange.Goods.GLASS, 16, 4));
            event.getTrades().get(2).add(offer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.SCAFFOLDING, 12), 12, 6));
            event.getTrades().get(3).add(offer(new ItemCost(Items.EMERALD, 1), new ItemStack(Blocks.DIRT, 16), 12, 6));
            event.getTrades().get(4).add(offer(new ItemCost(Items.EMERALD, 6), new ItemStack(ModContent.BUILDERS_BENCH_ITEM.get()), 8, 12));
        } else if (event.getType() == METEOROLOGIST.get() && Config.METEOROLOGIST_ENABLED.get()) {
            // What the counter deals in, and why it is paper and parts rather than storm logs and photographs.
            //
            // The records this mod keeps about a player - the chase ledger, the spotter log and the album - are
            // saved data rather than items, and they are already paid for by the services that keep them: a
            // confirmed report pays a bounty, and a photograph is worth what it scores. What a player can put on a
            // counter is the sheet a log is written on, the part a reading is taken with and the instrument that
            // takes it, which is what this villager trades. Inventing a "storm log" item to satisfy the wording
            // would have added a second, worse record beside the ones the mod already keeps.
            event.getTrades().get(1).add(buy(Items.PAPER, 24, 2));
            event.getTrades().get(1).add(offer(new ItemCost(Items.EMERALD, 6), new ItemStack(ModContent.HANDHELD_THERMOMETER_ITEM.get()), 12, 4));
            event.getTrades().get(2).add(buy(ModContent.PROBE_SENSOR_MODULE_ITEM.get(), 1, 3));
            event.getTrades().get(2).add(offer(new ItemCost(Items.EMERALD, 10), new ItemStack(ModContent.RAIN_GAUGE_BLOCK_ITEM.get()), 8, 8));
            event.getTrades().get(3).add(offer(new ItemCost(Items.EMERALD, 12), new ItemStack(ModContent.WEATHER_VANE_BLOCK_ITEM.get()), 8, 10));
            event.getTrades().get(3).add(offer(new ItemCost(Items.EMERALD, 14), new ItemStack(ModContent.HANDHELD_BAROMETER_ITEM.get()), 8, 12));
            event.getTrades().get(4).add(offer(new ItemCost(Items.EMERALD, 14), new ItemStack(ModContent.ANEMOMETER_BLOCK_ITEM.get()), 8, 14));
            event.getTrades().get(4).add(offer(new ItemCost(Items.EMERALD, 18), new ItemStack(ModContent.HANDHELD_ANEMOMETER_ITEM.get()), 8, 16));
            event.getTrades().get(5).add(offer(new ItemCost(Items.EMERALD, 18), new ItemStack(ModContent.BAROMETER_BLOCK_ITEM.get()), 8, 18));
            event.getTrades().get(5).add(offer(new ItemCost(Items.EMERALD, 24), new ItemStack(ModContent.WEATHER_STATION_ITEM.get()), 8, 20));
            event.getTrades().get(5).add(offer(new ItemCost(Items.EMERALD, 32), new ItemStack(ModContent.WEATHER_COMPUTER_BLOCK_ITEM.get()), 4, 24));
        }
    }

    /** A trade that pays emeralds for goods a villager wants, at a flat rate. */
    private static VillagerTrades.ItemListing buy(Item item, int count, int emeralds) {
        return offer(new ItemCost(item, count), new ItemStack(Items.EMERALD, emeralds), 16, 2);
    }

    /**
     * A trade that buys goods for the village at the price the village publishes. The cost and the payment
     * are both read off {@link VillageExchange}, so a price change reaches the counter, the bench and the
     * builder in one edit rather than three, and the three can never quote different numbers.
     */
    private static VillagerTrades.ItemListing buy(Item item, VillageExchange.Goods goods, int maxUses, int xp) {
        VillageExchange.Lot lot = VillageExchange.priceOf(goods);
        return offer(new ItemCost(item, lot.items()), new ItemStack(Items.EMERALD, lot.emeralds()), maxUses, xp);
    }

    private static VillagerTrades.ItemListing offer(ItemCost cost, ItemStack result, int maxUses, int xp) {
        return new VillagerTrades.ItemListing() {
            @Nullable
            @Override
            public MerchantOffer getOffer(net.minecraft.world.entity.Entity trader, net.minecraft.util.RandomSource random) {
                return new MerchantOffer(cost, result, maxUses, xp, 0.05F);
            }
        };
    }
}
