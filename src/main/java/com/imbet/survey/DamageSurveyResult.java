package com.imbet.survey;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public record DamageSurveyResult(
        String tornadoId,
        String efRating,
        int estimatedMph,
        String confidence,
        int scouredSurfaceBlocks,
        int debarkedLogs,
        int damagedColumns,
        int pathWidthBlocks,
        float maxTrackWidthBlocks,
        float maxTrackWindMph,
        long startTick,
        long endTick,
        double surveyX,
        double surveyZ,
        String mergeSummary,
        List<MapPoint> points
) {
    public static final DamageSurveyResult NONE = new DamageSurveyResult("NO TRACK", "No EF damage", 0, "none", 0, 0, 0, 0, 0.0F, 0.0F, 0L, 0L, 0.0D, 0.0D, "", List.of());

    public DamageSurveyResult {
        tornadoId = limit(tornadoId, 32);
        efRating = limit(efRating, 32);
        estimatedMph = Math.max(0, estimatedMph);
        confidence = limit(confidence, 32);
        scouredSurfaceBlocks = Math.max(0, scouredSurfaceBlocks);
        debarkedLogs = Math.max(0, debarkedLogs);
        damagedColumns = Math.max(0, damagedColumns);
        pathWidthBlocks = Math.max(0, pathWidthBlocks);
        maxTrackWidthBlocks = finite(maxTrackWidthBlocks);
        maxTrackWindMph = finite(maxTrackWindMph);
        surveyX = finite(surveyX);
        surveyZ = finite(surveyZ);
        mergeSummary = limit(mergeSummary, 192);
        points = copyPoints(points);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(tornadoId, 32);
        buf.writeUtf(efRating, 32);
        buf.writeVarInt(estimatedMph);
        buf.writeUtf(confidence, 32);
        buf.writeVarInt(scouredSurfaceBlocks);
        buf.writeVarInt(debarkedLogs);
        buf.writeVarInt(damagedColumns);
        buf.writeVarInt(pathWidthBlocks);
        buf.writeFloat(maxTrackWidthBlocks);
        buf.writeFloat(maxTrackWindMph);
        buf.writeLong(startTick);
        buf.writeLong(endTick);
        buf.writeDouble(surveyX);
        buf.writeDouble(surveyZ);
        buf.writeUtf(mergeSummary, 192);
        buf.writeVarInt(points.size());
        for (MapPoint point : points) {
            point.write(buf);
        }
    }

    public static DamageSurveyResult read(FriendlyByteBuf buf) {
        String tornadoId = buf.readUtf(32);
        String efRating = buf.readUtf(32);
        int estimatedMph = buf.readVarInt();
        String confidence = buf.readUtf(32);
        int scoured = buf.readVarInt();
        int debarked = buf.readVarInt();
        int damagedColumns = buf.readVarInt();
        int pathWidth = buf.readVarInt();
        float maxWidth = buf.readFloat();
        float maxWind = buf.readFloat();
        long startTick = buf.readLong();
        long endTick = buf.readLong();
        double surveyX = buf.readDouble();
        double surveyZ = buf.readDouble();
        String mergeSummary = buf.readUtf(192);
        int count = Math.min(buf.readVarInt(), 256);
        List<MapPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(MapPoint.read(buf));
        }
        return new DamageSurveyResult(tornadoId, efRating, estimatedMph, confidence, scoured, debarked, damagedColumns, pathWidth, maxWidth, maxWind, startTick, endTick, surveyX, surveyZ, mergeSummary, points);
    }

    public String widthMiles() {
        return String.format(java.util.Locale.ROOT, "%.3f", pathWidthBlocks / 1609.344D);
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0D;
    }

    private static List<MapPoint> copyPoints(List<MapPoint> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(point -> point != null)
                .limit(256)
                .toList();
    }

    public record MapPoint(double x, double z, float widthBlocks, float windMph) {
        public MapPoint {
            x = finite(x);
            z = finite(z);
            widthBlocks = finite(widthBlocks);
            windMph = finite(windMph);
        }

        public static MapPoint from(TornadoSurveySavedData.TrackPoint point) {
            return new MapPoint(point.x(), point.z(), point.widthBlocks(), point.windMph());
        }

        public double distanceTo(Vec3 vec) {
            double dx = x - vec.x;
            double dz = z - vec.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeDouble(x);
            buf.writeDouble(z);
            buf.writeFloat(widthBlocks);
            buf.writeFloat(windMph);
        }

        private static MapPoint read(FriendlyByteBuf buf) {
            return new MapPoint(buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat());
        }
    }
}
