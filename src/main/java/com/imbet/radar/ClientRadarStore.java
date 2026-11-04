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

    /**
     * The feed swept from a position, or null if nothing has been swept from there.
     *
     * <p>A display reads the feed rather than its own block entity: the feed says which product it is in and
     * how far it reaches, so a panel or a handheld can render without the block behind it being loaded - and
     * a mode the block has since been switched to is still correct, because the mode travelled with the feed.
     */
    public static RadarSnapshotPayload feedFor(BlockPos origin) {
        return SNAPSHOTS.get(origin);
    }

    public static List<RadarStormSnapshot> allStorms() {
        return SNAPSHOTS.values().stream()
                .flatMap(payload -> payload.storms().stream())
                .toList();
    }
}
