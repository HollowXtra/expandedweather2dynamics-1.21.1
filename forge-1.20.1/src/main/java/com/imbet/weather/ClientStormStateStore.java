package com.imbet.weather;

import com.imbet.network.StormStateSyncPayload;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientStormStateStore {
    private static final Map<Long, CompoundTag> PENDING_STATES = new ConcurrentHashMap<>();

    private ClientStormStateStore() {
    }

    public static void accept(StormStateSyncPayload payload) {
        PENDING_STATES.put(payload.stormId(), payload.state().copy());
    }

    public static CompoundTag consume(long stormId) {
        return PENDING_STATES.remove(stormId);
    }
}
