package com.imbet.weather;

import com.imbet.Config;
import com.imbet.network.AddonWeatherEventSyncPayload;
import com.imbet.network.NetworkSafety;
import com.imbet.radar.RadarStormSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
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
    /**
     * The old addon hurricane's rain core. Nothing stamps this UUID any more - a hurricane is a real
     * Weather2 water storm now, not a hijacked land cell - but saves from before that change can still
     * carry one, so the guard stays and every consumer keeps excluding it.
     */
    private static final String HURRICANE_HELPER_UUID = "00000000-0000-0000-0000-000000000002";
    /**
     * The stamp on every cell a synoptic low seeded on its own fronts, and on an outbreak's cells. It is
     * what lets a low count its own convection, and what tells a parent system apart from a squall line
     * the addon organised by itself.
     */
    private static final String FRONTAL_CONVECTION_HELPER_UUID = "00000000-0000-0000-0000-000000000003";
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
            }
            if (event.type() == StormSubtype.WILDFIRE && gameTime % 20L == 0L) {
                maintainWildfire(level, event);
                // The ground the fire has already been over. Its own pass is on the same one-second cadence as
                // the fire's upkeep, so the scar grows with the burn rather than in bursts.
                WildfireScar.tick(level, event);
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
            if (event.isLine()) {
                // Whether this line has become a derecho, on the same one-second cadence as the rest of the
                // upkeep, and then whatever the rung it reached does to the ground it is crossing. The pass
                // reads its own cadence from the config, and stands down entirely while the feature is off.
                if (gameTime % 20L == 0L) {
                    DerechoService.updateClassification(level, event);
                }
                DerechoService.tick(level, event);
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

    /**
     * Puts an event into the world with whatever body it is supposed to have, for systems outside this class
     * that make their own weather: the volcanic whirls come off a volcano rather than out of the storm ladder,
     * and this is their way in. It is the same path a spawned or commanded event takes, so a whirl made here is
     * built, tracked, synced, sampled by the wind field and cleaned up like every other one.
     */
    public static AddonWeatherEvent spawnAddonEvent(ServerLevel level, AddonWeatherEvent event) {
        if (level == null || event == null) {
            return null;
        }
        List<AddonWeatherEvent> events = eventsFor(level);
        if (events.contains(event)) {
            return event.snapshot();
        }
        addEventAndEntity(level, events, event);
        sync(level, events);
        return event.snapshot();
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
            case FUNNEL_CLOUD -> {
                float wind = commandFloat(requestedWindMph, 46.0D, 12.0D, FunnelCloudEntity.MAX_FUNNEL_CLOUD_WIND_MPH);
                float radius = commandFloat(requestedSizeBlocks, 4.0D, 1.0D, 16.0D);
                event = new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.FUNNEL_CLOUD, center, direction.scale(0.012D), radius, wind, gameTime, gameTime + funnelCloudLifetimeTicks(level));
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
            // HURRICANE and CYCLONE are not addon vortices any more: they are real Weather2 water
            // storms on the 1.12.2 tropical ladder, spawned through spawnCommandTropicalStorm. This
            // method returns null for them so the command takes that path instead.
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
        if (event.type() == StormSubtype.SQUALL_LINE) {
            events.add(event);
            seedWeather2SquallLineStorms(level, event.lineStart(), event.lineEnd(), event.motion(), event.maxWindMph(), gameTime);
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
            // A flammagenitus is a cloud: nothing falls out of it, so a radar has nothing to see.
            if (event.type() == StormSubtype.FLAMMAGENITUS) {
                continue;
            }
            SpcRisk risk = riskForEvent(event);
            boolean vortex = event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL || event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.FIRE_WHIRL || event.type() == StormSubtype.FLOATER_TORNADO || event.type() == StormSubtype.FUNNEL_CLOUD;
            float width = event.isLine() && event.lineStart() != null && event.lineEnd() != null
                    ? (float) event.lineStart().distanceTo(event.lineEnd())
                    : event.radius() * 2.0F;
            boolean dopplerSignature = event.type() != StormSubtype.LANDSPOUT && event.type() != StormSubtype.DUST_DEVIL && event.type() != StormSubtype.SNOW_DEVIL && event.type() != StormSubtype.FUNNEL_CLOUD;
            float eventWind = event.type() == StormSubtype.LANDSPOUT ? Math.min(LandspoutEntity.MAX_EF2_WIND_MPH, event.maxWindMph()) : event.maxWindMph();
            float[] lineField = squallLineField(event);
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
                    vortex ? inferredStageLabel(eventWind) : "",
                    // An addon event is its own storm as far as the radar is concerned: it has an extent but no
                    // resolved supercell structure of its own.
                    Math.max(24.0F, event.radius()),
                    0.0F,
                    0.0F,
                    0.0F,
                    0.0F,
                    0,
                    event.position().x,
                    event.position().z,
                    lineField
            ));
        }
        return snapshots;
    }

    /**
     * A squall line as the radar draws it: its two ends, its thickness, how far its middle bows forward, and which way it
     * is going. Without it a line was drawn as a round echo at its middle, where a real radar shows a long thin band.
     */
    /** The line a squall-line event's radar echo is drawn along, looked up by the event's id. */
    public static float[] radarLineField(ServerLevel level, long eventId) {
        for (AddonWeatherEvent event : eventsFor(level)) {
            if (event.id() == eventId) {
                return squallLineField(event);
            }
        }
        return new float[0];
    }

    private static float[] squallLineField(AddonWeatherEvent event) {
        if (event.type() != StormSubtype.SQUALL_LINE || event.lineStart() == null || event.lineEnd() == null) {
            return new float[0];
        }
        Vec3 line = event.lineEnd().subtract(event.lineStart());
        double length = Math.sqrt(line.horizontalDistanceSqr());
        if (length < 1.0D) {
            return new float[0];
        }
        Vec3 forward = event.motion() != null && event.motion().horizontalDistanceSqr() > 1.0E-5D
                ? new Vec3(event.motion().x, 0.0D, event.motion().z).normalize()
                : new Vec3(-line.z / length, 0.0D, line.x / length);
        double bow = Math.min(130.0D, Math.max(20.0D, length * 0.085D));
        return com.imbet.radar.RadarPrecipitation.packLine(event.lineStart(), event.lineEnd(), Math.max(24.0F, event.radius()), bow, forward);
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
                maybeSpawnGustnado(level, events, storm, gameTime);
                maybeSpawnFunnelCloud(level, events, storm, gameTime);
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
        // A landspout is a tornado - a high-based one, but one that puts a circulation on the ground and
        // gets an EF rating - so it goes through the same ground gate the storm promotion does.
        if (!TornadoGenesisGate.allows(level, storm.pos)) {
            return;
        }
        // The country's own odds, on top of the air: a landspout is high-based convection, so the dry
        // plains and the badlands make them and a rainforest does not.
        double chance = Config.naturalSpawnChance(
                Config.LANDSPOUT_SPAWN_CHANCE.get() * Math.max(0.35D, outlook.supercellSpawnMultiplier())
                        * BiomeClimateService.odds(level, storm.pos).landspout());
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
        double chance = Config.naturalSpawnChance(
                Config.WATERSPOUT_SPAWN_CHANCE.get() * Math.max(0.60D, outlook.windProbability() + 0.75D)
                        * BiomeClimateService.odds(level, storm.pos).waterspout());
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

    /**
     * Cold-air funnel clouds: a rotating condensation funnel hanging under a developing shower,
     * with nothing happening on the ground at all.
     *
     * <p>The candidate is a precipitating storm that has not reached the tornado-forming rung - the
     * shower or weak-thunder base a funnel cloud belongs under. The funnel is placed on the ground
     * only far enough to find the terrain height and is then lifted to the configured tip altitude,
     * which is the whole difference between this and a tornado: the circulation never comes down.
     */
    private static void maybeSpawnFunnelCloud(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!Config.FUNNEL_CLOUDS_ENABLED.get()
                || events.size() >= Config.MAX_ADDON_VORTICES.get()
                || !isFunnelCloudCandidate(storm)
                || hasNearbyAddonEvent(events, StormSubtype.FUNNEL_CLOUD, storm.pos, 220.0D)) {
            return;
        }
        // Cold-air funnels are a cool-season animal: a temperate or cool shower base favors them,
        // a hot tropical column does not.
        float baseTemp = level.getBiome(BlockPos.containing(storm.pos)).value().getBaseTemperature();
        double cool = baseTemp <= 0.35F ? 1.35D : baseTemp <= 0.70F ? 1.0D : 0.60D;
        // Deliberately the plain setting rather than enabledChance: this one is rolled on every scan
        // for as long as a shower lasts, so a floor here would make a funnel cloud permanent company.
        double chance = Config.naturalSpawnChance(Config.FUNNEL_CLOUD_SPAWN_CHANCE.get() * cool
                * BiomeClimateService.odds(level, storm.pos).funnelCloud());
        if (level.random.nextDouble() > chance) {
            return;
        }
        Vec3 ground = surfacePos(level, storm.pos.add(randomOffset(level, 16.0D, 74.0D)));
        if (DynamicStormController.isOverWaterOrOcean(level, ground.x, ground.z)) {
            return;
        }
        float wind = 28.0F + level.random.nextFloat() * (FunnelCloudEntity.MAX_FUNNEL_CLOUD_WIND_MPH - 28.0F);
        float radius = 2.0F + level.random.nextFloat() * 5.0F;
        Vec3 motion = storm.motion == null
                ? randomMotion(level, 0.004D, 0.020D)
                : storm.motion.scale(0.22D).add(randomMotion(level, 0.002D, 0.014D));
        addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.FUNNEL_CLOUD,
                funnelCloudAloftPos(level, ground), motion, radius, wind, gameTime, gameTime + funnelCloudLifetimeTicks(level)));
    }

    /**
     * A funnel cloud needs a shower base and nothing more: precipitating, and short of the rung
     * where Weather2 starts a real tornado of its own.
     */
    private static boolean isFunnelCloudCandidate(StormObject storm) {
        return storm != null
                && storm.pos != null
                && !storm.isDead
                && storm.isPrecipitating()
                && storm.levelCurIntensityStage < StormObject.STATE_FORMING
                && !storm.isTornadoFormingOrGreater()
                && !DynamicStormController.isAbsorbedOrDecaying(storm);
    }

    /** Lifts a ground position to the altitude a funnel cloud's ragged tip hangs at. */
    private static Vec3 funnelCloudAloftPos(ServerLevel level, Vec3 ground) {
        double tip = Math.max(16.0D, Config.FUNNEL_CLOUD_TIP_ALTITUDE_BLOCKS.get());
        return new Vec3(ground.x, ground.y + tip, ground.z);
    }

    private static long funnelCloudLifetimeTicks(ServerLevel level) {
        // Real cold-air funnels live anywhere from a minute to about ten.
        return 20L * 60L + level.random.nextInt(20 * 60 * 9 + 1);
    }

    private static void maybeSpawnGustnado(ServerLevel level, List<AddonWeatherEvent> events, StormObject storm, long gameTime) {
        if (!Config.GUSTNADOES_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || !isGustnadoCandidate(storm) || hasNearbyAddonEvent(events, StormSubtype.GUSTNADO, storm.pos, 140.0D)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(storm.pos));
        double severeOutflow = Math.max(0.55D, outlook.windProbability() + (storm.levelCurIntensityStage >= StormObject.STATE_HIGHWIND ? 0.35D : 0.0D) + (storm.isPrecipitating() ? 0.15D : 0.0D));
        double chance = Config.naturalSpawnChance(
                enabledChance(Config.GUSTNADO_SPAWN_CHANCE.get(), 0.045D) * severeOutflow
                        * BiomeClimateService.odds(level, storm.pos).gustnado());
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

    private static void spawnDustDevils(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        if (!Config.DUST_DEVILS_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || level.isRaining() || level.isThundering() || !isDryAfternoon(level)) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, level.getSharedSpawnPos());
        if (!outlook.favorsDustDevils()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            // Per player, because the dust devil is about the ground under the player: a desert afternoon
            // and a swamp afternoon are not the same afternoon even under one sky.
            double chance = Config.naturalSpawnChance(
                    enabledChance(Config.DUST_DEVIL_SPAWN_CHANCE.get(), 0.018D)
                            * Math.max(0.35D, outlook.dustDevilSpawnMultiplier())
                            * BiomeClimateService.odds(level, player.blockPosition()).dustDevil());
            if (events.size() >= Config.MAX_ADDON_VORTICES.get() || level.random.nextDouble() > chance) {
                continue;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 48.0D, 180.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            if (!lodSeedingAllowed(level, pos) || rainingHere(level, pos) || !openSkyHere(level, pos) || !hotDrySurface(level, pos) || hasNearbyWeather2Storm(level, candidate, 260.0D) || hasNearbyAddonEvent(events, StormSubtype.DUST_DEVIL, candidate, 96.0D)) {
                continue;
            }
            float wind = 24.0F + level.random.nextFloat() * 34.0F;
            float radius = 1.5F + level.random.nextFloat() * 5.0F;
            int life = 160 + level.random.nextInt(520);
            addEventAndEntity(level, events, new AddonWeatherEvent(nextId(gameTime, level), StormSubtype.DUST_DEVIL, candidate, randomMotion(level, 0.018D, 0.070D), radius, wind, gameTime, gameTime + life));
        }
    }

    private static void spawnSnowDevils(ServerLevel level, List<AddonWeatherEvent> events, long gameTime) {
        // The rain flag here used to be the level's own, and that is the bug this fixes: vanilla's snow is
        // rain as far as that flag is concerned, so while it snowed anywhere a snow devil could neither
        // form nor survive - in the one biome it belongs to. Thunder is still the whole level's, because a
        // thunderstorm anywhere is not fair weather, and everything else is read where the whirl is.
        if (!Config.SNOW_DEVILS_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || level.isThundering()) {
            return;
        }
        boolean fairWeatherOnly = Config.SNOW_DEVIL_FAIR_WEATHER_ONLY.get();
        for (ServerPlayer player : level.players()) {
            double chance = Config.naturalSpawnChance(Config.SNOW_DEVIL_SPAWN_CHANCE.get()
                    * BiomeClimateService.odds(level, player.blockPosition()).snowDevil());
            if (events.size() >= Config.MAX_ADDON_VORTICES.get() || level.random.nextDouble() > chance) {
                continue;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 42.0D, 170.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            if (!lodSeedingAllowed(level, pos) || !openSkyHere(level, pos) || !snowDevilGroundAllows(level, pos, fairWeatherOnly) || hasNearbyWeather2Storm(level, candidate, 220.0D) || hasNearbyAddonEvent(events, StormSubtype.SNOW_DEVIL, candidate, 128.0D)) {
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
            double chance = Config.naturalSpawnChance(configuredChance * multiplier);
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
        double chance = Config.naturalSpawnChance(
                Config.FIRE_WHIRL_SPAWN_CHANCE.get() * (0.70D + risk.severity() * 0.24D));
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

    /**
     * What a spout blows away: leaves, ground cover and what is growing on it, and nothing else.
     *
     * <p>Plants above the destructive tier are left to the ordinary vortex path below, which has been taking
     * crops and flowers since before this existed - so the two never both take the same block.
     */
    private static void blowLoose(ServerLevel level, AddonWeatherEvent event) {
        SpoutDamage.Tier tier = SpoutDamage.tierOf(event.maxWindMph());
        if (tier == SpoutDamage.Tier.CALM) {
            return;
        }
        boolean plantsBelowStrip = event.maxWindMph() < SpoutDamage.STRIP_AT_MPH;
        double chance = SpoutDamage.takeShare(event.maxWindMph());
        int attempts = Mth.clamp((int) Math.round(event.radius() * 1.2D), 3, 14);
        for (int i = 0; i < attempts; i++) {
            Vec3 target = event.isLine()
                    ? randomPointNearLine(level, event)
                    : event.position().add(randomOffset(level, 0.0D, Math.max(4.0D, event.radius() * 1.7D)));
            BlockPos column = BlockPos.containing(target);
            if (!level.isLoaded(column)) {
                continue;
            }
            BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
            if (surface == null) {
                continue;
            }
            for (int dy = 0; dy <= SPOUT_SCAN_HEIGHT; dy++) {
                BlockPos pos = surface.offset(0, dy, 0);
                if (!level.isLoaded(pos)) {
                    break;
                }
                BlockGrabSafety.BlockSample block = BlockGrabSafety.sample(level, pos, null);
                if (!BlockGrabSafety.canEdit(block)) {
                    continue;
                }
                SpoutDamage.BlockClass blockClass = spoutClassOf(block.state());
                if (blockClass == SpoutDamage.BlockClass.PLANT && !plantsBelowStrip) {
                    continue;
                }
                if (SpoutDamage.verdict(blockClass, event.maxWindMph()) != SpoutDamage.Kind.BLOW_AWAY) {
                    continue;
                }
                if (level.random.nextDouble() >= chance) {
                    continue;
                }
                BlockGrabSafety.safeSetBlock(level, pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** Which of the spout's three loose categories a block belongs to, if any. */
    private static SpoutDamage.BlockClass spoutClassOf(BlockState state) {
        if (state.is(BlockTags.LEAVES)) {
            return SpoutDamage.BlockClass.LEAVES;
        }
        if (state.is(BlockTags.CROPS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.DEAD_BUSH)) {
            return SpoutDamage.BlockClass.PLANT;
        }
        if (state.is(Blocks.SNOW)
                || idPathContains(state, "carpet")
                || idPathContains(state, "torch")) {
            return SpoutDamage.BlockClass.LOOSE;
        }
        return SpoutDamage.BlockClass.NONE;
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

    /** How far up a spout reads a column, so it strips a canopy rather than only what is at ground level. */
    private static final int SPOUT_SCAN_HEIGHT = 8;

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
        // A cold front is what squalls a line rather than a scatter of cells, so the front's own linear
        // weighting lowers the number of cells the line needs to organise - and a warm front, which is
        // elevated convection that never puts a downdraft on the ground, raises it.
        int minCells = Mth.clamp((int) Math.round(Config.SQUALL_LINE_MIN_CELLS.get()
                / Math.max(0.25D, outlook.frontalQlcsFactor())), 2, 16);
        if (count < minCells && !severeLinearSeed) {
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

    /**
     * Seeds one convective cell on a synoptic low's front, the way a front actually makes weather.
     *
     * <p>The cell arrives already going - a front's updraft is running before the cell is visible - so
     * it is placed at the front's own cloud base and given the rung its kind starts on. What it may grow
     * into is decided by {@link FrontalCellKind}: the warm sector's discrete supercells are the only ones
     * left their whole ladder, because they are the only ones an outbreak is made of, and the front's
     * forced and elevated cells are capped where the front is driving them.
     *
     * <p>The cell carries {@link #FRONTAL_CONVECTION_HELPER_UUID}, which is what lets its parent low
     * count its own convection instead of counting storms in the neighbourhood.
     *
     * @return the cell, or null when there is no manager or the sky already has a thunderstorm there
     */
    public static StormObject seedFrontalCell(ServerLevel level, Vec3 groundPosition, Vec3 motion, FrontalCellKind kind) {
        if (level == null || groundPosition == null || kind == null) {
            return null;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return null;
        }
        Vec3 aloft = squallLineAloftPos(level, groundPosition);
        if (hasNearbyWeather2Thunderstorm(manager, aloft, 150.0D)) {
            return null;
        }
        StormObject storm = new StormObject(manager);
        storm.initPositions(aloft);
        storm.initFirstTime();
        storm.naturallySpawned = true;
        storm.spawnerUUID = FRONTAL_CONVECTION_HELPER_UUID;
        configureFrontalCell(storm, motion, kind, deadlyAirShareAt(level, groundPosition));
        manager.addStormObject(storm);
        manager.syncStormNew(storm);
        manager.lastStormFormed = Math.max(manager.lastStormFormed, level.getGameTime());
        manager.isVanillaRainActiveOnServer = true;
        manager.isVanillaThunderActiveOnServer = true;
        manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 6);
        manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, 0.82F);
        return storm;
    }

    /**
     * The air's own reading where a storm is about to be seeded: whether this sky could carry a deadly storm.
     *
     * <p>The same method the forecast's percentage is printed from, deliberately, because a readout that
     * disagreed with the flag would be worse than no readout: a sky that reads 0.00% a day is a sky no cell of
     * this mod's is allowed to be promoted into a deadly storm out of. With the switch off the reading is full
     * air and nothing below the switch changes at all.
     *
     * <p>Every path this mod seeds a storm from reads it - the frontal cell, the tropical system and the
     * twin - so whether a cell may be promoted into a deadly one is the air's question wherever the addon
     * puts a storm, rather than a flag set on the frontal path alone.
     */
    public static double deadlyAirShareAt(ServerLevel level, Vec3 groundPosition) {
        if (!Config.deadlyStormFromAir()) {
            return DeadlyStormModel.FULL_AIR;
        }
        ThermodynamicProfile profile = ThermodynamicProfileService.compute(level, BlockPos.containing(groundPosition));
        return DeadlyStormModel.airShare(Config.deadlyStormSettings(), profile.capeJkg(), profile.deepLayerShearMps());
    }

    /**
     * Whether the sky at a place will carry a deadly storm, which is the flag every storm this mod seeds is
     * given: the reading above nothing at all, or a pass when the air is not being asked.
     */
    public static boolean deadlyAirSupports(ServerLevel level, Vec3 groundPosition) {
        return deadlyAirShareAt(level, groundPosition) > DeadlyStormModel.NO_AIR;
    }

    /**
     * How many cells a low has on the ground right now, counted by the stamp its own seeding left on
     * them. Counting storms in the neighbourhood instead would count the player's weather, the addon's
     * own squall lines and other lows' cells, and the cap would then be about the map rather than about
     * the system.
     */
    public static int frontalCellCount(ServerLevel level, SynopticLow low) {
        if (level == null || low == null) {
            return 0;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return 0;
        }
        double reach = low.radius() * 1.5D;
        double reachSqr = reach * reach;
        int count = 0;
        for (WeatherObject object : manager.getStormObjects()) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            if (!FRONTAL_CONVECTION_HELPER_UUID.equals(storm.spawnerUUID)) {
                continue;
            }
            double dx = storm.pos.x - low.x();
            double dz = storm.pos.z - low.z();
            if (dx * dx + dz * dz <= reachSqr) {
                count++;
            }
        }
        return count;
    }

    private static void configureFrontalCell(StormObject storm, Vec3 motion, FrontalCellKind kind, double airShare) {
        // A kind's own rung translated into the storm's ladder, in the one place that has to know both.
        int stage = kind.startRung() == FrontalCellKind.Rung.HAILSTORM
                ? StormObject.STATE_HAIL
                : StormObject.STATE_THUNDER;
        boolean airDecides = Config.deadlyStormFromAir();
        // A forced cell is the front's own updraft and dies with it, so it is not allowed to be deadly
        // in the discrete sense; a warm-sector supercell is exactly the cell that is - and while the air is
        // deciding, whether this sky could carry a deadly storm is the other half of that question. Weather2's
        // own deadly timer, in StormObject.tickProgression, promotes a fresh cell at STATE_NORMAL into a real,
        // deadly storm every three game days (ten, on its land-based timer) and asks this flag before it does.
        // Refusing it here does not make a lesser storm: the flag decides the promotion and nothing else.
        storm.canBeDeadly = !kind.forced() && (!airDecides || airShare > DeadlyStormModel.NO_AIR);
        // And how much room a cell is handed is the same reading, for the cells the air agreed to: a fully
        // loaded sky gives the warm sector's cell its whole ladder, a marginal one gives it a rung between its
        // own and the top, and a cell the air refused keeps the room its kind was built for. The intensity it
        // starts at is scaled the same way, so a deadly storm out of a marginal sky is a real storm that has
        // to grow into its rung rather than a violent one that arrived early.
        double room = storm.canBeDeadly && airDecides ? DeadlyStormModel.strengthScale(airShare) : 1.0D;
        storm.levelCurIntensityStage = Math.max(storm.levelCurIntensityStage, stage);
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity,
                (float) Math.min(1.0D, kind.startIntensity() * room));
        storm.stormType = StormObject.TYPE_LAND;
        storm.isGrowing = true;
        storm.hasStormPeaked = false;
        storm.alwaysProgresses = false;
        storm.spinSpeed = 0.0D;
        storm.tempAngleFormingTornado = 0.0F;
        storm.setCloudlessStorm(false);
        storm.setPrecipitating(true);
        if (kind.fullLadder()) {
            // The warm sector's discrete supercell, and only it: the whole ladder, so an extreme day's
            // cells are the ones that can actually reach the violent rungs - and while the air is deciding,
            // how much of that ladder the day has actually earned.
            int rung = airDecides && storm.canBeDeadly
                    ? DeadlyStormModel.ladderRung(stage, StormObject.STATE_STAGE5, airShare)
                    : (airDecides ? stage : StormObject.STATE_STAGE5);
            storm.maxIntensityStage = Math.max(storm.maxIntensityStage, rung);
            storm.levelStormIntensityMax = Math.max(storm.levelStormIntensityMax, storm.maxIntensityStage);
        } else {
            storm.maxIntensityStage = stage;
            storm.levelStormIntensityMax = stage;
        }
        Vec3 forward = motion != null && motion.horizontalDistanceSqr() > 1.0E-5D
                ? new Vec3(motion.x, 0.0D, motion.z).normalize()
                : new Vec3(0.923D, 0.0D, -0.382D);
        double speed = motion == null ? 0.03D : Mth.clamp(motion.horizontalDistance(), 0.012D, 0.12D);
        storm.motion = forward.scale(speed);
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

    /**
     * Spawns a real tropical storm for the command: a Weather2 water storm on the 1.12.2 ladder, which
     * is the same kind of storm the ocean can form on its own. The command is explicit player intent, so
     * it is not gated by the natural genesis knobs - the storm is placed, given the ladder rung that can
     * carry the wind it was asked for, and left to live.
     *
     * @param antiCyclonic the twin: same storm, opposite rotation, named a cyclone rather than a hurricane.
     * @return the storm, or null if the manager or the placement could not be had.
     */
    public static StormObject spawnCommandTropicalStorm(ServerLevel level, Vec3 center, Vec3 motion, double requestedWindMph, double requestedSizeBlocks, boolean antiCyclonic) {
        if (level == null || center == null) {
            return null;
        }
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return null;
        }
        float wind = (float) Mth.clamp(requestedWindMph, 35.0D, 200.0D);
        float radius = (float) Mth.clamp(requestedSizeBlocks, 160.0D, 1400.0D);
        StormObject storm = new StormObject(manager);
        storm.initPositions(cycloneAloftPos(level, center));
        storm.initFirstTime();
        storm.naturallySpawned = false;
        storm.alwaysProgresses = true;
        // The shield's radius IS the storm's size, so the requested size is applied to the storm's own
        // footprint directly - the deck, the eye and the wind field all read it back from there.
        storm.size = Math.max(storm.size, Math.round(radius));
        storm.maxSize = Math.max(storm.maxSize, Math.round(radius * 1.25F));
        manager.addStormObject(storm);
        if (!DynamicStormController.startTropicalStorm(level, storm, antiCyclonic)) {
            return null;
        }
        // The commanded strength: the storm climbs to the rung that can carry this wind, so the ladder
        // name it wears and the air it moves agree from the first tick.
        int stage = DynamicStormController.cycloneStageForWind(wind);
        storm.levelCurIntensityStage = Math.max(storm.levelCurIntensityStage, stage);
        storm.levelCurStagesIntensity = Math.max(storm.levelCurStagesIntensity, 0.74F);
        storm.levelStormIntensityMax = Math.max(Math.max(storm.levelStormIntensityMax, stage), storm.rollDiceOnMaxIntensity());
        storm.maxIntensityStage = Math.max(storm.maxIntensityStage, storm.levelStormIntensityMax);
        storm.levelWater = Math.max(storm.levelWater, Math.max(180, storm.levelWaterStartRaining * 5));
        storm.levelWindMomentum = Math.max(storm.levelWindMomentum, (float) Mth.clamp(wind / 85.0D, 0.95F, 2.45F));
        storm.spinSpeed = Math.abs(storm.spinSpeed) * (antiCyclonic ? -1.0D : 1.0D);
        if (motion != null && motion.horizontalDistanceSqr() > 1.0E-5D) {
            Vec3 forward = new Vec3(motion.x, 0.0D, motion.z).normalize();
            storm.motion = forward.scale(Mth.clamp(Math.sqrt(motion.horizontalDistanceSqr()), 0.012D, 0.065D));
        } else {
            storm.motion = randomMotion(level, 0.010D, 0.035D);
        }
        manager.syncStormNew(storm);
        manager.lastStormFormed = Math.max(manager.lastStormFormed, level.getGameTime());
        manager.isVanillaRainActiveOnServer = true;
        manager.isVanillaThunderActiveOnServer = true;
        manager.vanillaRainTimeOnServer = Math.max(manager.vanillaRainTimeOnServer, 20 * 60 * 10);
        manager.vanillaRainAmountOnServer = Math.max(manager.vanillaRainAmountOnServer, 0.95F);
        return storm;
    }

    private static Vec3 cycloneAloftPos(ServerLevel level, Vec3 center) {
        Vec3 surface = surfacePos(level, center);
        return new Vec3(surface.x, Math.max(surface.y + 96.0D, level.getSeaLevel() + 112.0D), surface.z);
    }


    private static void maybeSpawnGustnadoFromLine(ServerLevel level, List<AddonWeatherEvent> events, Vec3 start, Vec3 end, Vec3 motion, float lineWind, long gameTime) {
        if (!Config.GUSTNADOES_ENABLED.get() || events.size() >= Config.MAX_ADDON_VORTICES.get() || start == null || end == null || motion == null || motion.horizontalDistanceSqr() < 1.0E-5D) {
            return;
        }
        DailySpcOutlook outlook = DailySpcOutlookService.current(level, BlockPos.containing(start.add(end).scale(0.5D)));
        double chance = enabledChance(Config.GUSTNADO_SPAWN_CHANCE.get(), 0.045D) * 1.65D * Math.max(0.55D, outlook.windProbability())
                * BiomeClimateService.odds(level, start.add(end).scale(0.5D)).gustnado();
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
        // A funnel cloud is a picture, not a force: its circulation never comes down, so there is
        // no surface wind to push anything with.
        if (event.type() == StormSubtype.WILDFIRE || event.type() == StormSubtype.FLAMMAGENITUS || event.type() == StormSubtype.FUNNEL_CLOUD) {
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
        // A spout is the one vortex that does its damage below the destructive tier, and this is where it is
        // allowed to: what it takes is loose, and none of it is terrain. See SpoutDamage for the table.
        if (Config.SPOUT_DAMAGE_ENABLED.get()
                && (event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT)) {
            blowLoose(level, event);
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
        if (!Config.damageEnabled() || !CompatHooks.useVanillaBlockLifting() || event.maxWindMph() < 50.0F) {
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
        // A funnel cloud reads calm at every height: its circulation never reaches the surface, and
        // the mod's wind field has no vertical gating that could confine it to the air. Anything the
        // wind field can push is standing on the ground, and the ground is exactly what this vortex
        // does not touch.
        if (event.type() == StormSubtype.FUNNEL_CLOUD) {
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
            return snowDevilHasGone(level, event);
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
        } else if (event.type() == StormSubtype.FUNNEL_CLOUD) {
            entity = new FunnelCloudEntity(level, event.position(), life, event.radius(), event.maxWindMph());
        } else if (event.type() == StormSubtype.SNOW_DEVIL) {
            // Without this branch a snow devil had no entity at all, and so none of what an entity is for:
            // no volumetric column on the client, and no force on anything in the world.
            entity = new SnowDevilEntity(level, event.position(), life, event.radius(), event.maxWindMph());
        } else if (event.type() == StormSubtype.LAVA_WHIRL) {
            entity = new LavaWhirlEntity(level, event.position(), life, event.radius(), event.maxWindMph());
        } else if (event.type() == StormSubtype.STEAM_DEVIL) {
            entity = new SteamDevilEntity(level, event.position(), life, event.radius(), event.maxWindMph());
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
        // A funnel cloud is the one vortex whose whole identity is aloft: snapping it to the ground
        // every tick would put its circulation on the ground and make it a tornado.
        if (event.isLine() || event.type() == StormSubtype.FLOATER_TORNADO || event.type() == StormSubtype.FLAMMAGENITUS
                || event.type() == StormSubtype.FUNNEL_CLOUD) {
            return;
        }
        event.setPosition(surfacePos(level, event.position()));
    }

    /**
     * Whether a point may host an event while its chunk is not loaded, because an LOD renderer is drawing it.
     *
     * <p>Read through the one place that decides whether the storm box was widened, so "the compat is on and an
     * LOD mod is installed" is asked once rather than spelled out at each spawn site. A loaded chunk is always a
     * yes; an unloaded one is a yes only when there is terrain on screen to hang the weather on - which is the
     * whole claim: the LOD ground is real ground, and a world drawing it should be seeding weather over it. It
     * never forces a chunk to load, so what it buys is a placement, not a block: every reading that follows is
     * expected to answer from the biome when it cannot read the ground.
     */
    private static boolean lodSeedingAllowed(ServerLevel level, BlockPos pos) {
        return LodStormEnvelope.seedingUnloadedAllowed(level, pos);
    }

    /** Rain at a point: the level's own flag when the point cannot be read, the point's own answer when it can. */
    private static boolean rainingHere(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) ? level.isRainingAt(pos) : level.isRaining();
    }

    /** Whether the sky is open over a point: taken as open when there is no loaded ground to check against. */
    private static boolean openSkyHere(ServerLevel level, BlockPos pos) {
        return !level.isLoaded(pos) || level.canSeeSky(pos.above());
    }

    /**
     * Whether the ground under a point is the hot, dry kind a dust devil needs.
     *
     * <p>The loaded reading is the block under the point, as it always was. Over an unloaded chunk that block
     * cannot be read without loading it - and loading it to ask a cosmetic question is exactly the cost the
     * chunk gate used to exist to avoid - so the biome carries the answer instead: the same family classifier the
     * air, the tornado gate and the ground readings all use, restricted to the families that actually are dry
     * country. That is a weaker claim than the block test and deliberately so; it cannot tell sand from coarse
     * dirt, but it can tell either from a jungle, which is the distinction that matters here.
     */
    private static boolean hotDrySurface(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return dryCountry(level, pos);
        }
        float temp = level.getBiome(pos).value().getBaseTemperature();
        BlockState ground = level.getBlockState(pos.below());
        return temp >= 0.70F && (ground.is(Blocks.SAND) || ground.is(Blocks.RED_SAND) || ground.is(Blocks.TERRACOTTA) || ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT));
    }

    /** Whether a place's biome is one of the dry families, read without loading the chunk it is in. */
    private static boolean dryCountry(ServerLevel level, BlockPos pos) {
        BiomeClimate.Family family = TornadoGenesisGate.family(level, pos);
        return family == BiomeClimate.Family.DESERT
                || family == BiomeClimate.Family.BADLANDS
                || family == BiomeClimate.Family.SAVANNA
                || family == BiomeClimate.Family.PLAINS;
    }

    /**
     * Whether a snow devil may form at this point, decided by the model rather than here.
     *
     * <p>What is handed over is what is actually on the ground and in the air: liquid rain at the point,
     * any precipitation at all (which only matters when fair weather is being required), the level's
     * thunder flag, and the ground itself.
     */
    private static boolean snowDevilGroundAllows(ServerLevel level, BlockPos pos, boolean fairWeatherOnly) {
        boolean loaded = level.isLoaded(pos);
        // Every reading is taken from the ground when there is ground to read and from the sky over it when there
        // is not, so an unloaded chunk is judged by the biome rather than skipped. The block reads are the ones
        // that would have forced a load, and they are exactly the ones the unloaded branch replaces.
        return SnowDevilGround.canSpawn(rainingHere(level, pos),
                loaded ? precipitatingHere(level, pos) : level.isRaining(),
                fairWeatherOnly,
                level.isThundering(),
                loaded ? snowDevilSurface(level, pos) : biomeSurface(level, pos));
    }

    /**
     * The ground under an unreadable point, in the snow devil model's own terms, taken from the biome.
     *
     * <p>A biome that is cold enough to snow is reported as the frozen ground it is, on the same principle the
     * model already uses for a sheet of ice: the evidence that the country is cold is worth more than the
     * temperature it reports. No block is read, so nothing here can tell a snow layer from a drift - it can only
     * say the country is the kind that has one, which is the honest answer to a question asked from outside the
     * loaded world. Open water is reported as none, because a frozen biome's water is ice and a warm one's is
     * already refused by its temperature.
     */
    private static SnowDevilGround.Surface biomeSurface(ServerLevel level, BlockPos pos) {
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();
        boolean cold = biome.coldEnoughToSnow(pos);
        return new SnowDevilGround.Surface(biome.getBaseTemperature(), false, cold, cold, false, cold, false);
    }

    /** Whether anything at all is falling on a point, rain or snow, which the world only knows as rain. */
    private static boolean precipitatingHere(ServerLevel level, BlockPos pos) {
        return level.isRaining() && level.canSeeSky(pos.above());
    }

    /**
     * The ground under a point in the model's own terms.
     *
     * <p>The position a whirl stands at is the highest non-air block, which in snowy country is very
     * often the thin layer of snow lying on the grass - the ordinary case, and the one the old test threw
     * away because it only accepted a snow block, powder snow or ice. A snow layer counts, at the feet
     * and below them. Ice counts with it, and water under ice is not open water: a frozen lake is frozen
     * ground.
     */
    private static SnowDevilGround.Surface snowDevilSurface(ServerLevel level, BlockPos pos) {
        BlockState surface = level.getBlockState(pos);
        BlockState ground = level.getBlockState(pos.below());
        boolean frozenAtFeet = surface.is(Blocks.SNOW) || surface.is(Blocks.SNOW_BLOCK)
                || surface.is(Blocks.POWDER_SNOW) || surface.is(Blocks.ICE)
                || surface.is(Blocks.PACKED_ICE) || surface.is(Blocks.BLUE_ICE);
        boolean openWater = !frozenAtFeet && ground.getFluidState().is(FluidTags.WATER);
        return new SnowDevilGround.Surface(
                level.getBiome(pos).value().getBaseTemperature(),
                surface.is(Blocks.SNOW),
                surface.is(Blocks.SNOW) || ground.is(Blocks.SNOW),
                surface.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.SNOW_BLOCK),
                surface.is(Blocks.POWDER_SNOW) || ground.is(Blocks.POWDER_SNOW),
                frozenAtFeet || ground.is(Blocks.ICE) || ground.is(Blocks.PACKED_ICE) || ground.is(Blocks.BLUE_ICE),
                openWater);
    }

    /**
     * Whether a snow devil has lost the ground it was standing on.
     *
     * <p>This runs on every tick, so it is gated twice before it reads anything: a devil inside its
     * guaranteed life is never asked about, and outside it the ground is only read once a second. What it
     * then reads is a footprint - the centre and the four corners of the vortex's own radius - and any one
     * point that is still frozen ground keeps the whirl up. A snow devil that wanders off a drift and onto
     * a bare patch is a snow devil doing what a snow devil does.
     */
    private static boolean snowDevilHasGone(ServerLevel level, AddonWeatherEvent event) {
        long age = level.getGameTime() - event.spawnTick();
        long minLife = Math.max(0, Config.SNOW_DEVIL_MIN_LIFE_TICKS.get());
        if (age < minLife) {
            return false;
        }
        if (age % SnowDevilGround.CHECK_INTERVAL_TICKS != 0L) {
            return false;
        }
        BlockPos centre = BlockPos.containing(event.position());
        int radius = (int) Math.max(1.0D, Math.min(6.0D, event.radius() * 0.6D));
        List<SnowDevilGround.Surface> footprint = new ArrayList<>();
        for (int[] offset : SnowDevilGround.footprintOffsets(radius)) {
            BlockPos pos = centre.offset(offset[0], 0, offset[1]);
            footprint.add(level.isLoaded(pos) ? snowDevilSurface(level, pos) : null);
        }
        SnowDevilGround.Verdict verdict = SnowDevilGround.verdictOver(footprint,
                level.isRainingAt(centre), precipitatingHere(level, centre),
                Config.SNOW_DEVIL_FAIR_WEATHER_ONLY.get(), level.isThundering());
        return SnowDevilGround.doomed(verdict, age, minLife);
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
        if (type == StormSubtype.FUNNEL_CLOUD) {
            return funnelCloudAloftPos(level, surface);
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
        if (event.type() == StormSubtype.DUST_DEVIL || event.type() == StormSubtype.SNOW_DEVIL || event.type() == StormSubtype.GUSTNADO || event.type() == StormSubtype.LANDSPOUT || event.type() == StormSubtype.WATERSPOUT || event.type() == StormSubtype.FUNNEL_CLOUD) {
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
