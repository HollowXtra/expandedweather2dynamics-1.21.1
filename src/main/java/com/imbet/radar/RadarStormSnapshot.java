package com.imbet.radar;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

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
        String stageLabel,
        // Supercell structure. hookX/hookZ carry the storm's real hook-echo notch, mesoX/mesoZ its real
        // mesocyclone, and stormRadius its precipitation extent in blocks (0 for a circulation that is not
        // itself a storm). Without these the renderer had to invent a hook at a fixed offset from the storm
        // centre, which is why the hook echo and the tornado marker used to disagree.
        float stormRadius,
        float structureStrength,
        float mesoStrength,
        float ffdStrength,
        float hailStrength,
        int structureMode,
        double mesoX,
        double mesoZ,
        // The storm's precipitation field and its frame, packed by {@link RadarPrecipitation}: what the scope draws its
        // reflectivity from, so the echo is the rain that is really falling. Empty for a return with no field of its own.
        float[] precipitation
) {
    /** The snapshot of a return with no precipitation field: a satellite, a particle storm, a scripted event. */
    public RadarStormSnapshot(long id, String label, String risk, int severity, int color, double x, double z, double motionX,
                              double motionZ, float width, double windMph, double reflectivityDbz, double radialVelocityMps,
                              double rotationMps, boolean hookEcho, int subVortices, boolean vortex, double circulationX,
                              double circulationZ, double hookX, double hookZ, boolean hasConfirmedCirculation, int weatherStage,
                              String stageLabel, float stormRadius, float structureStrength, float mesoStrength, float ffdStrength,
                              float hailStrength, int structureMode, double mesoX, double mesoZ) {
        this(id, label, risk, severity, color, x, z, motionX, motionZ, width, windMph, reflectivityDbz, radialVelocityMps,
                rotationMps, hookEcho, subVortices, vortex, circulationX, circulationZ, hookX, hookZ, hasConfirmedCirculation,
                weatherStage, stageLabel, stormRadius, structureStrength, mesoStrength, ffdStrength, hailStrength, structureMode,
                mesoX, mesoZ, new float[0]);
    }

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
        stormRadius = Math.max(0.0F, finite(stormRadius));
        structureStrength = clamp01(structureStrength);
        mesoStrength = clamp01(mesoStrength);
        ffdStrength = clamp01(ffdStrength);
        hailStrength = clamp01(hailStrength);
        structureMode = Mth.clamp(structureMode, 0, 2);
        mesoX = finite(mesoX);
        mesoZ = finite(mesoZ);
        precipitation = precipitation == null
                || (precipitation.length != RadarPrecipitation.LENGTH && precipitation.length != RadarPrecipitation.LINE_LENGTH)
                ? new float[0] : precipitation;
    }

    /** Whether this return is a storm with a structure worth shaping an echo around. */
    public boolean hasStormExtent() {
        return stormRadius > 1.0F;
    }

    /** Whether the storm has a resolved supercell structure, as opposed to a plain cell. */
    public boolean hasStructure() {
        return structureStrength > 0.05F && hasStormExtent();
    }

    /** Whether this return carries the precipitation field its echo is drawn from. */
    public boolean hasPrecipitationField() {
        return precipitation.length == RadarPrecipitation.LENGTH;
    }

    /** Whether this return is a squall line, carrying the line itself rather than a cell's field. */
    public boolean hasLineField() {
        return precipitation.length == RadarPrecipitation.LINE_LENGTH;
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
        buf.writeFloat(stormRadius);
        buf.writeFloat(structureStrength);
        buf.writeFloat(mesoStrength);
        buf.writeFloat(ffdStrength);
        buf.writeFloat(hailStrength);
        buf.writeVarInt(structureMode);
        buf.writeDouble(mesoX);
        buf.writeDouble(mesoZ);
        buf.writeVarInt(precipitation.length);
        for (float value : precipitation) {
            buf.writeFloat(value);
        }
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
                buf.readUtf(16),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readDouble(),
                buf.readDouble(),
                readFloats(buf)
        );
    }

    private static float[] readFloats(FriendlyByteBuf buf) {
        int length = Math.min(buf.readVarInt(), RadarPrecipitation.LENGTH);
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = buf.readFloat();
        }
        return values;
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

    private static float clamp01(float value) {
        return Mth.clamp(finite(value), 0.0F, 1.0F);
    }
}
