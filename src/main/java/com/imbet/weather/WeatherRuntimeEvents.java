package com.imbet.weather;

import com.imbet.Config;
import com.imbet.alert.AlertStationNetwork;
import com.imbet.compat.immersiveaircraft.ImmersiveAircraftStormCompat;
import com.imbet.compat.mts.ImmersiveVehiclesStormCompat;
import com.imbet.compat.treephysics.TreePhysicsStormCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import com.imbet.registry.ModContent;
import com.imbet.survey.TornadoTrackService;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;
import weather2.weathersystem.storm.WeatherObjectParticleStorm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class WeatherRuntimeEvents {
    private static final Map<Long, Long> PARTICLE_ALERT_TICKS = new HashMap<>();

    private WeatherRuntimeEvents() {
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        TornadoMergeService.flushPendingRemovals(level);
        SupercellMergeService.tick(level);
        SevereWeatherManager.tick(level);
        LowPressureService.tick(level);
        ForecastReadout.tick(level);
        TornadoTrackService.tick(level);
        TornadoPlayerCapture.tick(level);
        ImmersiveVehiclesStormCompat.tick(level);
        ImmersiveAircraftStormCompat.tick(level);
        TreePhysicsStormCompat.tick(level);
        // Alert stations run on their own refresh cadence, and keep broadcasting while unloaded, so
        // this is called every tick rather than on the storm scan interval.
        AlertStationNetwork.broadcast(level);
        if (!Config.ALERTS_ENABLED.get() || level.getGameTime() % Config.PERFORMANCE_STORM_SCAN_INTERVAL_TICKS.get() != 0L) {
            return;
        }
        if (Config.ALERTS_REQUIRE_SIREN.get()) {
            // Every alert belongs to a siren station now, so the global scan has nothing left to say.
            return;
        }
        pruneParticleAlertCooldowns(level.getGameTime());
        maybeAlertAddonEvents(level);
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (object instanceof StormObject storm) {
                maybeAlert(level, storm);
            } else if (object instanceof WeatherObjectParticleStorm particleStorm) {
                maybeAlert(level, particleStorm);
            }
        }
    }

    /**
     * A player who has just joined has no addon storm state yet, and the steady-state push is gated
     * both by distance and by {@code multiplayer.stateSyncIntervalTicks}. Until it comes round they
     * would render a mature tornado as a fresh, narrow one with none of its satellites, sub-vortices
     * or horizontal vortices. Pushing everything nearby on login makes their first frame correct.
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DynamicStormController.pushAddonStateTo(player);
        }
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        if (event.getPlacedBlock().is(ModContent.RADAR_BLOCK.get())) {
            AdvancementAwarder.award(player, AdvancementAwarder.USE_RADAR);
        } else if (event.getPlacedBlock().is(ModContent.ANEMOMETER_BLOCK.get())) {
            AdvancementAwarder.award(player, AdvancementAwarder.USE_ANEMOMETER);
        } else if (event.getPlacedBlock().is(ModContent.WEATHER_ALERT_SIREN_BLOCK.get())) {
            AdvancementAwarder.award(player, AdvancementAwarder.USE_RADAR);
        }
    }

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !Config.AI_VILLAGER_TORNADO_SHELTER.get()) {
            return;
        }
        if (event.getEntity() instanceof Villager villager) {
            TornadoShelterGoal.attach(villager);
        }
    }

    private static void maybeAlert(ServerLevel level, StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        long gameTime = level.getGameTime();
        if (state.isAbsorbedOrDecaying() || state.lastAlertTick() + Config.ALERT_COOLDOWN_TICKS.get() > gameTime) {
            return;
        }
        boolean sent = false;
        for (ServerPlayer player : level.players()) {
            AlertService.AlertResult result = AlertService.alertForStorm(storm, player.position());
            if (result.level() != AlertLevel.NONE) {
                sendAlert(player, result);
                sent = true;
            }
            for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                if (player.position().distanceTo(center) <= Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
                    sendAlert(player, new AlertService.AlertResult(AlertLevel.TORNADO_WARNING, "Satellite Tornado", "TOR", "OBSERVED", center, center.add(satellite.motion().scale(Config.ALERT_WARNING_PATH_LEAD_TICKS.get())), satellite.id(), level.getGameTime() + Config.ALERT_COOLDOWN_TICKS.get()));
                    sent = true;
                }
            }
        }
        if (sent) {
            state.setLastAlertTick(gameTime);
        }
    }

    private static void maybeAlert(ServerLevel level, WeatherObjectParticleStorm storm) {
        // Particle storms can remain radar/SPC hazards, but they are not TOR/SVR EAS-style products.
    }

    private static void maybeAlertAddonEvents(ServerLevel level) {
        if (!Config.ALERTS_ENABLED.get() || level.getGameTime() % Config.PERFORMANCE_STORM_SCAN_INTERVAL_TICKS.get() != 0L) {
            return;
        }
        long gameTime = level.getGameTime();
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.lastAlertTick() + Config.ALERT_COOLDOWN_TICKS.get() > gameTime) {
                continue;
            }
            boolean sent = false;
            for (ServerPlayer player : level.players()) {
                AlertService.AlertResult result = AlertService.alertForAddonEvent(level, event, player.position());
                if (result.level() != AlertLevel.NONE) {
                    sendAlert(player, result);
                    sent = true;
                }
            }
            if (sent) {
                event.setLastAlertTick(gameTime);
            }
        }
    }

    /**
     * The ungated path: used only when siren gating is switched off, where an alert goes to every
     * player the storm reaches. A negative siren distance tells the banner no station covers them.
     */
    private static void sendAlert(ServerPlayer player, AlertService.AlertResult result) {
        AlertBroadcaster.send(player, result, -1, "", true);
    }

    private static void pruneParticleAlertCooldowns(long gameTime) {
        long maxAge = Config.ALERT_COOLDOWN_TICKS.get() * 4L;
        Iterator<Map.Entry<Long, Long>> iterator = PARTICLE_ALERT_TICKS.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() + maxAge < gameTime) {
                iterator.remove();
            }
        }
    }
}
