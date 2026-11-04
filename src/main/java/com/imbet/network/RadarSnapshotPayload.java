package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * One radar's picture, as it is swept.
 *
 * <p>The feed describes itself: {@code origin} is the block the sweep is centred on, {@code range} how far it
 * reaches, {@code mode} which product it is showing and {@code radarBlock} whether it came from a weather
 * radar in the world at all. Carrying those rather than looking them up on the client is what lets a display
 * work from the feed alone - the block entity may be outside the player's loaded chunks even when its feed
 * has reached them, and a mode cycled after the chunk loaded would otherwise never arrive.
 */
public record RadarSnapshotPayload(BlockPos origin, boolean radarBlock, int mode, int range, List<RadarStormSnapshot> storms) implements CustomPacketPayload {
    public static final Type<RadarSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "radar_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            RadarSnapshotPayload::read
    );

    public RadarSnapshotPayload {
        origin = origin == null ? BlockPos.ZERO : origin.immutable();
        mode = Mth.clamp(mode, 0, 2);
        range = Mth.clamp(range, 8, 8192);
        storms = copyStorms(storms);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(origin);
        buf.writeBoolean(radarBlock);
        buf.writeVarInt(mode);
        buf.writeVarInt(range);
        buf.writeVarInt(storms.size());
        for (RadarStormSnapshot snapshot : storms) {
            snapshot.write(buf);
        }
    }

    public static RadarSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        boolean radarBlock = buf.readBoolean();
        int mode = buf.readVarInt();
        int range = buf.readVarInt();
        int size = Math.min(buf.readVarInt(), 96);
        List<RadarStormSnapshot> storms = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            storms.add(RadarStormSnapshot.read(buf));
        }
        return new RadarSnapshotPayload(origin, radarBlock, mode, range, storms);
    }

    private static List<RadarStormSnapshot> copyStorms(List<RadarStormSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(snapshot -> snapshot != null)
                .limit(96)
                .toList();
    }
}
