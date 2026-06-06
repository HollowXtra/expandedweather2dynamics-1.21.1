package com.imbet.survey;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ChaserSurveySavedData extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_chaser_surveys";
    private static final Factory<ChaserSurveySavedData> FACTORY = new Factory<>(ChaserSurveySavedData::new, ChaserSurveySavedData::load);
    private final List<Entry> entries = new ArrayList<>();

    public static ChaserSurveySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Entry record(ServerPlayer player, String trackId, int ratedEf, int trueEf, int suggestedMph, List<DamageIndicator> indicators, long gameTime) {
        UUID uuid = player.getUUID();
        entries.removeIf(entry -> entry.playerId().equals(uuid) && entry.trackId().equals(trackId));
        int accuracy = accuracy(ratedEf, trueEf, suggestedMph, indicators);
        Entry entry = new Entry(uuid, player.getGameProfile().getName(), trackId, ratedEf, trueEf, accuracy, gameTime, List.copyOf(indicators), badgeFor(uuid, accuracy));
        entries.add(entry);
        if (entries.size() > 2048) {
            entries.remove(0);
        }
        setDirty();
        return entry;
    }

    public boolean alreadySubmitted(ServerPlayer player, String trackId) {
        UUID uuid = player.getUUID();
        for (Entry entry : entries) {
            if (entry.playerId().equals(uuid) && entry.trackId().equals(trackId)) {
                return true;
            }
        }
        return false;
    }

    private String badgeFor(UUID playerId, int latestAccuracy) {
        int excellent = latestAccuracy >= 90 ? 1 : 0;
        int nearPerfect = latestAccuracy >= 95 ? 1 : 0;
        for (Entry entry : entries) {
            if (!entry.playerId().equals(playerId)) {
                continue;
            }
            if (entry.accuracy() >= 90) {
                excellent++;
            }
            if (entry.accuracy() >= 95) {
                nearPerfect++;
            }
        }
        if (nearPerfect >= 10) {
            return "Forensic Chaser";
        }
        if (excellent >= 3) {
            return "Surveyor";
        }
        if (latestAccuracy >= 95) {
            return "Sharp Survey";
        }
        return "";
    }

    private static int accuracy(int ratedEf, int trueEf, int suggestedMph, List<DamageIndicator> indicators) {
        int tierPenalty = Math.abs(ratedEf - trueEf) * 18;
        int evidenceBonus = Math.min(10, indicators.size() * 2);
        int suggestionPenalty = suggestedMph <= 0 ? 0 : Math.min(18, Math.abs(suggestedMph - mphForEf(trueEf)) / 5);
        return Math.max(0, Math.min(100, 100 - tierPenalty - suggestionPenalty + evidenceBonus));
    }

    private static int mphForEf(int ef) {
        return switch (ef) {
            case 5 -> 201;
            case 4 -> 166;
            case 3 -> 136;
            case 2 -> 111;
            case 1 -> 86;
            default -> 65;
        };
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            list.add(entry.save());
        }
        tag.put("Entries", list);
        return tag;
    }

    private static ChaserSurveySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ChaserSurveySavedData data = new ChaserSurveySavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.entries.add(Entry.load(list.getCompound(i)));
        }
        return data;
    }

    public record Entry(UUID playerId, String playerName, String trackId, int ratedEf, int trueEf, int accuracy, long gameTime, List<DamageIndicator> indicators, String badge) {
        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("PlayerId", playerId);
            tag.putString("PlayerName", playerName == null ? "" : playerName);
            tag.putString("TrackId", trackId == null ? "" : trackId);
            tag.putInt("RatedEf", ratedEf);
            tag.putInt("TrueEf", trueEf);
            tag.putInt("Accuracy", accuracy);
            tag.putLong("GameTime", gameTime);
            tag.putString("Badge", badge == null ? "" : badge);
            ListTag indicatorList = new ListTag();
            for (DamageIndicator indicator : indicators) {
                CompoundTag indicatorTag = new CompoundTag();
                indicatorTag.putString("Id", indicator.name());
                indicatorList.add(indicatorTag);
            }
            tag.put("Indicators", indicatorList);
            return tag;
        }

        private static Entry load(CompoundTag tag) {
            List<DamageIndicator> indicators = new ArrayList<>();
            ListTag indicatorList = tag.getList("Indicators", Tag.TAG_COMPOUND);
            for (int i = 0; i < indicatorList.size(); i++) {
                try {
                    indicators.add(DamageIndicator.valueOf(indicatorList.getCompound(i).getString("Id").toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Skip bad saved indicator ids.
                }
            }
            return new Entry(
                    tag.hasUUID("PlayerId") ? tag.getUUID("PlayerId") : new UUID(0L, 0L),
                    tag.getString("PlayerName"),
                    tag.getString("TrackId"),
                    tag.getInt("RatedEf"),
                    tag.getInt("TrueEf"),
                    tag.getInt("Accuracy"),
                    tag.getLong("GameTime"),
                    List.copyOf(indicators),
                    tag.getString("Badge")
            );
        }
    }
}
