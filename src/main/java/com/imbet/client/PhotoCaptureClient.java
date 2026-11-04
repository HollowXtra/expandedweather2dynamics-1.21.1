package com.imbet.client;

import com.imbet.network.ServerboundTakePhotoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The shutter's client half: the two facts the server cannot see, and the click that says the picture was
 * taken.
 *
 * <p>The field of view is read from the player's own options rather than assumed, because it is what decides
 * whether a storm was in frame: scoring every photograph as though it had been taken through a default lens
 * would mark a zoomed-in shot down for being off centre when it was nowhere near the edge. The mod's own fear
 * effect narrows the view when a tornado is close, and that is a rendering effect rather than the lens, so the
 * option is the number this reads.
 *
 * <p>The click is played locally and only locally. A camera is heard by the person holding it.
 */
public final class PhotoCaptureClient {
    private PhotoCaptureClient() {
    }

    /** Sends the shutter, with the aim and the lens the client is using. */
    public static void capture(Player player) {
        if (player == null) {
            return;
        }
        Vec3 aim = player.getLookAngle();
        double fov = 70.0D;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.options != null) {
            fov = minecraft.options.fov().get();
        }
        PacketDistributor.sendToServer(new ServerboundTakePhotoPacket(aim.x, aim.y, aim.z, fov));
        // Locally, and only locally: a camera is heard by the person holding it, not by everyone within range
        // of a sound event they did not take a picture of.
        player.level().playLocalSound(player.getX(), player.getEyeY(), player.getZ(),
                SoundEvents.SPYGLASS_STOP_USING, SoundSource.PLAYERS, 0.9F, 1.0F, false);
    }
}
