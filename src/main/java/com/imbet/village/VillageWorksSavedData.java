package com.imbet.village;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * What a village has built, and where.
 *
 * <p>A village has to know its own works, or the answer to "what is owed next" is a guess: a house built
 * and then forgotten is a house built twice, on top of itself. So each finished building is recorded with
 * the corner it stands on, the way it faces, and its footprint, and the plan is counted from this list
 * rather than from what the builder happens to remember.
 *
 * <p>Recorded is not the same as standing. A work is checked before it counts - the door cell of the
 * blueprint - and one whose door is gone is forgotten rather than counted, so a village that has been
 * flattened by the next tornado is a village that gets to rebuild, instead of one that believes it already
 * has a house.
 */
public final class VillageWorksSavedData extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_village_works";

    /** The most works kept per level: a village's worth of buildings, with room to spare. */
    private static final int MAX_WORKS = 256;

    private static final Factory<VillageWorksSavedData> FACTORY =
            new Factory<>(VillageWorksSavedData::new, VillageWorksSavedData::load);

    private final List<Work> works = new ArrayList<>();

    /** One finished building: what it is, where its corner is, and how it faces. */
    public record Work(String building, int x, int y, int z, int facing, int width, int depth, long gameTime) {
        public BlockPos origin() {
            return new BlockPos(x, y, z);
        }

        public boolean within(BlockPos center, int radius) {
            double dx = x - center.getX();
            double dz = z - center.getZ();
            return dx * dx + dz * dz <= (double) radius * radius;
        }

        public boolean touches(int otherX, int otherZ, int otherWidth, int otherDepth) {
            return BuildSite.overlaps(x, z, width, depth, otherX, otherZ, otherWidth, otherDepth);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Building", building);
            tag.putInt("X", x);
            tag.putInt("Y", y);
            tag.putInt("Z", z);
            tag.putInt("Facing", facing);
            tag.putInt("Width", width);
            tag.putInt("Depth", depth);
            tag.putLong("GameTime", gameTime);
            return tag;
        }

        private static Work load(CompoundTag tag) {
            return new Work(tag.getString("Building"), tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"),
                    tag.getInt("Facing"), tag.getInt("Width"), tag.getInt("Depth"), tag.getLong("GameTime"));
        }
    }

    public static VillageWorksSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public void record(String building, BlockPos origin, int facing, int width, int depth, long gameTime) {
        works.removeIf(work -> work.building().equals(building) && work.origin().equals(origin));
        works.add(new Work(building, origin.getX(), origin.getY(), origin.getZ(), facing, width, depth, gameTime));
        while (works.size() > MAX_WORKS) {
            works.remove(0);
        }
        setDirty();
    }

    public void forget(Work work) {
        if (works.remove(work)) {
            setDirty();
        }
    }

    public List<Work> works() {
        return List.copyOf(works);
    }

    /** How many of one building the village has within reach of this point. */
    public int count(String building, BlockPos center, int radius) {
        int found = 0;
        for (Work work : works) {
            if (work.building().equals(building) && work.within(center, radius)) {
                found++;
            }
        }
        return found;
    }

    /** Whether a footprint would stand on, or beside, a building the village already has. */
    public boolean touchesAny(BlockPos center, int radius, int x, int z, int width, int depth) {
        for (Work work : works) {
            if (work.within(center, radius) && work.touches(x, z, width, depth)) {
                return true;
            }
        }
        return false;
    }

    public List<Work> near(BlockPos center, int radius) {
        List<Work> found = new ArrayList<>();
        for (Work work : works) {
            if (work.within(center, radius)) {
                found.add(work);
            }
        }
        return found;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Work work : works) {
            list.add(work.save());
        }
        tag.put("Works", list);
        return tag;
    }

    private static VillageWorksSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageWorksSavedData data = new VillageWorksSavedData();
        ListTag list = tag.getList("Works", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.works.add(Work.load(list.getCompound(i)));
        }
        return data;
    }
}
