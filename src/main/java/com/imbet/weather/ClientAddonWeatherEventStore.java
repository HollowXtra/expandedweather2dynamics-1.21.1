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

    public static List<AddonWeatherEvent> events() {
        return new ArrayList<>(events);
    }
}
