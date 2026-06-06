package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record LowPressureSyncPayload(List<LowPressureSnapshot> systems) implements CustomPacketPayload {
    public static final Type<LowPressureSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "low_pressure"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LowPressureSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            LowPressureSyncPayload::read
    );

    public LowPressureSyncPayload {
        systems = copySystems(systems);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(systems.size());
        for (LowPressureSnapshot system : systems) {
            system.write(buf);
        }
    }

    private static LowPressureSyncPayload read(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 8);
        List<LowPressureSnapshot> systems = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            systems.add(LowPressureSnapshot.read(buf));
        }
        return new LowPressureSyncPayload(systems);
    }

    public record LowPressureSnapshot(long id, double x, double z, float radius, float intensity, double motionX, double motionZ, String label) {
        public LowPressureSnapshot {
            x = finite(x);
            z = finite(z);
            radius = finite(radius);
            intensity = finite(intensity);
            motionX = finite(motionX);
            motionZ = finite(motionZ);
            label = label == null ? "" : (label.length() <= 24 ? label : label.substring(0, 24));
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeLong(id);
            buf.writeDouble(x);
            buf.writeDouble(z);
            buf.writeFloat(radius);
            buf.writeFloat(intensity);
            buf.writeDouble(motionX);
            buf.writeDouble(motionZ);
            buf.writeUtf(label, 24);
        }

        private static LowPressureSnapshot read(RegistryFriendlyByteBuf buf) {
            return new LowPressureSnapshot(
                    buf.readLong(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readUtf(24)
            );
        }

        private static float finite(float value) {
            return Float.isFinite(value) ? value : 0.0F;
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0.0D;
        }
    }

    private static List<LowPressureSnapshot> copySystems(List<LowPressureSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(system -> system != null)
                .limit(8)
                .toList();
    }
}
