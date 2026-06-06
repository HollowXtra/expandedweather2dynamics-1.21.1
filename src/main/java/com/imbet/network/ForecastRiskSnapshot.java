package com.imbet.network;

import net.minecraft.network.FriendlyByteBuf;

public record ForecastRiskSnapshot(
        String categoricalLabel,
        int categoricalSeverity,
        int categoricalColor,
        String tornadoLabel,
        int tornadoSeverity,
        int tornadoColor,
        String windLabel,
        int windSeverity,
        int windColor,
        String hailLabel,
        int hailSeverity,
        int hailColor,
        String hazard,
        int stormCount,
        int severeCount,
        double maxWindMph
) {
    public static final ForecastRiskSnapshot EMPTY = new ForecastRiskSnapshot(
            "None", 0, 0xFF263238,
            "None", 0, 0xFF263238,
            "None", 0, 0xFF263238,
            "None", 0, 0xFF263238,
            "No organized severe risk", 0, 0, 0.0D
    );

    public ForecastRiskSnapshot {
        categoricalLabel = limit(categoricalLabel, 16);
        tornadoLabel = limit(tornadoLabel, 16);
        windLabel = limit(windLabel, 16);
        hailLabel = limit(hailLabel, 16);
        hazard = limit(hazard, 64);
        categoricalSeverity = clampSeverity(categoricalSeverity);
        tornadoSeverity = clampSeverity(tornadoSeverity);
        windSeverity = clampSeverity(windSeverity);
        hailSeverity = clampSeverity(hailSeverity);
        stormCount = Math.max(0, stormCount);
        severeCount = Math.max(0, severeCount);
        maxWindMph = Double.isFinite(maxWindMph) ? Math.max(0.0D, maxWindMph) : 0.0D;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(categoricalLabel, 16);
        buf.writeVarInt(categoricalSeverity);
        buf.writeInt(categoricalColor);
        buf.writeUtf(tornadoLabel, 16);
        buf.writeVarInt(tornadoSeverity);
        buf.writeInt(tornadoColor);
        buf.writeUtf(windLabel, 16);
        buf.writeVarInt(windSeverity);
        buf.writeInt(windColor);
        buf.writeUtf(hailLabel, 16);
        buf.writeVarInt(hailSeverity);
        buf.writeInt(hailColor);
        buf.writeUtf(hazard, 64);
        buf.writeVarInt(stormCount);
        buf.writeVarInt(severeCount);
        buf.writeDouble(maxWindMph);
    }

    public static ForecastRiskSnapshot read(FriendlyByteBuf buf) {
        return new ForecastRiskSnapshot(
                buf.readUtf(16),
                buf.readVarInt(),
                buf.readInt(),
                buf.readUtf(16),
                buf.readVarInt(),
                buf.readInt(),
                buf.readUtf(16),
                buf.readVarInt(),
                buf.readInt(),
                buf.readUtf(16),
                buf.readVarInt(),
                buf.readInt(),
                buf.readUtf(64),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readDouble()
        );
    }

    private static int clampSeverity(int value) {
        return Math.max(0, Math.min(value, 6));
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
