package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class NetworkSafety {
    private static final int MAX_CLIENTBOUND_PAYLOAD_BYTES = 900_000;
    private static long lastWarnMillis;

    private NetworkSafety() {
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || payload == null || !canEncode(player, payload)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToPlayersNear(ServerLevel level, double x, double y, double z, double range, CustomPacketPayload payload) {
        if (level == null || payload == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(range) || range <= 0.0D) {
            return;
        }
        double rangeSqr = range * range;
        for (ServerPlayer player : level.players()) {
            if (player.level() != level || player.distanceToSqr(x, y, z) > rangeSqr) {
                continue;
            }
            sendToPlayer(player, payload);
        }
    }

    private static boolean canEncode(ServerPlayer player, CustomPacketPayload payload) {
        ByteBuf backing = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(backing, player.registryAccess());
            encode(payload, buf);
            int bytes = backing.readableBytes();
            if (bytes > MAX_CLIENTBOUND_PAYLOAD_BYTES) {
                warn("Skipped oversized payload {} ({} bytes) for {}", payload.type().id(), bytes, player.getGameProfile().getName(), null);
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            warn("Skipped bad payload {} for {}", payload.type().id(), player.getGameProfile().getName(), 0, throwable);
            return false;
        } finally {
            backing.release();
        }
    }

    private static void encode(CustomPacketPayload payload, RegistryFriendlyByteBuf buf) {
        if (payload instanceof RadarSnapshotPayload radar) {
            RadarSnapshotPayload.STREAM_CODEC.encode(buf, radar);
        } else if (payload instanceof ForecastSnapshotPayload forecast) {
            ForecastSnapshotPayload.STREAM_CODEC.encode(buf, forecast);
        } else if (payload instanceof AddonWeatherEventSyncPayload events) {
            AddonWeatherEventSyncPayload.STREAM_CODEC.encode(buf, events);
        } else if (payload instanceof LowPressureSyncPayload lowPressure) {
            LowPressureSyncPayload.STREAM_CODEC.encode(buf, lowPressure);
        } else if (payload instanceof StormStateSyncPayload stormState) {
            StormStateSyncPayload.STREAM_CODEC.encode(buf, stormState);
        } else if (payload instanceof DamageSurveyResultPayload survey) {
            DamageSurveyResultPayload.STREAM_CODEC.encode(buf, survey);
        }
    }

    private static void warn(String message, Object first, Object second, Object third, Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMillis < 5000L) {
            return;
        }
        lastWarnMillis = now;
        if (throwable == null) {
            ExpandedWeather2Dynamics.LOGGER.warn(message, first, second, third);
        } else {
            ExpandedWeather2Dynamics.LOGGER.warn(message, first, second, throwable);
        }
    }
}
