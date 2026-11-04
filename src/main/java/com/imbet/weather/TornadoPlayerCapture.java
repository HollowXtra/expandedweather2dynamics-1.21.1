package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side capture registry for the player launch system. Every few ticks we mark players whose
 * violent-core capture ramp is active; while marked (plus a grace period after they leave the core),
 * fall damage is cancelled so a launch that drops the player from altitude doesn't instantly kill them.
 */
public final class TornadoPlayerCapture {
    private static final int SAMPLE_INTERVAL_TICKS = 5;
    private static final int GRACE_TICKS = 120;

    private static final Map<UUID, Long> CAPTURED_UNTIL = new HashMap<>();

    private TornadoPlayerCapture() {
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        CAPTURED_UNTIL.entrySet().removeIf(entry -> entry.getValue() < now);
        if (!Config.WINDFIELD_PLAYER_LAUNCH_ENABLED.get() || !Config.WINDFIELD_PLAYER_LAUNCH_FALL_SAFE.get() || now % SAMPLE_INTERVAL_TICKS != 0L) {
            return;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null || level.players().isEmpty()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (captureRampAt(manager, player.position()) > 0.0D) {
                CAPTURED_UNTIL.put(player.getUUID(), now + GRACE_TICKS);
            }
        }
    }

    /** Marks a captured entity (player or mob) so its fall damage is cancelled until the grace expires. */
    public static void markCaptured(java.util.UUID uuid, long gameTime) {
        if (uuid == null) {
            return;
        }
        CAPTURED_UNTIL.put(uuid, gameTime + GRACE_TICKS);
    }

    public static void onFall(LivingFallEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity) || !Config.WINDFIELD_PLAYER_LAUNCH_FALL_SAFE.get()) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (CAPTURED_UNTIL.getOrDefault(entity.getUUID(), 0L) >= level.getGameTime()) {
            event.setCanceled(true);
        }
    }

    private static double captureRampAt(WeatherManagerServer manager, Vec3 position) {
        double best = 0.0D;
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || !DynamicStormController.isDynamicVortex(storm) || DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            TornadoWindField.WindSample sample = TornadoWindField.sample(storm, manager.getWorld(), position);
            double ramp = TornadoDamageModel.playerCaptureRamp(sample, Math.max(sample.speedMph(), sample.strongestLocalMph()));
            best = Math.max(best, ramp);
        }
        return best;
    }
}