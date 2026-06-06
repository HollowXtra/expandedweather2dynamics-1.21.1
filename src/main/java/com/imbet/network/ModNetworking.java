package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.radar.ClientRadarStore;
import com.imbet.survey.ClientDamageSurveyStore;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.ClientForecastStore;
import com.imbet.weather.ClientLowPressureStore;
import com.imbet.weather.ClientStormStateStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";
    private static final int MAX_CLIENTBOUND_PAYLOAD_BYTES = 900_000;
    private static long lastWarnMillis;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ExpandedWeather2Dynamics.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private ModNetworking() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(RadarSnapshotPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RadarSnapshotPayload::write)
                .decoder(RadarSnapshotPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientRadarStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(ForecastSnapshotPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ForecastSnapshotPayload::write)
                .decoder(ForecastSnapshotPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientForecastStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(LowPressureSyncPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(LowPressureSyncPayload::write)
                .decoder(LowPressureSyncPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientLowPressureStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(AddonWeatherEventSyncPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AddonWeatherEventSyncPayload::write)
                .decoder(AddonWeatherEventSyncPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientAddonWeatherEventStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(StormStateSyncPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StormStateSyncPayload::write)
                .decoder(StormStateSyncPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientStormStateStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(DamageSurveyResultPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DamageSurveyResultPayload::write)
                .decoder(DamageSurveyResultPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientDamageSurveyStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
        CHANNEL.messageBuilder(ServerboundSubmitSurveyPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ServerboundSubmitSurveyPacket::write)
                .decoder(ServerboundSubmitSurveyPacket::read)
                .consumerMainThread(ServerboundSubmitSurveyPacket::handle)
                .add();
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        if (player == null || payload == null || !canEncode(player, payload)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToPlayersNear(ServerLevel level, double x, double y, double z, double range, Object payload) {
        if (level == null || payload == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(range) || range <= 0.0D) {
            return;
        }
        double rangeSqr = range * range;
        for (ServerPlayer player : level.players()) {
            if (player.level() == level && player.distanceToSqr(x, y, z) <= rangeSqr) {
                sendToPlayer(player, payload);
            }
        }
    }

    private static boolean canEncode(ServerPlayer player, Object payload) {
        ByteBuf backing = Unpooled.buffer();
        try {
            FriendlyByteBuf buf = new FriendlyByteBuf(backing);
            if (payload instanceof RadarSnapshotPayload radar) {
                radar.write(buf);
            } else if (payload instanceof ForecastSnapshotPayload forecast) {
                forecast.write(buf);
            } else if (payload instanceof LowPressureSyncPayload lowPressure) {
                lowPressure.write(buf);
            } else if (payload instanceof AddonWeatherEventSyncPayload events) {
                events.write(buf);
            } else if (payload instanceof StormStateSyncPayload stormState) {
                stormState.write(buf);
            } else if (payload instanceof DamageSurveyResultPayload survey) {
                survey.write(buf);
            }
            int bytes = backing.readableBytes();
            if (bytes > MAX_CLIENTBOUND_PAYLOAD_BYTES) {
                warn("Skipped oversized Forge payload {} ({} bytes) for {}", payload.getClass().getSimpleName(), bytes, player.getGameProfile().getName(), null);
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            warn("Skipped bad Forge payload {} for {}", payload.getClass().getSimpleName(), player.getGameProfile().getName(), 0, throwable);
            return false;
        } finally {
            backing.release();
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
