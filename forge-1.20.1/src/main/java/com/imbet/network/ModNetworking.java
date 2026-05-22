package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.radar.ClientRadarStore;
import com.imbet.survey.ClientDamageSurveyStore;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.ClientStormStateStore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";
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
        CHANNEL.messageBuilder(DamageSurveyResultPayload.class, id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DamageSurveyResultPayload::write)
                .decoder(DamageSurveyResultPayload::read)
                .consumerMainThread((payload, context) -> {
                    ClientDamageSurveyStore.accept(payload);
                    context.get().setPacketHandled(true);
                })
                .add();
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToPlayersNear(ServerLevel level, double x, double y, double z, double range, Object payload) {
        CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(x, y, z, range, level.dimension())), payload);
    }
}
