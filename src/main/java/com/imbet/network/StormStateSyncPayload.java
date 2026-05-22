package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StormStateSyncPayload(long stormId, CompoundTag state) implements CustomPacketPayload {
    public static final Type<StormStateSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "storm_state_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StormStateSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            StormStateSyncPayload::read
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeLong(stormId);
        buf.writeNbt(state == null ? new CompoundTag() : state.copy());
    }

    private static StormStateSyncPayload read(RegistryFriendlyByteBuf buf) {
        long stormId = buf.readLong();
        CompoundTag state = buf.readNbt();
        return new StormStateSyncPayload(stormId, state == null ? new CompoundTag() : state);
    }
}
