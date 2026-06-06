package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.weather.AddonWeatherEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record AddonWeatherEventSyncPayload(List<AddonWeatherEvent> events) implements CustomPacketPayload {
    public static final Type<AddonWeatherEventSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "addon_weather_events"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AddonWeatherEventSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            AddonWeatherEventSyncPayload::read
    );

    public AddonWeatherEventSyncPayload {
        events = copyEvents(events);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(events.size());
        for (AddonWeatherEvent event : events) {
            event.write(buf);
        }
    }

    private static AddonWeatherEventSyncPayload read(RegistryFriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), 128);
        List<AddonWeatherEvent> events = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            events.add(AddonWeatherEvent.read(buf));
        }
        return new AddonWeatherEventSyncPayload(events);
    }

    private static List<AddonWeatherEvent> copyEvents(List<AddonWeatherEvent> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream()
                .filter(event -> event != null)
                .limit(128)
                .map(AddonWeatherEvent::snapshot)
                .toList();
    }
}
