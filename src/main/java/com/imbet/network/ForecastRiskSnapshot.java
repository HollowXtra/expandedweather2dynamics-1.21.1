package com.imbet.network;

import com.imbet.weather.SpcHazard;
import com.imbet.weather.SpcRisk;
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
        int tornadoProbability,
        int windProbability,
        int hailProbability,
        int tornadoCig,
        int windCig,
        int hailCig,
        String issuance,
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
            0, 0, 0, 0, 0, 0,
            "D1 0600Z", "No organized severe risk", 0, 0, 0.0D
    );

    public ForecastRiskSnapshot {
        categoricalLabel = limit(categoricalLabel, 16);
        tornadoLabel = limit(tornadoLabel, 16);
        windLabel = limit(windLabel, 16);
        hailLabel = limit(hailLabel, 16);
        hazard = limit(hazard, 64);
        issuance = issuance == null || issuance.isBlank() ? "D1 0600Z" : limit(issuance, 16);
        categoricalSeverity = clampSeverity(categoricalSeverity);
        tornadoSeverity = clampSeverity(tornadoSeverity);
        windSeverity = clampSeverity(windSeverity);
        hailSeverity = clampSeverity(hailSeverity);
        tornadoProbability = clampProbability(tornadoProbability);
        windProbability = clampProbability(windProbability);
        hailProbability = clampProbability(hailProbability);
        tornadoCig = clampCig(tornadoCig);
        windCig = clampCig(windCig);
        hailCig = clampCig(hailCig);
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
        buf.writeVarInt(tornadoProbability);
        buf.writeVarInt(windProbability);
        buf.writeVarInt(hailProbability);
        buf.writeVarInt(tornadoCig);
        buf.writeVarInt(windCig);
        buf.writeVarInt(hailCig);
        buf.writeUtf(issuance, 16);
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
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(16),
                buf.readUtf(64),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readDouble()
        );
    }

    private static int clampSeverity(int value) {
        return Math.max(0, Math.min(value, SpcRisk.maxSeverity()));
    }

    private static int clampProbability(int value) {
        return Math.max(0, Math.min(value, 100));
    }

    /** CIG 0-3 is the published ladder, 4 and 5 are the addon's extension columns. */
    private static int clampCig(int value) {
        return Math.max(0, Math.min(value, 5));
    }

    /** Compact product text for chat and HUD readouts, e.g. "TOR 10% CIG1 / WND 30% / HAIL 15%". */
    public String productLine() {
        return SpcHazard.TORNADO.productLine(tornadoProbability, tornadoCig)
                + " / " + SpcHazard.WIND.productLine(windProbability, windCig)
                + " / " + SpcHazard.HAIL.productLine(hailProbability, hailCig);
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
