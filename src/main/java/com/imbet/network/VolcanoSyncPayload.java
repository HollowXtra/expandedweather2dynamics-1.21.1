package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * One volcano, as a client needs to draw it.
 *
 * <p>The client is told what the volcano is doing, never what it is doing to the world: the blocks are the
 * server's business, and the plume, the glow, the haze and the advisory are the only parts that have to agree
 * on both sides. Everything here is the state a frame needs, so a volcano is drawn from one record rather than
 * from a chain of lookups, and an eruption that starts while a client is loading still arrives complete.
 */
public record VolcanoSyncPayload(List<VolcanoSnapshot> volcanoes) implements CustomPacketPayload {
    public static final Type<VolcanoSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "volcanoes"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VolcanoSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            VolcanoSyncPayload::read
    );

    public VolcanoSyncPayload {
        volcanoes = copy(volcanoes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(volcanoes.size());
        for (VolcanoSnapshot snapshot : volcanoes) {
            snapshot.write(buf);
        }
    }

    private static VolcanoSyncPayload read(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), 32);
        List<VolcanoSnapshot> snapshots = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            snapshots.add(VolcanoSnapshot.read(buf));
        }
        return new VolcanoSyncPayload(snapshots);
    }

    private static List<VolcanoSnapshot> copy(List<VolcanoSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().filter(snapshot -> snapshot != null).limit(32).toList();
    }

    /**
     * The state of one volcano at a moment.
     *
     * <p>{@code phase} is the ordinal of {@link com.imbet.volcano.VolcanoPhase}, because the phase is a ladder
     * both sides already agree on; sending its name would mean a string on the wire every second for something
     * an index says exactly. The rest is what the plume is drawn from: where it is, how big it is, how high its
     * column stands, which way its own wind is blowing, and how far its glow and its fallout reach.
     */
    public record VolcanoSnapshot(
            long id,
            int x,
            int z,
            int ventY,
            int scale,
            double intensity,
            int phase,
            double phaseProgress,
            double plumeMetres,
            double ashMm,
            double windX,
            double windZ,
            double windMph,
            double surgeReach,
            double edificeRadius,
            double craterRadius,
            double pressure,
            boolean lava,
            boolean pyroclastic
    ) {
        public VolcanoSnapshot {
            intensity = finite(intensity);
            phaseProgress = finite(phaseProgress);
            plumeMetres = Math.max(0.0D, finite(plumeMetres));
            ashMm = Math.max(0.0D, finite(ashMm));
            windX = finite(windX);
            windZ = finite(windZ);
            windMph = Math.max(0.0D, finite(windMph));
            surgeReach = Math.max(0.0D, finite(surgeReach));
            edificeRadius = Math.max(1.0D, finite(edificeRadius));
            craterRadius = Math.max(1.0D, finite(craterRadius));
            pressure = finite(pressure);
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeLong(id);
            buf.writeInt(x);
            buf.writeInt(z);
            buf.writeInt(ventY);
            buf.writeVarInt(scale);
            buf.writeFloat((float) intensity);
            buf.writeByte(phase);
            buf.writeFloat((float) phaseProgress);
            buf.writeFloat((float) plumeMetres);
            buf.writeFloat((float) ashMm);
            buf.writeFloat((float) windX);
            buf.writeFloat((float) windZ);
            buf.writeFloat((float) windMph);
            buf.writeFloat((float) surgeReach);
            buf.writeFloat((float) edificeRadius);
            buf.writeFloat((float) craterRadius);
            buf.writeFloat((float) pressure);
            buf.writeBoolean(lava);
            buf.writeBoolean(pyroclastic);
        }

        private static VolcanoSnapshot read(RegistryFriendlyByteBuf buf) {
            return new VolcanoSnapshot(
                    buf.readLong(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readByte(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean()
            );
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0.0D;
        }
    }
}
