package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Optional;

public final class TornadoThreatService {
    private TornadoThreatService() {
    }

    public static Optional<Threat> nearestThreat(ServerLevel level, Vec3 position, double radiusBlocks) {
        Threat best = null;
        double rangeSqr = radiusBlocks * radiusBlocks;
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm) || !DynamicStormController.isDynamicVortex(storm)) {
                    continue;
                }
                best = closer(best, threatFrom(storm.pos, TornadoWindField.maxWindMph(storm), position, rangeSqr));
                DynamicStormState state = DynamicStormController.stateFor(storm);
                for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                    Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                    best = closer(best, threatFrom(center, DynamicStormController.satelliteWindMph(storm, satellite), position, rangeSqr));
                }
            }
        }
        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (event.isLine()) {
                continue;
            }
            if (event.type() == StormSubtype.LANDSPOUT
                    || event.type() == StormSubtype.WATERSPOUT
                    || event.type() == StormSubtype.DUST_DEVIL
                    || event.type() == StormSubtype.SNOW_DEVIL
                    || event.type() == StormSubtype.FIRE_WHIRL
                    || event.type() == StormSubtype.FLOATER_TORNADO) {
                best = closer(best, threatFrom(event.position(), event.maxWindMph(), position, rangeSqr));
            }
        }
        return Optional.ofNullable(best);
    }

    private static Threat threatFrom(Vec3 center, double windMph, Vec3 position, double rangeSqr) {
        if (windMph < 55.0D) {
            return null;
        }
        double dx = center.x - position.x;
        double dz = center.z - position.z;
        double distanceSqr = dx * dx + dz * dz;
        if (distanceSqr > rangeSqr) {
            return null;
        }
        return new Threat(center, windMph, Math.sqrt(distanceSqr));
    }

    private static Threat closer(Threat current, Threat candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        double currentScore = current.distanceBlocks() - current.windMph() * 0.18D;
        double candidateScore = candidate.distanceBlocks() - candidate.windMph() * 0.18D;
        return candidateScore < currentScore ? candidate : current;
    }

    public static int configuredRadius() {
        return Math.max(32, Config.AI_VILLAGER_SHELTER_RADIUS_BLOCKS.get());
    }

    public record Threat(Vec3 center, double windMph, double distanceBlocks) {
    }
}
