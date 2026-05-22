package com.imbet.radar;

import com.imbet.network.RadarSnapshotPayload;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientRadarStore {
    private static final Map<BlockPos, RadarSnapshotPayload> SNAPSHOTS = new ConcurrentHashMap<>();

    private ClientRadarStore() {
    }

    public static void accept(RadarSnapshotPayload payload) {
        SNAPSHOTS.put(payload.origin(), payload);
    }

    public static List<RadarStormSnapshot> stormsFor(BlockPos origin) {
        RadarSnapshotPayload payload = SNAPSHOTS.get(origin);
        return payload == null ? List.of() : payload.storms();
    }
}
