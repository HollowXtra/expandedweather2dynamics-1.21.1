package com.imbet.village;

import com.imbet.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.Map;

/**
 * The village's counter: a player selling it the materials it is short of, at the price it publishes.
 *
 * <p>This is the half of the village's economy a player takes part in deliberately. The builder buys what
 * it needs from whoever in the village is holding it, but a builder is never sent to a player to take
 * their blocks: a player brings blocks to the bench and is paid for them, or does not. So the offer is
 * answered here, on the bench's own right-click, out of the same two things the builder spends - the
 * village's emeralds and the shopping list the builder itself works from - which is why a player and a
 * villager are paid the same price for the same stack.
 *
 * <p>Nothing is taken that the village cannot hold or pay for. The order is deliberate: work out the deal,
 * check the chests have room, take the money out of the chest, and only then take the goods. A village
 * that fails any of those steps has given up nothing and said why.
 */
public final class VillageCounter {
    private VillageCounter() {
    }

    /** Answers one click: buys as much of the held stack as the village wants and can pay for. */
    public static void sell(ServerLevel level, BlockPos bench, ServerPlayer player, ItemStack held) {
        VillageExchange.Goods goods = VillageMaterials.goodsOf(held.getItem());
        if (goods == null) {
            return;
        }
        int chestRange = Config.AI_VILLAGER_BUILDER_CHEST_RANGE_BLOCKS.get();
        Map<Item, Integer> stock = VillageStore.contents(level, bench, chestRange);
        int purse = stock.getOrDefault(Items.EMERALD, 0);
        int missing = missing(level, bench, stock, goods);
        int count = VillageExchange.buys(goods, missing, held.getCount(), purse);
        if (count <= 0) {
            say(player, "The village does not need " + VillageExchange.name(goods) + " right now"
                    + (purse <= 0 ? ", and it has no emeralds to buy with." : "."), ChatFormatting.GRAY);
            return;
        }
        ItemStack sold = held.copyWithCount(count);
        if (!VillageStore.fits(level, bench, chestRange, sold)) {
            say(player, "The village has nowhere to put " + VillageExchange.name(goods) + ".", ChatFormatting.GRAY);
            return;
        }
        int price = VillageExchange.emeraldsFor(goods, count);
        ItemStack money = VillageStore.withdraw(level, bench, chestRange, Items.EMERALD, price);
        if (money.getCount() < price) {
            // Say so and put it back rather than part-paying: a village that short of its own cash has
            // nothing to sell to either.
            VillageStore.deposit(level, bench, chestRange, money);
            say(player, "The village cannot reach its emeralds.", ChatFormatting.GRAY);
            return;
        }
        held.shrink(count);
        VillageStore.deposit(level, bench, chestRange, sold);
        if (!player.getInventory().add(money)) {
            player.drop(money, false);
        }
        say(player, "The village bought " + count + " " + VillageExchange.name(goods) + " for "
                + price + (price == 1 ? " emerald" : " emeralds") + ".", ChatFormatting.AQUA);
        level.playSound(null, bench, SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6F, 1.0F);
    }

    /** How much of these goods the village is short of, by the shopping list the builder works from. */
    private static int missing(ServerLevel level, BlockPos bench, Map<Item, Integer> stock,
            VillageExchange.Goods goods) {
        for (VillageExchange.Need need : BuilderGoal.needs(level, bench, stock)) {
            if (need.goods() == goods) {
                return need.missing();
            }
        }
        return 0;
    }

    /** Tells the player what the village did, above the hotbar so a counter is not a chat log. */
    private static void say(ServerPlayer player, String message, ChatFormatting colour) {
        player.displayClientMessage(Component.literal(message).withStyle(colour), true);
    }
}
