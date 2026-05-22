package com.imbet.radar;

import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class RadarMenu extends AbstractContainerMenu {
    private final BlockPos radarPos;

    public RadarMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, buf.readBlockPos());
    }

    public RadarMenu(int containerId, Inventory inventory, BlockPos radarPos) {
        super(ModContent.RADAR_MENU.get(), containerId);
        this.radarPos = radarPos;
    }

    public BlockPos radarPos() {
        return radarPos;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(radarPos).is(ModContent.RADAR_BLOCK.get())
                && player.distanceToSqr(radarPos.getX() + 0.5D, radarPos.getY() + 0.5D, radarPos.getZ() + 0.5D) <= 4096.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
