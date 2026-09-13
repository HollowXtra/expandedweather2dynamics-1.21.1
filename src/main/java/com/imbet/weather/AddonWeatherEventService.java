package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.AddonWeatherEventSyncPayload;
import com.imbet.network.NetworkSafety;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.config.ConfigMisc;
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
    private static final String SQUALL_LINE_HELPER_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String HURRICANE_HELPER_UUID = "00000000-0000-0000-0000-000000000002";
    private static final Map<ResourceKey<Level>, List<AddonWeatherEvent>> EVENTS = new HashMap<>();

    private AddonWeatherEventService() {
    }

    public static void tick(ServerLevel level) {
        List<AddonWeatherEvent> events = eventsFor(level);
        List<AddonWeatherEvent> pendingTransitions = new ArrayList<>();
        long gameTime = level.getGameTime();
        Iterator<AddonWeatherEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            AddonWeatherEvent event = iterator.next();
            if (event.expired(gameTime)) {
                iterator.remove();
                continue;
            }
            if (invalidSurface(level, event)) {
                trySurfaceTransition(level, event, pendingTransitions, gameTime);
                iterator.remove();
                continue;
            }
            if (isBroadRotatingStorm(event.type())) {
                event.setMotion(WindSteeringManager.addonSteeringVector(level, event));
            }
            event.tick();
            snapEventToSurface(level, event);
            if (isBroadRotatingStorm(event.type()) && gameTime % 40L == 0L) {
                maintainHurricaneWeather(level, event);
                reClampHurricaneHelperStages(level, event);
            }
            if (event.type() == StormSubtype.WILDFIRE && gameTime % 20L == 0L) {
                maintainWildfire(level, event);
                AddonWeatherEvent fireWhirl = maybeCreateFireWhirlFromWildfire(level, events, event, gameTime);
                if (fireWhirl != null) {
                    pendingTransitions.add(fireWhirl);
                }
                AddonWeatherEvent flammagenitus = maybeCreateFlammagenitusFromWildfire(level, events, event, gameTime);
                if (flammagenitus != null) {
                    pendingTransitions.add(flammagenitus);
                }
            } else if (event.type() == StormSubtype.FIRE_WHIRL && gameTime % 8L == 0L) {
                maintainFireWhirlPath(level, event);
            }
            boolean fastBoundary = event.type() == StormSubtype.GUSTNADO
                    || (event.type() == StormSubtype.SQUALL_LINE && gameTime % 2L == 0L);
            if (fastBoundary || gameTime % 4L == 0L) {
                applyImpacts(level, event);
            }
        }
        if (!pendingTransitions.isEmpty()) {
            for (AddonWeatherEvent pending : pendingTransitions) {
                addEventAndEntity(level, events, pending);
            }
        }
        int scanInterval = Math.max(20, Config.PERFORMANCE_STORM_SCAN_INTERVAL_TICKS.get());
        if (gameTime % scanInterval == 0L) {
            spawnFromWeather2(level, events, gameTime);
            spawnDustDevils(level, events, gameTime);
            spawnSnowDevils(level, events, gameTime);
            spawnFireWeather(level, events, gameTime);
        }
        if (gameTime % 20L == 0L) {
            sync(level, events);
        }
    }

    public static List<AddonWeatherEvent> activeEvents(ServerLevel level) {
        return List.copyOf(eventsFor(level));
    }

    /** The two members of the broad rotating storm family, treated identically server-side. */
    private static boolean isBroadRotatingStorm(StormSubtype type) {
        return type == StormSubtype.HURRICANE || type == StormSubtype.CYCLONE;
    }

    /**
     * Spin sign of a broad rotating storm's circulation: the hurricane turns cyclonically, the
     * cyclone anticyclonically. Everything that resolves a tangential direction asks this, so the
     * wind field and the visuals can never disagree about which way the storm turns.
     */
    public static double broadStormSpinSign(AddonWeatherEvent event) {
        return event != null && event.type() == StormSubtype.CYCLONE ? -1.0D : 1.0D;
    }

    public static boolean isSquallLineHelperStorm(StormObject storm) {
        return storm != null && SQUALL_LINE_HELPER_UUID.equals(storm.spawnerUUID);
    }

    public static boolean isHurricaneHelperStorm(StormObject storm) {
        return storm != null && HURRICANE_HELPER_UUID.equals(storm.spawnerUUID);
    }

    public static AddonWeatherEvent spawnCommandEvent(ServerLevel level, StormSubtype type, Vec3 position, Vec3 forward, double requestedWindMph, double requestedSizeBlocks) {
        if (level == null || type == null) {
            return null;
        }
        Vec3 direction = forward == null || forward.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(forward.x, 0.0D, forward.z).normalize();
        Vec3 requestedPosition = position == null ? Vec3.atCenterOf(level.getSharedSpawnPos()) : position;
        Vec3 center = commandSurfacePos(level, requestedPosition, type);
        long gameTime = level.getGameTime();
        AddonWeatherEvent event;
        switch (type) {
            case DUST_DEVIL -> {
                float wind = commandFloat(requestedWindMph, 42.0D, 18.0D, 72.0D);
                float radius = commandFloat(requestedSizeBlocks, 5.0D, 1.0D, 16.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.DUST_DEVIL, center, direction.scale(0.030D), radius, wind, gameTime, gameTime + 20L * 60L * 5L);
            }
            case SNOW_DEVIL -> {
                float wind = commandFloat(requestedWindMph, 30.0D, 12.0D, 62.0D);
                float radius = commandFloat(requestedSizeBlocks, 4.0D, 1.0D, 14.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.SNOW_DEVIL, center, direction.scale(0.026D), radius, wind, gameTime, gameTime + 20L * 60L * 3L);
            }
            case GUSTNADO -> {
                float wind = commandFloat(requestedWindMph, 76.0D, 45.0D, 110.0D);
                float radius = commandFloat(requestedSizeBlocks, 7.0D, 2.0D, 20.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.GUSTNADO, center, direction.scale(0.085D), radius, wind, gameTime, gameTime + gustnadoLifetimeTicks(level, true));
            }
            case FIRE_WHIRL -> {
                float wind = commandFloat(requestedWindMph, 82.0D, 42.0D, 130.0D);
                float radius = commandFloat(requestedSizeBlocks, 6.0D, 2.0D, 18.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.FIRE_WHIRL, center, direction.scale(0.045D), radius, wind, gameTime, gameTime + 20L * 60L * 4L);
            }
            case LANDSPOUT -> {
                float wind = commandFloat(requestedWindMph, 88.0D, 35.0D, LandspoutEntity.MAX_EF2_WIND_MPH);
                float radius = commandFloat(requestedSizeBlocks, 11.0D, 2.0D, 34.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.LANDSPOUT, center, direction.scale(0.032D), radius, wind, gameTime, gameTime + 20L * 60L * 8L);
            }
            case WATERSPOUT -> {
                float wind = commandFloat(requestedWindMph, 72.0D, 30.0D, 95.0D);
                float radius = commandFloat(requestedSizeBlocks, 9.0D, 2.0D, 30.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.WATERSPOUT, center, direction.scale(0.018D), radius, wind, gameTime, gameTime + waterspoutLifetimeTicks(level));
            }
            case SQUALL_LINE -> {
                float wind = commandFloat(requestedWindMph, 78.0D, 40.0D, 120.0D);
                double length = commandDouble(requestedSizeBlocks, 760.0D, 180.0D, 1600.0D);
                Vec3 axis = new Vec3(-direction.z, 0.0D, direction.x);
                Vec3 start = center.add(axis.scale(-length * 0.5D));
                Vec3 end = center.add(axis.scale(length * 0.5D));
                DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(center));
                long expire = gameTime + squallLineLifetimeTicks(level, outlook, wind);
                event = AddonWeatherEvent.squallLine(nextId(gameTime, level), start, end, direction.scale(0.095D), commandFloat(Double.NaN, 160.0D, 72.0D, Config.SQUALL_LINE_MAX_THICKNESS_BLOCKS.get()), wind, gameTime, expire);
            }
            case HURRICANE -> {
                float wind = commandFloat(requestedWindMph, 92.0D, 74.0D, 185.0D);
                float radius = commandFloat(requestedSizeBlocks, 560.0D, 160.0D, 1400.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.HURRICANE, center, direction.scale(0.018D), radius, wind, gameTime, gameTime + 20L * 60L * 60L * 12L);
            }
            case CYCLONE -> {
                // The anticyclonic sibling: same broad storm, opposite spin, a notch smaller on
                // every axis - a cyclone is the depression-strength member of the family.
                float wind = commandFloat(requestedWindMph, 80.0D, 58.0D, 130.0D);
                float radius = commandFloat(requestedSizeBlocks, 420.0D, 140.0D, 1000.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.CYCLONE, center, direction.scale(0.018D), radius, wind, gameTime, gameTime + 20L * 60L * 60L * 10L);
            }
            case WILDFIRE -> {
                float wind = commandFloat(requestedWindMph, 24.0D, 0.0D, 70.0D);
                float radius = commandFloat(requestedSizeBlocks, 72.0D, 20.0D, 260.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.WILDFIRE, center, direction.scale(0.010D), radius, wind, gameTime, gameTime + 20L * 60L * 18L);
            }
            case FLAMMAGENITUS -> {
                float radius = commandFloat(requestedSizeBlocks, 110.0D, 32.0D, 420.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.FLAMMAGENITUS, center, direction.scale(0.012D), radius, 0.0F, gameTime, gameTime + 20L * 60L * 16L);
            }
            default -> {
                return null;
            }
        }
        List<AddonWeatherEvent> events = eventsFor(level);
        if (event.type() == StormSubtype.SQUALL_LINE || isBroadRotatingStorm(event.type())) {
            events.add(event);
            if (event.type() == StormSubtype.SQUALL_LINE) {
                seedWeather2SquallLineStorms(level, event.lineStart(), event.lineEnd(), event.motion(), event.maxWindMph(), gameTime);
            } else {
                maintainHurricaneWeather(level, event);
            }
        } else {
            addEventAndEntity(level, events, event);
        }
        sync(level, events);
        return event.snapshot();
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
            boolean vortex = event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL || event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.FIRE_WHIRL || event.type() == StormSubtype.FLOATER_TORNADO;
            float width = event.isLine() && event.lineStart() != null && event.lineEnd() != null
                    ? (float) event.lineStart().distanceTo(event.lineEnd())
                    : event.radius() * 2.0F;
            boolean dopplerSignature = event.type() != StormSubtype.LANDSPOUT && event.type() != StormSubtype.DUST_DEVIL && event.type() != StormSubtype.SNOW_DEVIL;
            float eventWind = event.type() == StormSubtype.LANDSPOUT ? Math.min(LandspoutEntity.MAX_EF2_WIND_MPH, event.maxWindMph()) : event.maxWindMph();
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
                    eventWind,
                    reflectivity(event),
                    radialVelocity(radarPos, event.position(), event.motion()),
                    vortex && dopplerSignature ? eventWind * 0.44704D * 0.36D : 0.0D,
                    dopplerSignature && (event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.GUSTNADO),
                    0,
                    vortex,
                    event.position().x,
                    event.position().z,
                    event.position().x,
                    event.position().z,
                    vortex,
                    vortex ? inferredStage(eventWind) : 0,
                    vortex ? inferredStageLabel(eventWind) : ""
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
                maybeSpawnHurricane(level, manager, events, storm, gameTime);
            } else {
                maybeSpawnLandspout(level, events, storm, gameTime);
                maybeSpawnGustnado(level, events, storm, gameTime);
            }
        }
        detectSquallLine(level, events, activeCells, gameTime);
    }

    private static void maybeSpawnLandspout(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!isLandspoutCandidate(storm) || hasNearbyAddonEvent(events, StormSubtype.LANDSPOUT, storm.pos, 180.0D)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(storm.pos));
        if (!outlook.allowsLandspouts()) {
            return;
        }
        double chance = Config.LANDSPOUT_SPAWN_CHANCE.get() * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * Math.max(0.35D, outlook.supercellSpawnMultiplier());
        if (level.random.nextDouble() > chance) {
            return;
        }
        Vec3 offset = randomOffset(level, 28.0D, 90.0D);
        Vec3 pos = surfacePos(level, storm.pos.add(offset));
        if (DynamicStormController.isOverWaterOrOcean(level, pos.x, pos.z)) {
            return;
        }
        float wind = Math.min(LandspoutEntity.MAX_EF2_WIND_MPH, 62.0F + level.random.nextFloat() * 38.0F);
        float radius = 4.0F + level.random.nextFloat() * 12.0F;
        Vec3 motion = storm.motion == null ? randomMotion(level, 0.018D, 0.055D) : storm.motion.scale(0.35D).add(randomMotion(level, 0.010D, 0.035D));
        addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.LANDSPOUT, pos, motion, radius, wind, gameTime, gameTime + 300L + level.random.nextInt(620)));
    }

    private static void maybeSpawnWaterspout(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!isFairWeatherWaterspoutCandidate(storm) || hasNearbyAddonEvent(events, StormSubtype.WATERSPOUT, storm.pos, 180.0D)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(storm.pos));
        double chance = Config.WATERSPOUT_SPAWN_CHANCE.get() * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * Math.max(0.60D, outlook.windProbability() + 0.75D);
        if (level.random.nextDouble() > chance) {
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
        addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.WATERSPOUT, pos, motion, radius, wind, gameTime, gameTime + waterspoutLifetimeTicks(level)));
    }

    private static long waterspoutLifetimeTicks(ServerLevel level) {
        return 20L * 60L * 5L + level.random.nextInt(20 * 60 * 10 + 1);
    }

    private static void maybeSpawnGustnado(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!Config.GUSTNADOES_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || !isGustnadoCandidate(storm) || hasNearbyAddonEvent(events, StormSubtype.GUSTNADO, storm.pos, 140.0D)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(storm.pos));
        double severeOutflow = Math.max(0.55D, outlook.windProbability() + (storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND ? 0.35D : 0.0D) + (storm.isPrecipitating() ? 0.15D : 0.0D));
        double chance = enabledChance(Config.GUSTNADO_SPAWN_CHANCE.get(), 0.045D) * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * severeOutflow;
        if (level.random.nextDouble() > chance) {
            return;
        }
        Vec3 forward = stormForward(level, storm);
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 pos = surfacePos(level, storm.pos.add(forward.scale(54.0D + level.random.nextDouble() * 130.0D)).add(right.scale((level.random.nextDouble() - 0.5D) * 170.0D)));
        if (DynamicStormController.isOverWaterOrOcean(level, pos.x, pos.z) || hasNearbyAddonEvent(events, StormSubtype.GUSTNADO, pos, 110.0D)) {
            return;
        }
        float wind = gustnadoWindMph(level, outlook, storm);
        float radius = 2.5F + level.random.nextFloat() * 5.5F;
        Vec3 motion = forward.scale(0.045D + level.random.nextDouble() * 0.075D).add(right.scale((level.random.nextDouble() - 0.5D) * 0.025D));
        addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.GUSTNADO, pos, motion, radius, wind, gameTime, gameTime + gustnadoLifetimeTicks(level, false)));
    }

    private static void maybeSpawnHurricane(ServerLevel level, WeatherManagerServer manager, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!Config.HURRICANES_ENABLED.get() || !DailySpcOutlookService.hurricaneWindow(level, BlockPos.containing(storm.pos)) || countEvents(events, StormSubtype.HURRICANE) >= Config.HURRICANE_MAX_ACTIVE.get()) {
            return;
        }
        if (storm.isTornadoFormingOrGreater() || storm.levelCurIntensityStage < StormObject.STATE_THUNDER || !DynamicStormController.isOverWaterOrOcean(level, storm.pos.x, storm.pos.z)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(storm.pos));
        double chance = Config.HURRICANE_SPAWN_CHANCE.get() * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * Math.max(0.10D, outlook.hurricaneSpawnMultiplier());
        if (level.random.nextDouble() > chance || hasNearbyAddonEvent(events, StormSubtype.HURRICANE, storm.pos, 1200.0D) || hasNearbyAddonEvent(events, StormSubtype.CYCLONE, storm.pos, 1200.0D)) {
            return;
        }
        float radius = 420.0F + level.random.nextFloat() * 540.0F;
        float wind = (float) Mth.clamp(74.0D + outlook.windProbability() * 92.0D + level.random.nextDouble() * 38.0D, 74.0D, 185.0D);
        Vec3 motion = storm.motion == null || storm.motion.horizontalDistanceSqr() < 1.0E-5D ? randomMotion(level, 0.012D, 0.040D) : storm.motion.scale(0.45D);
        configureHurricaneRainCore(storm, motion, wind, radius);
        events.add(new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.HURRICANE, surfacePos(level, storm.pos), motion, radius, wind, gameTime, gameTime + 20L * 60L * (8L + level.random.nextInt(20))));
        maintainHurricaneWeather(level, events.get(events.size() - 1));
    }

    private static void spawnDustDevils(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        if (!Config.DUST_DEVILS_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || level.isRaining() || level.isThundering() || !isDryAfternoon(level)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, level.getSharedSpawnPos());
        if (!outlook.favorsDustDevils()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            double chance = enabledChance(Config.DUST_DEVIL_SPAWN_CHANCE.get(), 0.018D) * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * Math.max(0.35D, outlook.dustDevilSpawnMultiplier());
            if (events.size() >= Config.MAX_ADDON_VORTICES.get() || level.random.nextDouble() > chance) {
                continue;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 48.0D, 180.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            if (!level.isLoaded(pos) || level.isRainingAt(pos) || !level.canSeeSky(pos.above()) || !hotDrySurface(level, pos) || hasNearbyWeather2Storm(level, candidate, 260.0D) || hasNearbyAddonEvent(events, StormSubtype.DUST_DEVIL, candidate, 96.0D)) {
                continue;
            }
            float wind = 24.0F + level.random.nextFloat() * 34.0F;
            float radius = 1.5F + level.random.nextFloat() * 5.0F;
            int life = 160 + level.random.nextInt(520);
            addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.DUST_DEVIL, candidate, randomMotion(level, 0.018D, 0.070D), radius, wind, gameTime, gameTime + life));
        }
    }

    private static void spawnSnowDevils(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        if (!Config.SNOW_DEVILS_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || level.isRaining() || level.isThundering()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            double chance = Config.SNOW_DEVIL_SPAWN_CHANCE.get() * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get();
            if (events.size() >= Config.MAX_ADDON_VORTICES.get() || level.random.nextDouble() > chance) {
                continue;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 42.0D, 170.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            if (!level.isLoaded(pos) || level.isRainingAt(pos) || !level.canSeeSky(pos.above()) || !snowDevilSurface(level, pos) || hasNearbyWeather2Storm(level, candidate, 220.0D) || hasNearbyAddonEvent(events, StormSubtype.SNOW_DEVIL, candidate, 128.0D)) {
                continue;
            }
            float wind = 18.0F + level.random.nextFloat() * 28.0F;
            float radius = 1.4F + level.random.nextFloat() * 4.0F;
            int life = 120 + level.random.nextInt(420);
            addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.SNOW_DEVIL, candidate, randomMotion(level, 0.010D, 0.045D), radius, wind, gameTime, gameTime + life));
        }
    }

    private static void spawnFireWeather(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        if (!Config.WILDFIRES_ENABLED.get() || level.isRaining() || level.isThundering()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (events.size() >= Config.MAX_ADDON_VORTICES.get()) {
                return;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 90.0D, 280.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            SpcRisk risk = fireWeatherRiskAt(level, pos);
            double multiplier = 0.55D + risk.severity() * 0.28D;
            double configuredChance = Math.min(Config.WILDFIRE_SPAWN_CHANCE.get(), 0.0014D);
            double chance = configuredChance * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * multiplier;
            if (risk.severity() < SpcRisk.MARGINAL.severity() || level.random.nextDouble() > chance) {
                continue;
            }
            if (!level.isLoaded(pos) || !fireWeatherSurface(level, pos) || hasNearbyAddonEvent(events, StormSubtype.WILDFIRE, candidate, 520.0D)) {
                continue;
            }
            float radius = (float) (34.0D + level.random.nextDouble() * 58.0D + risk.severity() * 8.0D);
            float wind = (float) Mth.clamp(12.0D + risk.severity() * 7.0D + level.random.nextDouble() * 18.0D, 8.0D, 70.0D);
            AddonWeatherEvent wildfire = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.WILDFIRE, candidate, randomMotion(level, 0.002D, 0.014D), radius, wind, gameTime, gameTime + 20L * 60L * (8L + level.random.nextInt(24)));
            addEventAndEntity(level, events, wildfire);
            maintainWildfire(level, wildfire);
            AddonWeatherEvent flammagenitus = maybeCreateFlammagenitusFromWildfire(level, events, wildfire, gameTime);
            if (flammagenitus != null) {
                addEventAndEntity(level, events, flammagenitus);
            }
        }
    }

    private static AddonWeatherEvent maybeCreateFireWhirlFromWildfire(ServerLevel level, List<AddonWeatherEvent> events, AddonWeatherEvent wildfire, long gameTime) {
        if (!Config.FIRE_WHIRLS_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || level.isRaining() || level.isThundering() || hasNearbyAddonEvent(events, StormSubtype.FIRE_WHIRL, wildfire.position(), Math.max(120.0D, wildfire.radius() * 2.2D))) {
            return null;
        }
        SpcRisk risk = fireWeatherRiskAt(level, BlockPos.containing(wildfire.position()));
        double chance = Config.FIRE_WHIRL_SPAWN_CHANCE.get() * Config.DAILY_SPC_SPAWN_MULTIPLIER_SCALE.get() * (0.70D + risk.severity() * 0.24D);
        if (risk.severity() < SpcRisk.SLIGHT.severity() || level.random.nextDouble() > chance) {
            return null;
        }
        Vec3 pos = surfacePos(level, wildfire.position().add(randomOffset(level, 4.0D, Math.max(18.0D, wildfire.radius() * 0.70D))));
        if (!fireWeatherSurface(level, BlockPos.containing(pos))) {
            return null;
        }
        float wind = (float) Mth.clamp(54.0D + risk.severity() * 10.0D + level.random.nextDouble() * 32.0D, 45.0D, 130.0D);
        float radius = 2.5F + level.random.nextFloat() * 6.5F;
        return new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.FIRE_WHIRL, pos, randomMotion(level, 0.015D, 0.070D), radius, wind, gameTime, gameTime + 20L * (25L + level.random.nextInt(155)));
    }

    private static AddonWeatherEvent maybeCreateFlammagenitusFromWildfire(ServerLevel level, List<AddonWeatherEvent> events, AddonWeatherEvent wildfire, long gameTime) {
        if (!Config.FLAMMAGENITUS_CLOUDS_ENABLED.get() || hasNearbyAddonEvent(events, StormSubtype.FLAMMAGENITUS, wildfire.position(), Math.max(220.0D, wildfire.radius() * 2.4D))) {
            return null;
        }
        SpcRisk risk = fireWeatherRiskAt(level, BlockPos.containing(wildfire.position()));
        if (risk.severity() < SpcRisk.SLIGHT.severity() || wildfire.radius() < 42.0F || level.random.nextDouble() > 0.35D) {
            return null;
        }
        float radius = (float) Mth.clamp(wildfire.radius() * 1.65D, 70.0D, 260.0D);
        return new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.FLAMMAGENITUS, flammagenitusAloftPos(level, wildfire.position()), wildfire.motion().scale(0.70D), radius, 0.0F, gameTime, Math.max(wildfire.expireTick(), gameTime + 20L * 60L * 5L));
    }

    private static void maintainWildfire(ServerLevel level, AddonWeatherEvent event) {
        if (!Config.WILDFIRES_ENABLED.get()) {
            return;
        }
        int attempts = Mth.clamp((int) (event.radius() / 7.0F), 4, 18);
        for (int i = 0; i < attempts; i++) {
            Vec3 target = event.position().add(randomOffset(level, 0.0D, event.radius()));
            tryIgniteSurface(level, BlockPos.containing(target));
        }
    }

    private static void maintainFireWhirlPath(ServerLevel level, AddonWeatherEvent event) {
        if (!Config.FIRE_WHIRLS_ENABLED.get()) {
            return;
        }
        int attempts = event.maxWindMph() >= 90.0F ? 4 : 2;
        for (int i = 0; i < attempts; i++) {
            Vec3 target = event.position().add(randomOffset(level, 0.0D, Math.max(5.0D, event.radius() * 1.8D)));
            tryIgniteSurface(level, BlockPos.containing(target));
        }
    }

    private static void detectSquallLine(ServerLevel level, List<AddonWeatherEvent> events, List<StormObject> cells, long gameTime) {
        if (!Config.SQUALL_LINES_ENABLED.get() || cells.isEmpty() || !DailySpcOutlookService.allowsSquallLines(level, level.getSharedSpawnPos())) {
            return;
        }
        List<StormObject> eligibleCells = new ArrayList<>();
        for (StormObject cell : cells) {
            if (isSquallLineCandidate(cell)) {
                eligibleCells.add(cell);
            }
        }
        int count = eligibleCells.size();
        if (count == 0) {
            return;
        }

        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(eligibleCells.get(0).pos));
        boolean severeLinearSeed = false;
        int maxStage = StormObject.STATE_THUNDER;
        for (StormObject cell : eligibleCells) {
            maxStage = Math.max(maxStage, cell.levelCurIntensityStage);
            if (cell.levelCurIntensityStage >= StormObject.STATE_HIGHWIND || (cell.levelCurIntensityStage >= StormObject.STATE_HAIL && cell.isPrecipitating())) {
                severeLinearSeed = true;
            }
        }
        if (count < Config.SQUALL_LINE_MIN_CELLS.get() && !severeLinearSeed) {
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

        Vec3 averageMotion = averageStormMotion(eligibleCells);
        StormObject anchor = strongestStorm(eligibleCells);
        Vec3 forward = averageMotion.horizontalDistanceSqr() > 1.0E-5D ? new Vec3(averageMotion.x, 0.0D, averageMotion.z).normalize() : stormForward(level, anchor);
        Vec3 axis = new Vec3(-forward.z, 0.0D, forward.x);

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
        if (count >= 2) {
            double axisAngle = 0.5D * Math.atan2(2.0D * covXZ, covXX - covZZ);
            Vec3 pcaAxis = new Vec3(Math.cos(axisAngle), 0.0D, Math.sin(axisAngle));
            if (Math.abs(pcaAxis.dot(forward)) < 0.64D) {
                axis = pcaAxis;
                if (axis.dot(new Vec3(-forward.z, 0.0D, forward.x)) < 0.0D) {
                    axis = axis.scale(-1.0D);
                }
            }
        }

        List<Double> projections = new ArrayList<>(count);
        double minProjection = Double.MAX_VALUE;
        double maxProjection = -Double.MAX_VALUE;
        double avgAbsPerp = 0.0D;
        for (StormObject cell : eligibleCells) {
            double dx = cell.pos.x - meanX;
            double dz = cell.pos.z - meanZ;
            double projection = dx * axis.x + dz * axis.z;
            double perpendicular = Math.abs(dx * forward.x + dz * forward.z);
            projections.add(projection);
            minProjection = Math.min(minProjection, projection);
            maxProjection = Math.max(maxProjection, projection);
            avgAbsPerp += perpendicular;
        }
        avgAbsPerp /= count;

        float wind = squallLineWind(level, eligibleCells);
        double length = maxProjection - minProjection;
        if (length < 1.0D) {
            length = 360.0D + Mth.clamp(wind - 58.0D, 0.0D, 40.0D) * 8.0D + outlook.windProbability() * 260.0D;
            minProjection = -length * 0.5D;
            maxProjection = length * 0.5D;
        } else {
            double padding = 120.0D + Math.min(180.0D, wind * 1.35D);
            minProjection -= padding;
            maxProjection += padding;
            length = maxProjection - minProjection;
        }
        if (length < 260.0D || (count >= Config.SQUALL_LINE_MIN_CELLS.get() && largestProjectionGap(projections) > Math.max(360.0D, length * 0.62D))) {
            return;
        }
        length = Mth.clamp(length, 320.0D, 1280.0D);
        minProjection = -length * 0.5D;
        maxProjection = length * 0.5D;

        double lineSpeed = averageMotion.horizontalDistanceSqr() > 1.0E-5D ? Math.sqrt(averageMotion.horizontalDistanceSqr()) : 0.065D;
        Vec3 motion = forward.scale(Mth.clamp(lineSpeed, 0.035D, 0.16D));
        double gustFrontLead = 36.0D + wind * 0.70D + Math.max(0, maxStage - StormObject.STATE_THUNDER) * 10.0D;
        Vec3 center = squallLineAloftPos(level, new Vec3(meanX, meanY, meanZ).add(forward.scale(gustFrontLead)));
        Vec3 start = center.add(axis.scale(minProjection));
        Vec3 end = center.add(axis.scale(maxProjection));
        double maxThickness = Config.SQUALL_LINE_MAX_THICKNESS_BLOCKS.get();
        float windRadius = (float) Mth.clamp(avgAbsPerp + 92.0D + wind * 0.78D, 72.0D, maxThickness);
        long expireTick = gameTime + squallLineLifetimeTicks(level, outlook, wind);

        for (AddonWeatherEvent event : events) {
            if (event.type() == StormSubtype.SQUALL_LINE && event.position().distanceTo(center) < Math.max(360.0D, length * 0.38D)) {
                event.refreshLine(start, end, motion, expireTick);
                organizeSquallLineWeather2Cells(level, eligibleCells, motion, wind, false);
                seedWeather2SquallLineStorms(level, start, end, motion, wind, gameTime);
                maybeSpawnGustnadoFromLine(level, events, start, end, motion, wind, gameTime);
                return;
            }
        }
        events.add(AddonWeatherEvent.squallLine(nextId(gameTime, level), start, end, motion, windRadius, wind, gameTime, expireTick));
        organizeSquallLineWeather2Cells(level, eligibleCells, motion, wind, false);
        seedWeather2SquallLineStorms(level, start, end, motion, wind, gameTime);
        maybeSpawnGustnadoFromLine(level, events, start, end, motion, wind, gameTime);
    }

    private static void organizeSquallLineWeather2Cells(ServerLevel level, List<StormObject> cells, Vec3 motion, float lineWind, boolean capToThunderstorm) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null || cells == null || cells.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (StormObject cell : cells) {
            if (configureSquallThunderstormCell(cell, motion, lineWind, capToThunderstorm)) {
                changed = true;
                manager.syncStormUpdate(cell);
            }
        }
        if (changed) {
            manager.isVanillaRainActiveOnServer = true;
            manager.isVanillaThunderActiveOnServer = true;
            manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 6);
            manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, 0.82F);
        }
    }

    private static void seedWeather2SquallLineStorms(ServerLevel level, Vec3 start, Vec3 end, Vec3 motion, float lineWind, long gameTime) {
        if (start == null || end == null || motion == null) {
            return;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        Vec3 line = end.subtract(start);
        double length = Math.sqrt(line.horizontalDistanceSqr());
        if (length < 16.0D) {
            return;
        }
        Vec3 forward = motion.horizontalDistanceSqr() > 1.0E-5D ? new Vec3(motion.x, 0.0D, motion.z).normalize() : new Vec3(-line.z, 0.0D, line.x).normalize();
        int desiredCells = Mth.clamp(2 + (int) Math.round(length / 210.0D), 3, 9);
        int spawned = 0;
        for (int i = 0; i < desiredCells; i++) {
            double t = desiredCells == 1 ? 0.5D : i / (double) (desiredCells - 1);
            double bow = squallLineBowOffset(t, length);
            Vec3 candidate = squallLineAloftPos(level, start.lerp(end, t).add(forward.scale(bow - 34.0D)));
            if (hasNearbyWeather2Thunderstorm(manager, candidate, 150.0D)) {
                continue;
            }
            StormObject storm = new StormObject(manager);
            storm.initPositions(candidate);
            storm.initFirstTime();
            configureSquallThunderstormCell(storm, motion, lineWind, true);
            storm.naturallySpawned = true;
            storm.spawnerUUID = SQUALL_LINE_HELPER_UUID;
            manager.addStormObject(storm);
            manager.syncStormNew(storm);
            spawned++;
            if (spawned >= 4 && desiredCells > 5 && level.random.nextFloat() < 0.35F) {
                break;
            }
        }
        if (spawned > 0) {
            manager.lastStormFormed = Math.max(manager.lastStormFormed, gameTime);
            manager.isVanillaRainActiveOnServer = true;
            manager.isVanillaThunderActiveOnServer = true;
            manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 8);
            manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, 0.90F);
        }
    }

    private static boolean configureSquallThunderstormCell(StormObject storm, Vec3 motion, float lineWind, boolean capToThunderstorm) {
        if (storm == null || storm.isDead || storm.isTornadoFormingOrGreater() || DynamicStormController.isNativeCyclone(storm)) {
            return false;
        }
        int targetStage = StormObject.STATE_THUNDER;
        boolean changed = false;
        if (storm.levelCurIntensityStage < targetStage) {
            storm.levelCurIntensityStage = targetStage;
            changed = true;
        }
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity, lineWind >= 82.0F ? 0.72F : 0.58F);
        if (capToThunderstorm) {
            storm.maxIntensityStage = targetStage;
            storm.levelStormIntensityMax = targetStage;
        } else {
            storm.maxIntensityStage = Math.min(StormObject.STATE_HAIL, Math.max(storm.maxIntensityStage, targetStage));
            storm.levelStormIntensityMax = Math.min(StormObject.STATE_HAIL, Math.max(storm.levelStormIntensityMax, targetStage));
        }
        storm.stormType = StormObject.TYPE_LAND;
        storm.isGrowing = true;
        storm.hasStormPeaked = false;
        storm.canBeDeadly = false;
        storm.alwaysProgresses = false;
        storm.spawnerUUID = SQUALL_LINE_HELPER_UUID;
        storm.spinSpeed = 0.0D;
        storm.tempAngleFormingTornado = 0.0F;
        storm.setCloudlessStorm(false);
        storm.setPrecipitating(true);
        storm.attrib_waterSpout = false;
        storm.levelWindMomentum = Math.max(storm.levelWindMomentum, (float) Mth.clamp(lineWind / 90.0F, 0.55F, 1.35F));
        storm.levelWater = Math.max(storm.levelWater, Math.max(80, storm.levelWaterStartRaining * 2));
        storm.size = Math.max(storm.size, Mth.clamp(170 + (int) lineWind, 180, 360));
        storm.maxSize = Math.max(storm.maxSize, Mth.clamp(230 + (int) (lineWind * 1.4F), 260, 520));
        if (motion != null && motion.horizontalDistanceSqr() > 1.0E-5D) {
            Vec3 forward = new Vec3(motion.x, 0.0D, motion.z).normalize();
            storm.motion = forward.scale(Mth.clamp(Math.sqrt(motion.horizontalDistanceSqr()), 0.045D, 0.15D));
            storm.angleMovementTornadoOverride = 0.0F;
            storm.angleIsOverridden = false;
        }
        return changed || capToThunderstorm;
    }

    private static void configureHurricaneRainCore(StormObject storm, Vec3 motion, float wind, float radius) {
        if (storm == null) {
            return;
        }
        storm.stormType = StormObject.TYPE_LAND;
        storm.attrib_waterSpout = false;
        storm.spawnerUUID = HURRICANE_HELPER_UUID;
        storm.setCloudlessStorm(false);
        storm.setPrecipitating(true);
        storm.canBeDeadly = false;
        storm.alwaysProgresses = false;
        storm.hasStormPeaked = false;
        storm.isGrowing = true;
        storm.spinSpeed = 0.0D;
        storm.tempAngleFormingTornado = 0.0F;
        storm.angleMovementTornadoOverride = 0.0F;
        storm.angleIsOverridden = false;

        int safeStage = wind >= 96.0F ? StormObject.STATE_HAIL : StormObject.STATE_HIGHWIND;
        storm.levelCurIntensityStage = Mth.clamp(Math.max(storm.levelCurIntensityStage, StormObject.STATE_THUNDER), StormObject.STATE_THUNDER, safeStage);
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity, 0.62F);
        storm.levelStormIntensityMax = Mth.clamp(Math.max(storm.levelStormIntensityMax, safeStage), StormObject.STATE_THUNDER, StormObject.STATE_HAIL);
        storm.maxIntensityStage = Mth.clamp(Math.max(storm.maxIntensityStage, safeStage), StormObject.STATE_THUNDER, StormObject.STATE_HAIL);
        storm.levelWater = Math.max(storm.levelWater, Math.max(160, storm.levelWaterStartRaining * 5));
        storm.levelWindMomentum = Math.max(storm.levelWindMomentum, (float) Mth.clamp(wind / 90.0F, 0.75F, 1.85F));
        storm.size = Math.max(storm.size, Mth.clamp((int) Math.round(radius * 0.28F), 220, 560));
        storm.maxSize = Math.max(storm.maxSize, Mth.clamp((int) Math.round(radius * 0.46F), 300, 780));
        if (motion != null && motion.horizontalDistanceSqr() > 1.0E-5D) {
            Vec3 forward = new Vec3(motion.x, 0.0D, motion.z).normalize();
            storm.motion = forward.scale(Mth.clamp(Math.sqrt(motion.horizontalDistanceSqr()), 0.012D, 0.055D));
        }
    }

    private static void maintainHurricaneWeather(ServerLevel level, AddonWeatherEvent event) {
        if (level == null || event == null || !isBroadRotatingStorm(event.type())) {
            return;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        manager.isVanillaRainActiveOnServer = true;
        manager.isVanillaThunderActiveOnServer = true;
        manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 10);
        manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, event.maxWindMph() >= 96.0F ? 1.0F : 0.88F);
    }

    /**
     * Weather2's storm progression is re-clamped on the hurricane's helper rain core every two
     * seconds. The core is configured below the forming stage at spawn, but it is still a real
     * ticking StormObject with growth enabled, and Weather2's own progression keeps raising its
     * stage - past the forming stage, where Weather2 hangs a point funnel that reads as a tornado
     * in the hurricane's middle. The funnel suppressor and damage gates are the backstop; this
     * keeps the core honest in the first place, so its rain and wind profile never silently
     * becomes a tornado's.
     */
    private static void reClampHurricaneHelperStages(ServerLevel level, AddonWeatherEvent event) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        double range = Math.max(320.0D, event.radius());
        double rangeSqr = range * range;
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject storm
                    && isHurricaneHelperStorm(storm)
                    && storm.pos != null
                    && !storm.isDead
                    && storm.pos.distanceToSqr(event.position()) <= rangeSqr) {
                if (storm.levelCurIntensityStage > StormObject.STATE_HAIL) {
                    storm.levelCurIntensityStage = StormObject.STATE_HAIL;
                }
                if (storm.levelStormIntensityMax > StormObject.STATE_HAIL) {
                    storm.levelStormIntensityMax = StormObject.STATE_HAIL;
                }
                if (storm.maxIntensityStage > StormObject.STATE_HAIL) {
                    storm.maxIntensityStage = StormObject.STATE_HAIL;
                }
            }
        }
    }

    private static boolean hasNearbyWeather2Thunderstorm(WeatherManagerServer manager, Vec3 center, double range) {
        double rangeSqr = range * range;
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject storm
                    && storm.pos != null
                    && !storm.isDead
                    && storm.pos.distanceToSqr(center) <= rangeSqr
                    && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER) {
                return true;
            }
        }
        return false;
    }

    private static void seedNativeTropicalCyclone(ServerLevel level, Vec3 center, Vec3 motion, float maxWindMph, float radius, long gameTime) {
        if (!Config.CYCLONES_ENABLED.get() || level == null || center == null) {
            return;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null || hasNearbyWeather2Cyclone(manager, center, Math.max(220.0D, radius * 0.68D))) {
            return;
        }
        StormObject storm = new StormObject(manager);
        storm.initPositions(cycloneAloftPos(level, center));
        storm.initFirstTime();
        configureNativeTropicalCyclone(storm, motion, maxWindMph, radius);
        storm.naturallySpawned = true;
        manager.addStormObject(storm);
        manager.syncStormNew(storm);
        manager.lastStormFormed = Math.max(manager.lastStormFormed, gameTime);
        manager.isVanillaRainActiveOnServer = true;
        manager.isVanillaThunderActiveOnServer = true;
        manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 10);
        manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, 0.95F);
    }

    private static void configureNativeTropicalCyclone(StormObject storm, Vec3 motion, float maxWindMph, float radius) {
        if (storm == null) {
            return;
        }
        storm.stormType = StormObject.TYPE_WATER;
        storm.attrib_waterSpout = false;
        storm.setCloudlessStorm(false);
        storm.setPrecipitating(true);
        storm.canBeDeadly = true;
        storm.isGrowing = true;
        storm.hasStormPeaked = false;
        storm.alwaysProgresses = true;
        int stage = DynamicStormController.cycloneStageForWind(maxWindMph);
        storm.levelCurIntensityStage = Math.max(storm.levelCurIntensityStage, stage);
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity, 0.74F);
        storm.levelStormIntensityMax = Math.max(Math.max(storm.levelStormIntensityMax, stage), storm.rollDiceOnMaxIntensity());
        storm.maxIntensityStage = Math.max(storm.maxIntensityStage, storm.levelStormIntensityMax);
        storm.levelWater = Math.max(storm.levelWater, Math.max(180, storm.levelWaterStartRaining * 5));
        storm.levelWindMomentum = Math.max(storm.levelWindMomentum, (float) Mth.clamp(maxWindMph / 85.0F, 0.95F, 2.45F));
        storm.size = Math.max(storm.size, Mth.clamp((int) Math.round(radius * 0.36F), 260, 760));
        storm.maxSize = Math.max(storm.maxSize, Mth.clamp((int) Math.round(radius * 0.72F), 420, 1300));
        if (motion != null && motion.horizontalDistanceSqr() > 1.0E-5D) {
            Vec3 forward = new Vec3(motion.x, 0.0D, motion.z).normalize();
            storm.motion = forward.scale(Mth.clamp(Math.sqrt(motion.horizontalDistanceSqr()), 0.012D, 0.065D));
        } else if (storm.manager.getWorld() instanceof ServerLevel level) {
            storm.motion = randomMotion(level, 0.010D, 0.035D);
        } else {
            storm.motion = new Vec3(0.018D, 0.0D, 0.0D);
        }
        storm.spinSpeed = Math.max(Math.abs(storm.spinSpeed), 0.045D);
        DynamicStormController.stateFor(storm).setSubtype(DynamicStormController.isNativeHurricane(storm) ? StormSubtype.HURRICANE : StormSubtype.CYCLONE);
    }

    private static Vec3 cycloneAloftPos(ServerLevel level, Vec3 center) {
        Vec3 surface = surfacePos(level, center);
        return new Vec3(surface.x, Math.max(surface.y + 96.0D, level.getSeaLevel() + 112.0D), surface.z);
    }

    private static boolean hasNearbyWeather2Cyclone(WeatherManagerServer manager, Vec3 center, double range) {
        double rangeSqr = range * range;
        for (WeatherObject object : manager.getStormObjects()) {
            if (object instanceof StormObject storm
                    && storm.pos != null
                    && !storm.isDead
                    && DynamicStormController.isNativeCyclone(storm)
                    && storm.pos.distanceToSqr(center) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    private static void maybeSpawnGustnadoFromLine(ServerLevel level, List<AddonWeatherEvent> events, Vec3 start, Vec3 end, Vec3 motion, float lineWind, long gameTime) {
        if (!Config.GUSTNADOES_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || start == null || end == null || motion == null || motion.horizontalDistanceSqr() < 1.0E-5D) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(start.add(end).scale(0.5D)));
        double chance = enabledChance(Config.GUSTNADO_SPAWN_CHANCE.get(), 0.045D) * 1.65D * Math.max(0.55D, outlook.windProbability());
        if (level.random.nextDouble() > chance) {
            return;
        }
        Vec3 alongPoint = start.lerp(end, level.random.nextDouble());
        Vec3 forward = new Vec3(motion.x, 0.0D, motion.z).normalize();
        Vec3 pos = surfacePos(level, alongPoint.add(forward.scale(18.0D + level.random.nextDouble() * 48.0D)));
        if (DynamicStormController.isOverWaterOrOcean(level, pos.x, pos.z) || hasNearbyAddonEvent(events, StormSubtype.GUSTNADO, pos, 105.0D)) {
            return;
        }
        float wind = gustnadoLineWindMph(level, outlook, lineWind);
        float radius = 2.0F + level.random.nextFloat() * 5.0F;
        addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.GUSTNADO, pos, forward.scale(0.060D + level.random.nextDouble() * 0.070D), radius, wind, gameTime, gameTime + gustnadoLifetimeTicks(level, true)));
    }

    private static float gustnadoWindMph(ServerLevel level, DailySpcOutlook outlook, StormObject storm) {
        double windProbability = outlook == null ? 0.0D : outlook.windProbability();
        boolean aggressiveDowndraft = storm != null
                && storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND
                && storm.isPrecipitating()
                && windProbability >= 0.45D;
        double roll = level.random.nextDouble();
        double intenseChance = aggressiveDowndraft ? Mth.clamp(0.06D + windProbability * 0.14D, 0.06D, 0.20D) : 0.0D;
        if (roll < intenseChance) {
            return (float) (100.0D + level.random.nextDouble() * 10.0D);
        }
        boolean strongOutflow = storm != null && (storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND || windProbability >= 0.50D);
        double strongChance = strongOutflow ? 0.24D : 0.08D;
        if (roll < intenseChance + strongChance) {
            return (float) (80.0D + level.random.nextDouble() * 20.0D);
        }
        return (float) (50.0D + level.random.nextDouble() * 30.0D);
    }

    private static float gustnadoLineWindMph(ServerLevel level, DailySpcOutlook outlook, float lineWind) {
        double windProbability = outlook == null ? 0.0D : outlook.windProbability();
        boolean aggressiveDowndraft = lineWind >= 88.0F && windProbability >= 0.45D;
        double roll = level.random.nextDouble();
        double intenseChance = aggressiveDowndraft ? Mth.clamp(0.05D + (lineWind - 88.0D) / 160.0D + windProbability * 0.10D, 0.05D, 0.22D) : 0.0D;
        if (roll < intenseChance) {
            return (float) (100.0D + level.random.nextDouble() * 10.0D);
        }
        double strongChance = lineWind >= 74.0F ? 0.30D : 0.12D;
        if (roll < intenseChance + strongChance) {
            return (float) (80.0D + level.random.nextDouble() * 20.0D);
        }
        return (float) (50.0D + level.random.nextDouble() * 30.0D);
    }

    private static long gustnadoLifetimeTicks(ServerLevel level, boolean lineSpawn) {
        int base = lineSpawn ? 100 : 120;
        int commonRange = lineSpawn ? 960 : 1260;
        int longTail = level.random.nextFloat() < 0.22F ? 900 + level.random.nextInt(2100) : 0;
        return base + level.random.nextInt(commonRange) + longTail;
    }

    private static long squallLineLifetimeTicks(ServerLevel level, DailySpcOutlook outlook, float lineWindMph) {
        long hour = 20L * 60L * 60L;
        long common = 3L * hour + level.random.nextInt((int) (5L * hour) + 1);
        double windProbability = outlook == null ? 0.0D : outlook.windProbability();
        double exceptionalChance = Mth.clamp(0.04D + windProbability * 0.22D + Math.max(0.0D, lineWindMph - 70.0D) / 160.0D, 0.04D, 0.30D);
        long life = lineWindMph >= 74.0F && level.random.nextDouble() < exceptionalChance
                ? 12L * hour + level.random.nextInt((int) (18L * hour) + 1)
                : common;
        return Math.min(life, Math.max(hour, Config.SQUALL_LINE_MAX_AGE_TICKS.get().longValue()));
    }

    private static float commandFloat(double value, double fallback, double min, double max) {
        return (float) commandDouble(value, fallback, min, max);
    }

    private static double commandDouble(double value, double fallback, double min, double max) {
        double chosen = Double.isFinite(value) ? value : fallback;
        return Mth.clamp(chosen, min, max);
    }

    private static void applyImpacts(ServerLevel level, AddonWeatherEvent event) {
        if (event.type() == StormSubtype.WILDFIRE || event.type() == StormSubtype.FLAMMAGENITUS) {
            return;
        }
        double range = event.isLine()
                ? event.radius() + 96.0D
                : event.type() == StormSubtype.GUSTNADO ? Math.max(event.radius() * 7.0D, 36.0D) : event.type() == StormSubtype.FIRE_WHIRL ? Math.max(event.radius() * 5.5D, 28.0D) : event.radius() * 4.0D;
        AABB box = impactBox(level, event, range);
        for (Entity entity : level.getEntities((Entity) null, box, Entity::isAlive)) {
            if (entity instanceof Player player && player.isCreative()) {
                continue;
            }
            TornadoWindField.WindSample sample = sampleEventWind(event, entity.position());
            double impulseThreshold = event.type() == StormSubtype.GUSTNADO ? 10.0D : event.type() == StormSubtype.FIRE_WHIRL ? 12.0D : event.type() == StormSubtype.SQUALL_LINE ? 18.0D : 24.0D;
            if (sample.speedMph() >= impulseThreshold) {
                Vec3 impulse = TornadoWindField.mphToEntityDelta(sample.mphVector());
                if (event.type() == StormSubtype.GUSTNADO) {
                    double lift = Mth.clamp((sample.speedMph() - 38.0D) * 0.0032D, 0.0D, 0.20D);
                    impulse = impulse.scale(3.25D).add(0.0D, lift, 0.0D);
                } else if (event.type() == StormSubtype.FIRE_WHIRL) {
                    double lift = Mth.clamp((sample.speedMph() - 32.0D) * 0.0030D, 0.0D, 0.24D);
                    impulse = impulse.scale(2.35D).add(0.0D, lift, 0.0D);
                } else if (event.type() == StormSubtype.SQUALL_LINE) {
                    impulse = impulse.scale(1.55D);
                }
                entity.setDeltaMovement(entity.getDeltaMovement().add(impulse));
                entity.hurtMarked = true;
                if (event.type() == StormSubtype.GUSTNADO && sample.speedMph() >= 58.0D && entity.tickCount % 20 == 0) {
                    float damage = (float) Mth.clamp((sample.speedMph() - 46.0D) / 34.0D, 0.5D, 2.0D);
                    entity.hurt(level.damageSources().generic(), damage);
                } else if (event.type() == StormSubtype.FIRE_WHIRL && sample.speedMph() >= 48.0D && entity.tickCount % 20 == 0) {
                    entity.setRemainingFireTicks(60);
                    float damage = (float) Mth.clamp((sample.speedMph() - 38.0D) / 42.0D, 0.5D, 2.4D);
                    entity.hurt(level.damageSources().onFire(), damage);
                }
            }
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            tryPickupGustnadoDebris(level, event);
        }
        if (event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL || event.type() == StormSubtype.FLOATER_TORNADO || event.maxWindMph() < (event.type() == StormSubtype.GUSTNADO ? 48.0F : 62.0F)) {
            return;
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            int chanceDivisor = event.maxWindMph() >= 72.0F ? 2 : 3;
            if (level.random.nextInt(chanceDivisor) != 0) {
                return;
            }
        } else if (level.random.nextInt(3) != 0) {
            return;
        }
        int edits = event.type() == StormSubtype.SQUALL_LINE ? 3 : event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.FIRE_WHIRL ? 4 : 2;
        for (int i = 0; i < edits; i++) {
            double blockRadius = event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.FIRE_WHIRL ? Math.max(event.radius() * 2.8D, 11.0D) : event.radius() * 1.7D;
            Vec3 target = event.isLine() ? randomPointNearLine(level, event) : event.position().add(randomOffset(level, 0.0D, blockRadius));
            BlockPos targetPos = BlockPos.containing(target);
            if (!level.isLoaded(targetPos)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, targetPos, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            if (event.type() == StormSubtype.GUSTNADO) {
                BlockGrabSafety.BlockSample plant = BlockGrabSafety.sample(level, surface, null);
                if (BlockGrabSafety.canEdit(plant) && isGustnadoFragileBlock(plant.state())) {
                    BlockGrabSafety.safeSetBlock(level, surface, Blocks.AIR.defaultBlockState(), 3);
                    continue;
                }
            }
            BlockPos pos = surface.below();
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            if (!BlockGrabSafety.canEdit(block)) {
                continue;
            }
            BlockState state = block.state();
            if (event.type() == StormSubtype.FIRE_WHIRL && isFireFuel(state)) {
                tryIgniteSurface(level, surface);
                continue;
            }
            if (state.is(BlockTags.CROPS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES) || (event.type() == StormSubtype.GUSTNADO && isGustnadoFragileBlock(state))) {
                BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3);
            } else if (event.type() == StormSubtype.GUSTNADO && event.maxWindMph() >= 86.0F && isGustnadoLooseBlock(state)) {
                BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3);
            } else if ((event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.GUSTNADO) && event.maxWindMph() >= 75.0F && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.FARMLAND))) {
                float wind = event.maxWindMph();
                Block replacement = wind >= 150.0F ? Blocks.COARSE_DIRT : Blocks.DIRT_PATH;
                if (Config.DAMAGE_SCOURING_EXCAVATION.get() && wind >= 185.0F) {
                    BlockState below = level.getBlockState(pos.below());
                    if (!below.isAir() && below.getFluidState().isEmpty() && !below.is(Blocks.BEDROCK)) {
                        BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3);
                        continue;
                    }
                }
                BlockGrabSafety.safeSetBlock(level, pos, replacement.defaultBlockState(), 3);
            }
        }
    }

    private static void tryPickupGustnadoDebris(ServerLevel level, AddonWeatherEvent event) {
        if (!Config.DAMAGE_ENABLED.get() || !CompatHooks.useVanillaBlockLifting() || event.maxWindMph() < 50.0F) {
            return;
        }
        int cap = event.maxWindMph() >= 100.0F ? 10 : event.maxWindMph() >= 80.0F ? 7 : 4;
        int lifted = 0;
        int attempts = cap * 18;
        double searchRadius = Math.max(event.radius() * 7.0D, 36.0D);
        for (int i = 0; i < attempts && lifted < cap; i++) {
            Vec3 target = event.position().add(randomOffset(level, 0.0D, searchRadius));
            BlockPos column = BlockPos.containing(target);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            BlockPos pos = gustnadoPickupCandidate(level, surface);
            if (pos == null || !level.isLoaded(pos)) {
                continue;
            }
            BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
            TornadoWindField.WindSample sample = sampleEventWind(event, Vec3.atCenterOf(pos));
            double localMph = Math.max(sample.strongestLocalMph(), event.maxWindMph() * gustnadoDistanceFalloff(event, pos));
            if (!canPickupGustnadoBlock(block, localMph)) {
                continue;
            }
            if (spawnGustnadoFallingBlock(level, event, pos, block.state(), localMph)) {
                lifted++;
            }
        }
    }

    private static BlockPos gustnadoPickupCandidate(ServerLevel level, BlockPos surface) {
        BlockPos[] candidates = {
                surface,
                surface.below(),
                surface.above(),
                surface.above(2),
                surface.below(2)
        };
        for (BlockPos candidate : candidates) {
            BlockGrabSafety.BlockSample sample = BlockGrabSafety.sample(level, candidate, null);
            if (sample.valid() && !sample.air() && !sample.fluid()) {
                return candidate;
            }
        }
        return null;
    }

    private static double gustnadoDistanceFalloff(AddonWeatherEvent event, BlockPos pos) {
        double distance = Vec3.atCenterOf(pos).distanceTo(event.position());
        double radius = Math.max(event.radius() * 4.6D, 18.0D);
        return Mth.clamp(1.0D - distance / radius, 0.0D, 1.0D);
    }

    private static boolean canPickupGustnadoBlock(BlockGrabSafety.BlockSample sample, double windMph) {
        if (!BlockGrabSafety.canEdit(sample) || sample.hasBlockEntity()) {
            return false;
        }
        BlockState state = sample.state();
        if (Config.DAMAGE_NEVER_PICKUP_SOIL.get() && BlockGrabSafety.isNeverPickupTerrain(sample)) {
            return false;
        }
        if (isGustnadoFragileBlock(state)) {
            return windMph >= 34.0D;
        }
        if (isGustnadoLightDebris(state)) {
            return windMph >= 40.0D;
        }
        if (isGustnadoLooseBlock(state)) {
            return windMph >= 52.0D;
        }
        if (isGustnadoWoodDebris(state)) {
            return windMph >= 76.0D;
        }
        return BlockGrabSafety.canPickupByWind(sample, windMph) && sample.destroySpeed() <= 1.5F;
    }

    private static boolean spawnGustnadoFallingBlock(ServerLevel level, AddonWeatherEvent event, BlockPos pos, BlockState state, double localMph) {
        try {
            FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, state);
            if (falling == null) {
                return false;
            }
            Vec3 center = Vec3.atCenterOf(pos);
            Vec3 fromCenter = center.subtract(event.position());
            double distance = Math.max(0.45D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
            Vec3 radial = new Vec3(fromCenter.x / distance, 0.0D, fromCenter.z / distance);
            Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
            Vec3 outflow = event.motion() != null && event.motion().horizontalDistanceSqr() > 1.0E-5D
                    ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize()
                    : Vec3.ZERO;
            double strength = Mth.clamp(localMph / 100.0D, 0.45D, 1.18D);
            Vec3 launch = tangent.scale(0.12D + strength * 0.24D)
                    .add(radial.scale(-0.035D * strength))
                    .add(outflow.scale(0.08D + strength * 0.13D))
                    .add(0.0D, 0.16D + strength * 0.34D, 0.0D);
            falling.setDeltaMovement(launch);
            falling.hurtMarked = true;
            return true;
        } catch (RuntimeException ignored) {
            BlockGrabSafety.invalidate(level, pos);
            return false;
        }
    }

    private static boolean isGustnadoFragileBlock(BlockState state) {
        return state.is(BlockTags.CROPS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.CAVE_VINES)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.DEAD_BUSH);
    }

    private static boolean isGustnadoLightDebris(BlockState state) {
        return idPathContains(state, "glass")
                || idPathContains(state, "pane")
                || idPathContains(state, "wool")
                || idPathContains(state, "carpet")
                || idPathContains(state, "banner");
    }

    private static boolean isGustnadoLooseBlock(BlockState state) {
        return state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.FENCE_GATES)
                || idPathContains(state, "trapdoor")
                || idPathContains(state, "sign");
    }

    private static boolean isGustnadoWoodDebris(BlockState state) {
        return state.is(BlockTags.PLANKS)
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS)
                || idPathContains(state, "plank");
    }

    private static boolean idPathContains(BlockState state, String text) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && id.getPath().contains(text);
    }

    private static TornadoWindField.WindSample sampleEventWind(AddonWeatherEvent event, Vec3 position) {
        if (event.isLine()) {
            return sampleLineWind(event, position);
        }
        if (event.type() == StormSubtype.HURRICANE || event.type() == StormSubtype.CYCLONE) {
            return sampleHurricaneWind(event, position);
        }
        if (event.type() == StormSubtype.WILDFIRE || event.type() == StormSubtype.FLAMMAGENITUS) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 center = new Vec3(event.position().x, position.y, event.position().z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(0.4D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double rmw = Math.max(1.2D, event.radius());
        double max = event.maxWindMph();
        double tangential = radius <= rmw ? max * (radius / rmw) : max * Math.pow(rmw / radius, 0.86D);
        double outerRadius = event.type() == StormSubtype.GUSTNADO ? rmw * 7.4D : event.type() == StormSubtype.FIRE_WHIRL ? rmw * 5.5D : event.type() == StormSubtype.FLOATER_TORNADO ? rmw * 5.0D : event.type() == StormSubtype.SNOW_DEVIL ? rmw * 3.3D : rmw * 4.0D;
        if (radius > outerRadius) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 radial = fromCenter.scale(1.0D / radius);
        Vec3 tangent = new Vec3(-radial.z, 0.0D, radial.x);
        double inflow = 0.18D;
        if (event.type() == StormSubtype.DUST_DEVIL) {
            inflow = 0.10D;
        } else if (event.type() == StormSubtype.SNOW_DEVIL) {
            inflow = 0.08D;
        } else if (event.type() == StormSubtype.WATERSPOUT) {
            inflow = 0.24D;
        } else if (event.type() == StormSubtype.GUSTNADO) {
            inflow = 0.18D;
        } else if (event.type() == StormSubtype.FIRE_WHIRL) {
            inflow = 0.22D;
        } else if (event.type() == StormSubtype.FLOATER_TORNADO) {
            inflow = 0.14D;
        }
        double lift = event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL ? tangential * 0.18D : event.type() == StormSubtype.GUSTNADO ? tangential * 0.24D : event.type() == StormSubtype.FIRE_WHIRL ? tangential * 0.30D : event.type() == StormSubtype.FLOATER_TORNADO ? tangential * 0.12D : tangential * 0.25D;
        Vec3 outflow = event.type() == StormSubtype.GUSTNADO && event.motion().horizontalDistanceSqr() > 1.0E-5D
                ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize().scale(tangential * 0.60D)
                : Vec3.ZERO;
        Vec3 vector = tangent.scale(tangential).add(radial.scale(-tangential * inflow)).add(outflow).add(0.0D, lift, 0.0D);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), radius, rmw * 2.0D, rmw);
    }

    private static TornadoWindField.WindSample sampleHurricaneWind(AddonWeatherEvent event, Vec3 position) {
        Vec3 center = new Vec3(event.position().x, position.y, event.position().z);
        Vec3 fromCenter = position.subtract(center);
        double radius = Math.max(1.0D, Math.sqrt(fromCenter.x * fromCenter.x + fromCenter.z * fromCenter.z));
        double eye = Math.max(32.0D, event.radius() * 0.16D);
        double rmw = Math.max(64.0D, event.radius() * 0.42D);
        double outer = Math.max(event.radius() * 2.2D, rmw * 3.0D);
        if (radius > outer) {
            return TornadoWindField.WindSample.CALM;
        }
        double max = event.type() == StormSubtype.LANDSPOUT ? Math.min(LandspoutEntity.MAX_EF2_WIND_MPH, event.maxWindMph()) : event.maxWindMph();
        double inner = radius < eye ? Math.max(0.12D, radius / eye) : 1.0D;
        double tangential = radius <= rmw ? max * inner * Math.max(0.20D, radius / rmw) : max * Math.pow(rmw / radius, 0.48D);
        double rainBand = 0.84D + Math.sin(radius / 48.0D + event.spawnTick() * 0.013D) * 0.16D;
        // The cyclone's wind field is the hurricane's mirrored: same eye/eyewall/doldrum profile,
        // opposite tangential direction, so gameplay pushes entities the way the bands visibly turn.
        double spinSign = broadStormSpinSign(event);
        Vec3 radial = fromCenter.scale(1.0D / radius);
        Vec3 tangent = new Vec3(-radial.z * spinSign, 0.0D, radial.x * spinSign);
        Vec3 vector = tangent.scale(tangential * rainBand).add(radial.scale(-tangential * 0.08D));
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), radius, event.radius() * 2.0D, rmw);
    }

    private static TornadoWindField.WindSample sampleLineWind(AddonWeatherEvent event, Vec3 position) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return TornadoWindField.WindSample.CALM;
        }
        Vec3 line = end.subtract(start);
        Vec3 along = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(line.x, 0.0D, line.z).normalize();
        Vec3 forward = event.motion().horizontalDistanceSqr() > 1.0E-5D ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize() : new Vec3(-along.z, 0.0D, along.x);
        double t = lineSampleT(position, start, end);
        Vec3 closest = start.lerp(end, t).add(forward.scale(squallLineBowOffset(t, Math.sqrt(line.horizontalDistanceSqr()))));
        Vec3 offset = new Vec3(position.x - closest.x, 0.0D, position.z - closest.z);
        double signedFrontDistance = offset.dot(forward);
        double sideDistance = Math.abs(offset.dot(new Vec3(-forward.z, 0.0D, forward.x)));
        double leadingDepth = Math.max(48.0D, event.radius() * 0.62D);
        double rearDepth = Math.max(140.0D, event.radius() * 2.05D);
        double sideWidth = Math.max(80.0D, event.radius() * 0.72D);
        if (signedFrontDistance > leadingDepth || signedFrontDistance < -rearDepth || sideDistance > sideWidth) {
            return TornadoWindField.WindSample.CALM;
        }
        double frontFalloff = signedFrontDistance >= 0.0D
                ? Mth.clamp(1.0D - signedFrontDistance / leadingDepth, 0.0D, 1.0D)
                : Mth.clamp(1.0D + signedFrontDistance / rearDepth, 0.0D, 1.0D);
        double sideFalloff = Mth.clamp(1.0D - sideDistance / sideWidth, 0.0D, 1.0D);
        double gustCore = frontFalloff * sideFalloff;
        double rearRainPush = signedFrontDistance < 0.0D ? 0.42D + 0.58D * frontFalloff : 1.0D;
        double alongDistance = position.subtract(start).dot(along);
        double wave = Math.sin(alongDistance * 0.035D + event.spawnTick() * 0.019D) * 0.075D;
        double speed = event.maxWindMph() * (0.35D + 0.65D * gustCore) * sideFalloff * rearRainPush;
        Vec3 vector = forward.scale(speed)
                .add(along.scale(speed * wave))
                .add(0.0D, event.maxWindMph() * 0.035D * gustCore, 0.0D);
        double distance = Math.abs(signedFrontDistance);
        return new TornadoWindField.WindSample(vector, vector.length(), vector.length(), distance, event.radius(), leadingDepth);
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
        if (event.type() == StormSubtype.SNOW_DEVIL) {
            return level.isRaining() || level.isThundering() || !snowDevilSurface(level, BlockPos.containing(event.position()));
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            return DynamicStormController.isOverWaterOrOcean(level, event.position().x, event.position().z);
        }
        if (event.type() == StormSubtype.WILDFIRE || event.type() == StormSubtype.FIRE_WHIRL) {
            return level.isRainingAt(BlockPos.containing(event.position()));
        }
        if (isBroadRotatingStorm(event.type())) {
            return !DynamicStormController.isOverWaterOrOcean(level, event.position().x, event.position().z);
        }
        return false;
    }

    private static void trySurfaceTransition(ServerLevel level, AddonWeatherEvent event, List<AddonWeatherEvent> pending, long gameTime) {
        if (event.type() == StormSubtype.WATERSPOUT && Config.LANDSPOUTS_ENABLED.get() && !DynamicStormController.isOverWaterOrOcean(level, event.position().x, event.position().z)) {
            float wind = (float) Mth.clamp(event.maxWindMph() * 0.72F, 35.0F, 70.0F);
            float radius = Math.max(2.0F, event.radius() * 0.70F);
            pending.add(new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.LANDSPOUT, surfacePos(level, event.position()), event.motion().scale(0.75D), radius, wind, gameTime, gameTime + 100L + level.random.nextInt(180)));
        } else if (event.type() == StormSubtype.LANDSPOUT && Config.WATERSPOUTS_ENABLED.get() && DynamicStormController.isOverWaterOrOcean(level, event.position().x, event.position().z)) {
            float wind = (float) Mth.clamp(event.maxWindMph() * 0.84F, 35.0F, 78.0F);
            float radius = Math.max(2.0F, event.radius() * 0.82F);
            pending.add(new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.WATERSPOUT, surfacePos(level, event.position()), event.motion().scale(0.70D), radius, wind, gameTime, gameTime + waterspoutLifetimeTicks(level)));
        }
    }

    private static void applyNativeWaterspoutTag(ServerLevel level, WeatherManagerServer manager, StormObject storm) {
        if (!Config.WATERSPOUTS_ENABLED.get() || storm == null || storm.pos == null) {
            return;
        }
        boolean overWater = DynamicStormController.isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
        boolean fairWeatherWaterCell = overWater
                && storm.stormType == StormObject.TYPE_WATER
                && isFairWeatherWaterspoutCandidate(storm);
        boolean shouldBeWaterspout = storm.attrib_waterSpout && fairWeatherWaterCell;
        if (storm.attrib_waterSpout != shouldBeWaterspout) {
            storm.attrib_waterSpout = shouldBeWaterspout;
            manager.syncStormUpdate(storm);
        }
    }

    private static void applyNativeTropicalCycloneTag(WeatherManagerServer manager, StormObject storm, double targetWindMph) {
        if (manager == null || storm == null || manager.getWorld() == null) {
            return;
        }
        if (manager.getWorld() instanceof ServerLevel level) {
            DynamicStormController.promoteNativeCyclone(level, storm, true, targetWindMph);
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
                && !isHurricaneHelperStorm(storm)
                && !DynamicStormController.isNativeCyclone(storm)
                && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                && storm.levelCurIntensityStage <= StormObject.STATE_HAIL;
    }

    private static boolean isFairWeatherWaterspoutCandidate(StormObject storm) {
        if (!Config.FAIR_WEATHER_WATERSPOUTS_ENABLED.get() || !Config.WATERSPOUTS_ENABLED.get() || storm == null || storm.pos == null) {
            return false;
        }
        if (storm.isTornadoFormingOrGreater() || isHurricaneHelperStorm(storm) || DynamicStormController.isNativeCyclone(storm) || storm.levelCurIntensityStage > StormObject.STATE_THUNDER) {
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
                && !isHurricaneHelperStorm(storm)
                && !DynamicStormController.isNativeCyclone(storm);
    }

    private static boolean isGustnadoCandidate(StormObject storm) {
        return storm != null
                && storm.pos != null
                && !storm.isDead
                && storm.stormType == StormObject.TYPE_LAND
                && !storm.isTornadoFormingOrGreater()
                && !isHurricaneHelperStorm(storm)
                && !DynamicStormController.isNativeCyclone(storm)
                && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER
                && (storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND || storm.levelCurIntensityStage >= StormObject.STATE_HAIL || storm.isPrecipitating() || storm.isGrowing);
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

    private static int countEvents(List<AddonWeatherEvent> events, StormSubtype type) {
        int count = 0;
        for (AddonWeatherEvent event : events) {
            if (event.type() == type) {
                count++;
            }
        }
        return count;
    }

    private static void addEventAndEntity(ServerLevel level, List<AddonWeatherEvent> events, AddonWeatherEvent event) {
        if (level == null || events == null || event == null) {
            return;
        }
        events.add(event);
        int life = (int) Math.max(20L, event.expireTick() - event.spawnTick());
        DustDevilEntity entity = null;
        if (event.type() == StormSubtype.DUST_DEVIL) {
            entity = new DustDevilEntity(level, event.position(), life, event.radius(), event.maxWindMph());
        } else if (event.type() == StormSubtype.LANDSPOUT) {
            entity = new LandspoutEntity(level, event.position(), life, event.radius(), Math.min(LandspoutEntity.MAX_EF2_WIND_MPH, event.maxWindMph()));
        } else if (event.type() == StormSubtype.WATERSPOUT) {
            entity = new WaterspoutEntity(level, event.position(), life, event.radius(), event.maxWindMph());
        } else if (event.type() == StormSubtype.GUSTNADO) {
            entity = new GustnadoEntity(level, event.position(), life, event.radius(), event.maxWindMph());
        }
        if (entity != null) {
            Vec3 motion = event.motion() == null ? Vec3.ZERO : event.motion();
            entity.setDeltaMovement(new Vec3(motion.x, 0.0D, motion.z));
            level.addFreshEntity(entity);
        }
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

    private static StormObject strongestStorm(List<StormObject> cells) {
        StormObject strongest = cells.get(0);
        for (StormObject cell : cells) {
            if (cell.levelCurIntensityStage > strongest.levelCurIntensityStage) {
                strongest = cell;
            }
        }
        return strongest;
    }

    private static Vec3 stormForward(ServerLevel level, StormObject storm) {
        if (storm.motion != null && storm.motion.horizontalDistanceSqr() > 1.0E-5D) {
            return new Vec3(storm.motion.x, 0.0D, storm.motion.z).normalize();
        }
        Vec3 steering = WindSteeringManager.globalWindVector(storm);
        if (steering.horizontalDistanceSqr() > 1.0E-5D) {
            return new Vec3(steering.x, 0.0D, steering.z).normalize();
        }
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
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
        if (event.isLine() || event.type() == StormSubtype.FLOATER_TORNADO || event.type() == StormSubtype.FLAMMAGENITUS) {
            return;
        }
        event.setPosition(surfacePos(level, event.position()));
    }

    private static boolean hotDrySurface(ServerLevel level, BlockPos pos) {
        float temp = level.getBiome(pos).value().getBaseTemperature();
        BlockState ground = level.getBlockState(pos.below());
        return temp >= 0.70F && (ground.is(Blocks.SAND) || ground.is(Blocks.RED_SAND) || ground.is(Blocks.TERRACOTTA) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT));
    }

    private static boolean snowDevilSurface(ServerLevel level, BlockPos pos) {
        float temp = level.getBiome(pos).value().getBaseTemperature();
        BlockState ground = level.getBlockState(pos.below());
        BlockState surface = level.getBlockState(pos);
        return temp <= 0.28F && (surface.is(Blocks.SNOW) || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.POWDER_SNOW) || ground.is(Blocks.ICE) || ground.is(Blocks.PACKED_ICE) || ground.is(Blocks.BLUE_ICE));
    }

    private static boolean fireWeatherSurface(ServerLevel level, BlockPos pos) {
        if (level.isRainingAt(pos) || !level.canSeeSky(pos.above())) {
            return false;
        }
        float temp = level.getBiome(pos).value().getBaseTemperature();
        BlockState ground = level.getBlockState(pos.below());
        return (temp >= 0.55F || hotDrySurface(level, pos)) && (isFireFuel(ground) || hotDrySurface(level, pos));
    }

    private static boolean isFireFuel(BlockState state) {
        return state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || idPathContains(state, "grass")
                || idPathContains(state, "hay")
                || idPathContains(state, "wool")
                || idPathContains(state, "wood")
                || idPathContains(state, "bush");
    }

    private static boolean tryIgniteSurface(ServerLevel level, BlockPos column) {
        if (!level.isLoaded(column)) {
            return false;
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (surface == null || !level.isLoaded(surface) || level.isRainingAt(surface)) {
            return false;
        }
        BlockPos firePos = level.getBlockState(surface).isAir() ? surface : surface.above();
        BlockState fuel = level.getBlockState(firePos.below());
        if (!level.getBlockState(firePos).isAir() || !isFireFuel(fuel)) {
            return false;
        }
        BlockGrabSafety.safeSetBlock(level, firePos, Blocks.FIRE.defaultBlockState(), 3);
        return true;
    }

    private static SpcRisk fireWeatherRiskAt(ServerLevel level, BlockPos pos) {
        if (!Config.FIRE_WEATHER_RISK_ENABLED.get() || level.isRaining() || level.isThundering() || !level.isLoaded(pos)) {
            return SpcRisk.NONE;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, pos);
        float temp = level.getBiome(pos).value().getBaseTemperature();
        double score = Mth.clamp((temp - 0.45D) * 2.4D, 0.0D, 1.35D);
        if (fireWeatherSurface(level, pos)) {
            score += 0.90D;
        }
        if (hotDrySurface(level, pos)) {
            score += 0.45D;
        }
        if (isDryAfternoon(level)) {
            score += 0.25D;
        }
        score += outlook.windProbability() * 0.70D + outlook.dustDevilSpawnMultiplier() * 0.35D;
        score += Math.max(0, outlook.risk().severity() - SpcRisk.TSTM.severity()) * 0.16D;
        if (score >= 3.25D) {
            return SpcRisk.HIGH;
        }
        if (score >= 2.65D) {
            return SpcRisk.MODERATE;
        }
        if (score >= 2.10D) {
            return SpcRisk.ENHANCED;
        }
        if (score >= 1.45D) {
            return SpcRisk.SLIGHT;
        }
        if (score >= 0.90D) {
            return SpcRisk.MARGINAL;
        }
        return SpcRisk.NONE;
    }

    private static double enabledChance(double configuredChance, double floor) {
        return configuredChance <= 0.0D ? 0.0D : Math.max(configuredChance, floor);
    }

    private static Vec3 surfacePos(ServerLevel level, Vec3 pos) {
        BlockPos sample = BlockPos.containing(pos);
        if (!level.isLoaded(sample)) {
            return pos;
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level, sample, Heightmap.Types.WORLD_SURFACE);
        return surface == null ? pos : Vec3.atBottomCenterOf(surface);
    }

    private static Vec3 commandSurfacePos(ServerLevel level, Vec3 position, StormSubtype type) {
        Vec3 surface = surfacePos(level, position);
        if (type == StormSubtype.SQUALL_LINE) {
            return squallLineAloftPos(level, surface);
        }
        if (type == StormSubtype.FLOATER_TORNADO) {
            return floaterAloftPos(level, surface);
        }
        if (type == StormSubtype.FLAMMAGENITUS) {
            return flammagenitusAloftPos(level, surface);
        }
        if (type == StormSubtype.WATERSPOUT || isBroadRotatingStorm(type)) {
            if (DynamicStormController.isOverWaterOrOcean(level, surface.x, surface.z)) {
                return surface;
            }
            Vec3 water = findCommandSurface(level, surface, true, type == StormSubtype.WATERSPOUT ? 128 : 256);
            return water == null ? surface : water;
        }
        if (type == StormSubtype.DUST_DEVIL || type == StormSubtype.SNOW_DEVIL || type == StormSubtype.GUSTNADO || type == StormSubtype.FIRE_WHIRL || type == StormSubtype.WILDFIRE || type == StormSubtype.LANDSPOUT) {
            if (!DynamicStormController.isOverWaterOrOcean(level, surface.x, surface.z)) {
                return surface;
            }
            Vec3 land = findCommandSurface(level, surface, false, 128);
            return land == null ? surface : land;
        }
        return surface;
    }

    private static Vec3 squallLineAloftPos(ServerLevel level, Vec3 pos) {
        Vec3 surface = surfacePos(level, pos);
        double cloudBase = Math.max(level.getSeaLevel() + 34.0D, ConfigMisc.Cloud_Layer0_Height - 30.0D);
        double y = Mth.clamp(Math.max(surface.y + 42.0D, cloudBase), level.getMinBuildHeight() + 24.0D, level.getMaxBuildHeight() - 20.0D);
        return new Vec3(pos.x, y, pos.z);
    }

    private static Vec3 floaterAloftPos(ServerLevel level, Vec3 pos) {
        Vec3 surface = surfacePos(level, pos);
        double cloudBase = Math.max(level.getSeaLevel() + 32.0D, ConfigMisc.Cloud_Layer0_Height - 42.0D);
        double y = Mth.clamp(Math.max(surface.y + 18.0D, cloudBase), level.getMinBuildHeight() + 16.0D, level.getMaxBuildHeight() - 28.0D);
        return new Vec3(pos.x, y, pos.z);
    }

    private static Vec3 flammagenitusAloftPos(ServerLevel level, Vec3 pos) {
        Vec3 surface = surfacePos(level, pos);
        double y = Mth.clamp(Math.max(surface.y + 70.0D, ConfigMisc.Cloud_Layer0_Height - 8.0D), level.getMinBuildHeight() + 48.0D, level.getMaxBuildHeight() - 18.0D);
        return new Vec3(pos.x, y, pos.z);
    }

    private static AABB impactBox(ServerLevel level, AddonWeatherEvent event, double range) {
        if (event.isLine() && event.lineStart() != null && event.lineEnd() != null) {
            double minX = Math.min(event.lineStart().x, event.lineEnd().x) - range;
            double maxX = Math.max(event.lineStart().x, event.lineEnd().x) + range;
            double minZ = Math.min(event.lineStart().z, event.lineEnd().z) - range;
            double maxZ = Math.max(event.lineStart().z, event.lineEnd().z) + range;
            return new AABB(minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(), maxZ);
        }
        return new AABB(event.position().x - range, event.position().y - 12.0D, event.position().z - range, event.position().x + range, event.position().y + 56.0D, event.position().z + range);
    }

    private static Vec3 findCommandSurface(ServerLevel level, Vec3 center, boolean water, int range) {
        BlockPos origin = BlockPos.containing(center);
        for (int radius = 16; radius <= range; radius += 16) {
            for (int dx = -radius; dx <= radius; dx += 16) {
                for (int dz = -radius; dz <= radius; dz += 16) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    BlockPos sample = origin.offset(dx, 0, dz);
                    if (!level.isLoaded(sample)) {
                        continue;
                    }
                    Vec3 surface = surfacePos(level, Vec3.atCenterOf(sample));
                    if (DynamicStormController.isOverWaterOrOcean(level, surface.x, surface.z) == water) {
                        return surface;
                    }
                }
            }
        }
        return null;
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

    private static double lineSampleT(Vec3 point, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start);
        double lenSqr = segment.x * segment.x + segment.z * segment.z;
        if (lenSqr < 1.0E-5D) {
            return 0.5D;
        }
        return Mth.clamp(((point.x - start.x) * segment.x + (point.z - start.z) * segment.z) / lenSqr, 0.0D, 1.0D);
    }

    private static double squallLineBowOffset(double t, double lineLength) {
        double clamped = Mth.clamp(t, 0.0D, 1.0D);
        double centerBow = Math.sin(Math.PI * clamped);
        return Math.min(130.0D, Math.max(20.0D, lineLength * 0.085D)) * centerBow;
    }

    private static double reflectivity(AddonWeatherEvent event) {
        if (event.type() == StormSubtype.DUST_DEVIL) {
            return 8.0D + event.maxWindMph() * 0.12D;
        }
        if (event.type() == StormSubtype.SNOW_DEVIL) {
            return 10.0D + event.maxWindMph() * 0.08D;
        }
        if (event.type() == StormSubtype.GUSTNADO) {
            return 14.0D + event.maxWindMph() * 0.13D;
        }
        if (event.type() == StormSubtype.FIRE_WHIRL) {
            return 22.0D + event.maxWindMph() * 0.18D;
        }
        if (event.type() == StormSubtype.WILDFIRE) {
            return 18.0D + event.radius() * 0.10D;
        }
        if (event.type() == StormSubtype.FLAMMAGENITUS) {
            return 34.0D + event.radius() * 0.05D;
        }
        if (event.type() == StormSubtype.FLOATER_TORNADO) {
            return 24.0D + event.maxWindMph() * 0.15D;
        }
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return 48.0D + event.maxWindMph() * 0.10D;
        }
        if (isBroadRotatingStorm(event.type())) {
            return 40.0D + event.maxWindMph() * 0.09D;
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
        if (isBroadRotatingStorm(event.type())) {
            return event.maxWindMph() >= 130.0F ? SpcRisk.HIGH : event.maxWindMph() >= 96.0F ? SpcRisk.MODERATE : SpcRisk.ENHANCED;
        }
        if (event.type() == StormSubtype.WILDFIRE) {
            return event.radius() >= 90.0F ? SpcRisk.ENHANCED : event.radius() >= 52.0F ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
        }
        if (event.type() == StormSubtype.FIRE_WHIRL) {
            return event.maxWindMph() >= 92.0F ? SpcRisk.ENHANCED : SpcRisk.SLIGHT;
        }
        if (event.type() == StormSubtype.FLAMMAGENITUS) {
            return SpcRisk.SLIGHT;
        }
        if (event.type() == StormSubtype.FLOATER_TORNADO) {
            return event.maxWindMph() >= 96.0F ? SpcRisk.SLIGHT : SpcRisk.MARGINAL;
        }
        if (event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL || event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT) {
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

    private static int inferredStage(float windMph) {
        if (windMph >= 200.0F) {
            return 9;
        }
        if (windMph >= 166.0F) {
            return 8;
        }
        if (windMph >= 136.0F) {
            return 7;
        }
        if (windMph >= 111.0F) {
            return 6;
        }
        if (windMph >= 86.0F) {
            return 5;
        }
        if (windMph >= 65.0F) {
            return 4;
        }
        return 0;
    }

    private static String inferredStageLabel(float windMph) {
        int stage = inferredStage(windMph);
        return stage <= 0 ? "" : "S" + stage;
    }

    private static long nextId(long gameTime, ServerLevel level) {
        return (gameTime << 20) ^ level.random.nextInt(1_000_000);
    }

    private static void sync(ServerLevel level, List<AddonWeatherEvent> events) {
        for (ServerPlayer player : level.players()) {
            List<AddonWeatherEvent> nearby = events.stream()
                    .filter(event -> event.position().distanceTo(player.position()) <= Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get())
                    .toList();
            NetworkSafety.sendToPlayer(player, new AddonWeatherEventSyncPayload(nearby));
        }
    }
}
