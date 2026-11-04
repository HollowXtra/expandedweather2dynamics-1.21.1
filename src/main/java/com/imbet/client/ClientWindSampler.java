package com.imbet.client;

import com.imbet.weather.AddonWeatherEvent;
import com.imbet.weather.ClientAddonWeatherEventStore;
import com.imbet.weather.DynamicStormController;
import com.imbet.weather.StormSubtype;
import com.imbet.weather.SupercellStructureService;
import com.imbet.weather.TornadoWindField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import weather2.ClientTickHandler;
import weather2.weathersystem.WeatherManagerClient;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;
import weather2.weathersystem.wind.WindManager;

import java.util.ArrayList;

/**
 * The wind the client believes is blowing at a point, in mph.
 *
 * <p>It exists because more than one thing on the client reacts to the wind - the foliage sway shader and the
 * ocean's wave field - and each having its own reading is how two features end up disagreeing about how hard
 * it is blowing at the same place. There is one sampler, and everyone asks it.
 *
 * <p>It reads every source the client has: the tornado's own wind field, the supercell's structure wind, the
 * broad wind of any thunderstorm near, and the addon's hurricane/cyclone/dust-devil events, of which a
 * hurricane's is by far the largest and the reason this matters over open sea. A candidate only replaces the
 * running best if it is genuinely faster, compared on the raw speed rather than on a normalized strength -
 * strength is a per-feature scale, and two candidates could both saturate it, which would let an arbitrary
 * one of them win on iteration order.
 *
 * <p>Everything here is a pure read of state the client already has. Nothing is synced for it, so a wave
 * field and a foliage shader computing from the same numbers on different machines still agree.
 */
public final class ClientWindSampler {

    /**
     * mph per unit of Weather2's cloud wind, which is the conversion the rest of this mod already uses for
     * that reading - the same one the tornado's own wind field and the thermodynamic profile are built on.
     * It is here rather than inlined so the two cannot drift and start reporting different air.
     */
    private static final double CLOUD_WIND_MPH_PER_UNIT = 18.0D;

    /**
     * A wind reading: the horizontal vector in mph, and its speed.
     *
     * <p>Vector and speed are both carried rather than one derived from the other, because the callers want
     * different things - the waves need a direction and a magnitude, the foliage needs the magnitude and a
     * normalized direction - and the vector is not normalized here (a 4 mph breeze is a much shorter vector
     * than a hurricane).
     */
    public record Wind(Vec3 vectorMph, double speedMph) {
        public static final Wind CALM = new Wind(Vec3.ZERO, 0.0D);

        /** Whichever of two readings is the faster; ties keep the first, so the answer is stable. */
        public Wind stronger(Wind other) {
            return other != null && other.speedMph > speedMph ? other : this;
        }
    }

    private ClientWindSampler() {
    }

