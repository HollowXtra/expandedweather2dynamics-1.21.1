package com.imbet.volcano;

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
 * The level's own record of its volcanoes.
 *
 * <p>A volcano is the longest-lived thing this mod owns: it is a mountain with a history, and its phase, its
 * ash, its half-finished lava flows and the size of its cone are all things a player will come back to find in
 * the same state. Deriving it from the terrain on load would lose the only part that matters - what it is doing
 * right now - so it is written to the mod's own file, exactly like the synoptic lows.
 */
public final class VolcanoStore extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_volcanoes";
    private static final int MAX_VOLCANOES = 32;

    private static final Factory<VolcanoStore> FACTORY =
            new Factory<>(VolcanoStore::new, VolcanoStore::load);

    private final List<Volcano> volcanoes = new ArrayList<>();
    private long nextId = 1L;
    private long lastGenesisTick = Long.MIN_VALUE;

    public static VolcanoStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public List<Volcano> volcanoes() {
        return volcanoes;
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

    public boolean hasRoom() {
        return volcanoes.size() < MAX_VOLCANOES;
    }

    public void add(Volcano volcano) {
        volcanoes.add(volcano);
        setDirty();
    }

    /** Drops a volcano the world no longer wants, which only ever happens when it is removed on purpose. */
    public boolean remove(long id) {
        boolean removed = volcanoes.removeIf(volcano -> volcano.id() == id);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public Volcano byId(long id) {
        for (Volcano volcano : volcanoes) {
            if (volcano.id() == id) {
                return volcano;
            }
        }
        return null;
    }

    /** The closest volcano to a point, or null when there is none within the given reach. */
    public Volcano nearest(double x, double z, double maxDistance) {
        Volcano closest = null;
        double closestSqr = maxDistance * maxDistance;
        for (Volcano volcano : volcanoes) {
            double dx = volcano.x() - x;
            double dz = volcano.z() - z;
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < closestSqr) {
                closestSqr = distanceSqr;
                closest = volcano;
            }
        }
        return closest;
    }

    public void markDirty() {
        setDirty();
    }

    private static VolcanoStore load(CompoundTag tag, HolderLookup.Provider registries) {
        VolcanoStore store = new VolcanoStore();
        store.nextId = Math.max(1L, tag.getLong("NextId"));
        store.lastGenesisTick = tag.contains("LastGenesis") ? tag.getLong("LastGenesis") : Long.MIN_VALUE;
        ListTag list = tag.getList("Volcanoes", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            Volcano volcano = Volcano.load(list.getCompound(index));
            if (!volcano.dead() && store.volcanoes.size() < MAX_VOLCANOES) {
                store.volcanoes.add(volcano);
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
        for (Volcano volcano : volcanoes) {
            list.add(volcano.save());
        }
        tag.put("Volcanoes", list);
        return tag;
    }
}
