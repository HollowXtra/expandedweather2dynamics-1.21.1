package com.imbet.survey;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TornadoSurveySavedData extends SavedData {
    private static final String NAME = ExpandedWeather2Dynamics.MODID + "_tornado_surveys";
    private final Map<String, TornadoTrack> tracksByDisplayId = new LinkedHashMap<>();
    private final Map<String, String> displayIdBySource = new LinkedHashMap<>();
    private int nextId = 1;

    public static TornadoSurveySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TornadoSurveySavedData::load, TornadoSurveySavedData::new, NAME);
    }

    public TornadoTrack recordPoint(String sourceKey, long sourceStormId, long sourceCirculationId, long gameTime, Vec3 position, float width, double windMph, String subtype) {
        String displayId = displayIdBySource.get(sourceKey);
        TornadoTrack track = displayId == null ? null : tracksByDisplayId.get(displayId);
        if (track != null && track.endTick() + Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get() < gameTime) {
            displayIdBySource.remove(sourceKey);
            displayId = null;
            track = null;
        }
        if (track == null) {
            displayId = nextDisplayId();
            track = new TornadoTrack(displayId, sourceKey, sourceStormId, sourceCirculationId, gameTime, subtype);
            tracksByDisplayId.put(displayId, track);
            displayIdBySource.put(sourceKey, displayId);
        }
        track.addPoint(new TrackPoint(gameTime, position.x, position.y, position.z, width, (float) windMph, subtype));
        setDirty();
        return track;
    }

    public Optional<TornadoTrack> nearestTrack(Vec3 center, int radius, long gameTime, long maxAgeTicks) {
        return nearestTrack(center, radius, gameTime, maxAgeTicks, Config.SURVEY_RECENT_TRACK_WINDOW_TICKS.get());
    }

    public Optional<TornadoTrack> nearestTrack(Vec3 center, int radius, long gameTime, long maxAgeTicks, long recentWindowTicks) {
        double bestDistance = Double.MAX_VALUE;
        TornadoTrack best = null;
        double searchRadius = Math.max(radius, 1);
        long recentWindow = Math.max(20L, recentWindowTicks);
        for (TornadoTrack track : tracksByDisplayId.values()) {
            if (track.points().isEmpty() || track.endTick() + maxAgeTicks < gameTime || !track.hasPointSince(gameTime - recentWindow)) {
                continue;
            }
            double distance = track.distanceTo(center, gameTime - recentWindow);
            double allowed = Math.max(searchRadius, track.recentMaxWidthBlocks(gameTime - recentWindow) * 0.65D + searchRadius);
            if (distance <= allowed && distance < bestDistance) {
                bestDistance = distance;
                best = track;
            }
        }
        return Optional.ofNullable(best);
    }

    public void recordMerge(String absorberSourceKey, String absorbedSourceKey) {
        TornadoTrack absorber = trackBySource(absorberSourceKey);
        TornadoTrack absorbed = trackBySource(absorbedSourceKey);
        if (absorber == null || absorbed == null || absorber == absorbed) {
            return;
        }
        absorber.addMergeHistory("merged with " + absorbed.displayId());
        absorbed.addMergeHistory("absorbed by " + absorber.displayId());
        setDirty();
    }

    public TornadoTrack trackBySource(String sourceKey) {
        String displayId = displayIdBySource.get(sourceKey);
        return displayId == null ? null : tracksByDisplayId.get(displayId);
    }

    public List<TornadoTrack> tracks() {
        return List.copyOf(tracksByDisplayId.values());
    }

    public void markSurveyed(TornadoTrack track, String efRating, int estimatedMph, String confidence) {
        track.surveyed = true;
        track.finalEfRating = efRating;
        track.finalEstimatedMph = estimatedMph;
        track.finalConfidence = confidence;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("NextId", nextId);
        ListTag tracks = new ListTag();
        for (TornadoTrack track : tracksByDisplayId.values()) {
            tracks.add(track.save());
        }
        tag.put("Tracks", tracks);
        return tag;
    }

    private static TornadoSurveySavedData load(CompoundTag tag) {
        TornadoSurveySavedData data = new TornadoSurveySavedData();
        data.nextId = Math.max(1, tag.getInt("NextId"));
        ListTag tracks = tag.getList("Tracks", Tag.TAG_COMPOUND);
        for (int i = 0; i < tracks.size(); i++) {
            TornadoTrack track = TornadoTrack.load(tracks.getCompound(i));
            data.tracksByDisplayId.put(track.displayId(), track);
            data.displayIdBySource.put(track.sourceKey(), track.displayId());
        }
        return data;
    }

    private String nextDisplayId() {
        return "TOR-" + String.format(java.util.Locale.ROOT, "%04d", nextId++);
    }

    public static final class TornadoTrack {
        private final String displayId;
        private final String sourceKey;
        private final long sourceStormId;
        private final long sourceCirculationId;
        private final long startTick;
        private long endTick;
        private String primarySubtype;
        private float maxWidthBlocks;
        private float maxWindMph;
        private boolean surveyed;
        private String finalEfRating = "UNRATED";
        private int finalEstimatedMph;
        private String finalConfidence = "none";
        private final List<TrackPoint> points = new ArrayList<>();
        private final List<String> mergeHistory = new ArrayList<>();

        private TornadoTrack(String displayId, String sourceKey, long sourceStormId, long sourceCirculationId, long startTick, String primarySubtype) {
            this.displayId = displayId;
            this.sourceKey = sourceKey;
            this.sourceStormId = sourceStormId;
            this.sourceCirculationId = sourceCirculationId;
            this.startTick = startTick;
            this.endTick = startTick;
            this.primarySubtype = primarySubtype;
        }

        private void addPoint(TrackPoint point) {
            points.add(point);
            if (points.size() > 3600) {
                points.remove(0);
            }
            endTick = Math.max(endTick, point.tick());
            primarySubtype = point.subtype();
            maxWidthBlocks = Math.max(maxWidthBlocks, point.widthBlocks());
            maxWindMph = Math.max(maxWindMph, point.windMph());
        }

        private void addMergeHistory(String message) {
            if (!mergeHistory.contains(message)) {
                mergeHistory.add(message);
            }
        }

        private double distanceTo(Vec3 center) {
            return points.stream()
                    .mapToDouble(point -> point.distanceTo(center))
                    .min()
                    .orElse(Double.MAX_VALUE);
        }

        private boolean hasPointSince(long minTick) {
            for (TrackPoint point : points) {
                if (point.tick() >= minTick) {
                    return true;
                }
            }
            return false;
        }

        private double distanceTo(Vec3 center, long minTick) {
            return points.stream()
                    .filter(point -> point.tick() >= minTick)
                    .mapToDouble(point -> point.distanceTo(center))
                    .min()
                    .orElse(distanceTo(center));
        }

        private float recentMaxWidthBlocks(long minTick) {
            float max = 0.0F;
            for (TrackPoint point : points) {
                if (point.tick() >= minTick) {
                    max = Math.max(max, point.widthBlocks());
                }
            }
            return max > 0.0F ? max : maxWidthBlocks;
        }

        public List<TrackPoint> compactRecentPoints(int maxPoints, long gameTime, long recentWindowTicks, Vec3 surveyPos, double contextBlocks) {
            if (points.isEmpty()) {
                return List.of();
            }
            long minTick = gameTime - Math.max(20L, recentWindowTicks);
            double context = Math.max(16.0D, contextBlocks);
            List<TrackPoint> recentNearSurvey = new ArrayList<>();
            for (TrackPoint point : points) {
                if (point.tick() >= minTick && point.distanceTo(surveyPos) <= context) {
                    recentNearSurvey.add(point);
                }
            }
            if (recentNearSurvey.size() < 2) {
                recentNearSurvey.clear();
                for (TrackPoint point : points) {
                    if (point.tick() >= minTick) {
                        recentNearSurvey.add(point);
                    }
                }
            }
            if (recentNearSurvey.isEmpty()) {
                int from = Math.max(0, points.size() - maxPoints);
                recentNearSurvey.addAll(points.subList(from, points.size()));
            }
            return compact(recentNearSurvey, maxPoints);
        }

        public List<TrackPoint> compactPoints(int maxPoints) {
            return compact(points, maxPoints);
        }

        private static List<TrackPoint> compact(List<TrackPoint> source, int maxPoints) {
            if (source.size() <= maxPoints) {
                return List.copyOf(source);
            }
            List<TrackPoint> compact = new ArrayList<>(maxPoints);
            double step = (source.size() - 1) / (double) Math.max(1, maxPoints - 1);
            for (int i = 0; i < maxPoints; i++) {
                compact.add(source.get(Mth.clamp((int) Math.round(i * step), 0, source.size() - 1)));
            }
            return compact;
        }

        public String displayId() {
            return displayId;
        }

        public String sourceKey() {
            return sourceKey;
        }

        public long sourceStormId() {
            return sourceStormId;
        }

        public long sourceCirculationId() {
            return sourceCirculationId;
        }

        public long startTick() {
            return startTick;
        }

        public long endTick() {
            return endTick;
        }

        public String primarySubtype() {
            return primarySubtype;
        }

        public float maxWidthBlocks() {
            return maxWidthBlocks;
        }

        public float maxWindMph() {
            return maxWindMph;
        }

        public boolean surveyed() {
            return surveyed;
        }

        public String finalEfRating() {
            return finalEfRating;
        }

        public int finalEstimatedMph() {
            return finalEstimatedMph;
        }

        public String finalConfidence() {
            return finalConfidence;
        }

        public List<TrackPoint> points() {
            return List.copyOf(points);
        }

        public List<String> mergeHistory() {
            return List.copyOf(mergeHistory);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("DisplayId", displayId);
            tag.putString("SourceKey", sourceKey);
            tag.putLong("SourceStormId", sourceStormId);
            tag.putLong("SourceCirculationId", sourceCirculationId);
            tag.putLong("StartTick", startTick);
            tag.putLong("EndTick", endTick);
            tag.putString("PrimarySubtype", primarySubtype);
            tag.putFloat("MaxWidthBlocks", maxWidthBlocks);
            tag.putFloat("MaxWindMph", maxWindMph);
            tag.putBoolean("Surveyed", surveyed);
            tag.putString("FinalEfRating", finalEfRating);
            tag.putInt("FinalEstimatedMph", finalEstimatedMph);
            tag.putString("FinalConfidence", finalConfidence);
            ListTag pointList = new ListTag();
            for (TrackPoint point : points) {
                pointList.add(point.save());
            }
            tag.put("Points", pointList);
            ListTag mergeList = new ListTag();
            for (String merge : mergeHistory) {
                CompoundTag mergeTag = new CompoundTag();
                mergeTag.putString("Text", merge);
                mergeList.add(mergeTag);
            }
            tag.put("MergeHistory", mergeList);
            return tag;
        }

        private static TornadoTrack load(CompoundTag tag) {
            TornadoTrack track = new TornadoTrack(
                    tag.getString("DisplayId"),
                    tag.getString("SourceKey"),
                    tag.getLong("SourceStormId"),
                    tag.getLong("SourceCirculationId"),
                    tag.getLong("StartTick"),
                    tag.getString("PrimarySubtype")
            );
            track.endTick = tag.getLong("EndTick");
            track.maxWidthBlocks = tag.getFloat("MaxWidthBlocks");
            track.maxWindMph = tag.getFloat("MaxWindMph");
            track.surveyed = tag.getBoolean("Surveyed");
            track.finalEfRating = tag.contains("FinalEfRating") ? tag.getString("FinalEfRating") : "UNRATED";
            track.finalEstimatedMph = tag.getInt("FinalEstimatedMph");
            track.finalConfidence = tag.contains("FinalConfidence") ? tag.getString("FinalConfidence") : "none";
            ListTag points = tag.getList("Points", Tag.TAG_COMPOUND);
            for (int i = 0; i < points.size(); i++) {
                track.points.add(TrackPoint.load(points.getCompound(i)));
            }
            track.points.sort(Comparator.comparingLong(TrackPoint::tick));
            ListTag merges = tag.getList("MergeHistory", Tag.TAG_COMPOUND);
            for (int i = 0; i < merges.size(); i++) {
                track.mergeHistory.add(merges.getCompound(i).getString("Text"));
            }
            return track;
        }
    }

    public record TrackPoint(long tick, double x, double y, double z, float widthBlocks, float windMph, String subtype) {
        public double distanceTo(Vec3 center) {
            double dx = x - center.x;
            double dz = z - center.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Tick", tick);
            tag.putDouble("X", x);
            tag.putDouble("Y", y);
            tag.putDouble("Z", z);
            tag.putFloat("WidthBlocks", widthBlocks);
            tag.putFloat("WindMph", windMph);
            tag.putString("Subtype", subtype);
            return tag;
        }

        public static TrackPoint load(CompoundTag tag) {
            return new TrackPoint(
                    tag.getLong("Tick"),
                    tag.getDouble("X"),
                    tag.getDouble("Y"),
                    tag.getDouble("Z"),
                    tag.getFloat("WidthBlocks"),
                    tag.getFloat("WindMph"),
                    tag.getString("Subtype")
            );
        }
    }
}
