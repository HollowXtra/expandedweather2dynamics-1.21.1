package com.imbet.weather;

import com.imbet.network.LowPressureSyncPayload;

import java.util.List;

public final class ClientLowPressureStore {
    private static final long EXPIRE_MILLIS = 12_000L;
    private static List<LowPressureSyncPayload.LowPressureSnapshot> systems = List.of();
    private static long expiresAtMillis;

    private ClientLowPressureStore() {
    }

    public static void accept(LowPressureSyncPayload payload) {
        systems = payload == null || payload.systems() == null ? List.of() : payload.systems();
        expiresAtMillis = System.currentTimeMillis() + EXPIRE_MILLIS;
    }

    public static List<LowPressureSyncPayload.LowPressureSnapshot> active() {
        if (System.currentTimeMillis() > expiresAtMillis) {
            systems = List.of();
        }
        return systems;
    }
}
