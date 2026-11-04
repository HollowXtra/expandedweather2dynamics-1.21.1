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

/**
 * The handheld display.
 *
 * <p>It is a remote screen for a weather radar rather than a radar of its own: using it finds the nearest
 * weather radar block within {@code radar.portableLinkRangeBlocks} and shows what that radar sees, in that
 * radar's product and at that radar's range, updating as the block sweeps. Two radars standing side by side
 * can then be trusted to agree - which they could not while the handheld swept a picture of its own, from the
 * player's position, at the configured range, with its own idea of the mode.
 *
 * <p>With no weather radar within reach it falls back to a local sweep at the configured range and says so
 * on the screen, so the item is not dead weight in a world that has not built one yet.
 */
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

        int linkRange = Config.PORTABLE_RADAR_LINK_RANGE_BLOCKS.get();
        RadarBlockEntity linked = RadarBlockEntity.findNearest(serverLevel, player.position(), linkRange);
        BlockPos origin;
        RadarSnapshotPayload payload;
        Component status;
        if (linked != null) {
            origin = linked.getBlockPos();
            int range = linked.effectiveRange();
            payload = new RadarSnapshotPayload(origin, true, linked.getMode(), range,
                    RadarService.buildSnapshot(serverLevel, origin, range));
            status = Component.translatable("message.expandedweather2dynamics.portable_radar_linked",
                    linked.getModeLabel(), range);
        } else {
            origin = player.blockPosition().immutable();
            int range = Math.max(512, Math.min(Config.RADAR_RANGE_BLOCKS.get(), 4096));
            payload = new RadarSnapshotPayload(origin, false, 0, range,
                    RadarService.buildSnapshot(serverLevel, origin, range));
            status = Component.translatable("message.expandedweather2dynamics.portable_radar_unlinked", linkRange);
        }

        NetworkSafety.sendToPlayer(serverPlayer, payload);
        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, openedPlayer) -> new RadarMenu(containerId, inventory, origin),
                Component.translatable("item.expandedweather2dynamics.portable_radar")
        ), buffer -> buffer.writeBlockPos(origin));
        serverPlayer.displayClientMessage(status, true);
        player.getCooldowns().addCooldown(this, 10);
        return InteractionResultHolder.success(stack);
    }
}
