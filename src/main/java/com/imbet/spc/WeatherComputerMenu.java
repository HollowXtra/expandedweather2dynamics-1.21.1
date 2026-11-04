package com.imbet.spc;

import com.imbet.Config;
import com.imbet.network.NetworkSafety;
import com.imbet.network.RadarSnapshotPayload;
import com.imbet.radar.RadarService;
import com.imbet.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * The weather computer's screen, as a menu.
 *
 * <p>It has no slots, because there is nothing in a weather computer to move about. What it has instead is a
 * refresh: the same tick the game already spends asking a container what changed is the tick this station pushes
 * its three tabs to whoever is reading it, and the radar sweep for its own scope. That is the whole reason it is
 * a menu rather than a screen opened directly, and it is the same mechanism the radar block and the handheld
 * already use - so a computer that is open costs a player one refresh a second, and a computer nobody is looking
 * at costs nothing at all.
 *
 * <p>The sweep is centred on the computer's own block. A station's scope is its own: the alternative would be to
 * hunt for a radar block somewhere in the loaded world and show someone else's sweep, which would make the range
 * ring on the screen mean one thing on one computer and another on the next.
 */
public class WeatherComputerMenu extends AbstractContainerMenu {

    /** How often the tabs are refreshed, in ticks. A screen is read at a human pace, so this is not a tick rate. */
    public static final int REFRESH_TICKS = 20;

    private final BlockPos computerPos;
    private final Player player;
    private long lastPush = Long.MIN_VALUE;

    public WeatherComputerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, buf.readBlockPos());
    }

    public WeatherComputerMenu(int containerId, Inventory inventory, BlockPos computerPos) {
        super(ModContent.WEATHER_COMPUTER_MENU.get(), containerId);
        this.computerPos = computerPos;
        // The inventory is the player's own, so the menu can find its owner without the block having to be
        // touched by every refresh: the open menu is already the only thing that knows who is looking.
        this.player = inventory.player;
    }

    public BlockPos computerPos() {
        return computerPos;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player == null || player.level().getBlockState(computerPos).getBlock() != ModContent.WEATHER_COMPUTER_BLOCK.get()) {
            return false;
        }
        return player.distanceToSqr(computerPos.getX() + 0.5D, computerPos.getY() + 0.5D, computerPos.getZ() + 0.5D) <= 4096.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // There are no slots, so a shift-click has nowhere to go and nothing to move.
        return ItemStack.EMPTY;
    }

    /**
     * The station's own tick: the game has already asked what changed, so this answers with what the computer
     * knows, once a second.
     *
     * <p>It also sends the scope. A tab that says "Live Radar" and then shows nothing until the player walks to a
     * radar block would not be live, and the sweep is the one thing on this screen that has to arrive on its own.
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (lastPush != Long.MIN_VALUE && now - lastPush < REFRESH_TICKS) {
            return;
        }
        lastPush = now;
        NetworkSafety.sendToPlayer(serverPlayer, ComputerFeed.build(level, serverPlayer, computerPos));
        int range = Config.RADAR_RANGE_BLOCKS.get();
        NetworkSafety.sendToPlayer(serverPlayer, new RadarSnapshotPayload(computerPos, false, 0, range,
                RadarService.buildSnapshot(level, computerPos, range)));
    }
}
