package com.imbet.weather;

import com.imbet.Config;
import com.imbet.survey.TornadoTrackService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SupercellMergeService {
    private static final Map<MergeKey, Integer> OVERLAP_TICKS = new HashMap<>();

    private SupercellMergeService() {
    }

    public static void tick(ServerLevel level) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        int interval = Math.max(5, Config.SUPERCELL_MERGE_SCAN_INTERVAL_TICKS.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }

        List<StormObject> storms = stormSnapshot(manager);
        processAbsorbedStorms(level, manager, storms);
        if (!Config.SUPERCELL_MERGING_ENABLED.get()) {
            return;
        }

        List<StormObject> candidates = new ArrayList<>();
        for (StormObject storm : storms) {
            if (isEligibleSupercell(storm)) {
                candidates.add(storm);
            }
        }
        Set<Long> activeIds = new HashSet<>();
        for (StormObject storm : candidates) {
            activeIds.add(storm.ID);
        }
        pruneInactiveOverlapKeys(level, activeIds);

        for (int i = 0; i < candidates.size(); i++) {
            StormObject first = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                StormObject second = candidates.get(j);
                tryMergePair(level, manager, first, second, interval);
            }
        }
    }

    private static List<StormObject> stormSnapshot(WeatherManagerServer manager) {
        List<StormObject> storms = new ArrayList<>();
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (object instanceof StormObject storm && storm.pos != null && !storm.isDead) {
                storms.add(storm);
            }
        }
        return storms;
    }

    private static boolean isEligibleSupercell(StormObject storm) {
        if (storm == null || storm.pos == null || storm.isDead || DynamicStormController.isAbsorbedOrDecaying(storm)) {
            return false;
        }
        if (storm.stormType != StormObject.TYPE_LAND
                || storm.attrib_waterSpout
                || DynamicStormController.isNativeCyclone(storm)
                || AddonWeatherEventService.isSquallLineHelperStorm(storm)
                || AddonWeatherEventService.isHurricaneHelperStorm(storm)) {
            return false;
        }
        return storm.levelCurIntensityStage >= Config.SUPERCELL_MERGE_MIN_INTENSITY_STAGE.get();
    }

    private static void tryMergePair(ServerLevel level, WeatherManagerServer manager, StormObject first, StormObject second, int interval) {
        MergeKey key = MergeKey.of(level, first.ID, second.ID);
        double mergeDistance = mergeDistance(first, second);
        double distance = first.pos.distanceTo(second.pos);
        boolean closeEnough = distance <= mergeDistance;
        boolean sustainedOverlap = distance <= mergeDistance * 0.72D;
        if (!closeEnough || (!sustainedOverlap && !isConverging(first, second, interval))) {
            OVERLAP_TICKS.remove(key);
            return;
        }
        int ticks = OVERLAP_TICKS.getOrDefault(key, 0) + interval;
        OVERLAP_TICKS.put(key, ticks);
        if (ticks < Config.SUPERCELL_MERGE_OVERLAP_TICKS.get()) {
            return;
        }

        StormObject stronger = strength(first) >= strength(second) ? first : second;
        StormObject weaker = stronger == first ? second : first;
        if (Config.MERGE_ORBIT_BEFORE_ABSORB.get() && ticks < Config.SUPERCELL_MERGE_OVERLAP_TICKS.get() + Config.MERGE_ORBIT_TICKS.get()) {
            beginOrbit(level, manager, stronger, weaker);
            return;
        }
        absorb(level, manager, stronger, weaker);
        OVERLAP_TICKS.remove(key);
    }

    static void absorb(ServerLevel level, WeatherManagerServer manager, StormObject stronger, StormObject weaker) {
        if (stronger == null || weaker == null || stronger == weaker || weaker.isDead) {
            return;
        }
        DynamicStormState strongerState = DynamicStormController.stateFor(stronger);
        DynamicStormState weakerState = DynamicStormController.stateFor(weaker);
        double strongerWind = Math.max(1.0D, TornadoWindField.maxWindMph(stronger));
        float absorbedWindFraction = (float) Mth.clamp(TornadoWindField.maxWindMph(weaker) / strongerWind, 0.10D, 1.0D);
        strongerState.recordSupercellMerge(level.getGameTime(), weakerState.damagePathWidth(), absorbedWindFraction);
        weakerState.markAbsorbedBySupercell(level.getGameTime(), stronger.ID);
        strongerState.clearMergeOrbit();
        weakerState.clearMergeOrbit();

        blendMotion(stronger, weaker);
        pulseIntensity(stronger);
        weakenAbsorbedStorm(weaker);
        TornadoTrackService.recordStormMerge(level, stronger.ID, weaker.ID);

        manager.syncStormUpdate(stronger);
        if (Config.SUPERCELL_MERGE_HARD_REMOVE_ABSORBED.get()) {
            manager.removeWeatherObjectAndSync(weaker);
        } else {
            manager.syncStormUpdate(weaker);
        }
    }

    private static void beginOrbit(ServerLevel level, WeatherManagerServer manager, StormObject stronger, StormObject weaker) {
        Vec3 center = stronger.pos.add(weaker.pos).scale(0.5D);
        double radius = Math.max(12.0D, stronger.pos.distanceTo(weaker.pos) * 0.5D);
        DynamicStormController.stateFor(stronger).beginMergeOrbit(level.getGameTime(), weaker.ID, center, radius, 1.0F);
        DynamicStormController.stateFor(weaker).beginMergeOrbit(level.getGameTime(), stronger.ID, center, radius, -1.0F);
        manager.syncStormUpdate(stronger);
        manager.syncStormUpdate(weaker);
    }

    private static void blendMotion(StormObject stronger, StormObject weaker) {
        if (stronger.motion == null && weaker.motion == null) {
            return;
        }
        Vec3 strongMotion = stronger.motion == null ? Vec3.ZERO : stronger.motion;
        Vec3 weakMotion = weaker.motion == null ? Vec3.ZERO : weaker.motion;
        double fraction = Config.SUPERCELL_MERGE_MOTION_ABSORB_FRACTION.get();
        stronger.motion = strongMotion.scale(1.0D - fraction).add(weakMotion.scale(fraction));
    }

    private static void pulseIntensity(StormObject stronger) {
        stronger.hasStormPeaked = false;
        stronger.isGrowing = true;
        float pulse = Config.SUPERCELL_MERGE_INTENSITY_PULSE.get().floatValue();
        stronger.levelCurStagesIntensity += pulse;
        if (stronger.levelCurStagesIntensity >= 1.0F && stronger.levelCurIntensityStage < Math.min(stronger.maxIntensityStage, StormObject.STATE_STAGE5)) {
            stronger.levelCurStagesIntensity = Math.max(0.0F, stronger.levelCurStagesIntensity - 1.0F);
            stronger.stageNext();
            stronger.levelCurStagesIntensity = Math.min(stronger.levelCurStagesIntensity, 0.35F);
        } else {
            stronger.levelCurStagesIntensity = Math.min(stronger.levelCurStagesIntensity, 0.99F);
        }
    }

    private static void processAbsorbedStorms(ServerLevel level, WeatherManagerServer manager, List<StormObject> storms) {
        for (StormObject storm : storms) {
            DynamicStormState state = DynamicStormController.stateFor(storm);
            if (!state.isAbsorbedOrDecaying()) {
                continue;
            }
            if (Config.SUPERCELL_MERGE_HARD_REMOVE_ABSORBED.get()) {
                manager.removeWeatherObjectAndSync(storm);
                continue;
            }
            if (!Config.SUPERCELL_MERGE_SOFT_DISSIPATE_ABSORBED.get()) {
                continue;
            }
            weakenAbsorbedStorm(storm);
            manager.syncStormUpdate(storm);
        }
    }

    private static void weakenAbsorbedStorm(StormObject storm) {
        storm.isGrowing = false;
        storm.hasStormPeaked = true;
        storm.levelCurStagesIntensity -= 0.32F;
        if (storm.levelCurStagesIntensity <= 0.0F) {
            if (storm.levelCurIntensityStage > StormObject.STATE_NORMAL) {
                storm.stagePrev();
                storm.levelCurStagesIntensity = 0.62F;
            } else {
                storm.levelCurStagesIntensity = 0.0F;
            }
        }
        if (storm.motion != null) {
            storm.motion = storm.motion.scale(0.82D);
        }
    }

    private static double mergeDistance(StormObject first, StormObject second) {
        double max = Config.SUPERCELL_MERGE_MAX_DISTANCE_BLOCKS.get();
        double distance = (stormCoreRadius(first) + stormCoreRadius(second)) * Config.SUPERCELL_MERGE_DISTANCE_MULTIPLIER.get();
        return Mth.clamp(distance, 24.0D, max);
    }

    private static double stormCoreRadius(StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double stage = Math.max(0, storm.levelCurIntensityStage - StormObject.STATE_THUNDER);
        double dynamicWidth = DynamicStormController.isDynamicVortex(storm) ? state.damagePathWidth() * 0.28D : 0.0D;
        return Mth.clamp(44.0D + stage * 22.0D + storm.levelCurStagesIntensity * 22.0D + dynamicWidth, 36.0D, Config.SUPERCELL_MERGE_MAX_DISTANCE_BLOCKS.get());
    }

    private static boolean isConverging(StormObject first, StormObject second, int interval) {
        Vec3 firstMotion = first.motion == null ? Vec3.ZERO : first.motion;
        Vec3 secondMotion = second.motion == null ? Vec3.ZERO : second.motion;
        double current = first.pos.distanceToSqr(second.pos);
        Vec3 futureFirst = first.pos.add(firstMotion.scale(interval));
        Vec3 futureSecond = second.pos.add(secondMotion.scale(interval));
        return futureFirst.distanceToSqr(futureSecond) <= current;
    }

    private static double strength(StormObject storm) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        double vortexBonus = storm.isTornadoFormingOrGreater() ? 75.0D : 0.0D;
        double satelliteBonus = state.satelliteTornadoes().isEmpty() ? 0.0D : 35.0D;
        return storm.levelCurIntensityStage * 100.0D
                + storm.levelCurStagesIntensity * 35.0D
                + TornadoWindField.maxWindMph(storm) * 0.38D
                + state.damagePathWidth() * 0.12D
                + vortexBonus
                + satelliteBonus;
    }

    private static void pruneInactiveOverlapKeys(ServerLevel level, Set<Long> activeIds) {
        String dimension = level.dimension().location().toString();
        OVERLAP_TICKS.keySet().removeIf(key -> key.dimension().equals(dimension) && (!activeIds.contains(key.first()) || !activeIds.contains(key.second())));
    }

    private record MergeKey(String dimension, long first, long second) {
        private static MergeKey of(ServerLevel level, long first, long second) {
            long min = Math.min(first, second);
            long max = Math.max(first, second);
            return new MergeKey(level.dimension().location().toString(), min, max);
        }
    }
}
