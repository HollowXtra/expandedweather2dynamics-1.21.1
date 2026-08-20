package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.vortex.VortexSnapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record VortexSyncPayload(List<VortexSnapshot> snapshots) implements CustomPacketPayload {
    public static final Type<VortexSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "vortex_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VortexSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            VortexSyncPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(snapshots.size());
        for (VortexSnapshot snapshot : snapshots) {
            VortexSnapshot.STREAM_CODEC.encode(buf, snapshot);
        }
    }

    private static VortexSyncPayload read(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<VortexSnapshot> snapshots = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            snapshots.add(VortexSnapshot.STREAM_CODEC.decode(buf));
        }
        return new VortexSyncPayload(snapshots);
    }
}
