package com.imbet.photo;

import com.imbet.Config;
import com.imbet.network.PhotoAimPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Feeds the viewfinder of every player holding a camera.
 *
 * <p>This is a tick pass and not a request, which is the whole reason a viewfinder can be live: the candidate list
 * is one thing the client genuinely cannot work out for itself without keeping a second, private list of storms
 * that would drift from the shutter's. So the server publishes it on an interval - four ticks by default, a fifth
 * of a second, which is short enough that a funnel moving at fifty blocks a second has not moved far enough to
 * matter and long enough that the payload is nothing - and the client does the rest of it every frame.
 *
 * <p>What is sent is what the shutter would accept: the floor, the camera's range and the level's own list of
 * circulations, all decided here. Nothing is sent to a player who is not holding a camera, and nothing is sent at
 * all when photography is switched off or the viewfinder is - so the cost of this feature is paid only by the
 * players using it.
 */
public final class PhotoAimService {
    /** The interval is floored at a tick so the knob cannot turn this into a packet per tick. */
    public static final int MIN_INTERVAL_TICKS = 1;

    private PhotoAimService() {
    }

    /** Runs the pass for one level, on its own interval. */
    public static void tick(ServerLevel level) {
        if (level == null || !Config.PHOTO_ENABLED.get() || !Config.PHOTO_VIEWFINDER.get()) {
            return;
        }
        int interval = Mth.clamp(Config.PHOTO_AIM_INTERVAL_TICKS.get(), MIN_INTERVAL_TICKS, 200);
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        List<ServerPlayer> watchers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (PhotoService.holdsCamera(player)) {
                watchers.add(player);
            }
        }
        if (watchers.isEmpty()) {
            return;
        }
        // The candidates are the level's, so they are worked out once and then filtered per eye: two players
        // holding cameras in the same world are looking at the same circulations from different places.
        List<PhotoService.Subject> subjects = PhotoService.subjects(level);
        double maxDistance = Math.max(8.0D, Config.PHOTO_MAX_DISTANCE_BLOCKS.get());
        double sweetMin = Math.max(1.0D, Config.PHOTO_SWEET_SPOT_MIN_BLOCKS.get());
        double sweetMax = Math.max(sweetMin, Config.PHOTO_SWEET_SPOT_MAX_BLOCKS.get());
        double floor = Config.PHOTO_MIN_SUBJECT_MPH.get();
        for (ServerPlayer player : watchers) {
            Vec3 eye = player.getEyePosition();
            List<PhotoTarget> targets = PhotoService.targetsOf(subjects, eye, maxDistance);
            PacketDistributor.sendToPlayer(player,
                    new PhotoAimPayload(targets, maxDistance, sweetMin, sweetMax, floor));
        }
    }
}
