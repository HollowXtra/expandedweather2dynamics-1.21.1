package com.imbet.weather;

import com.imbet.Config;
import com.imbet.spotter.SpotterService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Optional;

public final class TornadoThreatService {
    /**
     * A funnel narrower than this on paper still destroys its own width of ground; below it the shelter
     * arithmetic would treat a real tornado as a point and put villagers in its path.
     */
    private static final double MIN_CORE_RADIUS_BLOCKS = 10.0D;

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
                DynamicStormState state = DynamicStormController.stateFor(storm);
                best = closer(best, threatFrom(storm.pos, TornadoWindField.maxWindMph(storm), position, rangeSqr,
                        storm.motion, state.damagePathWidth() * 0.5D));
                for (DynamicStormState.TornadoCirculationState satellite : state.satelliteTornadoes()) {
                    Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
                    best = closer(best, threatFrom(center, DynamicStormController.satelliteWindMph(storm, satellite), position, rangeSqr,
                            satellite.motion(), satellite.fullDamagePathWidth() * 0.5D));
                }
            }
        }
        // A spotter's report is a threat like any other while it stands. That is what makes a warned village
        // survive: the drill below asks for the nearest threat and shelters from it, so a report standing in
        // front of a village puts its villagers under cover before the mod's own scan would have said a word.
        for (SpotterService.Threat report : SpotterService.threatsNear(level, position, radiusBlocks)) {
            best = closer(best, threatFromReport(report, position));
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
                best = closer(best, threatFrom(event.position(), event.maxWindMph(), position, rangeSqr,
                        event.motion(), event.radius()));
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A reported circulation as a threat. Deliberately not routed through {@link #threatFrom}: that method's
     * wind floor is the one the mod uses to decide something is a tornado, and a report exists precisely to
     * be believed below it.
     */
    private static Threat threatFromReport(SpotterService.Threat report, Vec3 position) {
        double dx = report.x() - position.x;
        double dz = report.z() - position.z;
        return new Threat(new Vec3(report.x(), report.y(), report.z()), report.windMph(), Math.sqrt(dx * dx + dz * dz),
                report.motionX(), report.motionZ(), Math.max(MIN_CORE_RADIUS_BLOCKS, report.coreRadiusBlocks()));
    }

    private static Threat threatFrom(Vec3 center, double windMph, Vec3 position, double rangeSqr, @Nullable Vec3 motion, double coreRadiusBlocks) {
        if (windMph < 55.0D) {
            return null;
        }
        double dx = center.x - position.x;
        double dz = center.z - position.z;
        double distanceSqr = dx * dx + dz * dz;
        if (distanceSqr > rangeSqr) {
            return null;
        }
        Vec3 drift = motion == null ? Vec3.ZERO : motion;
        return new Threat(center, windMph, Math.sqrt(distanceSqr), drift.x, drift.z,
                Math.max(MIN_CORE_RADIUS_BLOCKS, coreRadiusBlocks));
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

    /**
     * The funnel's ground track and footprint, which is what the shelter AI needs to know not just that
     * a tornado exists but where it is going: a shelter in front of the funnel is not a shelter.
     *
     * @param motionX,motionZ    blocks per tick the circulation is drifting
     * @param coreRadiusBlocks   half the damaged path width, the radius the funnel actually destroys
     */
    public record Threat(Vec3 center, double windMph, double distanceBlocks, double motionX, double motionZ,
                         double coreRadiusBlocks) {
        /** Where the funnel is expected to be after this many ticks. */
        public Vec3 projectedCenter(int leadTicks) {
            return new Vec3(center.x + motionX * leadTicks, center.y, center.z + motionZ * leadTicks);
        }
    }
}
