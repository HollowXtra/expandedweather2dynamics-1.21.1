package com.imbet.radar;

import net.minecraft.network.FriendlyByteBuf;

public record RadarStormSnapshot(
        long id,
        String label,
        String risk,
        int severity,
        int color,
        double x,
        double z,
        double motionX,
        double motionZ,
        float width,
        double windMph,
        double reflectivityDbz,
        double radialVelocityMps,
        double rotationMps,
        boolean hookEcho,
        int subVortices,
        boolean vortex,
        double circulationX,
        double circulationZ,
        double hookX,
        double hookZ,
        boolean hasConfirmedCirculation,
        int weatherStage,
        String stageLabel
) {
    public RadarStormSnapshot {
        label = limit(label, 64);
        risk = limit(risk, 32);
        severity = Math.max(0, severity);
        x = finite(x);
        z = finite(z);
        motionX = finite(motionX);
        motionZ = finite(motionZ);
        width = finite(width);
        windMph = finite(windMph);
        reflectivityDbz = finite(reflectivityDbz);
        radialVelocityMps = finite(radialVelocityMps);
        rotationMps = finite(rotationMps);
        subVortices = Math.max(0, subVortices);
        circulationX = finite(circulationX);
        circulationZ = finite(circulationZ);
        hookX = finite(hookX);
        hookZ = finite(hookZ);
        weatherStage = Math.max(0, weatherStage);
        stageLabel = limit(stageLabel, 16);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(id);
        buf.writeUtf(label, 64);
        buf.writeUtf(risk, 32);
        buf.writeVarInt(severity);
        buf.writeInt(color);
        buf.writeDouble(x);
        buf.writeDouble(z);
        buf.writeDouble(motionX);
        buf.writeDouble(motionZ);
        buf.writeFloat(width);
        buf.writeDouble(windMph);
        buf.writeDouble(reflectivityDbz);
        buf.writeDouble(radialVelocityMps);
        buf.writeDouble(rotationMps);
        buf.writeBoolean(hookEcho);
        buf.writeVarInt(subVortices);
        buf.writeBoolean(vortex);
        buf.writeDouble(circulationX);
        buf.writeDouble(circulationZ);
        buf.writeDouble(hookX);
        buf.writeDouble(hookZ);
        buf.writeBoolean(hasConfirmedCirculation);
        buf.writeVarInt(weatherStage);
        buf.writeUtf(stageLabel, 16);
    }

    public static RadarStormSnapshot read(FriendlyByteBuf buf) {
        return new RadarStormSnapshot(
                buf.readLong(),
                buf.readUtf(64),
                buf.readUtf(32),
                buf.readVarInt(),
                buf.readInt(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readUtf(16)
        );
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
}
