package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.AddonWeatherEventSyncPayload;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class AddonWeatherEventService {
    private static final Map<ResourceKey<Level>, List<AddonWeatherEvent>> EVENTS = new HashMap<>();

    private AddonWeatherEventService() {
    }

    public static void tick(ServerLevel level) {
        List<AddonWeatherEvent> events = eventsFor(level);
        long gameTime = level.getGameTime();
        Iterator<AddonWeatherEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            AddonWeatherEvent event = iterator.next();
            if (event.expired(gameTime) || invalidSurface(level, event)) {
                iterator.remove();
                continue;
            }
            event.tick();
            snapEventToSurface(level, event);
            if (gameTime % 4L == 0L) {
                applyImpacts(level, event);
            }
        }
        int scanInterval = Math.max(20, Config.PERFORMANCE_STORM_SCAN_INTERVAL_TICKS.get());
        if (gameTime % scanInterval == 0L) {
            spawnFromWeather2(level, events, gameTime);
            spawnDustDevils(level, events, gameTime);
        }
        if (gameTime % 20L == 0L) {
            sync(level, events);
        }
    }

    public static List<AddonWeatherEvent> activeEvents(ServerLevel level) {
        return List.copyOf(eventsFor(level));
    }

    public static TornadoWindField.WindSample sampleWind(Level level, Vec3 position) {
        List<AddonWeatherEvent> events = EVENTS.get(level.dimension());
        if (events == null || events.isEmpty() || !Config.WINDFIELD_ENABLED.get()) {
            return TornadoWindField.WindSample.CALM;
        }
        TornadoWindField.WindSample strongest = TornadoWindField.WindSample.CALM;
        for (AddonWeatherEvent event : events) {
            TornadoWindField.WindSample sample = sampleEventWind(event, position);
            if (sample.speedMph() > strongest.speedMph()) {
                strongest = sample;
            }
        }
        return strongest;
    }

    public static List<RadarStormSnapshot> radarSnapshots(ServerLevel level, Vec3 radarPos, int range) {
        List<RadarStormSnapshot> snapshots = new ArrayList<>();
        for (AddonWeatherEvent event : activeEvents(level)) {
            if (event.position().distanceTo(radarPos) > range) {
                continue;
            }
            SpcRisk risk = riskForEvent(event);
            boolean vortex = event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.DUST_DEVIL;
            float width = event.isLine() && event.lineStart() != null && event.lineEnd() != null
                    ? (float) event.lineStart().distanceTo(event.lineEnd())
                    : event.radius() * 2.0F;
            snapshots.add(new RadarStormSnapshot(
                    event.id(),
                    event.type().label(),
                    risk.label(),
                    risk.severity(),
                    risk.color(),
                    event.position().x,
                    event.position().z,
                    event.motion().x,
                    event.motion().z,
                    width,
                    event.maxWindMph(),
                    reflectivity(event),
                    radialVelocity(radarPos, event.position(), event.motion()),
                    vortex ? event.maxWindMph() * 0.44704D * 0.36D : 0.0D,
                    event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT,
                    0,
                    vortex,
                    event.position().x,
                    event.position().z,
                    event.position().x,
                    event.position().z,
                    event.type() == StormSubtype.LANDSPOUT
            ));
        }
        return snapshots;
    }

    private static List<AddonWeatherEvent> eventsFor(ServerLevel level) {
        return EVENTS.computeIfAbsent(level.dimension(), key -> new ArrayList<>());
    }

    private static void spawnFromWeather2(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        List<StormObject> activeCells = new ArrayList<>();
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            if (DynamicStormController.isAbsorbedOrDecaying(storm)) {
                continue;
            }
            applyNativeWaterspoutTag(level, manager, storm);
            if (storm.levelCurIntensityStage >= StormObject.STATE_THUNDER) {
                activeCells.add(storm);
            }
            if (events.size() >= Config.MAX_ADDON_VORTICES.get()) {
                continue;
            }
            boolean water = DynamicStormController.isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
            if (water) {
                maybeSpawnWaterspout(level, events, storm, gameTime);
            } else {
                maybeSpawnLandspout(level, events, storm, gameTime);
            }
        }
        detectSquallLine(level, events, activeCells, gameTime);
    }

    private static void maybeSpawnLandspout(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!isLandspoutCandidate(storm) || hasNearbyAddonEvent(events, StormSubtype.LANDSPOUT, storm.pos, 180.0D)) {
            return;
        }
        if (level.random.nextDouble() > Config.LANDSPOUT_SPAWN_CHANCE.get()) {
            return;
        }
        Vec3 offset = randomOffset(level, 28.0D, 90.0D);
        Vec3 pos = surfacePos(level, storm.pos.add(offset));
        if (DynamicStormController.isOverWaterOrOcean(level, pos.x, pos.z)) {
            return;
        }
        float wind = 62.0F + level.random.nextFloat() * 38.0F;
        float radius = 4.0F + level.random.nextFloat() * 12.0F;
        Vec3 motion = storm.motion == null ? randomMotion(level, 0.018D, 0.055D) : storm.motion.scale(0.35D).add(randomMotion(level, 0.010D, 0.035D));
        events.add(new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.LANDSPOUT, pos, motion, radius, wind, gameTime, gameTime + 300L + level.random.nextInt(620)));
    }

    private static void maybeSpawnWaterspout(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!isFairWeatherWaterspoutCandidate(storm) || hasNearbyAddonEvent(events, StormSubtype.WATERSPOUT, storm.pos, 180.0D)) {
            return;
        }
        if (level.random.nextDouble() > Config.WATERSPOUT_SPAWN_CHANCE.get()) {
            return;
        }
        Vec3 offset = randomOffset(level, 20.0D, 110.0D);
        Vec3 pos = surfacePos(level, storm.pos.add(offset));
        if (!DynamicStormController.isOverWaterOrOcean(level, pos.x, pos.z)) {
            return;
        }
        float wind = 45.0F + level.random.nextFloat() * 40.0F;
        float radius = 3.5F + level.random.nextFloat() * 9.0F;
        Vec3 motion = storm.motion == null ? randomMotion(level, 0.004D, 0.028D) : storm.motion.scale(0.18D).add(randomMotion(level, 0.002D, 0.018D));
        events.add(new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.WATERSPOUT, pos, motion, radius, wind, gameTime, gameTime + 220L + level.random.nextInt(680)));
    }

    private static void spawnDustDevils(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        if (!Config.DUST_DEVILS_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || level.isRaining() || level.isThundering() || !isDryAfternoon(level)) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (events.size() >= Config.MAX_ADDON_VORTICES.get() || level.random.nextDouble() > Config.DUST_DEVIL_SPAWN_CHANCE.get()) {
                continue;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 48.0D, 180.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            if (!level.isLoaded(pos) || level.isRainingAt(pos) || !level.canSeeSky(pos.above()) || !hotDrySurface(level, pos) || hasNearbyWeather2Storm(level, candidate, 420.0D) || hasNearbyAddonEvent(events, StormSubtype.DUST_DEVIL, candidate, 96.0D)) {
                continue;
            }
            float wind = 24.0F + level.random.nextFloat() * 34.0F;
            float radius = 1.5F + level.random.nextFloat() * 5.0F;
            events.add(new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.DUST_DEVIL, candidate, randomMotion(level, 0.018D, 0.070D), radius, wind, gameTime, gameTime + 160L + level.random.nextInt(520)));
        }
    }

    private static void detectSquallLine(ServerLevel level, List<AddonWeatherEvent> events, List<StormObject> cells, long gameTime) {
        if (!Config.SQUALL_LINES_ENABLED.get() || cells.size() < Config.SQUALL_LINE_MIN_CELLS.get()) {
            return;
        }
        List<StormObject> eligibleCells = new ArrayList<>();
        for (StormObject cell : cells) {
            if (isSquallLineCandidate(cell)) {
                eligibleCells.add(cell);
            }
        }
        int count = eligibleCells.size();
        if (count < Config.SQUALL_LINE_MIN_CELLS.get()) {
            return;
        }

        double meanX = 0.0D;
        double meanY = 0.0D;
        double meanZ = 0.0D;
        for (StormObject cell : eligibleCells) {
            meanX += cell.pos.x;
            meanY += cell.pos.y;
            meanZ += cell.pos.z;
        }
        meanX /= count;
        meanY /= count;
        meanZ /= count;

        double covXX = 0.0D;
        double covXZ = 0.0D;
        double covZZ = 0.0D;
        for (StormObject cell : eligibleCells) {
            double dx = cell.pos.x - meanX;
            double dz = cell.pos.z - meanZ;
            covXX += dx * dx;
            covXZ += dx * dz;
            covZZ += dz * dz;
        }
        double axisAngle = 0.5D * Math.atan2(2.0D * covXZ, covXX - covZZ);
        Vec3 axis = new Vec3(Math.cos(axisAngle), 0.0D, Math.sin(axisAngle));
        Vec3 normal = new Vec3(-axis.z, 0.0D, axis.x);

        List<Double> projections = new ArrayList<>(count);
        double minProjection = Double.MAX_VALUE;
        double maxProjection = -Double.MAX_VALUE;
        double maxAbsPerp = 0.0D;
        double avgAbsPerp = 0.0D;
        for (StormObject cell : eligibleCells) {
            double dx = cell.pos.x - meanX;
            double dz = cell.pos.z - meanZ;
            double projection = dx * axis.x + dz * axis.z;
            double perpendicular = Math.abs(dx * normal.x + dz * normal.z);
            projections.add(projection);
            minProjection = Math.min(minProjection, projection);
            maxProjection = Math.max(maxProjection, projection);
            maxAbsPerp = Math.max(maxAbsPerp, perpendicular);
            avgAbsPerp += perpendicular;
        }
        avgAbsPerp /= count;

        double length = maxProjection - minProjection;
        double maxThickness = Config.SQUALL_LINE_MAX_THICKNESS_BLOCKS.get();
        if (length < Math.max(260.0D, count * 72.0D) || maxAbsPerp * 2.0D > maxThickness || largestProjectionGap(projections) > Math.max(300.0D, length * 0.56D)) {
            return;
        }

        Vec3 averageMotion = averageStormMotion(eligibleCells);
        if (averageMotion.horizontalDistanceSqr() > 1.0E-5D && averageMotion.dot(normal) < 0.0D) {
            normal = normal.scale(-1.0D);
        }
        double lineSpeed = averageMotion.horizontalDistanceSqr() > 1.0E-5D ? Math.sqrt(averageMotion.horizontalDistanceSqr()) : 0.055D;
        Vec3 motion = normal.scale(Mth.clamp(lineSpeed, 0.035D, 0.16D));
        Vec3 center = new Vec3(meanX, meanY, meanZ);
        Vec3 start = center.add(axis.scale(minProjection));
        Vec3 end = center.add(axis.scale(maxProjection));
        float wind = squallLineWind(level, eligibleCells);
        float windRadius = (float) Mth.clamp(avgAbsPerp + 72.0D, 56.0D, maxThickness);

        for (AddonWeatherEvent event : events) {
            if (event.type() == StormSubtype.SQUALL_LINE && event.position().distanceTo(start.add(end).scale(0.5D)) < 260.0D) {
                event.refreshLine(start, end, motion, gameTime + Config.SQUALL_LINE_MAX_AGE_TICKS.get());
                return;
            }
        }
        events.add(AddonWeatherEvent.squallLine(nextId(gameTime, level), start, end, motion, windRadius, wind, gameTime, gameTime + Config.SQUALL_LINE_MAX_AGE_TICKS.get()));
    }

    private static void applyImpacts(ServerLevel level, AddonWeatherEvent event) {
        double range = event.isLine() ? event.radius() + 48.0D : event.radius() * 4.0D;
        AABB box = new AABB(event.position().x - range, event.position().y - 12.0D, event.position().z - range, event.position().x + range, event.position().y + 56.0D, event.position().z + range);
        for (Entity entity : level.getEntities((Entity) null, box, Entity::isAlive)) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            TornadoWindField.WindSample sample = sampleEventWind(event, entity.position());
            if (sample.speedMph() >= 24.0D) {
                entity.setDeltaMovement(entity.getDeltaMovement().add(TornadoWindField.mphToEntityDelta(sample.mphVector())));
                entity.hurtMarked = true;
            }
        }
        if (event.type() == StormSubtype.DUST_DEVIL || event.maxWindMph() < 62.0F || level.random.nextInt(3) != 0) {
            return;
        }
        int edits = event.type() == StormSubtype.SQUALL_LINE ? 3 : 2;
        for (int i = 0; i < edits; i++) {
            Vec3 target = event.isLine() ? randomPointNearLine(level, event) : event.position().add(randomOffset(level, 0.0D, event.radius() * 1.7D));
            BlockPos targetPos = BlockPos.containing(target);
            if (!level.isLoaded(targetPos)) {
                continue;
            }
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetPos).below();
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            } else if ((event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT) && event.maxWindMph() >= 85.0F && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.FARMLAND))) {
                level.setBlock(pos, Blocks.DIRT_PATH.defaultBlockState(), 3);
            }
        }
    }

    private static TornadoWindField.WindSample sampleEventWind(AddonWeatherEvent event, Vec3 position) {
        if (event.isLine()) {
            return sampleLineWind(event, position);
        }
        Vec3 center = new Vec3(event.position().x, position.y, event.position().z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.4D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double rmw = Math.max(1.2D, event.radius());
        double max = event.maxWindMph();
        double tangential = radius <= rmw ? max * (radius / rmw) : max * Math.pow(rmw / radius, 0.86D);
        if (radius > rmw * 4.0D) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 radial = fromCenter.scale(1.0D / radius);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double inflow = 0.18D;
        if (event.type() == StormSubtype.DUST_DEVIL) {
            inflow = 0.10D;
        } else if (event.type() == StormSubtype.WATERSPOUT) {
            inflow = 0.24D;
        }
        double lift = event.type() == StormSubtype.DUST_DEVIL ? tangential * 0.18D : tangential * 0.25D;
        Vec3 vector = tangent.scale(tangential).add(radial.scale(-tangential * inflow)).add(0.0D, lift, 0.0D);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), radius, rmw * 2.0D, rmw);
    }

    private static TornadoWindField.WindSample sampleLineWind(AddonWeatherEvent event, Vec3 position) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 closest = closestPointOnSegment(position, start, end);
        double distance = Math.sqrt((position.x - closest.x) * (position.x - closest.x) + (position.z - closest.z) * (position.z - closest.z));
        if (distance > event.radius()) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 line = end.subtract(start);
        Vec3 along = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(line.x, 0.0D, line.z).normalize();
        Vec3 forward = event.motion().horizontalDistanceSqr() > 1.0E-5D ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize() : new Vec3(-along.z, 0.0D, along.x);
        double falloff = Mth.clamp(1.0D - distance / Math.max(1.0D, event.radius()), 0.0D, 1.0D);
        Vec3 vector = forward.scale(event.maxWindMph() * falloff).add(0.0D, event.maxWindMph() * 0.04D * falloff, 0.0D);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, event.radius(), event.radius() * 0.5D);
    }

    private static boolean invalidSurface(ServerLevel level, AddonWeatherEvent event) {
        if (event.type() == StormSubtype.WATERSPOUT) {
            return !DynamicStormController.isOverWaterOrOcean(level, event.position().x, event.position().z);
        }
        if (event.type() == StormSubtype.LANDSPOUT) {
            return DynamicStormController.isOverWaterOrOcean(level, event.position().x, event.position().z);
        }
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return level.isRaining() || level.isThundering();
        }
        return false;
    }

    private static void applyNativeWaterspoutTag(ServerLevel level, WeatherManagerServer manager, StormObject storm) {
        if (!Config.WATERSPOUTS_ENABLED.get() || storm == null || storm.pos == null) {
            return;
        }
        boolean overWater = DynamicStormController.isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
        boolean shouldBeWaterspout = overWater && (storm.attrib_waterSpout || (storm.stormType == StormObject.TYPE_LAND && storm.isTornadoFormingOrGreater()));
        if (storm.attrib_waterSpout != shouldBeWaterspout) {
            storm.attrib_waterSpout = shouldBeWaterspout;
            manager.syncStormUpdate(storm);
        }
    }

    private static boolean isLandspoutCandidate(StormObject storm) {
        return Config.LANDSPOUTS_ENABLED.get()
                && storm != null
                && storm.pos != null
                && storm.stormType == StormObject.TYPE_LAND
                && storm.isGrowing
                && !storm.hasStormPeaked
                && !storm.isTornadoFormingOrGreater()
                && !storm.isCycloneFormingOrGreater()
                && !storm.isTropicalCyclone()
                && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                && storm.levelCurIntensityStage <= StormObject.STATE_HAIL;
    }

    private static boolean isFairWeatherWaterspoutCandidate(StormObject storm) {
        if (!Config.FAIR_WEATHER_WATERSPOUTS_ENABLED.get() || !Config.WATERSPOUTS_ENABLED.get() || storm == null || storm.pos == null) {
            return false;
        }
        if (storm.isTornadoFormingOrGreater() || storm.isCycloneFormingOrGreater() || storm.isTropicalCyclone() || storm.levelCurIntensityStage > StormObject.STATE_THUNDER) {
            return false;
        }
        double cloudWind = storm.manager == null ? 0.0D : storm.manager.getWindManager().getWindSpeedForClouds();
        return (storm.isGrowing || storm.levelCurIntensityStage == StormObject.STATE_NORMAL) && cloudWind <= 1.35D;
    }

    private static boolean isSquallLineCandidate(StormObject storm) {
        return storm != null
                && storm.pos != null
                && !storm.isDead
                && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                && !storm.isTornadoFormingOrGreater()
                && !storm.isCycloneFormingOrGreater()
                && !storm.isTropicalCyclone();
    }

    private static boolean isDryAfternoon(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        return dayTime >= 4500L && dayTime <= 10500L;
    }

    private static boolean hasNearbyAddonEvent(List<AddonWeatherEvent> events, StormSubtype type, Vec3 center, double range) {
        double rangeSqr = range * range;
        for (AddonWeatherEvent event : events) {
            if (event.type() == type && event.position().distanceToSqr(center) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyWeather2Storm(ServerLevel level, Vec3 center, double range) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return false;
        }
        double rangeSqr = range * range;
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject storm && storm.pos != null && !storm.isDead && !DynamicStormController.isAbsorbedOrDecaying(storm) && storm.pos.distanceToSqr(center) <= rangeSqr && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 averageStormMotion(List<StormObject> cells) {
        Vec3 motion = Vec3.ZERO;
        int count = 0;
        for (StormObject cell : cells) {
            if (cell.motion != null && cell.motion.horizontalDistanceSqr() > 1.0E-5D) {
                motion = motion.add(cell.motion);
                count++;
            }
        }
        return count == 0 ? Vec3.ZERO : motion.scale(1.0D / count);
    }

    private static double largestProjectionGap(List<Double> projections) {
        if (projections.size() < 2) {
            return 0.0D;
        }
        projections.sort(Comparator.naturalOrder());
        double largest = 0.0D;
        for (int i = 1; i < projections.size(); i++) {
            largest = Math.max(largest, projections.get(i) - projections.get(i - 1));
        }
        return largest;
    }

    private static float squallLineWind(ServerLevel level, List<StormObject> cells) {
        int maxStage = StormObject.STATE_THUNDER;
        double wind = 48.0D;
        for (StormObject cell : cells) {
            maxStage = Math.max(maxStage, cell.levelCurIntensityStage);
        }
        wind += Math.max(0, maxStage - StormObject.STATE_THUNDER) * 9.0D;
        if (maxStage >= StormObject.STATE_HAIL) {
            wind += 8.0D;
        }
        return (float) Mth.clamp(wind + level.random.nextDouble() * 10.0D, 45.0D, 92.0D);
    }

    private static void snapEventToSurface(ServerLevel level, AddonWeatherEvent event) {
        if (event.isLine()) {
            return;
        }
        event.setPosition(surfacePos(level, event.position()));
    }

    private static boolean hotDrySurface(ServerLevel level, BlockPos pos) {
        float temp = level.getBiome(pos).value().getBaseTemperature();
        BlockState ground = level.getBlockState(pos.below());
        return temp >= 0.95F && (ground.is(Blocks.SAND) || ground.is(Blocks.RED_SAND) || ground.is(Blocks.TERRACOTTA) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT));
    }

    private static Vec3 surfacePos(ServerLevel level, Vec3 pos) {
        BlockPos sample = BlockPos.containing(pos);
        if (!level.isLoaded(sample)) {
            return pos;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, sample);
        return Vec3.atBottomCenterOf(surface);
    }

    private static Vec3 randomOffset(ServerLevel level, double min, double max) {
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        double distance = min + level.random.nextDouble() * Math.max(0.0D, max - min);
        return new Vec3(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
    }

    private static Vec3 randomMotion(ServerLevel level, double min, double max) {
        Vec3 offset = randomOffset(level, min, max);
        return new Vec3(offset.x, 0.0D, offset.z);
    }

    private static Vec3 randomPointNearLine(ServerLevel level, AddonWeatherEvent event) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return event.position();
        }
        double t = level.random.nextDouble();
        Vec3 point = start.lerp(end, t);
        Vec3 line = end.subtract(start);
        Vec3 normal = new Vec3(-line.z, 0.0D, line.x).normalize();
        return point.add(normal.scale((level.random.nextDouble() - 0.5D) * event.radius()));
    }

    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lenSqr = segment.lengthSqr();
        if (lenSqr < 1.0E-5D) {
            return start;
        }
        double t = Mth.clamp(point.subtract(start).dot(segment) / lenSqr, 0.0D, 1.0D);
        return start.add(segment.scale(t));
    }

    private static double reflectivity(AddonWeatherEvent event) {
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return 8.0D + event.maxWindMph() * 0.12D;
        }
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return 48.0D + event.maxWindMph() * 0.10D;
        }
        if (event.type() == StormSubtype.WATERSPOUT) {
            return 28.0D + event.maxWindMph() * 0.18D;
        }
        if (event.type() == StormSubtype.LANDSPOUT) {
            return 22.0D + event.maxWindMph() * 0.14D;
        }
        return 24.0D;
    }

    private static SpcRisk riskForEvent(AddonWeatherEvent event) {
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return event.maxWindMph() >= 75.0F ? SpcRisk.MODERATE : event.maxWindMph() >= 58.0F ? SpcRisk.ENHANCED : SpcRisk.SLIGHT;
        }
        if (event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT) {
            return SpcRisk.MARGINAL;
        }
        return SpcRisk.TSTM;
    }

    private static double radialVelocity(Vec3 radarPos, Vec3 stormPos, Vec3 motion) {
        if (radarPos == null || stormPos == null || motion == null) {
            return 0.0D;
        }
        double dx = stormPos.x - radarPos.x;
        double dz = stormPos.z - radarPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        return distance < 0.001D ? 0.0D : ((motion.x * dx + motion.z * dz) / distance) * 20.0D;
    }

    private static long nextId(long gameTime, ServerLevel level) {
        return (gameTime << 20) ^ level.random.nextInt(1_000_000);
    }

    private static void sync(ServerLevel level, List<AddonWeatherEvent> events) {
        for (ServerPlayer player : level.players()) {
            List<AddonWeatherEvent> nearby = events.stream()
                    .filter(event -> event.position().distanceTo(player.position()) <= Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get())
                    .toList();
            PacketDistributor.sendToPlayer(player, new AddonWeatherEventSyncPayload(nearby));
        }
    }
}
