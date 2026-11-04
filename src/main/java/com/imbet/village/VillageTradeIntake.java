package com.imbet.village;

import com.imbet.Config;
import com.imbet.registry.ModVillagers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;

/**
 * What a village is paid for its trades.
 *
 * <p>Vanilla throws the payment away: a player who sells a stack of cobblestone to a villager is paid an
 * emerald and the cobblestone is gone, and a player who buys a bench pays six emeralds that are gone too.
 * For a village the mod has given a bench to, that payment lands in the village instead - the emeralds go
 * into its chests, and the goods go into the hands of the villager that accepted them - which is the only
 * reason a village has any money or materials of its own. "A village with trades can afford to rebuild" is
 * this function, and nothing else.
 *
 * <p>Nothing is created. The payment is read off the offer as the trade completes, so it is the same stack
 * the game was about to delete: a village cannot be richer than what has been paid to it, and the builder's
 * spending is bounded by exactly that.
 */
public final class VillageTradeIntake {
    private VillageTradeIntake() {
    }

    public static void onTrade(TradeWithVillagerEvent event) {
        if (!Config.AI_VILLAGER_BUILDER_TRADE_INTAKE.get()) {
            return;
        }
        AbstractVillager trader = event.getAbstractVillager();
        if (!(trader.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos bench = benchNear(level, trader.blockPosition());
        if (bench == null) {
            return;
        }
        MerchantOffer offer = event.getMerchantOffer();
        keep(level, trader, bench, offer.getCostA());
        keep(level, trader, bench, offer.getCostB());
    }

    /**
     * The bench that makes this a village: a villager with no bench of this mod's in reach is a villager
     * standing in a field, and a payment to one of those is not a village's business.
     */
    private static BlockPos benchNear(ServerLevel level, BlockPos pos) {
        int range = Config.AI_VILLAGER_BUILDER_PURCHASE_RANGE_BLOCKS.get();
        return level.getPoiManager()
                .findClosest(holder -> holder.is(ModVillagers.BUILDERS_BENCH_POI), pos, range, PoiManager.Occupancy.ANY)
                .orElse(null);
    }

    /**
     * Puts one thing the player paid where it still is tomorrow. Emeralds are the village's money and go
     * into its store; everything else is what the villager was sold, so it stays with that villager - who
     * is then a villager the builder can buy it back from, which is what makes a village's own handful of
     * traders a place materials come from.
     *
     * <p>Every step has a fallback, because the alternative to a full chest is not a deleted stack. The
     * money goes into the village's store and the goods into the hands of the villager that accepted them -
     * a villager the builder can then buy them back from. Whatever fits in neither is dropped at the
     * villager's feet, which is a worse delivery than either and still not a stack that never existed.
     */
    private static void keep(ServerLevel level, AbstractVillager trader, BlockPos bench, ItemStack paid) {
        if (paid.isEmpty()) {
            return;
        }
        int chestRange = Config.AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS.get();
        ItemStack left;
        if (paid.is(Items.EMERALD)) {
            left = VillageStore.deposit(level, bench, chestRange, paid.copy());
        } else {
            left = trader.getInventory().addItem(paid.copy());
            if (!left.isEmpty()) {
                left = VillageStore.deposit(level, bench, chestRange, left);
            }
        }
        if (!left.isEmpty()) {
            Block.popResource(level, trader.blockPosition(), left);
        }
    }
}
