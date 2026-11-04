package com.imbet.photo;

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
 * The camera: right-click to take a photograph of whatever the view is centred on, sneak and right-click to
 * read the album.
 *
 * <p>The two modes are on one item rather than two because they are the same object in the player's head - the
 * thing that takes the picture is the thing the pictures are in - and because a camera that could only take
 * photographs would leave its own album reachable only from a block.
 *
 * <p>Nothing is decided here. The shutter needs two things the server cannot see - which way the camera was
 * pointed and how wide its view was - so the client sends them; the server validates the player, measures the
 * world and scores the frame. A camera fired by something other than a client therefore produces nothing,
 * which is the correct answer to a photograph nobody looked through.
 */
public class CameraItem extends Item {
    public CameraItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            if (!player.isShiftKeyDown()) {
                // The one client-side call in the item, guarded by the only side that can make it. The aim and
                // the field of view live in the client's options, and the server is told them - it does not ask.
                com.imbet.client.PhotoCaptureClient.capture(player);
            }
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) {
                PhotoService.openAlbum(serverPlayer);
            }
            return InteractionResultHolder.success(stack);
        }
        // Not sneaking: the shot itself is filed by the packet the client sent, which carries the camera's aim.
        // Succeeding here without doing anything is what keeps a client that never sent one from taking a
        // photograph of the inside of its own head.
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.expandedweather2dynamics.camera.tooltip")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.expandedweather2dynamics.camera.tooltip_album")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
