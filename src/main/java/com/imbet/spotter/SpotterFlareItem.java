package com.imbet.spotter;

import com.imbet.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The spotter flare: the thing a player fires when they see a funnel, which is the only way the mod learns
 * something its own scan cannot see yet.
 *
 * <p>A flare and not a radio message, because a report is a sighting: it is fired at the thing you are looking
 * at, from where you are standing, and it is visible going up over the funnel. That also makes the item's own
 * rules legible without a manual - you cannot report a tornado you are not near, and nothing is spent when
 * there is nothing to report, so the flare is never a resource you are punished for testing.
 */
public class SpotterFlareItem extends Item {
    public SpotterFlareItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return InteractionResultHolder.fail(stack);
        }
        SpotterService.Outcome outcome = SpotterService.report(serverPlayer);
        if (outcome != SpotterService.Outcome.FILED) {
            // Nothing was filed, so nothing is spent and no cooldown is started: a flare that costs you a
            // report slot for firing at the wrong cloud would teach players not to test the item at all.
            return InteractionResultHolder.fail(stack);
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        int cooldown = Math.max(0, Config.SPOTTER_COOLDOWN_TICKS.get());
        if (cooldown > 0) {
            serverPlayer.getCooldowns().addCooldown(this, cooldown);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.expandedweather2dynamics.spotter_flare.tooltip")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
