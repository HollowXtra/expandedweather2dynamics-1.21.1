package com.imbet.weather.vortex;

import com.imbet.Config;
import com.imbet.network.VortexSyncPayload;
import com.imbet.radar.RadarStormSnapshot;
import com.imbet.weather.AlertLevel;
import com.imbet.weather.AlertService;
import com.imbet.weather.SpcRisk;
import com.imbet.weather.StormSubtype;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.TornadoHelper;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VortexEngine {
    private static final Map<ResourceKey<Level>, LevelVortexState> STATES = new HashMap<>();
    private static final ConcurrentLinkedQueue<SurfaceAnalysis> ANALYSIS_RESULTS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<MutableWindSample> LOCAL_SAMPLE = ThreadLocal.withInitial(MutableWindSample::new);
    private static final String NBT_KEY = "expandedweather2dynamics";
    private static final ExecutorService ANALYSIS_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 3)),
            runnable -> {
                Thread thread = new Thread(runnable, "ExpandedWeather2D-VortexAnalysis");
                thread.setDaemon(true);
                return thread;
            }
    );

    private VortexEngine() {
    }

    public static void tick(ServerLevel level) {
        LevelVortexState state = stateFor(level);
        long gameTime = level.getGameTime();
        consumeAnalysis(level, state);
        upsertWeather2Vortices(level, state, gameTime);
        spawnStandaloneVortices(level, state, gameTime);
        tickInstances(level, state, gameTime);
        if (gameTime % 20L == 0L) {
            sync(level, state);
        }
    }

    public static void tickWeather2Storm(StormObject storm) {
        if (storm == null || storm.manager == null || storm.manager.getWorld() == null) {
            return;
        }
        Level world = storm.manager.getWorld();
        if (world instanceof ServerLevel serverLevel) {
            AbstractVortexInstance vortex = instanceForStorm(storm, serverLevel);
            if (vortex != null) {
                vortex.tick(serverLevel, serverLevel.getGameTime());
            }
        }
    }

    static boolean requestAnalysis(ServerLevel level, AbstractVortexInstance vortex, int generation) {
        SurfaceSample sample = captureSurfaceSample(level, vortex, generation);
        ANALYSIS_EXECUTOR.execute(() -> ANALYSIS_RESULTS.add(sample.analyze()));
        return true;
    }

    public static List<VortexSnapshot> activeSnapshots(ServerLevel level) {
        LevelVortexState state = STATES.get(level.dimension());
        if (state == null || state.vortices.isEmpty()) {
            return List.of();
        }
        List<VortexSnapshot> snapshots = new ArrayList<>(state.vortices.size() + 4);
        for (AbstractVortexInstance vortex : state.vortices.values()) {
            if (!vortex.dead()) {
                snapshots.add(vortex.snapshot());
                vortex.addSatelliteSnapshots(snapshots);
            }
        }
        return snapshots;
    }

    public static List<RadarStormSnapshot> radarSnapshots(ServerLevel level, Vec3 radarPos, int range) {
        List<RadarStormSnapshot> snapshots = new ArrayList<>();
        double rangeSqr = (double) range * range;
        for (VortexSnapshot vortex : activeSnapshots(level)) {
            double dx = vortex.x() - radarPos.x;
            double dz = vortex.z() - radarPos.z;
            if (dx * dx + dz * dz > rangeSqr) {
                continue;
            }
            SpcRisk risk = riskFor(vortex);
            double rotation = vortex.maxWindMph() * 0.44704D * (vortex.mode() == VortexMode.DUST_DEVIL ? 0.18D : 0.42D) * (vortex.antiCyclonic() ? -1.0D : 1.0D);
            boolean hook = vortex.mode() == VortexMode.LAND_TORNADO || vortex.mode() == VortexMode.FIRENADO;
            snapshots.add(new RadarStormSnapshot(
                    vortex.id(),
                    labelFor(vortex),
                    risk.label(),
                    risk.severity(),
                    risk.color(),
                    vortex.x(),
                    vortex.z(),
                    vortex.motionX(),
                    vortex.motionZ(),
                    vortex.damagePathWidth(),
                    vortex.maxWindMph(),
                    reflectivityDbz(vortex),
                    radialVelocityMps(radarPos, vortex),
                    rotation,
                    hook,
                    vortex.childCount(),
                    true,
                    vortex.x(),
                    vortex.z(),
                    vortex.x(),
                    vortex.z(),
                    vortex.warningCapable()
            ));
        }
        snapshots.sort(Comparator.comparingInt(RadarStormSnapshot::severity).reversed());
        return snapshots;
    }

    public static boolean sampleWind(Level level, double x, double y, double z, MutableWindSample out) {
        out.clear();
        if (level instanceof ServerLevel serverLevel) {
            LevelVortexState state = STATES.get(serverLevel.dimension());
            if (state != null) {
                for (AbstractVortexInstance vortex : state.vortices.values()) {
                    if (!vortex.dead()) {
                        vortex.sampleWind(x, y, z, out);
                    }
                }
            }
        } else {
            ClientVortexStore.sampleWind(x, y, z, out);
        }
        return out.speedMph > 0.0D;
    }

    public static boolean sampleWeather2StormWind(StormObject storm, Level level, double x, double y, double z, MutableWindSample out) {
        out.clear();
        AbstractVortexInstance vortex = instanceForStorm(storm, level);
        if (vortex != null) {
            vortex.sampleWind(x, y, z, out);
            return out.speedMph > 0.0D;
        }
        if (!(level instanceof ServerLevel)) {
            VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
            if (snapshot != null) {
                sampleSnapshot(snapshot, x, y, z, out);
                return out.speedMph > 0.0D;
            }
        }
        return false;
    }

    public static boolean hasWeather2Vortex(StormObject storm) {
        if (storm == null) {
            return false;
        }
        Level level = storm.manager == null ? null : storm.manager.getWorld();
        if (instanceForStorm(storm, level) != null) {
            return true;
        }
        return storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout;
    }

    public static boolean isDynamicWeather2Vortex(StormObject storm) {
        return storm != null && (storm.isTornadoFormingOrGreater() || storm.attrib_waterSpout);
    }

    public static int adjustedTornadoBaseSize(StormObject storm, int originalSize) {
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            return Mth.clamp(Math.round(vortex.visibleFunnelWidth()), 3, Math.max(3, (int) Math.round(Config.effectiveMaxVisibleFunnelWidthBlocks())));
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
        if (snapshot != null) {
            return Mth.clamp(Math.round(snapshot.visibleFunnelWidth()), 3, Math.max(3, (int) Math.round(Config.effectiveMaxVisibleFunnelWidthBlocks())));
        }
        return originalSize;
    }

    public static Vec3 bendFunnelLayer(StormObject storm, Vec3 basePos, Vec3 layerPos, int layerIndex, int layerCount) {
        if (storm == null || basePos == null || layerPos == null || layerCount <= 1 || !Config.TORNADO_FUNNEL_BEND_ENABLED.get()) {
            return layerPos;
        }
        VortexSnapshot snapshot = snapshotForStorm(storm);
        if (snapshot == null) {
            return layerPos;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        double anchor = Config.TORNADO_FUNNEL_BEND_ANCHOR_FRACTION.get();
        if (fraction <= anchor) {
            return layerPos;
        }
        double effective = Mth.clamp((fraction - anchor) / Math.max(0.05D, 1.0D - anchor), 0.0D, 1.0D);
        double heading = snapshot.motionX() * snapshot.motionX() + snapshot.motionZ() * snapshot.motionZ() > 1.0E-5D
                ? Math.atan2(snapshot.motionZ(), snapshot.motionX())
                : (snapshot.id() % 360L) * Mth.DEG_TO_RAD;
        double maxBend = Math.min(Config.TORNADO_FUNNEL_BEND_MAX_BLOCKS.get(), Math.max(2.0D, snapshot.damagePathWidth() * 0.16D));
        double phase = (snapshot.spawnTick() * 0.031D) + layerIndex * 0.41D;
        double along = Math.pow(effective, 1.45D) * maxBend;
        double sway = Math.sin(phase + effective * Math.PI * 1.6D) * Config.TORNADO_FUNNEL_BEND_SWAY_BLOCKS.get() * Math.sin(effective * Math.PI);
        double leanX = Math.cos(heading);
        double leanZ = Math.sin(heading);
        double crossX = -leanZ;
        double crossZ = leanX;
        return new Vec3(basePos.x + leanX * along + crossX * sway, layerPos.y, basePos.z + leanZ * along + crossZ * sway);
    }

    public static Vec3 adjustEntityMotion(StormObject storm, Vec3 position, Vec3 originalMotion) {
        Level level = storm == null || storm.manager == null ? null : storm.manager.getWorld();
        if (level == null || position == null || originalMotion == null) {
            return originalMotion;
        }
        MutableWindSample sample = LOCAL_SAMPLE.get().clear();
        if (!sampleWeather2StormWind(storm, level, position.x, position.y, position.z, sample) || sample.speedMph <= 1.0D) {
            return originalMotion;
        }
        return originalMotion.add(sample.mphX * Config.WINDFIELD_ENTITY_PUSH_SCALE.get(), sample.mphY * Config.WINDFIELD_ENTITY_PUSH_SCALE.get(), sample.mphZ * Config.WINDFIELD_ENTITY_PUSH_SCALE.get());
    }

    public static boolean shouldTryRip(TornadoHelper helper, Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || helper == null || helper.storm == null || pos == null) {
            return false;
        }
        if (Config.DAMAGE_LOADED_CHUNKS_ONLY.get() && !serverLevel.isLoaded(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        if (Config.DAMAGE_OPEN_DOORS.get() && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN) && !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)) {
            serverLevel.setBlock(pos, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN, true), 3);
            serverLevel.updateNeighborsAt(pos, state.getBlock());
            return false;
        }
        AbstractVortexInstance vortex = instanceForStorm(helper.storm, serverLevel);
        return vortex != null && vortex.tryConsumeNativeBlockDamage(serverLevel.getGameTime());
    }

    public static boolean canGrabBlock(TornadoHelper helper, Level level, BlockState state, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || helper == null || helper.storm == null || state == null || pos == null) {
            return false;
        }
        AbstractVortexInstance vortex = instanceForStorm(helper.storm, serverLevel);
        return vortex != null && vortex.canGrabBlock(serverLevel, state, pos);
    }

    public static boolean canGrabEntity(TornadoHelper helper, Entity entity) {
        if (helper == null || helper.storm == null || entity == null) {
            return false;
        }
        if (entity instanceof Player) {
            return Config.DAMAGE_GRAB_PLAYERS.get();
        }
        if (entity instanceof LivingEntity) {
            return Config.DAMAGE_GRAB_LIVING_ENTITIES.get();
        }
        return true;
    }

    public static boolean isAntiCyclonic(StormObject storm) {
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            return vortex.antiCyclonic();
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
        return snapshot != null && snapshot.antiCyclonic();
    }

    public static double rotationSign(StormObject storm) {
        return isAntiCyclonic(storm) ? -1.0D : 1.0D;
    }

    public static float visualSpinSpeed(StormObject storm) {
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            return vortex.visualSpinSpeed();
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
        return snapshot == null ? 0.055F : Math.max(0.055F, snapshot.maxWindMph() / 760.0F);
    }

    public static void applyClientVisualSpin(StormObject storm) {
        if (storm == null || !Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get()) {
            return;
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm.ID);
        if (snapshot != null) {
            double magnitude = Math.max(0.0D, snapshot.maxWindMph() / 760.0D);
            storm.spinSpeed = magnitude * rotationSign(storm);
        }
    }

    public static float adjustedAngle(StormObject storm, float originalAngle) {
        if (!Config.DYNAMICS_ENABLED.get() || storm == null || !isDynamicWeather2Vortex(storm)) {
            return originalAngle;
        }
        if (storm.weatherMachineControlled || storm.isPlayerControlled() || storm.isPet()) {
            return originalAngle;
        }
        return originalAngle;
    }

    public static void afterStageNext(StormObject storm) {
        if (!Config.CYCLONES_ENABLED.get() || storm == null) {
            return;
        }
        if (storm.isTropicalCyclone() || storm.isHurricane() || storm.isCycloneFormingOrGreater()) {
            AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
            if (vortex != null) {
                vortex.transitionTo(VortexMode.LAND_TORNADO, storm.manager.getWorld().getGameTime());
            }
        }
    }

    public static void saveStateToStorm(StormObject storm) {
        if (!Config.PERSISTENCE_ENABLED.get() || storm == null || storm.getNbtCache() == null) {
            return;
        }
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            storm.getNbtCache().put(NBT_KEY, vortex.snapshotToNbt());
        }
    }

    public static void loadStateFromStorm(StormObject storm) {
        if (!Config.PERSISTENCE_ENABLED.get() || storm == null || storm.getNbtCache() == null || !storm.getNbtCache().contains(NBT_KEY)) {
            return;
        }
        // Load state from NBT - would need to implement this in AbstractVortexInstance
    }

    public static boolean shouldCancelNaturalPlayerAim(StormObject storm) {
        return Config.NO_PLAYER_AIM_ON_SPAWN.get()
                && storm != null
                && !storm.weatherMachineControlled
                && !storm.isPlayerControlled()
                && !storm.isPet();
    }

    public static float extraFunnelLayerSpinDegrees(StormObject storm, int layerIndex, int layerCount) {
        if (storm == null || layerCount <= 1 || !Config.TORNADO_WIND_SPEED_SPIN_ENABLED.get()) {
            return 0.0F;
        }
        float fraction = Mth.clamp(layerIndex / (float) (layerCount - 1), 0.0F, 1.0F);
        float layerScale = Mth.lerp(fraction, 0.65F, 1.0F);
        float spinDelta = (visualSpinSpeed(storm) - 0.08F) * 70.0F;
        return Mth.clamp(spinDelta * layerScale * (float) rotationSign(storm), -6.0F, 24.0F);
    }

    public static double maxWindMph(StormObject storm) {
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            return vortex.maxWindMph();
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
        if (snapshot != null) {
            return snapshot.maxWindMph();
        }
        return Weather2VortexInstance.windForStage(storm);
    }

    public static int baseRadiusForWind(StormObject storm, int fallback) {
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            return Math.max(8, Math.round(vortex.damagePathWidth() * 0.5F));
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
        if (snapshot != null) {
            return Math.max(8, Math.round(snapshot.damagePathWidth() * 0.5F));
        }
        return fallback;
    }

    public static double radiusOfMaxWind(StormObject storm, double fallback) {
        AbstractVortexInstance vortex = instanceForStorm(storm, storm == null || storm.manager == null ? null : storm.manager.getWorld());
        if (vortex != null) {
            return vortex.radiusOfMaxWind();
        }
        VortexSnapshot snapshot = ClientVortexStore.byParentStormId(storm == null ? Long.MIN_VALUE : storm.ID);
        return snapshot == null ? fallback : snapshot.radiusOfMaxWind();
    }

    public static AlertService.AlertResult alertForStorm(StormObject storm, Vec3 origin) {
        VortexSnapshot snapshot = snapshotForStorm(storm);
        if (snapshot == null) {
            return AlertService.NONE;
        }
        Level world = storm == null || storm.manager == null ? null : storm.manager.getWorld();
        return alertForSnapshot(world, snapshot, origin);
    }

    public static AlertService.AlertResult highestAlert(ServerLevel level, Vec3 origin, boolean warningsOnly) {
        AlertService.AlertResult best = AlertService.NONE;
        for (VortexSnapshot snapshot : activeSnapshots(level)) {
            AlertService.AlertResult candidate = alertForSnapshot(level, snapshot, origin);
            if (warningsOnly && !candidate.level().isWarning()) {
                continue;
            }
            if (candidate.level().redstonePower() > best.level().redstonePower()) {
                best = candidate;
            }
        }
        return best;
    }

    public static boolean isOverWaterOrOcean(Level level, double x, double z) {
        BlockPos sample = new BlockPos(Mth.floor(x), 0, Mth.floor(z));
        if (!level.isLoaded(sample)) {
            return false;
        }
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, sample);
        BlockState below = level.getBlockState(surface.below());
        if (below.getFluidState().is(FluidTags.WATER)) {
            return true;
        }
        return level.getBiome(surface).unwrapKey()
                .map(key -> {
                    String path = key.location().getPath();
                    return path.contains("ocean") || path.contains("beach") || path.contains("river");
                })
                .orElse(false);
    }

    private static void consumeAnalysis(ServerLevel level, LevelVortexState state) {
        SurfaceAnalysis analysis;
        while ((analysis = ANALYSIS_RESULTS.poll()) != null) {
            if (!analysis.dimension().equals(level.dimension())) {
                ANALYSIS_RESULTS.add(analysis);
                break;
            }
            AbstractVortexInstance vortex = state.vortices.get(analysis.vortexId());
            if (vortex != null) {
                vortex.acceptAnalysis(analysis);
            }
        }
    }

    private static void upsertWeather2Vortices(ServerLevel level, LevelVortexState state, long gameTime) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        state.seenStormIds.clear();
        if (manager == null) {
            return;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead) {
                continue;
            }
            applyNativeWaterspoutTag(level, manager, storm);
            if (!isDynamicWeather2Vortex(storm)) {
                continue;
            }
            state.seenStormIds.add(storm.ID);
            Weather2VortexInstance instance = state.weather2ByStormId.get(storm.ID);
            if (instance == null || instance.dead()) {
                instance = new Weather2VortexInstance(storm, level, gameTime);
                state.weather2ByStormId.put(storm.ID, instance);
                state.vortices.put(storm.ID, instance);
            }
            instance.attach(storm, level, gameTime);
        }
        for (Weather2VortexInstance instance : state.weather2ByStormId.values()) {
            if (!state.seenStormIds.contains(instance.parentStormId()) && instance.lastSeenTick + 60L < gameTime) {
                instance.dead = true;
            }
        }
    }

    private static void spawnStandaloneVortices(ServerLevel level, LevelVortexState state, long gameTime) {
        int scanInterval = Math.max(20, Config.PERFORMANCE_STORM_SCAN_INTERVAL_TICKS.get());
        if (gameTime % scanInterval != 0L || state.vortices.size() >= Config.MAX_ADDON_VORTICES.get() + 12) {
            return;
        }
        spawnStormAdjacentVortices(level, state, gameTime);
        spawnDustDevils(level, state, gameTime);
    }

    private static void spawnStormAdjacentVortices(ServerLevel level, LevelVortexState state, long gameTime) {
        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager == null) {
            return;
        }
        for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
            if (!(object instanceof StormObject storm) || storm.pos == null || storm.isDead || state.vortices.size() >= Config.MAX_ADDON_VORTICES.get() + 12) {
                continue;
            }
            if (isOverWaterOrOcean(level, storm.pos.x, storm.pos.z)) {
                maybeSpawnWaterspout(level, state, storm, gameTime);
            } else {
                maybeSpawnLandspout(level, state, storm, gameTime);
            }
        }
    }

    private static void maybeSpawnLandspout(ServerLevel level, LevelVortexState state, StormObject storm, long gameTime) {
        if (!Config.LANDSPOUTS_ENABLED.get() || storm.stormType != StormObject.TYPE_LAND || storm.isTornadoFormingOrGreater() || storm.isCycloneFormingOrGreater() || storm.isTropicalCyclone() || storm.levelCurIntensityStage < StormObject.STATE_THUNDER || storm.levelCurIntensityStage > StormObject.STATE_HAIL || hasNearbyMode(state, VortexMode.LANDSPOUT, storm.pos, 180.0D)) {
            return;
        }
        if (level.random.nextDouble() > Config.LANDSPOUT_SPAWN_CHANCE.get()) {
            return;
        }
        Vec3 pos = surfacePos(level, storm.pos.add(randomOffset(level, 28.0D, 90.0D)));
        if (isOverWaterOrOcean(level, pos.x, pos.z)) {
            return;
        }
        float wind = 62.0F + level.random.nextFloat() * 38.0F;
        float radius = 4.0F + level.random.nextFloat() * 12.0F;
        Vec3 motion = storm.motion == null ? randomMotion(level, 0.018D, 0.055D) : storm.motion.scale(0.35D).add(randomMotion(level, 0.010D, 0.035D));
        StandaloneVortexInstance instance = new StandaloneVortexInstance(nextStandaloneId(gameTime, level), VortexMode.LANDSPOUT, pos, motion, radius, wind, gameTime, gameTime + 300L + level.random.nextInt(620));
        state.vortices.put(instance.id(), instance);
    }

    private static void maybeSpawnWaterspout(ServerLevel level, LevelVortexState state, StormObject storm, long gameTime) {
        if (!Config.WATERSPOUTS_ENABLED.get() || !Config.FAIR_WEATHER_WATERSPOUTS_ENABLED.get() || storm.isTornadoFormingOrGreater() || storm.isCycloneFormingOrGreater() || storm.isTropicalCyclone() || storm.levelCurIntensityStage > StormObject.STATE_THUNDER || hasNearbyMode(state, VortexMode.WATERSPOUT, storm.pos, 180.0D)) {
            return;
        }
        double cloudWind = storm.manager == null ? 0.0D : storm.manager.getWindManager().getWindSpeedForClouds();
        if ((!storm.isGrowing && storm.levelCurIntensityStage != StormObject.STATE_NORMAL) || cloudWind > 1.35D || level.random.nextDouble() > Config.WATERSPOUT_SPAWN_CHANCE.get()) {
            return;
        }
        Vec3 pos = surfacePos(level, storm.pos.add(randomOffset(level, 20.0D, 110.0D)));
        if (!isOverWaterOrOcean(level, pos.x, pos.z)) {
            return;
        }
        float wind = 45.0F + level.random.nextFloat() * 40.0F;
        float radius = 3.5F + level.random.nextFloat() * 9.0F;
        Vec3 motion = storm.motion == null ? randomMotion(level, 0.004D, 0.028D) : storm.motion.scale(0.18D).add(randomMotion(level, 0.002D, 0.018D));
        StandaloneVortexInstance instance = new StandaloneVortexInstance(nextStandaloneId(gameTime, level), VortexMode.WATERSPOUT, pos, motion, radius, wind, gameTime, gameTime + 220L + level.random.nextInt(680));
        state.vortices.put(instance.id(), instance);
    }

    private static void spawnDustDevils(ServerLevel level, LevelVortexState state, long gameTime) {
        if (!Config.DUST_DEVILS_ENABLED.get() || level.isRaining() || level.isThundering() || !isDryAfternoon(level)) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (state.vortices.size() >= Config.MAX_ADDON_VORTICES.get() + 12 || level.random.nextDouble() > Config.DUST_DEVIL_SPAWN_CHANCE.get()) {
                continue;
            }
            Vec3 candidate = surfacePos(level, player.position().add(randomOffset(level, 48.0D, 180.0D)));
            BlockPos pos = BlockPos.containing(candidate);
            if (!level.isLoaded(pos) || level.isRainingAt(pos) || !level.canSeeSky(pos.above()) || !hotDrySurface(level, pos) || hasNearbyWeather2Storm(level, candidate, 420.0D) || hasNearbyMode(state, VortexMode.DUST_DEVIL, candidate, 96.0D)) {
                continue;
            }
            float wind = 24.0F + level.random.nextFloat() * 34.0F;
            float radius = 1.5F + level.random.nextFloat() * 5.0F;
            StandaloneVortexInstance instance = new StandaloneVortexInstance(nextStandaloneId(gameTime, level), VortexMode.DUST_DEVIL, candidate, randomMotion(level, 0.018D, 0.070D), radius, wind, gameTime, gameTime + 160L + level.random.nextInt(520));
            state.vortices.put(instance.id(), instance);
        }
    }

    private static void tickInstances(ServerLevel level, LevelVortexState state, long gameTime) {
        state.removals.clear();
        for (AbstractVortexInstance vortex : state.vortices.values()) {
            vortex.tick(level, gameTime);
            if (vortex.dead()) {
                state.removals.add(vortex.id());
            }
        }
        for (long id : state.removals) {
            AbstractVortexInstance removed = state.vortices.remove(id);
            if (removed instanceof Weather2VortexInstance) {
                state.weather2ByStormId.remove(removed.parentStormId());
            }
        }
    }

    private static void sync(ServerLevel level, LevelVortexState state) {
        if (state.vortices.isEmpty()) {
            return;
        }
        List<VortexSnapshot> all = activeSnapshots(level);
        for (ServerPlayer player : level.players()) {
            Vec3 playerPos = player.position();
            List<VortexSnapshot> nearby = all.stream()
                    .filter(snapshot -> horizontalDistanceSqr(snapshot.x(), snapshot.z(), playerPos.x, playerPos.z) <= Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get() * Config.MULTIPLAYER_SYNC_RANGE_BLOCKS.get())
                    .limit(128)
                    .toList();
            PacketDistributor.sendToPlayer(player, new VortexSyncPayload(nearby));
        }
    }

    private static SurfaceSample captureSurfaceSample(ServerLevel level, AbstractVortexInstance vortex, int generation) {
        int centerX = Mth.floor(vortex.x());
        int centerZ = Mth.floor(vortex.z());
        int water = 0;
        int deepWater = 0;
        int land = 0;
        int arid = 0;
        int hot = 0;
        int fire = 0;
        int flammable = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int centerSurfaceY = Mth.floor(vortex.y());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos depthCursor = new BlockPos.MutableBlockPos();
        for (int ox = -12; ox <= 12; ox += 6) {
            for (int oz = -12; oz <= 12; oz += 6) {
                cursor.set(centerX + ox, 0, centerZ + oz);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                BlockPos surface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, cursor);
                centerSurfaceY = ox == 0 && oz == 0 ? surface.getY() : centerSurfaceY;
                minY = Math.min(minY, surface.getY());
                maxY = Math.max(maxY, surface.getY());
                cursor.set(surface.getX(), surface.getY() - 1, surface.getZ());
                BlockState state = level.getBlockState(cursor);
                if (state.getFluidState().is(FluidTags.WATER)) {
                    water++;
                    int depth = 0;
                    for (int dy = 0; dy < 6; dy++) {
                        depthCursor.set(cursor.getX(), cursor.getY() - dy, cursor.getZ());
                        if (level.getBlockState(depthCursor).getFluidState().is(FluidTags.WATER)) {
                            depth++;
                        }
                    }
                    if (depth >= 4) {
                        deepWater++;
                    }
                } else {
                    land++;
                }
                if (isAridSurface(state)) {
                    arid++;
                }
                if (isHotSource(state)) {
                    hot++;
                }
                if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
                    fire++;
                }
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                    flammable++;
                }
                cursor.set(surface.getX(), surface.getY(), surface.getZ());
                BlockState above = level.getBlockState(cursor);
                if (above.is(Blocks.FIRE) || above.is(Blocks.SOUL_FIRE) || above.is(Blocks.CAMPFIRE) || above.is(Blocks.SOUL_CAMPFIRE)) {
                    fire++;
                }
            }
        }
        if (minY == Integer.MAX_VALUE) {
            minY = centerSurfaceY;
            maxY = centerSurfaceY;
        }
        BlockPos center = new BlockPos(centerX, centerSurfaceY, centerZ);
        float temperature = level.isLoaded(center) ? level.getBiome(center).value().getBaseTemperature() : 0.0F;
        boolean raining = level.isRainingAt(center);
        boolean canSeeSky = level.canSeeSky(center.above());
        return new SurfaceSample(level.dimension(), vortex.id(), generation, centerX, centerZ, centerSurfaceY, water, deepWater, land, arid, hot, fire, flammable, minY, maxY, temperature, raining, canSeeSky);
    }

    private static AbstractVortexInstance instanceForStorm(StormObject storm, Level level) {
        if (storm == null || level == null) {
            return null;
        }
        LevelVortexState state = STATES.get(level.dimension());
        return state == null ? null : state.weather2ByStormId.get(storm.ID);
    }

    private static VortexSnapshot snapshotForStorm(StormObject storm) {
        if (storm == null) {
            return null;
        }
        Level level = storm.manager == null ? null : storm.manager.getWorld();
        AbstractVortexInstance instance = instanceForStorm(storm, level);
        if (instance != null) {
            return instance.snapshot();
        }
        return ClientVortexStore.byParentStormId(storm.ID);
    }

    private static AlertService.AlertResult alertForSnapshot(Level level, VortexSnapshot snapshot, Vec3 origin) {
        if (snapshot == null || origin == null || !snapshot.warningCapable()) {
            return AlertService.NONE;
        }
        double distance = distanceToPath(origin, snapshot);
        if (distance > Config.ALERT_WARNING_RANGE_BLOCKS.get()) {
            return AlertService.NONE;
        }
        AlertLevel levelType = warningLevel(snapshot);
        return new AlertService.AlertResult(
                levelType,
                labelFor(snapshot),
                productCode(levelType),
                "OBSERVED",
                snapshot.position(),
                projected(snapshot.position(), snapshot.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get()),
                snapshot.id(),
                level == null ? 0L : level.getGameTime() + Math.max(20L * 60L, Config.ALERT_COOLDOWN_TICKS.get())
        );
    }

    private static void applyNativeWaterspoutTag(ServerLevel level, WeatherManagerServer manager, StormObject storm) {
        if (!Config.WATERSPOUTS_ENABLED.get() || storm == null || storm.pos == null) {
            return;
        }
        boolean overWater = isOverWaterOrOcean(level, storm.pos.x, storm.pos.z);
        boolean shouldBeWaterspout = overWater && (storm.attrib_waterSpout || (storm.stormType == StormObject.TYPE_LAND && storm.isTornadoFormingOrGreater()));
        if (storm.attrib_waterSpout != shouldBeWaterspout) {
            storm.attrib_waterSpout = shouldBeWaterspout;
            manager.syncStormUpdate(storm);
        }
    }

    private static LevelVortexState stateFor(ServerLevel level) {
        return STATES.computeIfAbsent(level.dimension(), key -> new LevelVortexState());
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

    private static boolean isDryAfternoon(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        return dayTime >= 4500L && dayTime <= 10500L;
    }

    private static boolean hotDrySurface(ServerLevel level, BlockPos pos) {
        float temp = level.getBiome(pos).value().getBaseTemperature();
        BlockState ground = level.getBlockState(pos.below());
        return temp >= 0.95F && isAridSurface(ground);
    }

    private static boolean isAridSurface(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.TERRACOTTA) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT);
    }

    private static boolean isHotSource(BlockState state) {
        return state.is(Blocks.LAVA) || state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private static boolean hasNearbyMode(LevelVortexState state, VortexMode mode, Vec3 center, double range) {
        double rangeSqr = range * range;
        for (AbstractVortexInstance vortex : state.vortices.values()) {
            if (vortex.mode() == mode && horizontalDistanceSqr(vortex.x(), vortex.z(), center.x, center.z) <= rangeSqr) {
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
            if (object instanceof StormObject storm && storm.pos != null && !storm.isDead && storm.pos.distanceToSqr(center) <= rangeSqr && storm.levelCurIntensityStage >= StormObject.STATE_THUNDER) {
                return true;
            }
        }
        return false;
    }

    private static long nextStandaloneId(long gameTime, ServerLevel level) {
        return Long.MIN_VALUE ^ (gameTime << 20) ^ level.random.nextInt(1_000_000);
    }

    private static void sampleSnapshot(VortexSnapshot snapshot, double px, double py, double pz, MutableWindSample out) {
        double dx = px - snapshot.x();
        double dz = pz - snapshot.z();
        double radius = Math.max(0.4D, Math.sqrt(dx * dx + dz * dz));
        double baseRadius = Math.max(4.0D, snapshot.damagePathWidth() * 0.5D);
        if (radius > baseRadius * 4.2D) {
            return;
        }
        double rmw = Math.max(3.0D, snapshot.radiusOfMaxWind());
        double radialX = dx / radius;
        double radialZ = dz / radius;
        double sign = snapshot.antiCyclonic() ? -1.0D : 1.0D;
        double tangential = radius <= rmw ? snapshot.maxWindMph() * (radius / rmw) : snapshot.maxWindMph() * Math.pow(rmw / radius, 0.78D);
        double inflow = snapshot.mode() == VortexMode.DUST_DEVIL ? 0.10D : snapshot.mode() == VortexMode.WATERSPOUT ? 0.24D : 0.36D;
        double falloff = Mth.clamp(1.0D - radius / Math.max(1.0D, baseRadius * 1.8D), 0.0D, 1.0D);
        double mphX = -radialZ * sign * tangential - radialX * tangential * inflow;
        double mphY = snapshot.maxWindMph() * Config.WINDFIELD_VERTICAL_LIFT_FRACTION.get() * falloff;
        double mphZ = radialX * sign * tangential - radialZ * tangential * inflow;
        double speed = Math.sqrt(mphX * mphX + mphY * mphY + mphZ * mphZ);
        out.setIfStronger(snapshot.id(), mphX, mphY, mphZ, speed, speed, radius, baseRadius, rmw);
    }

    private static SpcRisk riskFor(VortexSnapshot snapshot) {
        if (snapshot.mode() == VortexMode.DUST_DEVIL || snapshot.mode() == VortexMode.WATERSPOUT || snapshot.mode() == VortexMode.LANDSPOUT) {
            return SpcRisk.MARGINAL;
        }
        if (snapshot.maxWindMph() >= 220.0F) {
            return SpcRisk.HIGH;
        }
        if (snapshot.maxWindMph() >= 170.0F || snapshot.mode() == VortexMode.FIRENADO) {
            return SpcRisk.MODERATE;
        }
        if (snapshot.maxWindMph() >= 135.0F || snapshot.mode() == VortexMode.SATELLITE_TORNADO) {
            return SpcRisk.ENHANCED;
        }
        return SpcRisk.SLIGHT;
    }

    private static String labelFor(VortexSnapshot snapshot) {
        if (snapshot.mode() == VortexMode.LAND_TORNADO && snapshot.antiCyclonic()) {
            return "Anti-Cyclonic Tornado";
        }
        return snapshot.mode().subtype().label();
    }

    private static double reflectivityDbz(VortexSnapshot snapshot) {
        double dbz = switch (snapshot.mode()) {
            case DUST_DEVIL -> 8.0D + snapshot.maxWindMph() * 0.12D;
            case WATERSPOUT -> 28.0D + snapshot.maxWindMph() * 0.18D;
            case LANDSPOUT -> 22.0D + snapshot.maxWindMph() * 0.14D;
            case FIRENADO -> 44.0D + snapshot.maxWindMph() * 0.09D;
            case SATELLITE_TORNADO -> 38.0D + snapshot.maxWindMph() / 6.0D;
            case LAND_TORNADO -> 32.0D + snapshot.maxWindMph() * 0.10D + snapshot.childCount() * 1.2D;
        };
        return Mth.clamp(dbz, 5.0D, 76.0D);
    }

    private static double radialVelocityMps(Vec3 radarPos, VortexSnapshot snapshot) {
        double dx = snapshot.x() - radarPos.x;
        double dz = snapshot.z() - radarPos.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.001D) {
            return 0.0D;
        }
        return ((snapshot.motionX() * dx + snapshot.motionZ() * dz) / distance) * 20.0D;
    }

    private static AlertLevel warningLevel(VortexSnapshot snapshot) {
        if (!Config.ALERTS_STRUCTURED_PRODUCTS.get()) {
            return AlertLevel.TORNADO_WARNING;
        }
        if (Config.ALERTS_TORNADO_EMERGENCY.get() && snapshot.maxWindMph() >= 190.0F && snapshot.damagePathWidth() >= 180.0F) {
            return AlertLevel.TORNADO_EMERGENCY;
        }
        if (snapshot.mode() == VortexMode.FIRENADO || snapshot.maxWindMph() >= 150.0F || snapshot.damagePathWidth() >= 110.0F) {
            return AlertLevel.PDS_TORNADO_WARNING;
        }
        return AlertLevel.TORNADO_WARNING;
    }

    private static double distanceToPath(Vec3 origin, VortexSnapshot snapshot) {
        Vec3 current = snapshot.position();
        double distance = origin.distanceTo(current);
        if (snapshot.motionX() * snapshot.motionX() + snapshot.motionZ() * snapshot.motionZ() < 1.0E-5D) {
            return distance;
        }
        Vec3 future = projected(current, snapshot.motion(), Config.ALERT_WARNING_PATH_LEAD_TICKS.get());
        return Math.min(distance, origin.distanceTo(future));
    }

    private static Vec3 projected(Vec3 start, Vec3 motion, int leadTicks) {
        if (start == null || motion == null || leadTicks <= 0 || motion.lengthSqr() < 1.0E-5D) {
            return start == null ? Vec3.ZERO : start;
        }
        return start.add(motion.scale(leadTicks));
    }

    private static String productCode(AlertLevel level) {
        if (level == AlertLevel.TORNADO_WARNING || level == AlertLevel.PDS_TORNADO_WARNING || level == AlertLevel.TORNADO_EMERGENCY) {
            return "TOR";
        }
        if (level == AlertLevel.SEVERE_STORM_WARNING) {
            return "SVR";
        }
        if (level == AlertLevel.TORNADO_WATCH) {
            return "TOR WATCH";
        }
        return "NONE";
    }

    private static double horizontalDistanceSqr(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private static final class LevelVortexState {
        private final Map<Long, AbstractVortexInstance> vortices = new HashMap<>();
        private final Map<Long, Weather2VortexInstance> weather2ByStormId = new HashMap<>();
        private final Set<Long> seenStormIds = new HashSet<>();
        private final List<Long> removals = new ArrayList<>();
    }
}
