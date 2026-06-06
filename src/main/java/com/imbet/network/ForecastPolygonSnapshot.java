package com.imbet.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

public record ForecastPolygonSnapshot(
        String label,
        String productCode,
        int type,
        int color,
        long expiresAt,
        List<Point> points
) {
    public static final int TYPE_WARNING = 0;
    public static final int TYPE_WATCH = 1;
    public static final int TYPE_RISK = 2;
    public static final int TYPE_SIGNIFICANT_HAZARD = 3;

    public ForecastPolygonSnapshot {
        label = limit(label, 48);
        productCode = limit(productCode, 24);
        type = Math.max(0, Math.min(type, TYPE_SIGNIFICANT_HAZARD));
        points = copyPoints(points);
    }

    public boolean warning() {
        return type == TYPE_WARNING;
    }

    public boolean watch() {
        return type == TYPE_WATCH;
    }

    public boolean risk() {
        return type == TYPE_RISK;
    }

    public boolean significantHazard() {
        return type == TYPE_SIGNIFICANT_HAZARD;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(label, 48);
        buf.writeUtf(productCode, 24);
        buf.writeVarInt(type);
        buf.writeInt(color);
        buf.writeLong(expiresAt);
        buf.writeVarInt(points.size());
        for (Point point : points) {
            buf.writeDouble(point.x());
            buf.writeDouble(point.z());
        }
    }

    public static ForecastPolygonSnapshot read(FriendlyByteBuf buf) {
        String label = buf.readUtf(48);
        String productCode = buf.readUtf(24);
        int type = buf.readVarInt();
        int color = buf.readInt();
        long expiresAt = buf.readLong();
        int size = Math.min(buf.readVarInt(), 16);
        java.util.ArrayList<Point> points = new java.util.ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            points.add(new Point(buf.readDouble(), buf.readDouble()));
        }
        return new ForecastPolygonSnapshot(label, productCode, type, color, expiresAt, points);
    }

    private static List<Point> copyPoints(List<Point> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(point -> point != null)
                .limit(16)
                .toList();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record Point(double x, double z) {
        public Point {
            x = Double.isFinite(x) ? x : 0.0D;
            z = Double.isFinite(z) ? z : 0.0D;
        }
    }
}
