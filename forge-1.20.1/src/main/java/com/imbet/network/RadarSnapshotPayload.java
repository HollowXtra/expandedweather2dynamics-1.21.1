package com.imbet.network;

import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record RadarSnapshotPayload(BlockPos origin, List<RadarStormSnapshot> storms) {
    public void write(FriendlyByteBuf buf) {
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

    public static RadarSnapshotPayload read(FriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        int size = Math.min(buf.readVarInt(), 96);
        List<RadarStormSnapshot> storms = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            storms.add(RadarStormSnapshot.read(buf));
        }
        return new RadarSnapshotPayload(origin, storms);
    }
}
