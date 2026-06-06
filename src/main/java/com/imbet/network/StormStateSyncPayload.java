package com.imbet.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

public record StormStateSyncPayload(long stormId, CompoundTag state) {
    public StormStateSyncPayload {
        state = state == null ? new CompoundTag() : state.copy();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(stormId);
        buf.writeNbt(state);
    }

    public static StormStateSyncPayload read(FriendlyByteBuf buf) {
        long stormId = buf.readLong();
        CompoundTag state = buf.readNbt();
        return new StormStateSyncPayload(stormId, state == null ? new CompoundTag() : state);
    }
}
