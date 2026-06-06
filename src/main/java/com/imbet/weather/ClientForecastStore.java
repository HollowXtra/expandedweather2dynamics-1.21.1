package com.imbet.weather;

import com.imbet.network.ForecastSnapshotPayload;
import com.imbet.network.ForecastPolygonSnapshot;
import com.imbet.network.ForecastRiskSnapshot;
import com.imbet.network.ForecastTrackSnapshot;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientForecastStore {
    private static final long DISPLAY_MILLIS = 10L * 60L * 1000L;
    private static final Map<BlockPos, Display> DISPLAYS = new ConcurrentHashMap<>();

    private ClientForecastStore() {
    }

    public static void accept(ForecastSnapshotPayload payload) {
        if (payload == null || payload.origin() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        DISPLAYS.put(payload.origin(), new Display(payload.origin(), payload.range(), payload.mode(), payload.storms(), payload.tracks(), payload.polygons(), payload.risk(), payload.portable(), now + DISPLAY_MILLIS));
    }

    public static List<Display> active() {
        long now = System.currentTimeMillis();
        DISPLAYS.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        return List.copyOf(DISPLAYS.values());
    }

    public static void discard(BlockPos origin) {
        if (origin != null) {
            DISPLAYS.remove(origin);
        }
    }

    public static Optional<Display> displayFor(BlockPos origin) {
        if (origin == null) {
            return Optional.empty();
        }
        active();
        Display direct = DISPLAYS.get(origin);
        if (direct != null) {
            return Optional.of(direct);
        }
        return DISPLAYS.values().stream()
                .filter(Display::portable)
                .min((first, second) -> Double.compare(first.origin().distSqr(origin), second.origin().distSqr(origin)));
    }

    public record Display(BlockPos origin, int range, int mode, List<RadarStormSnapshot> storms, List<ForecastTrackSnapshot> tracks, List<ForecastPolygonSnapshot> polygons, ForecastRiskSnapshot risk, boolean portable, long expiresAtMillis) {
    }
}
