package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.TickEvent.LevelTickEvent;
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

    public static void onLevelTick(LevelTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel level)) {
            return;
        }
        TornadoMergeService.flushPendingRemovals(level);
        SupercellMergeService.tick(level);
        AddonWeatherEventService.tick(level);
        TornadoTrackService.tick(level);
        maybeAlertAddonEvents(level);
        if (!Config.ALERTS_ENABLED.get() || level.getGameTime() % Config.PERFORMANCE_STORM_SCAN_INTERVAL_TICKS.get() != 0L) {
            return;
        }
        pruneParticleAlertCooldowns(level.getGameTime());
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

    private static void sendAlert(ServerPlayer player, AlertService.AlertResult result) {
        int distance = (int) player.position().distanceTo(result.position());
        int leadDistance = (int) player.position().distanceTo(result.projectedPosition());
        player.sendSystemMessage(Component.literal("[Expanded Weather Fictional In-Game Alert] " + result.productCode() + " / " + result.level().label() + ": " + result.label() + " " + result.source() + ", " + distance + " blocks away, projected path " + leadDistance + " blocks away.")
                .withStyle(result.level().isWarning() ? ChatFormatting.RED : ChatFormatting.GOLD));
        player.displayClientMessage(Component.literal(result.level().label() + " - " + distance + " blocks away").withStyle(result.level().isWarning() ? ChatFormatting.RED : ChatFormatting.YELLOW), true);
        if (result.level().activatesEas() && Config.ALERTS_FAUX_SOUND.get()) {
            player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.8F, result.level().isWarning() ? 0.55F : 0.82F);
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.7F, result.level().isWarning() ? 1.0F : 1.25F);
        }
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
