package com.imbet.radar;

import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * The radar screen, whether it was opened at a weather radar block or by a handheld.
 *
 * <p>{@code radarPos} is what the picture is swept from: a radar block in the world when the handheld linked
 * to one or the block itself opened the screen, and the player's own feet for the handheld's fallback sweep.
 */
public class RadarMenu extends AbstractContainerMenu {
    private final BlockPos radarPos;

    public RadarMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, buf.readBlockPos());
    }

    public RadarMenu(int containerId, Inventory inventory, BlockPos radarPos) {
        super(ModContent.RADAR_MENU.get(), containerId);
        this.radarPos = radarPos;
    }

    /** The position the picture is swept from: a weather radar block, or the handheld's own spot. */
    public BlockPos radarPos() {
        return radarPos;
    }

    @Override
    public boolean stillValid(Player player) {
        // A handheld keeps its link wherever its owner walks, which is the point of holding one rather than
        // standing at the block. A screen opened at a block closes when the player leaves it or the block
        // goes; a fallback sweep lives as long as the item that is sweeping it.
        if (holdsRadar(player)) {
            return true;
        }
        return player.level().getBlockEntity(radarPos) instanceof RadarBlockEntity
                && player.distanceToSqr(radarPos.getX() + 0.5D, radarPos.getY() + 0.5D, radarPos.getZ() + 0.5D) <= 4096.0D;
    }

    private static boolean holdsRadar(Player player) {
        return player.getMainHandItem().is(ModContent.PORTABLE_RADAR_ITEM.get())
                || player.getOffhandItem().is(ModContent.PORTABLE_RADAR_ITEM.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
