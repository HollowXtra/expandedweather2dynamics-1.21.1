package com.imbet.network;

import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.drone.WeatherDroneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The pilot's controls, sent up from the client while they are flying a drone.
 *
 * <p>The movement keys travel because vanilla's player input packet is only applied to a player who is
 * riding something; a drone is flown remotely, so the keys have to come up in a packet of their own.
 *
 * <p>The pilot's <em>look</em> travels too, and it is the fix for a camera that could not be turned. The
 * camera is deliberately not aimed by an event or a mixin - see the client and its mixin - so the airframe's
 * own heading has to be told where the pilot is looking, or the model keeps the yaw it was placed with while
 * the view turns freely. Two floats a tick is the whole cost, and it means every other client sees the drone
 * point where its pilot is looking instead of skating sideways.
 *
 * <p>Six bits, one per control, in a single byte, plus the two angles. It is sent every client tick while
 * piloting, which is what makes the drone feel attached to the keyboard rather than to a network round trip,
 * and it stops being sent the moment the link does.
 */
public record DroneControlPayload(byte flags, float yaw, float pitch) implements CustomPacketPayload {
    public static final Type<DroneControlPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "drone_control"));

    public static final StreamCodec<FriendlyByteBuf, DroneControlPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeByte(payload.flags());
                buf.writeFloat(payload.yaw());
                buf.writeFloat(payload.pitch());
            },
            buf -> new DroneControlPayload(buf.readByte(), buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DroneControlPayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // Only the drone this player is actually flying accepts their input. Without that check one
            // player's keys could steer another player's drone.
            WeatherDroneEntity drone = null;
            for (WeatherDroneEntity candidate : player.serverLevel().getEntitiesOfClass(WeatherDroneEntity.class,
                    player.getBoundingBox().inflate(512.0D), entity -> entity.isPilotedBy(player))) {
                drone = candidate;
                break;
            }
            if (drone != null) {
                drone.setControlFlags(payload.flags());
                drone.setPilotLook(payload.yaw(), payload.pitch());
            }
        });
    }
}
