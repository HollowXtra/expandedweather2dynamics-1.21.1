package com.imbet.weather;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A mod-owned mirror of each dynamic storm's addon state, so a rejoin can never lose the
 * tornado's dynamic size.
 *
 * <p>The primary persistence path writes the state into Weather2's own storm NBT
 * ({@link DynamicStormController#saveStateToStorm}) and reads it back when the storm object
 * is rebuilt. That path depends on Weather2's cache surviving the save/reload round trip.
 * This mirror is the safety net: the same snapshot is also written into the level's own
 * {@link SavedData} (a file the mod owns outright), and on the next tick after a storm
 * object appears without loaded state, the mirror restores it.
 *
 * <p>Entries are pruned by world time, so the map cannot grow unbounded.
 */
public final class StormAddonStateMirror extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_storm_addon_state";
    private static final Factory<StormAddonStateMirror> FACTORY =
            new Factory<>(StormAddonStateMirror::new, StormAddonStateMirror::load);
    /** Entries older than this are dropped on load and on prune. */
    private static final long MAX_AGE_TICKS = 20L * 60L * 60L * 4L;

    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    public static StormAddonStateMirror get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public void put(long stormId, CompoundTag snapshot, long gameTime) {
        entries.put(stormId, new Entry(snapshot, gameTime));
        setDirty();
    }

    /**
     * Returns the mirrored snapshot for this storm and removes it, so the restore happens
     * exactly once and a deliberately deleted storm cannot resurrect stale state forever.
     */
    public CompoundTag take(long stormId) {
        Entry entry = entries.remove(stormId);
        if (entry == null) {
            return null;
        }
        setDirty();
        return entry.snapshot();
    }

    public void prune(long gameTime) {
        boolean removed = entries.values().removeIf(entry -> gameTime - entry.savedAtTick() > MAX_AGE_TICKS);
        if (removed) {
            setDirty();
        }
    }

    public static void save(ServerLevel level, long stormId, DynamicStormState state, long gameTime) {
        get(level).put(stormId, state.save(), gameTime);
    }

    public static CompoundTag restore(ServerLevel level, long stormId) {
        return get(level).take(stormId);
    }

    private static StormAddonStateMirror load(CompoundTag tag, HolderLookup.Provider registries) {
        StormAddonStateMirror mirror = new StormAddonStateMirror();
        CompoundTag map = tag.getCompound("Entries");
        for (String key : map.getAllKeys()) {
            try {
                long stormId = Long.parseLong(key);
                CompoundTag entry = map.getCompound(key);
                mirror.entries.put(stormId, new Entry(entry.getCompound("Snapshot"), entry.getLong("SavedAt")));
            } catch (NumberFormatException ignored) {
                // A key we cannot parse is skipped, not fatal.
            }
        }
        return mirror;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag map = new CompoundTag();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.put("Snapshot", e.getValue().snapshot());
            entry.putLong("SavedAt", e.getValue().savedAtTick());
            map.put(Long.toString(e.getKey(), 36), entry);
        }
        tag.put("Entries", map);
        return tag;
    }

    private record Entry(CompoundTag snapshot, long savedAtTick) {
    }
}
