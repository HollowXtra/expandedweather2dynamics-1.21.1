package com.imbet.photo;

import com.imbet.registry.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The album screen's menu: a way to get one player's album to their own client, and nothing else.
 *
 * <p>A menu rather than a payload because the album is player state the server owns and the screen has to be
 * given a snapshot of it, which is precisely what the menu-open path does here: the server writes the album
 * into the open buffer, the client reads it back into a real {@link PhotoAlbum}. The client therefore draws
 * the same object type the server files into, and no second schema can drift away from the stored one.
 *
 * <p>The capacity is the gallery's, not a real container's: nothing is moved, so nothing has slots.
 */
public class PhotoAlbumMenu extends AbstractContainerMenu {
    private final PhotoAlbum album;
    private final long nowTick;

    public PhotoAlbumMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, read(buf), buf.readVarLong());
    }

    public PhotoAlbumMenu(int containerId, Inventory inventory, PhotoAlbum album) {
        this(containerId, inventory, album, 0L);
    }

    private PhotoAlbumMenu(int containerId, Inventory inventory, PhotoAlbum album, long nowTick) {
        super(ModContent.PHOTO_ALBUM_MENU.get(), containerId);
        this.album = album == null ? new PhotoAlbum() : album;
        this.nowTick = nowTick;
    }

    /** The album as the server had it when the screen was opened. */
    public PhotoAlbum album() {
        return album;
    }

    /** The server's game time when the album was sent, which is what its ages are counted from. */
    public long nowTick() {
        return nowTick;
    }

    @Override
    public boolean stillValid(Player player) {
        // The album lives in the camera: put it away and the screen closes, the same rule the handheld radar
        // uses. A player who dies or drops the camera should not keep reading a screen they no longer hold.
        return player.getMainHandItem().is(ModContent.CAMERA_ITEM.get())
                || player.getOffhandItem().is(ModContent.CAMERA_ITEM.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * Writes an album to the open buffer. The lifetime counters travel with the gallery rather than being
     * recomputed from it, because the gallery is capped and the counters are not.
     */
    public static void write(RegistryFriendlyByteBuf buf, PhotoAlbum album, long nowTick) {
        PhotoAlbum source = album == null ? new PhotoAlbum() : album;
        buf.writeVarInt(source.shotsTaken());
        buf.writeVarInt(source.totalSum());
        int[] grades = source.grades();
        for (int grade : grades) {
            buf.writeVarInt(grade);
        }
        List<PhotoShot> gallery = source.gallery();
        buf.writeVarInt(gallery.size());
        for (PhotoShot shot : gallery) {
            writeShot(buf, shot);
        }
        buf.writeVarLong(nowTick);
    }

    private static PhotoAlbum read(RegistryFriendlyByteBuf buf) {
        int shotsTaken = buf.readVarInt();
        int totalSum = buf.readVarInt();
        int[] grades = new int[PhotoAlbum.GRADE_COUNT];
        for (int index = 0; index < grades.length; index++) {
            grades[index] = buf.readVarInt();
        }
        int size = Math.min(256, Math.max(0, buf.readVarInt()));
        List<PhotoShot> gallery = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            gallery.add(readShot(buf));
        }
        PhotoAlbum album = new PhotoAlbum();
        album.restore(shotsTaken, totalSum, grades, gallery);
        return album;
    }

    private static void writeShot(RegistryFriendlyByteBuf buf, PhotoShot shot) {
        buf.writeUtf(shot.targetKey(), 64);
        buf.writeUtf(shot.targetLabel(), 64);
        buf.writeDouble(shot.stormMph());
        buf.writeDouble(shot.distanceBlocks());
        buf.writeDouble(shot.offAxisFraction());
        buf.writeDouble(shot.occlusionFraction());
        buf.writeVarInt(shot.framing());
        buf.writeVarInt(shot.proximity());
        buf.writeVarInt(shot.subject());
        buf.writeVarInt(shot.structure());
        buf.writeVarInt(shot.atmosphere());
        buf.writeVarInt(shot.depth());
        buf.writeVarInt(shot.total());
        buf.writeVarLong(shot.gameTime());
        buf.writeVarLong(shot.dayTime());
        buf.writeBoolean(shot.lightning());
        buf.writeBoolean(shot.raining());
        buf.writeBoolean(shot.insideCore());
    }

    private static PhotoShot readShot(RegistryFriendlyByteBuf buf) {
        return new PhotoShot(
                buf.readUtf(64),
                buf.readUtf(64),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readVarLong(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean()
        );
    }
}
