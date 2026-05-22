package com.imbet.weather;

import com.imbet.Config;
import com.imbet.survey.TornadoTrackService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TornadoMergeService {
    private static final Map<Long, Integer> OVERLAP_TICKS = new HashMap<>();
    private static final Map<Long, PendingRemoval> PENDING_REMOVALS = new LinkedHashMap<>();

    private TornadoMergeService() {
    }

    public static void tick(ServerLevel level, StormObject storm, DynamicStormState state) {
        if (!Config.MERGING_ENABLED.get() || !Config.MERGE_ABSORB_WEAKER.get() || storm == null || storm.pos == null || storm.isDead || state.isAbsorbedOrDecaying() || isRemovalQueued(storm.ID) || !DynamicStormController.isDynamicVortex(storm)) {
            return;
        }
        int interval = Math.max(5, Config.MERGE_SCAN_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        mergeSatellitesIntoParent(level, storm, state, interval);
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        List<WeatherObject> snapshot = new ArrayList<>(manager.getStormObjects());
        for (WeatherObject object : snapshot) {
            if (!(object instanceof StormObject other) || other == storm || other.pos == null || other.isDead || DynamicStormController.isAbsorbedOrDecaying(other) || isRemovalQueued(other.ID) || !DynamicStormController.isDynamicVortex(other)) {
                continue;
            }
            if (storm.ID >= other.ID) {
                continue;
            }
            tryMergeStormPair(level, manager, storm, other, interval);
        }
    }

    private static void mergeSatellitesIntoParent(ServerLevel level, StormObject storm, DynamicStormState state, int interval) {
        double parentRadius = Math.max(8.0D, DynamicStormController.baseRadiusForWind(storm));
        for (DynamicStormState.TornadoCirculationState satellite : new ArrayList<>(state.satelliteTornadoes())) {
            if (satellite.absorbed()) {
                continue;
            }
            Vec3 center = DynamicStormController.satelliteCenter(storm, satellite, storm.pos.y);
            double mergeDistance = Math.max(8.0D, (parentRadius + DynamicStormController.satelliteRadius(storm, satellite)) * 0.42D);
            if (!isConverging(center, satellite.motion(), storm.pos, storm.motion) || center.distanceTo(storm.pos) > mergeDistance) {
                reset(overlapKey(storm.ID, satellite.id()));
                continue;
            }
            int ticks = recordOverlap(overlapKey(storm.ID, satellite.id()), interval);
            if (ticks < Config.MERGE_OVERLAP_TICKS.get()) {
                continue;
            }
            if (Config.MERGE_ORBIT_BEFORE_ABSORB.get() && ticks < Config.MERGE_OVERLAP_TICKS.get() + Config.MERGE_ORBIT_TICKS.get()) {
                continue;
            }
            satellite.absorb();
            state.recordMerge(level.getGameTime(), satellite.damagePathWidth(), satellite.windFraction());
            TornadoTrackService.recordSatelliteMerge(level, storm.ID, satellite.id());
        }
    }

    private static void tryMergeStormPair(ServerLevel level, WeatherManagerServer manager, StormObject first, StormObject second, int interval) {
        double radius = Math.max(12.0D, (DynamicStormController.baseRadiusForWind(first) + DynamicStormController.baseRadiusForWind(second)) * 0.56D);
        if (first.pos.distanceTo(second.pos) > radius || !isConverging(first.pos, first.motion, second.pos, second.motion)) {
            reset(overlapKey(first.ID, second.ID));
            return;
        }
        int ticks = recordOverlap(overlapKey(first.ID, second.ID), interval);
        if (ticks < Config.MERGE_OVERLAP_TICKS.get()) {
            return;
        }
        StormObject stronger = strength(first) >= strength(second) ? first : second;
        StormObject weaker = stronger == first ? second : first;
        DynamicStormState strongerState = DynamicStormController.stateFor(stronger);
        DynamicStormState weakerState = DynamicStormController.stateFor(weaker);
        if (Config.MERGE_ORBIT_BEFORE_ABSORB.get() && ticks < Config.MERGE_OVERLAP_TICKS.get() + Config.MERGE_ORBIT_TICKS.get()) {
            Vec3 center = first.pos.add(second.pos).scale(0.5D);
            double orbitRadius = Math.max(8.0D, first.pos.distanceTo(second.pos) * 0.5D);
            strongerState.beginMergeOrbit(level.getGameTime(), weaker.ID, center, orbitRadius, 1.0F);
            weakerState.beginMergeOrbit(level.getGameTime(), stronger.ID, center, orbitRadius, -1.0F);
            manager.syncStormUpdate(stronger);
            manager.syncStormUpdate(weaker);
            return;
        }
        strongerState.recordMerge(level.getGameTime(), weakerState.damagePathWidth(), (float) Math.min(1.0D, TornadoWindField.maxWindMph(weaker) / Math.max(1.0D, TornadoWindField.maxWindMph(stronger))));
        strongerState.clearMergeOrbit();
        weakerState.clearMergeOrbit();
        if (stronger.motion != null && weaker.motion != null) {
            stronger.motion = stronger.motion.scale(0.72D).add(weaker.motion.scale(0.28D));
        }
        weakerState.markAbsorbedBySupercell(level.getGameTime(), stronger.ID);
        TornadoTrackService.recordStormMerge(level, stronger.ID, weaker.ID);
        if (Config.SUPERCELL_MERGE_HARD_REMOVE_ABSORBED.get()) {
            queueRemoval(manager, weaker);
        } else {
            manager.syncStormUpdate(stronger);
            manager.syncStormUpdate(weaker);
        }
        reset(overlapKey(first.ID, second.ID));
    }

    public static void flushPendingRemovals(ServerLevel level) {
        if (!Config.SUPERCELL_MERGE_HARD_REMOVE_ABSORBED.get() || PENDING_REMOVALS.isEmpty()) {
            return;
        }
        Iterator<PendingRemoval> iterator = PENDING_REMOVALS.values().iterator();
        while (iterator.hasNext()) {
            PendingRemoval removal = iterator.next();
            if (removal.manager.getWorld() != level) {
                continue;
            }
            if (!removal.storm.isDead) {
                removal.manager.removeWeatherObjectAndSync(removal.storm);
            }
            iterator.remove();
        }
    }

    private static boolean isConverging(Vec3 firstPos, Vec3 firstMotion, Vec3 secondPos, Vec3 secondMotion) {
        if (firstPos == null || secondPos == null) {
            return false;
        }
        Vec3 delta = secondPos.subtract(firstPos);
        if (delta.lengthSqr() < 1.0D) {
            return true;
        }
        Vec3 relative = (firstMotion == null ? Vec3.ZERO : firstMotion).subtract(secondMotion == null ? Vec3.ZERO : secondMotion);
        return relative.dot(delta) > -0.01D || delta.length() < 18.0D;
    }

    private static double strength(StormObject storm) {
        return TornadoWindField.maxWindMph(storm) + DynamicStormController.stateFor(storm).damagePathWidth() * 0.18D + storm.levelCurIntensityStage * 8.0D;
    }

    private static int recordOverlap(long key, int interval) {
        int ticks = OVERLAP_TICKS.getOrDefault(key, 0) + interval;
        OVERLAP_TICKS.put(key, ticks);
        return ticks;
    }

    private static void reset(long key) {
        OVERLAP_TICKS.remove(key);
    }

    private static long overlapKey(long first, long second) {
        long min = Math.min(first, second);
        long max = Math.max(first, second);
        return (min << 32) ^ max;
    }

    private static void queueRemoval(WeatherManagerServer manager, StormObject storm) {
        if (storm == null) {
            return;
        }
        PENDING_REMOVALS.putIfAbsent(storm.ID, new PendingRemoval(manager, storm));
    }

    private static boolean isRemovalQueued(long stormId) {
        return PENDING_REMOVALS.containsKey(stormId);
    }

    private record PendingRemoval(WeatherManagerServer manager, StormObject storm) {
    }
}
