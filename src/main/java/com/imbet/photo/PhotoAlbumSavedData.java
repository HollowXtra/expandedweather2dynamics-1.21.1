package com.imbet.photo;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's album, saved with the world.
 *
 * <p>An album is a fact about a player rather than about a storm, so it is stored per player and outlives the
 * tornado in it: the photograph is all that is left of the storm by the time anybody reads the album. The
 * lifetime counters are their own fields for the reason {@link PhotoAlbum} gives - the gallery is capped, so
 * the number of shots cannot be recovered from it.
 *
 * <p>Shots are stored as their own compounds, field for field, and a shot this mod cannot read is dropped
 * rather than taking the album with it. The grade is not among the stored fields: it is derived from the
 * total, which is the only number that decides it.
 */
public final class PhotoAlbumSavedData extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_photo_album";
    private static final Factory<PhotoAlbumSavedData> FACTORY =
            new Factory<>(PhotoAlbumSavedData::new, PhotoAlbumSavedData::load);
    private final Map<UUID, PhotoAlbum> albums = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();

    public static PhotoAlbumSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * A player's album, created on first use. A read-only caller gets an empty album rather than null, so a
     * computer right-clicked before anybody has taken a photograph still answers.
     */
    public PhotoAlbum album(UUID playerId) {
        if (playerId == null) {
            return new PhotoAlbum();
        }
        PhotoAlbum existing = albums.get(playerId);
        if (existing != null) {
            return existing;
        }
        PhotoAlbum created = new PhotoAlbum();
        albums.put(playerId, created);
        names.putIfAbsent(playerId, "");
        setDirty();
        return created;
    }

    /** Files one photograph into the player's album, keeping the gallery to its configured size. */
    public void record(ServerPlayer player, PhotoShot shot, int gallerySize) {
        if (player == null || shot == null) {
            return;
        }
        PhotoAlbum album = album(player.getUUID());
        album.note(shot, gallerySize);
        names.put(player.getUUID(), player.getGameProfile().getName());
        setDirty();
    }

    /** The name of the player an album belongs to, as of their last photograph. */
    public String nameOf(UUID playerId) {
        return names.getOrDefault(playerId, "");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PhotoAlbum> entry : albums.entrySet()) {
            PhotoAlbum album = entry.getValue();
            CompoundTag albumTag = new CompoundTag();
            albumTag.putUUID("Player", entry.getKey());
            albumTag.putString("Name", names.getOrDefault(entry.getKey(), ""));
            albumTag.putInt("Shots", album.shotsTaken());
            albumTag.putInt("TotalSum", album.totalSum());
            albumTag.putIntArray("Grades", album.grades());
            ListTag gallery = new ListTag();
            for (PhotoShot shot : album.gallery()) {
                gallery.add(writeShot(new CompoundTag(), shot));
            }
            albumTag.put("Gallery", gallery);
            list.add(albumTag);
        }
        tag.put("Albums", list);
        return tag;
    }

    private static PhotoAlbumSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PhotoAlbumSavedData data = new PhotoAlbumSavedData();
        ListTag list = tag.getList("Albums", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag albumTag = list.getCompound(index);
            if (!albumTag.hasUUID("Player")) {
                continue;
            }
            UUID playerId = albumTag.getUUID("Player");
            List<PhotoShot> gallery = new ArrayList<>();
            ListTag galleryTag = albumTag.getList("Gallery", Tag.TAG_COMPOUND);
            for (int entry = 0; entry < galleryTag.size(); entry++) {
                PhotoShot shot = readShot(galleryTag.getCompound(entry));
                if (shot != null) {
                    gallery.add(shot);
                }
            }
            PhotoAlbum album = new PhotoAlbum();
            album.restore(albumTag.getInt("Shots"), albumTag.getInt("TotalSum"),
                    albumTag.getIntArray("Grades"), gallery);
            // The cap is a setting, and it can be lowered between sessions: an album loaded under a smaller cap
            // is trimmed here, where the loader is the only thing that knows what the cap now is, rather than
            // staying over its own size until the player happens to take another photograph.
            album.trim(Config.PHOTO_ALBUM_SIZE.get());
            data.albums.put(playerId, album);
            data.names.put(playerId, albumTag.getString("Name"));
        }
        return data;
    }

    /** Writes one shot. Every field here has a matching read, which is what the harness checks. */
    private static CompoundTag writeShot(CompoundTag tag, PhotoShot shot) {
        if (shot == null) {
            return tag;
        }
        tag.putBoolean("Present", true);
        tag.putString("Key", shot.targetKey() == null ? "" : shot.targetKey());
        tag.putString("Label", shot.targetLabel() == null ? "" : shot.targetLabel());
        tag.putDouble("StormMph", shot.stormMph());
        tag.putDouble("Distance", shot.distanceBlocks());
        tag.putDouble("OffAxis", shot.offAxisFraction());
        tag.putDouble("Occlusion", shot.occlusionFraction());
        tag.putInt("Framing", shot.framing());
        tag.putInt("Proximity", shot.proximity());
        tag.putInt("Subject", shot.subject());
        tag.putInt("Structure", shot.structure());
        tag.putInt("Atmosphere", shot.atmosphere());
        tag.putInt("Depth", shot.depth());
        tag.putInt("Total", shot.total());
        tag.putLong("GameTime", shot.gameTime());
        tag.putLong("DayTime", shot.dayTime());
        tag.putBoolean("Lightning", shot.lightning());
        tag.putBoolean("Raining", shot.raining());
        tag.putBoolean("InsideCore", shot.insideCore());
        return tag;
    }

    private static PhotoShot readShot(CompoundTag tag) {
        if (tag == null || !tag.getBoolean("Present")) {
            return null;
        }
        try {
            return new PhotoShot(
                    tag.getString("Key"),
                    tag.getString("Label"),
                    tag.getDouble("StormMph"),
                    tag.getDouble("Distance"),
                    tag.getDouble("OffAxis"),
                    tag.getDouble("Occlusion"),
                    tag.getInt("Framing"),
                    tag.getInt("Proximity"),
                    tag.getInt("Subject"),
                    tag.getInt("Structure"),
                    tag.getInt("Atmosphere"),
                    tag.getInt("Depth"),
                    tag.getInt("Total"),
                    tag.getLong("GameTime"),
                    tag.getLong("DayTime"),
                    tag.getBoolean("Lightning"),
                    tag.getBoolean("Raining"),
                    tag.getBoolean("InsideCore")
            );
        } catch (RuntimeException failure) {
            // A photograph this mod cannot read is dropped rather than taking the album with it.
            return null;
        }
    }
}
