package com.imbet.weather;

import com.imbet.network.AddonWeatherEventSyncPayload;

import java.util.ArrayList;
import java.util.List;

public final class ClientAddonWeatherEventStore {
    private static List<AddonWeatherEvent> events = List.of();

    private ClientAddonWeatherEventStore() {
    }

    public static void accept(AddonWeatherEventSyncPayload payload) {
        events = List.copyOf(payload.events());
    }

    public static void tick(long gameTime) {
        if (events.isEmpty()) {
            return;
        }
        List<AddonWeatherEvent> updated = new ArrayList<>(events.size());
        for (AddonWeatherEvent event : events) {
            if (event == null || event.expired(gameTime)) {
                continue;
            }
            event.tick();
            updated.add(event);
        }
        events = List.copyOf(updated);
    }

    public static List<AddonWeatherEvent> events() {
        return new ArrayList<>(events);
    }
}
