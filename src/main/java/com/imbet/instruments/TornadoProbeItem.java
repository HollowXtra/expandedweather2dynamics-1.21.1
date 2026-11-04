package com.imbet.instruments;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * The deployable probe, as an item.
 *
 * <p>A probe that still holds a reading is not an ordinary block item: when it is mined its reading is
 * written into the stack's {@code block_entity_data} component, so it survives being picked up and is
 * restored onto the block when it is planted again. That means the round trip - deploy, get hit,
 * recover, re-deploy - keeps the data instead of quietly resetting it.
 *
 * <p>Two things make that visible in the inventory: a glint on a probe that is carrying a reading, and
 * a tooltip printing the same summary the block gives. Both come from
 * {@link TornadoProbeReading}, so an item and the block it came from can never disagree.
 */
public class TornadoProbeItem extends BlockItem {
    public TornadoProbeItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Glint on a probe holding a reading, so it is obvious which one in a stack to look at. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return TornadoProbeReading.recordedFromItem(stack) != null || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // The fitted kit is shown first and shown always, because it decides how the reading below it should
        // be read: the same tornado gives a different peak wind to a stock head than to a grade three one.
        ProbeUpgrades upgrades = ProbeUpgrades.of(stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag());
        tooltip.add(Component.literal(upgrades.bare() ? "Stock fit" : "Fitted: " + upgrades.label())
                .withStyle(upgrades.bare() ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD));
        if (!upgrades.bare()) {
            tooltip.add(Component.literal(upgrades.effectLabel()).withStyle(ChatFormatting.DARK_AQUA));
        }
        TornadoProbeReading reading = TornadoProbeReading.recordedFromItem(stack);
        if (reading == null) {
            tooltip.add(Component.literal("No reading recorded").withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("Plant it in a storm's path, then recover it")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        tooltip.add(Component.literal("Reading recorded - " + TornadoProbeReading.stateLabel(reading.state()))
                .withStyle(reading.overrun() ? ChatFormatting.RED : ChatFormatting.AQUA));
        for (String line : reading.summaryLines()) {
            tooltip.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
        }
        if (reading.overrun()) {
            tooltip.add(Component.literal("The wind destroyed it; re-arm it to use it again")
                    .withStyle(ChatFormatting.DARK_RED));
        }
    }
}
