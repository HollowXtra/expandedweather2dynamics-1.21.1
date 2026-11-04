package com.imbet.chase;

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
 * Every player's chase log, saved with the world.
 *
 * <p>A chase is a fact about a player, not about a storm, so it is stored per player and survives the storm
 * that produced it: the tornado is gone by the time the line is worth reading. The schema keeps the lifetime
 * totals as their own fields rather than deriving them from the stored encounters, because only a capped
 * number of recent encounters is kept - see {@link ChaseLedger}, which is where that reasoning lives.
 *
 * <p>Records are stored as their own small compounds and read back field for field. A missing or corrupt
 * record is skipped rather than dropping the whole ledger: a saved world that loses one tornado's numbers
 * should lose that tornado, not the player's history.
 */
public final class ChaseLogSavedData extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_chase_log";
    private static final Factory<ChaseLogSavedData> FACTORY =
            new Factory<>(ChaseLogSavedData::new, ChaseLogSavedData::load);
    private final Map<UUID, ChaseLedger> ledgers = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();

    public static ChaseLogSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * The player's ledger, created on first use. Read-only callers get an empty ledger rather than null, so
     * a computer that is right-clicked before any storm has ever been logged still answers.
     */
    public ChaseLedger ledger(UUID playerId) {
        if (playerId == null) {
            return new ChaseLedger();
        }
        ChaseLedger existing = ledgers.get(playerId);
        if (existing != null) {
            return existing;
        }
        ChaseLedger created = new ChaseLedger();
        ledgers.put(playerId, created);
        names.putIfAbsent(playerId, "");
        setDirty();
        return created;
    }

    /** Folds one finished chase into the player's ledger and keeps the recent list to its cap. */
    public void record(ServerPlayer player, ChaseRecord record, int maxRecent) {
        if (player == null || record == null) {
            return;
        }
        ChaseLedger ledger = ledger(player.getUUID());
        ledger.note(record);
        ledger.trim(maxRecent);
        names.put(player.getUUID(), player.getGameProfile().getName());
        setDirty();
    }

    /** The player's name as of the last chase, for a readout that has to name somebody else's record. */
    public String nameOf(UUID playerId) {
        return names.getOrDefault(playerId, "");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ChaseLedger> entry : ledgers.entrySet()) {
            ChaseLedger ledger = entry.getValue();
            CompoundTag ledgerTag = new CompoundTag();
            ledgerTag.putUUID("Player", entry.getKey());
            ledgerTag.putString("Name", names.getOrDefault(entry.getKey(), ""));
            ledgerTag.putInt("Storms", ledger.stormsLogged());
            ledgerTag.putDouble("WorstFelt", ledger.worstFeltMph());
            ledgerTag.putIntArray("Tiers", ledger.tierCountsCopy());
            writeRecord(ledgerTag, "WorstFeltRecord", ledger.worstFelt());
            writeRecord(ledgerTag, "Best", ledger.best());
            writeRecord(ledgerTag, "Closest", ledger.closest());
            writeRecord(ledgerTag, "Longest", ledger.longest());
            ListTag recent = new ListTag();
            for (ChaseRecord record : ledger.recent()) {
                recent.add(writeRecord(new CompoundTag(), record));
            }
            ledgerTag.put("Recent", recent);
            list.add(ledgerTag);
        }
        tag.put("Ledgers", list);
        return tag;
    }

    private static ChaseLogSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ChaseLogSavedData data = new ChaseLogSavedData();
        ListTag list = tag.getList("Ledgers", Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag ledgerTag = list.getCompound(index);
            if (!ledgerTag.hasUUID("Player")) {
                continue;
            }
            UUID playerId = ledgerTag.getUUID("Player");
            List<ChaseRecord> recent = new ArrayList<>();
            ListTag recentTag = ledgerTag.getList("Recent", Tag.TAG_COMPOUND);
            for (int entry = 0; entry < recentTag.size(); entry++) {
                ChaseRecord record = readRecord(recentTag.getCompound(entry));
                if (record != null) {
                    recent.add(record);
                }
            }
            ChaseLedger ledger = new ChaseLedger();
            ledger.restore(
                    ledgerTag.getInt("Storms"),
                    ledgerTag.getDouble("WorstFelt"),
                    ledgerTag.getIntArray("Tiers"),
                    readRecord(ledgerTag.getCompound("WorstFeltRecord")),
                    readRecord(ledgerTag.getCompound("Best")),
                    readRecord(ledgerTag.getCompound("Closest")),
                    readRecord(ledgerTag.getCompound("Longest")),
                    recent
            );
            data.ledgers.put(playerId, ledger);
            data.names.put(playerId, ledgerTag.getString("Name"));
        }
        return data;
    }

    /** Writes one encounter. An absent record is written as an empty compound and read back as null. */
    private static CompoundTag writeRecord(CompoundTag parent, String key, ChaseRecord record) {
        parent.put(key, writeRecord(new CompoundTag(), record));
        return parent;
    }

    private static CompoundTag writeRecord(CompoundTag tag, ChaseRecord record) {
        if (record == null) {
            return tag;
        }
        tag.putBoolean("Present", true);
        tag.putString("Key", record.key() == null ? "" : record.key());
        tag.putString("Label", record.label() == null ? "" : record.label());
        tag.putLong("Start", record.startTick());
        tag.putLong("End", record.endTick());
        tag.putDouble("StormMph", record.stormPeakMph());
        tag.putDouble("FeltMph", record.feltPeakMph());
        tag.putDouble("Closest", record.closestBlocks());
        tag.putInt("CoreTicks", record.coreTicks());
        tag.putInt("Observations", record.observations());
        return tag;
    }

    private static ChaseRecord readRecord(CompoundTag tag) {
        if (tag == null || !tag.getBoolean("Present")) {
            return null;
        }
        try {
            return new ChaseRecord(
                    tag.getString("Key"),
                    tag.getString("Label"),
                    tag.getLong("Start"),
                    tag.getLong("End"),
                    tag.getDouble("StormMph"),
                    tag.getDouble("FeltMph"),
                    tag.getDouble("Closest"),
                    tag.getInt("CoreTicks"),
                    tag.getInt("Observations")
            );
        } catch (RuntimeException failure) {
            // A record this mod cannot read is dropped rather than taking the ledger with it.
            return null;
        }
    }
}
