package com.imbet.spotter;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every spotter report in force, every player's spotting record, and any bounty that could not be handed over
 * yet, saved with the world.
 *
 * <p>The reports have to persist because a warning that only exists while the reporter is logged in is not a
 * warning: a player files a report at a funnel and logs off, and the villages under it still need to shelter.
 * They also have to survive a reload for the same reason a chase does - the fact is about the world, not about
 * the session that produced it.
 *
 * <p>A bounty is held rather than dropped on the ground when the spotter is offline, because a reward that
 * evaporates because you were not standing there is worse than no reward: it teaches a player that the loop
 * only pays when the server is watching. Held bounty is paid out on the next login.
 *
 * <p>The schema is written and read field for field, and a report or ledger this mod cannot read is skipped
 * rather than taking the file with it.
 */
public final class SpotterStore extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_spotter_net";
    private static final Factory<SpotterStore> FACTORY = new Factory<>(SpotterStore::new, SpotterStore::load);

    private final Map<Long, SpotterReport> reports = new LinkedHashMap<>();
    private final Map<UUID, SpotterLedger> ledgers = new LinkedHashMap<>();
    private final Map<UUID, Integer> pendingBounty = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private long nextReportId = 1L;

    public static SpotterStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Files a report, giving it an identity of its own. */
    public SpotterReport file(SpotterReport report) {
        if (report == null) {
            return null;
        }
        SpotterReport stored = new SpotterReport(nextReportId++, report.reporterId(), report.reporterName(),
                report.x(), report.y(), report.z(), report.projectedX(), report.projectedZ(), report.label(),
                report.stormWindMph(), report.coreRadiusBlocks(), report.motionX(), report.motionZ(),
                report.filedTick(), report.expiresTick(), report.confirmedTick(), report.villagersWarned(),
                report.bountyEmeralds(), report.paid());
        reports.put(stored.id(), stored);
        ledger(stored.reporterId()).noteFiled(stored);
        names.put(stored.reporterId(), stored.reporterName() == null ? "" : stored.reporterName());
        setDirty();
        return stored;
    }

    /** Replaces a report that has changed, e.g. once it has been confirmed. */
    public void update(SpotterReport report) {
        if (report == null) {
            return;
        }
        reports.put(report.id(), report);
        setDirty();
    }

    /** Drops a report that has run out of time, recording it as a lapse. */
    public void lapse(SpotterReport report) {
        if (report == null) {
            return;
        }
        if (reports.remove(report.id()) != null) {
            ledger(report.reporterId()).noteLapsed(report);
            setDirty();
        }
    }

    /**
     * Retires a confirmed report: it stops warning anybody, and its outcome is filed in the reporter's ledger.
     * A confirmed report is not in force any more - it is a fact, and the storm's own warning is the thing to
     * believe from here on.
     */
    public void settle(SpotterReport report, int emeralds) {
        if (report == null) {
            return;
        }
        reports.remove(report.id());
        ledger(report.reporterId()).noteConfirmed(report, emeralds);
        setDirty();
    }

    /** The player's ledger, created on first use, so a readout never has to deal with null. */
    public SpotterLedger ledger(UUID playerId) {
        if (playerId == null) {
            return new SpotterLedger();
        }
        SpotterLedger existing = ledgers.get(playerId);
        if (existing != null) {
            return existing;
        }
        SpotterLedger created = new SpotterLedger();
        ledgers.put(playerId, created);
        setDirty();
        return created;
    }

    /** The reports still in force, oldest first. */
    public List<SpotterReport> activeReports(long nowTick) {
        List<SpotterReport> active = new ArrayList<>();
        for (SpotterReport report : reports.values()) {
            if (report.active(nowTick)) {
                active.add(report);
            }
        }
        return active;
    }

    /** Every report on file, in force or not, which is what a confirmation pass scans. */
    public List<SpotterReport> reports() {
        return List.copyOf(reports.values());
    }

    /** Adds a bounty that could not be handed over, for the next time the player is online. */
    public void holdBounty(UUID playerId, int emeralds) {
        if (playerId == null || emeralds <= 0) {
            return;
        }
        pendingBounty.merge(playerId, emeralds, Integer::sum);
        setDirty();
    }

    /** Takes the bounty held for a player, if any. */
    public int takeHeldBounty(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        Integer held = pendingBounty.remove(playerId);
        if (held == null || held <= 0) {
            return 0;
        }
        setDirty();
        return held;
    }

    public String nameOf(UUID playerId) {
        return names.getOrDefault(playerId, "");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextId", nextReportId);
        ListTag reportList = new ListTag();
        for (SpotterReport report : reports.values()) {
            reportList.add(writeReport(new CompoundTag(), report));
        }
        tag.put("Reports", reportList);
        ListTag ledgerList = new ListTag();
        for (Map.Entry<UUID, SpotterLedger> entry : ledgers.entrySet()) {
            SpotterLedger ledger = entry.getValue();
            CompoundTag ledgerTag = new CompoundTag();
            ledgerTag.putUUID("Player", entry.getKey());
            ledgerTag.putString("Name", names.getOrDefault(entry.getKey(), ""));
            ledgerTag.putInt("Filed", ledger.filed());
            ledgerTag.putInt("Confirmed", ledger.confirmed());
            ledgerTag.putInt("Lapsed", ledger.lapsed());
            ledgerTag.putInt("Bounty", ledger.bountyEmeralds());
            ledgerTag.putInt("Warned", ledger.villagersWarned());
            ledgerTag.putInt("Strongest", ledger.strongestConfirmedMph());
            writeReport(ledgerTag, ledger.last());
            ledgerList.add(ledgerTag);
        }
        tag.put("Ledgers", ledgerList);
        ListTag bountyList = new ListTag();
        for (Map.Entry<UUID, Integer> entry : pendingBounty.entrySet()) {
            CompoundTag bountyTag = new CompoundTag();
            bountyTag.putUUID("Player", entry.getKey());
            bountyTag.putInt("Emeralds", entry.getValue());
            bountyList.add(bountyTag);
        }
        tag.put("Pending", bountyList);
        return tag;
    }

    private static SpotterStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SpotterStore store = new SpotterStore();
        store.nextReportId = Math.max(1L, tag.getLong("NextId"));
        ListTag reportList = tag.getList("Reports", Tag.TAG_COMPOUND);
        for (int index = 0; index < reportList.size(); index++) {
            SpotterReport report = readReport(reportList.getCompound(index));
            if (report != null) {
                store.reports.put(report.id(), report);
            }
        }
        ListTag ledgerList = tag.getList("Ledgers", Tag.TAG_COMPOUND);
        for (int index = 0; index < ledgerList.size(); index++) {
            CompoundTag ledgerTag = ledgerList.getCompound(index);
            if (!ledgerTag.hasUUID("Player")) {
                continue;
            }
            UUID playerId = ledgerTag.getUUID("Player");
            SpotterLedger ledger = new SpotterLedger();
            ledger.restore(
                    ledgerTag.getInt("Filed"),
                    ledgerTag.getInt("Confirmed"),
                    ledgerTag.getInt("Lapsed"),
                    ledgerTag.getInt("Bounty"),
                    ledgerTag.getInt("Warned"),
                    ledgerTag.getInt("Strongest"),
                    readReport(ledgerTag)
            );
            store.ledgers.put(playerId, ledger);
            store.names.put(playerId, ledgerTag.getString("Name"));
        }
        ListTag bountyList = tag.getList("Pending", Tag.TAG_COMPOUND);
        for (int index = 0; index < bountyList.size(); index++) {
            CompoundTag bountyTag = bountyList.getCompound(index);
            if (!bountyTag.hasUUID("Player")) {
                continue;
            }
            store.pendingBounty.put(bountyTag.getUUID("Player"), Math.max(0, bountyTag.getInt("Emeralds")));
        }
        return store;
    }

    /**
     * Writes one report into a compound under the keys {@link #readReport} reads.
     *
     * <p>An absent report writes nothing at all and reads back as null, which is what a ledger with no report
     * to remember has.
     */
    private static CompoundTag writeReport(CompoundTag tag, SpotterReport report) {
        if (report == null) {
            return tag;
        }
        tag.putBoolean("HasReport", true);
        tag.putLong("Id", report.id());
        tag.putUUID("Reporter", report.reporterId());
        tag.putString("ReporterName", report.reporterName() == null ? "" : report.reporterName());
        tag.putDouble("X", report.x());
        tag.putDouble("Y", report.y());
        tag.putDouble("Z", report.z());
        tag.putDouble("ProjectedX", report.projectedX());
        tag.putDouble("ProjectedZ", report.projectedZ());
        tag.putString("Label", report.label() == null ? "" : report.label());
        tag.putInt("StormMph", report.stormWindMph());
        tag.putDouble("CoreRadius", report.coreRadiusBlocks());
        tag.putDouble("MotionX", report.motionX());
        tag.putDouble("MotionZ", report.motionZ());
        tag.putLong("Filed", report.filedTick());
        tag.putLong("Expires", report.expiresTick());
        tag.putLong("Confirmed", report.confirmedTick());
        tag.putInt("VillagersWarned", report.villagersWarned());
        tag.putInt("Bounty", report.bountyEmeralds());
        tag.putBoolean("Paid", report.paid());
        return tag;
    }

    private static SpotterReport readReport(CompoundTag tag) {
        if (tag == null || !tag.getBoolean("HasReport")) {
            return null;
        }
        try {
            return new SpotterReport(
                    tag.getLong("Id"),
                    tag.hasUUID("Reporter") ? tag.getUUID("Reporter") : null,
                    tag.getString("ReporterName"),
                    tag.getDouble("X"),
                    tag.getDouble("Y"),
                    tag.getDouble("Z"),
                    tag.getDouble("ProjectedX"),
                    tag.getDouble("ProjectedZ"),
                    tag.getString("Label"),
                    tag.getInt("StormMph"),
                    tag.getDouble("CoreRadius"),
                    tag.getDouble("MotionX"),
                    tag.getDouble("MotionZ"),
                    tag.getLong("Filed"),
                    tag.getLong("Expires"),
                    tag.getLong("Confirmed"),
                    tag.getInt("VillagersWarned"),
                    tag.getInt("Bounty"),
                    tag.getBoolean("Paid")
            );
        } catch (RuntimeException failure) {
            // A report this mod cannot read is dropped rather than taking the network's history with it.
            return null;
        }
    }
}
