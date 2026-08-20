package com.imbet.weather.vortex;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

public record VortexSnapshot(
        long id,
        long parentStormId,
        VortexMode mode,
        double x,
        double y,
        double z,
        double motionX,
        double motionY,
        double motionZ,
        float radius,
        float damagePathWidth,
        float visibleFunnelWidth,
        float coreRadius,
        float radiusOfMaxWind,
        float maxWindMph,
        int childCount,
        boolean antiCyclonic,
        boolean touchedDown,
        long spawnTick,
        long expireTick
) {
    public static final StreamCodec<RegistryFriendlyByteBuf, VortexSnapshot> STREAM_CODEC = StreamCodec.of(
            VortexSnapshot::write,
            VortexSnapshot::read
    );

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    public Vec3 motion() {
        return new Vec3(motionX, motionY, motionZ);
    }

    public boolean weather2Backed() {
        return parentStormId != Long.MIN_VALUE && parentStormId == id;
    }

    public boolean satellite() {
        return mode == VortexMode.SATELLITE_TORNADO;
    }

    public boolean fire() {
        return mode == VortexMode.FIRENADO;
    }

    public boolean warningCapable() {
        return mode == VortexMode.LAND_TORNADO || mode == VortexMode.FIRENADO || mode == VortexMode.SATELLITE_TORNADO;
    }

    private static void write(RegistryFriendlyByteBuf buf, VortexSnapshot snapshot) {
        buf.writeLong(snapshot.id);
        buf.writeLong(snapshot.parentStormId);
        buf.writeEnum(snapshot.mode == null ? VortexMode.LAND_TORNADO : snapshot.mode);
        buf.writeDouble(snapshot.x);
        buf.writeDouble(snapshot.y);
        buf.writeDouble(snapshot.z);
        buf.writeDouble(snapshot.motionX);
        buf.writeDouble(snapshot.motionY);
        buf.writeDouble(snapshot.motionZ);
        buf.writeFloat(snapshot.radius);
        buf.writeFloat(snapshot.damagePathWidth);
        buf.writeFloat(snapshot.visibleFunnelWidth);
        buf.writeFloat(snapshot.coreRadius);
        buf.writeFloat(snapshot.radiusOfMaxWind);
        buf.writeFloat(snapshot.maxWindMph);
        buf.writeVarInt(snapshot.childCount);
        buf.writeBoolean(snapshot.antiCyclonic);
        buf.writeBoolean(snapshot.touchedDown);
        buf.writeLong(snapshot.spawnTick);
        buf.writeLong(snapshot.expireTick);
    }

    private static VortexSnapshot read(RegistryFriendlyByteBuf buf) {
        return new VortexSnapshot(
                buf.readLong(),
                buf.readLong(),
                buf.readEnum(VortexMode.class),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readLong(),
                buf.readLong()
        );
    }
}
