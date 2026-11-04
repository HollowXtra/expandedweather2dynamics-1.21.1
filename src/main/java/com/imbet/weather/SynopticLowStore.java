package com.imbet.weather;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The level's own record of its synoptic lows.
 *
 * <p>A low lives for half an in-game day and drifts thousands of blocks while it does, so it cannot
 * be anything but persistent state: rebuilding lows from the storms around them on every reload -
 * which is what the old derived version effectively did - threw away the identity, the age and the
 * occlusion state of a system that the barometer, the forecast plate and the readout all describe.
 * This is that state, written into a file the mod owns, so the same low is still there, at the same
 * age, after the world is reloaded.
 */
public final class SynopticLowStore extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_synoptic_lows";

    private static final Factory<SynopticLowStore> FACTORY =
            new Factory<>(SynopticLowStore::new, SynopticLowStore::load);

    private final List<SynopticLow> lows = new ArrayList<>();
    private long nextId = 1L;
    private long lastGenesisTick = Long.MIN_VALUE;

    public static SynopticLowStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public List<SynopticLow> lows() {
        return lows;
    }

    public long nextId() {
        return nextId++;
    }

    public long lastGenesisTick() {
        return lastGenesisTick;
    }

    public void setLastGenesisTick(long gameTime) {
        lastGenesisTick = gameTime;
        setDirty();
    }

    /** Removes the lows that have filled in and run their course. */
    public void pruneFinished() {
        if (lows.removeIf(SynopticLow::finished)) {
            setDirty();
        }
    }

    public void add(SynopticLow low) {
        lows.add(low);
        setDirty();
    }

    private static SynopticLowStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SynopticLowStore store = new SynopticLowStore();
        store.nextId = Math.max(1L, tag.getLong("NextId"));
        store.lastGenesisTick = tag.contains("LastGenesis") ? tag.getLong("LastGenesis") : Long.MIN_VALUE;
        ListTag list = tag.getList("Lows", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            SynopticLow low = SynopticLow.load(list.getCompound(index));
            if (!low.finished()) {
                store.lows.add(low);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextId", nextId);
        if (lastGenesisTick != Long.MIN_VALUE) {
            tag.putLong("LastGenesis", lastGenesisTick);
        }
        ListTag list = new ListTag();
        for (SynopticLow low : lows) {
            list.add(low.save());
        }
        tag.put("Lows", list);
        return tag;
    }
}
