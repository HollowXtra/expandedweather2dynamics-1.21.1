package com.imbet.network;

import com.imbet.weather.AddonWeatherEvent;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record AddonWeatherEventSyncPayload(List<AddonWeatherEvent> events) {
    public void write(FriendlyByteBuf buf) {
        List<AddonWeatherEvent> safeEvents = events == null ? List.of() : events.stream()
                .filter(event -> event != null)
                .limit(128)
                .toList();
        buf.writeVarInt(safeEvents.size());
        for (AddonWeatherEvent event : safeEvents) {
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
