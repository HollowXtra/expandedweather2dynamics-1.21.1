package com.imbet.network;

import com.imbet.weather.AddonWeatherEvent;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record AddonWeatherEventSyncPayload(List<AddonWeatherEvent> events) {
    public AddonWeatherEventSyncPayload {
        events = events == null ? List.of() : events.stream()
                .filter(event -> event != null)
                .limit(128)
                .map(AddonWeatherEvent::snapshot)
                .toList();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(events.size());
        for (AddonWeatherEvent event : events) {
            event.write(buf);
        }
    }

    public static AddonWeatherEventSyncPayload read(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), 128);
        List<AddonWeatherEvent> events = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            events.add(AddonWeatherEvent.read(buf));
        }
        return new AddonWeatherEventSyncPayload(events);
    }
}