    /**
     * The strongest wind found within {@code rangeBlocks} of {@code position}.
     *
     * <p>Only the strongest one is returned: a point can sit inside a tornado's inflow, its parent storm's
     * outflow and a passing hurricane's wind field at the same time, and what a leaf or a wave responds to is
     * the fastest of them, not their sum.
     */
    public static Wind sample(ClientLevel level, Vec3 position, int rangeBlocks) {
        if (level == null || position == null) {
            return Wind.CALM;
        }
        double rangeSqr = (double) rangeBlocks * rangeBlocks;
        Wind best = Wind.CALM;

        WeatherManagerClient manager = ClientTickHandler.weatherManager;
        if (manager != null) {
            // The ambient wind first, and unconditionally: it is the air itself rather than the air any one
            // storm is making, so it is present when there is no storm at all - which is most of the time,
            // and is exactly when a reading taken only from storms reports a dead calm that is not there.
            if (manager.getWindManager() != null) {
                best = best.stronger(ambientWind(manager.getWindManager()));
            }
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead
                        || horizontalDistanceSqr(position, storm.pos) > rangeSqr) {
                    continue;
                }
                if (DynamicStormController.isDynamicVortex(storm) && !DynamicStormController.isAbsorbedOrDecaying(storm)) {
                    best = best.stronger(fromWindSample(TornadoWindField.sample(storm, level, position)));
                }
                best = best.stronger(fromWindSample(SupercellStructureService.sampleWind(storm, level, position)));
                best = best.stronger(broadStormWind(storm, position, rangeBlocks));
            }
        }

        for (AddonWeatherEvent event : ClientAddonWeatherEventStore.events()) {
            if (event == null || horizontalDistanceSqr(position, event.position()) > rangeSqr) {
                continue;
            }
            best = best.stronger(fromWindSample(sampleAddonWind(event, position)));
        }
        return best;
    }

    /**
     * Weather2's own ambient wind, as the client knows it.
     *
     * <p>This is the reading the clouds are drawn from, so taking it as well is what keeps the leaves and
     * the sky over them agreeing about which way the air is moving and how hard. It is also the only source
     * that is still there on a clear day, when a sampler reading storms alone has nothing to report.
     */
    private static Wind ambientWind(WindManager windManager) {
        double speed = Math.max(0.0D, windManager.getWindSpeedForClouds() * CLOUD_WIND_MPH_PER_UNIT);
        if (speed <= 0.01D) {
            return Wind.CALM;
        }
        // The same angle convention as WindSteeringManager, so a leaf and a storm are pushed by the same air.
        double angle = Math.toRadians(windManager.getWindAngleForClouds());
        return fromVector(new Vec3(-Math.sin(angle) * speed, 0.0D, Math.cos(angle) * speed));
    }

    /**
     * The broad, non-rotational wind of a storm: a thunderstorm's own motion dragging air along with it.
     *
     * <p>Without it a storm that has not yet produced a vortex reads as dead calm, so a leaf hangs still
     * under a gusty shelf cloud and the sea stays glassy ahead of it.
     */
    private static Wind broadStormWind(StormObject storm, Vec3 position, int range) {
        if (storm.levelCurIntensityStage < StormObject.STATE_THUNDER && !storm.attrib_precipitation) {
            return Wind.CALM;
        }
        Vec3 motion = storm.motion == null ? Vec3.ZERO : new Vec3(storm.motion.x, 0.0D, storm.motion.z);
        if (motion.horizontalDistanceSqr() < 1.0E-5D) {
            return Wind.CALM;
        }
        double distance = Math.sqrt(horizontalDistanceSqr(position, storm.pos));
        double falloff = Mth.clamp(1.0D - distance / Math.max(1.0D, range), 0.0D, 1.0D);
        double stage = Mth.clamp(storm.levelCurIntensityStage / (double) StormObject.STATE_STAGE5, 0.0D, 1.0D);
        return fromVector(motion.normalize().scale((10.0D + stage * 46.0D) * falloff));
    }

    /**
     * An addon event's wind at a point: a hurricane's swirling field, or the straight-line wind of a squall
     * line.
     *
     * <p>This is the reading that matters most for the sea. A hurricane's wind field is hundreds of blocks
     * across, so it is the one source that is still blowing at full strength when the player is nowhere near
     * a tornado.
     */
    private static TornadoWindField.WindSample sampleAddonWind(AddonWeatherEvent event, Vec3 position) {
        if (event.isLine()) {
            return sampleLineWind(event, position);
        }
        Vec3 center = new Vec3(event.position().x, position.y, event.position().z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.4D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double rmw = Math.max(1.2D, event.radius());
        if (radius > rmw * 4.0D) {
            return TornadoWindField.WindSample.CALM;
        }
        double max = event.maxWindMph();
        double tangential = radius <= rmw ? max * (radius / rmw) : max * Math.pow(rmw / radius, 0.86D);
        Vec3 radial = fromCenter.scale(1.0D / radius);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double inflow = event.type() == StormSubtype.DUST_DEVIL ? 0.10D : event.type() == StormSubtype.GUSTNADO ? 0.055D : event.type() == StormSubtype.WATERSPOUT ? 0.24D : 0.18D;
        Vec3 vector = tangent.scale(tangential).add(radial.scale(-tangential * inflow));
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), radius, rmw * 2.0D, rmw);
    }

    private static TornadoWindField.WindSample sampleLineWind(AddonWeatherEvent event, Vec3 position) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 closest = closestPointOnSegment(position, start, end);
        double distance = Math.sqrt(horizontalDistanceSqr(position, closest));
        if (distance > event.radius()) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 line = end.subtract(start);
        Vec3 along = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(line.x, 0.0D, line.z).normalize();
        Vec3 motion = event.motion();
        Vec3 forward = motion != null && motion.horizontalDistanceSqr() > 1.0E-5D ? new Vec3(motion.x, 0.0D, motion.z).normalize() : new Vec3(-along.z, 0.0D, along.x);
        double falloff = Mth.clamp(1.0D - distance / Math.max(1.0D, event.radius()), 0.0D, 1.0D);
        Vec3 vector = forward.scale(event.maxWindMph() * falloff);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, event.radius(), event.radius() * 0.5D);
    }

    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 ab = end.subtract(start);
        double lengthSqr = ab.x * ab.x + ab.z * ab.z;
        if (lengthSqr <= 1.0E-5D) {
            return start;
        }
        double t = ((point.x - start.x) * ab.x + (point.z - start.z) * ab.z) / lengthSqr;
        t = Mth.clamp(t, 0.0D, 1.0D);
        return new Vec3(start.x + ab.x * t, point.y, start.z + ab.z * t);
    }

    private static Wind fromWindSample(TornadoWindField.WindSample sample) {
        if (sample == null || sample.speedMph() <= 0.01D) {
            return Wind.CALM;
        }
        return fromVector(new Vec3(sample.mphVector().x, 0.0D, sample.mphVector().z));
    }

    /** A horizontal vector as a reading. A vertical component would be meaningless to either caller. */
    public static Wind fromVector(Vec3 vector) {
        if (vector == null) {
            return Wind.CALM;
        }
        Vec3 horizontal = new Vec3(vector.x, 0.0D, vector.z);
        double speed = Math.sqrt(horizontal.x * horizontal.x + horizontal.z * horizontal.z);
        return speed <= 0.01D ? Wind.CALM : new Wind(horizontal, speed);
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }
}
