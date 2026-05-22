package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record RadarSnapshotPayload(BlockPos origin, List<RadarStormSnapshot> storms) implements CustomPacketPayload {
    public static final Type<RadarSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "radar_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            RadarSnapshotPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        List<RadarStormSnapshot> safeStorms = storms == null ? List.of() : storms.stream()
                .filter(snapshot -> snapshot != null)
                .limit(96)
                .toList();
        buf.writeBlockPos(origin == null ? BlockPos.ZERO : origin);
        buf.writeVarInt(safeStorms.size());
        for (RadarStormSnapshot snapshot : safeStorms) {
            snapshot.write(buf);
        }
    }

    public static RadarSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        int size = Math.min(buf.readVarInt(), 96);
        List<RadarStormSnapshot> storms = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            storms.add(RadarStormSnapshot.read(buf));
        }
        return new RadarSnapshotPayload(origin, storms);
    }
}
