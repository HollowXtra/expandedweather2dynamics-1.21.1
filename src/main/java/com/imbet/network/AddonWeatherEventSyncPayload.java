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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        List<AddonWeatherEvent> safeEvents = events == null ? List.of() : events.stream()
                .filter(event -> event != null)
                .limit(128)
                .toList();
        buf.writeVarInt(safeEvents.size());
        for (AddonWeatherEvent event : safeEvents) {
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
}
