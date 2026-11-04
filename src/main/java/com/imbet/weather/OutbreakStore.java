package com.imbet.weather;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The level's record of the outbreak it is running, if any.
 *
 * <p>An outbreak is a multi-minute, level-wide event: several cells, a corridor, a clock. Rebuilding
 * that from the storms on the ground after a reload cannot work, because the storms are the episode's
 * output rather than its state - so the episode itself is what is written down. A world reloaded
 * mid-outbreak comes back mid-outbreak, with the same corridor, the same clock and the same number of
 * cells already seeded, and the cells it has not seeded yet still to come.
 */
public final class OutbreakStore extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_outbreaks";

    private static final Factory<OutbreakStore> FACTORY =
            new Factory<>(OutbreakStore::new, OutbreakStore::load);

    private OutbreakService.Episode episode;
    private long nextId = 1L;
    private long lastEligibilityTick = Long.MIN_VALUE;

    public static OutbreakStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** The running episode, or null when no outbreak is on. */
    public OutbreakService.Episode episode() {
        return episode;
    }

    public void setEpisode(OutbreakService.Episode next) {
        episode = next;
        setDirty();
    }

    /** Ends the episode. Returns the one that ended, so a caller can report it, or null if none was on. */
    public OutbreakService.Episode end() {
        OutbreakService.Episode ended = episode;
        if (episode != null) {
            episode = null;
            setDirty();
        }
        return ended;
    }

    public long nextId() {
        return nextId++;
    }

    public long lastEligibilityTick() {
        return lastEligibilityTick;
    }

    public void setLastEligibilityTick(long gameTime) {
        lastEligibilityTick = gameTime;
        setDirty();
    }

    private static OutbreakStore load(CompoundTag tag, HolderLookup.Provider registries) {
        OutbreakStore store = new OutbreakStore();
        store.nextId = Math.max(1L, tag.getLong("NextId"));
        if (tag.contains("LastEligibility")) {
            store.lastEligibilityTick = tag.getLong("LastEligibility");
        }
        if (tag.contains("Episode")) {
            store.episode = OutbreakService.Episode.load(tag.getCompound("Episode"));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextId", nextId);
        if (lastEligibilityTick != Long.MIN_VALUE) {
            tag.putLong("LastEligibility", lastEligibilityTick);
        }
        if (episode != null) {
            tag.put("Episode", episode.save());
        }
        return tag;
    }
}
