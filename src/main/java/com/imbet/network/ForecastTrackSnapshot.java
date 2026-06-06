package com.imbet.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record ForecastTrackSnapshot(
        String tornadoId,
        String label,
        float maxWindMph,
        float maxWidthBlocks,
        long ageTicks,
        List<Point> points
) {
    public ForecastTrackSnapshot {
        tornadoId = limit(tornadoId, 24);
        label = limit(label, 48);
        maxWindMph = finite(maxWindMph);
        maxWidthBlocks = finite(maxWidthBlocks);
        ageTicks = Math.max(0L, ageTicks);
        points = copyPoints(points);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(tornadoId, 24);
        buf.writeUtf(label, 48);
        buf.writeFloat(maxWindMph);
        buf.writeFloat(maxWidthBlocks);
        buf.writeVarLong(ageTicks);
        buf.writeVarInt(points.size());
        for (Point point : points) {
            point.write(buf);
        }
    }

    public static ForecastTrackSnapshot read(FriendlyByteBuf buf) {
        String tornadoId = buf.readUtf(24);
        String label = buf.readUtf(48);
        float maxWindMph = buf.readFloat();
        float maxWidthBlocks = buf.readFloat();
        long ageTicks = buf.readVarLong();
        int size = Math.min(buf.readVarInt(), 48);
        List<Point> points = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            points.add(Point.read(buf));
        }
        return new ForecastTrackSnapshot(tornadoId, label, maxWindMph, maxWidthBlocks, ageTicks, points);
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

    private static List<Point> copyPoints(List<Point> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(point -> point != null)
                .limit(48)
                .toList();
    }

    public record Point(double x, double z) {
        public Point {
            x = Double.isFinite(x) ? x : 0.0D;
            z = Double.isFinite(z) ? z : 0.0D;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeDouble(x);
            buf.writeDouble(z);
        }

        private static Point read(FriendlyByteBuf buf) {
            return new Point(buf.readDouble(), buf.readDouble());
        }
    }
}
