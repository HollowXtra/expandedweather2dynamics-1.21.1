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

public record ForecastSnapshotPayload(
        BlockPos origin,
        int range,
        int mode,
        List<RadarStormSnapshot> storms,
        List<ForecastTrackSnapshot> tracks,
        List<ForecastPolygonSnapshot> polygons,
        ForecastRiskSnapshot risk
) implements CustomPacketPayload {
    public static final int MODE_LIVE = 0;
    public static final int MODE_TRACKS = 1;
    public static final int MODE_RISK = 2;
    public static final Type<ForecastSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "forecast_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ForecastSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            ForecastSnapshotPayload::read
    );

    public ForecastSnapshotPayload {
        origin = origin == null ? BlockPos.ZERO : origin.immutable();
        range = Math.max(128, Math.min(range, 8192));
        mode = switch (mode) {
            case MODE_TRACKS -> MODE_TRACKS;
            case MODE_RISK -> MODE_RISK;
            default -> MODE_LIVE;
        };
        storms = copyStorms(storms);
        tracks = copyTracks(tracks);
        polygons = copyPolygons(polygons);
        risk = risk == null ? ForecastRiskSnapshot.EMPTY : risk;
    }

    public ForecastSnapshotPayload(BlockPos origin, int range, List<RadarStormSnapshot> storms) {
        this(origin, range, MODE_LIVE, storms, List.of(), List.of(), ForecastRiskSnapshot.EMPTY);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(origin);
        buf.writeVarInt(range);
        buf.writeVarInt(mode);
        buf.writeVarInt(storms.size());
        for (RadarStormSnapshot snapshot : storms) {
            snapshot.write(buf);
        }
        buf.writeVarInt(tracks.size());
        for (ForecastTrackSnapshot track : tracks) {
            track.write(buf);
        }
        buf.writeVarInt(polygons.size());
        for (ForecastPolygonSnapshot polygon : polygons) {
            polygon.write(buf);
        }
        risk.write(buf);
    }

    private static ForecastSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        int range = buf.readVarInt();
        int payloadStart = buf.readerIndex();
        try {
            return readCurrent(origin, range, buf);
        } catch (RuntimeException currentDecodeFailed) {
            buf.readerIndex(payloadStart);
            try {
                return readLegacyStormsOnly(origin, range, buf);
            } catch (RuntimeException legacyDecodeFailed) {
                return new ForecastSnapshotPayload(origin, range, MODE_LIVE, List.of(), List.of(), List.of(), ForecastRiskSnapshot.EMPTY);
            }
        }
    }

    private static ForecastSnapshotPayload readCurrent(BlockPos origin, int range, RegistryFriendlyByteBuf buf) {
        if (!buf.isReadable()) {
            return new ForecastSnapshotPayload(origin, range, MODE_LIVE, List.of(), List.of(), List.of(), ForecastRiskSnapshot.EMPTY);
        }
        int mode = buf.readVarInt();
        if (!buf.isReadable()) {
            return new ForecastSnapshotPayload(origin, range, mode, List.of(), List.of(), List.of(), ForecastRiskSnapshot.EMPTY);
        }
        int size = Math.min(Math.max(0, buf.readVarInt()), 24);
        List<RadarStormSnapshot> storms = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            storms.add(RadarStormSnapshot.read(buf));
        }
        if (!buf.isReadable()) {
            return new ForecastSnapshotPayload(origin, range, mode, storms, List.of(), List.of(), ForecastRiskSnapshot.EMPTY);
        }
        int trackSize = Math.min(Math.max(0, buf.readVarInt()), 8);
        List<ForecastTrackSnapshot> tracks = new ArrayList<>(trackSize);
        for (int i = 0; i < trackSize; i++) {
            tracks.add(ForecastTrackSnapshot.read(buf));
        }
        if (!buf.isReadable()) {
            return new ForecastSnapshotPayload(origin, range, mode, storms, tracks, List.of(), ForecastRiskSnapshot.EMPTY);
        }
        int polygonSize = Math.min(Math.max(0, buf.readVarInt()), 32);
        List<ForecastPolygonSnapshot> polygons = new ArrayList<>(polygonSize);
        for (int i = 0; i < polygonSize; i++) {
            polygons.add(ForecastPolygonSnapshot.read(buf));
        }
        ForecastRiskSnapshot risk = buf.isReadable() ? ForecastRiskSnapshot.read(buf) : ForecastRiskSnapshot.EMPTY;
        return new ForecastSnapshotPayload(origin, range, mode, storms, tracks, polygons, risk);
    }

    private static ForecastSnapshotPayload readLegacyStormsOnly(BlockPos origin, int range, RegistryFriendlyByteBuf buf) {
        if (!buf.isReadable()) {
            return new ForecastSnapshotPayload(origin, range, List.of());
        }
        int size = Math.min(Math.max(0, buf.readVarInt()), 24);
        List<RadarStormSnapshot> storms = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            storms.add(RadarStormSnapshot.read(buf));
        }
        return new ForecastSnapshotPayload(origin, range, MODE_LIVE, storms, List.of(), List.of(), ForecastRiskSnapshot.EMPTY);
    }

    private static List<RadarStormSnapshot> copyStorms(List<RadarStormSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(snapshot -> snapshot != null)
                .limit(24)
                .toList();
    }

    private static List<ForecastTrackSnapshot> copyTracks(List<ForecastTrackSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(track -> track != null)
                .limit(8)
                .toList();
    }

    private static List<ForecastPolygonSnapshot> copyPolygons(List<ForecastPolygonSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(polygon -> polygon != null)
                .limit(32)
                .toList();
    }
}
