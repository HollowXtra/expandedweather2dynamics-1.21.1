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

    public void write(FriendlyByteBuf buf) {
        List<MapPoint> safePoints = points == null ? List.of() : points;
        buf.writeUtf(limit(tornadoId, 32), 32);
        buf.writeUtf(limit(efRating, 32), 32);
        buf.writeVarInt(Math.max(0, estimatedMph));
        buf.writeUtf(limit(confidence, 32), 32);
        buf.writeVarInt(Math.max(0, scouredSurfaceBlocks));
        buf.writeVarInt(Math.max(0, debarkedLogs));
        buf.writeVarInt(Math.max(0, damagedColumns));
        buf.writeVarInt(Math.max(0, pathWidthBlocks));
        buf.writeFloat(finite(maxTrackWidthBlocks));
        buf.writeFloat(finite(maxTrackWindMph));
        buf.writeLong(startTick);
        buf.writeLong(endTick);
        buf.writeDouble(finite(surveyX));
        buf.writeDouble(finite(surveyZ));
        buf.writeUtf(limit(mergeSummary, 192), 192);
        int count = Math.min(safePoints.size(), 256);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            MapPoint point = safePoints.get(i);
            if (point == null) {
                new MapPoint(0.0D, 0.0D, 0.0F, 0.0F).write(buf);
            } else {
                point.write(buf);
            }
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

    public record MapPoint(double x, double z, float widthBlocks, float windMph) {
        public static MapPoint from(TornadoSurveySavedData.TrackPoint point) {
            return new MapPoint(point.x(), point.z(), point.widthBlocks(), point.windMph());
        }

        public double distanceTo(Vec3 vec) {
            double dx = x - vec.x;
            double dz = z - vec.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeDouble(finite(x));
            buf.writeDouble(finite(z));
            buf.writeFloat(finite(widthBlocks));
            buf.writeFloat(finite(windMph));
        }

        private static MapPoint read(FriendlyByteBuf buf) {
            return new MapPoint(buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat());
        }
    }
}
