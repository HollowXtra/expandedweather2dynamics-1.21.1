package com.imbet.radar;

import com.imbet.Config;
import com.imbet.network.NetworkSafety;
import com.imbet.network.RadarSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

public class PortableRadarItem extends Item {
    public PortableRadarItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        BlockPos origin = player.blockPosition().immutable();
        int range = Math.max(512, Math.min(Config.RADAR_RANGE_BLOCKS.get(), 4096));
        List<RadarStormSnapshot> snapshots = RadarService.buildSnapshot(serverLevel, origin, range);
        NetworkSafety.sendToPlayer(serverPlayer, new RadarSnapshotPayload(origin, snapshots));
        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, openedPlayer) -> new RadarMenu(containerId, inventory, origin),
                Component.translatable("item.expandedweather2dynamics.portable_radar")
        ), buffer -> buffer.writeBlockPos(origin));
        player.getCooldowns().addCooldown(this, 10);
        return InteractionResultHolder.success(stack);
    }
}
