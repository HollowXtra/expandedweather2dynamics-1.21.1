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
        boolean hasConfirmedCirculation
) {
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(id);
        buf.writeUtf(limit(label, 64), 64);
        buf.writeUtf(limit(risk, 32), 32);
        buf.writeVarInt(Math.max(0, severity));
        buf.writeInt(color);
        buf.writeDouble(finite(x));
        buf.writeDouble(finite(z));
        buf.writeDouble(finite(motionX));
        buf.writeDouble(finite(motionZ));
        buf.writeFloat(finite(width));
        buf.writeDouble(finite(windMph));
        buf.writeDouble(finite(reflectivityDbz));
        buf.writeDouble(finite(radialVelocityMps));
        buf.writeDouble(finite(rotationMps));
        buf.writeBoolean(hookEcho);
        buf.writeVarInt(Math.max(0, subVortices));
        buf.writeBoolean(vortex);
        buf.writeDouble(finite(circulationX));
        buf.writeDouble(finite(circulationZ));
        buf.writeDouble(finite(hookX));
        buf.writeDouble(finite(hookZ));
        buf.writeBoolean(hasConfirmedCirculation);
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
                buf.readBoolean()
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
