package com.imbet.weather;

import com.imbet.Config;
import com.imbet.chase.ChaseLogService;
import com.imbet.photo.PhotoAimService;
import com.imbet.spotter.SpotterService;
import com.imbet.alert.AlertStationNetwork;
import com.imbet.compat.immersiveaircraft.ImmersiveAircraftStormCompat;
import com.imbet.compat.mts.ImmersiveVehiclesStormCompat;
import com.imbet.compat.treephysics.TreePhysicsStormCompat;
import com.imbet.instruments.HandheldInstrumentItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import com.imbet.registry.ModContent;
import com.imbet.survey.TornadoTrackService;
import com.imbet.volcano.VolcanoService;
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
    /** Keeps a player's per-player alert cooldown from ever colliding with a storm or event ID. */
    private static final long PLAYER_ALERT_KEY_BASE = -1_000_000_000L;

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
        // The fronts then make their own weather, and an extreme day with a deep low over it runs an
        // outbreak: both are driven by the lows above, so they tick beside them and in that order.
        FrontalConvectionService.tick(level);
        OutbreakService.tick(level);
        // A twin is seeded beside a tornado the outbreak may well have put there, so it ticks after the
        // systems that decide which storms are tornadic, and before the survey that will record both tracks.
        TwinTornadoService.tick(level);
        // Volcanoes run on every tick rather than on the weather scan: a lava front, a lahar and a collapsing
        // column are the level's own events, and none of them have anything to do with where the storms are.
        VolcanoService.tick(level);
        // The sky's and the land's big moments: eclipse forecasts, and the advancements for being there.
        SpectacleService.tick(level);
        ForecastReadout.tick(level);
        TornadoTrackService.tick(level);
        // The chase log reads the same snapshots the survey does and runs beside it, on its own interval:
        // it is a record of what players were near, so it has to see the world before the storms move on.
        ChaseLogService.tick(level);
        // The spotter net runs its own interval: it is deciding whether the world proved or outlasted a
        // report, and a warning that stood for a minute is not made truer by being checked every tick.
        SpotterService.tick(level);
        // The camera's viewfinder is fed from here on its own interval: it is the server's list of circulations
        // that a held camera is shown, so the reticle and the shutter cannot disagree about what is in frame.
        PhotoAimService.tick(level);
        TornadoPlayerCapture.tick(level);
        ImmersiveVehiclesStormCompat.tick(level);
        ImmersiveAircraftStormCompat.tick(level);
        TreePhysicsStormCompat.tick(level);
        // Trees that are already airborne still have to land, whatever happened to the storm that threw
        // them, so their landings resolve on the level's own tick rather than on a storm's.
        TreeLift.tickImpacts(level);
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
        maybeAlertVolcanoes(level);
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
            // A joining client has no volcano state either, and a 12 km column drawn only from the next
            // periodic sync would arrive a second late and from nowhere.
            VolcanoService.pushTo(player);
            // A bounty earned for a report confirmed while the spotter was offline is handed over here: a
            // reward that only pays when the server happens to see you is worse than no reward at all.
            SpotterService.payHeldBounty(player);
        }
    }

    /**
     * Forgets what a leaving player's handheld instruments had recorded.
     *
     * <p>A handheld instrument's memory is a record of what its holder has seen - a peak gust and a pressure
     * trend - and it lives on the server rather than in the item, so it is dropped when the holder goes. A
     * server that kept one per player who ever held a barometer would grow a map of everyone who ever joined.
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HandheldInstrumentItem.forget(player.getUUID());
            HailClatterService.forget(player.getUUID());
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
        if (event.getLevel().isClientSide()) {
            return;
        }
        // A bolt is remembered before anything else looks at it, because a bolt only exists for two ticks and
        // nothing downstream of this event can ask about it afterwards. See ServerLightningLog: the camera's
        // lightning bonus is a window rather than an entity, for exactly that reason.
        if (event.getEntity() instanceof LightningBolt bolt && event.getLevel() instanceof ServerLevel serverLevel) {
            ServerLightningLog.record(serverLevel, bolt.getX(), bolt.getY(), bolt.getZ());
        }
        // A herd's drill goes on animals rather than villagers, which is a separate selector and a separate
        // decision: a villager runs into a house, and an animal bunches with the animals next to it.
        if (event.getEntity() instanceof Animal animal) {
            AnimalHerdGoal.attach(animal);
        }
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        // Every drill is attached to every villager and each one checks its own config and its own need
        // before it acts; the three jobs additionally check the villager's profession, because a villager
        // can be hired long after it spawned and re-attaching a goal per promotion would leave a trail of
        // dead ones behind.
        TornadoShelterGoal.attach(villager);
        com.imbet.village.SurveyorGoal.attach(villager);
        com.imbet.village.BuilderGoal.attach(villager);
        com.imbet.village.LumberjackGoal.attach(villager);
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
     * The ungated volcanic product, for worlds that run alerts without siren stations.
     *
     * <p>With a station online the volcanic products go through {@link AlertService#highestAlert} with
     * everything else, because a siren is told to sound by the strongest product it can see. This is the
     * other path: no station, so the advisory is broadcast directly to whoever it covers.
     */
    private static void maybeAlertVolcanoes(ServerLevel level) {
        if (!Config.VOLCANO_ENABLED.get() || !Config.VOLCANO_ALERTS.get()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            AlertService.AlertResult result = VolcanoService.alertFor(level, player.position());
            if (result.level() == AlertLevel.NONE) {
                continue;
            }
            // A player's own cooldown slot, offset clear of the storm and event ID space.
            long key = PLAYER_ALERT_KEY_BASE - player.getId();
            Long last = PARTICLE_ALERT_TICKS.get(key);
            if (last != null && last + Config.ALERT_COOLDOWN_TICKS.get() > level.getGameTime()) {
                continue;
            }
            PARTICLE_ALERT_TICKS.put(key, level.getGameTime());
            sendAlert(player, result);
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
