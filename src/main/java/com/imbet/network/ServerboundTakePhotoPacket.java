package com.imbet.network;

import com.imbet.Config;
import com.imbet.ExpandedWeather2Dynamics;
import com.imbet.photo.PhotoService;
import com.imbet.registry.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The shutter, as told to the server: which way the camera was pointed and how wide its view was.
 *
 * <p>Those two numbers are the only things about a photograph the server cannot see for itself. Where the
 * player is standing and what is in the world are the server's; the aim is a rendering-level fact and the
 * field of view is a client option. So the client reports both, and then nothing else about the shot is
 * trusted: the subject is picked, the line of sight is cast and the score is computed server-side, from the
 * server's own world.
 *
 * <p>An aim that is not three finite numbers, or is too short to have a direction, is handed on as a zero
 * vector, which the service reads as "no aim given" and answers with the player's own look angle. Nothing is
 * spent unless a photograph is actually filed, so a malformed packet costs the sender nothing.
 */
public record ServerboundTakePhotoPacket(double aimX, double aimY, double aimZ, double fov) implements CustomPacketPayload {
    public static final Type<ServerboundTakePhotoPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExpandedWeather2Dynamics.MODID, "take_photo"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundTakePhotoPacket> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.write(buf),
            ServerboundTakePhotoPacket::read
    );

    public ServerboundTakePhotoPacket {
        boolean usable = Double.isFinite(aimX) && Double.isFinite(aimY) && Double.isFinite(aimZ)
                && (aimX * aimX + aimY * aimY + aimZ * aimZ) > 1.0E-6D;
        if (!usable) {
            aimX = 0.0D;
            aimY = 0.0D;
            aimZ = 0.0D;
        }
        fov = Double.isFinite(fov) ? fov : 70.0D;
    }

    public static void handle(ServerboundTakePhotoPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PhotoService.Outcome outcome = PhotoService.take(player, new Vec3(payload.aimX(), payload.aimY(), payload.aimZ()), payload.fov());
            if (outcome != PhotoService.Outcome.FILED) {
                return;
            }
            int cooldown = Math.max(0, Config.PHOTO_COOLDOWN_TICKS.get());
            if (cooldown > 0) {
                player.getCooldowns().addCooldown(ModContent.CAMERA_ITEM.get(), cooldown);
            }
        });
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeDouble(aimX);
        buf.writeDouble(aimY);
        buf.writeDouble(aimZ);
        buf.writeDouble(fov);
    }

    private static ServerboundTakePhotoPacket read(RegistryFriendlyByteBuf buf) {
        return new ServerboundTakePhotoPacket(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
