package com.imbet.weather;

import com.imbet.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import weather2.ServerTickHandler;
import weather2.weathersystem.WeatherManagerServer;
import weather2.weathersystem.storm.StormObject;
import weather2.weathersystem.storm.WeatherObject;

import java.util.ArrayList;

public final class FloodingService {
    private FloodingService() {
    }

    public static void tick(ServerLevel level) {
        if (level == null || level.isClientSide() || !Config.FLOODING_ENABLED.get()) {
            return;
        }
        long gameTime = level.getGameTime();
        int interval = Math.max(5, Config.FLOODING_SCAN_INTERVAL_TICKS.get());
        if (gameTime % interval != 0L) {
            return;
        }
        int budget = Math.max(0, Config.FLOODING_MAX_BLOCKS_PER_TICK.get());
        if (budget <= 0) {
            return;
        }

        WeatherManagerServer manager = ServerTickHandler.getWeatherManagerFor(level);
        if (manager != null) {
            for (WeatherObject object : new ArrayList<>(manager.getStormObjects())) {
                if (budget <= 0) {
                    return;
                }
                if (object instanceof StormObject storm && storm.pos != null && !storm.isDead) {
                    // The same rain floods harder during the wet season, because the ground it is landing on has
                    // been wet for days. The season is a reading of the calendar and the local air, so this is
                    // the one line that couples a storm to it.
                    double intensity = weather2RainIntensity(storm) * MonsoonService.floodMultiplier(level, storm.pos);
                    if (intensity >= Config.FLOODING_RAIN_THRESHOLD.get()) {
                        double radius = Mth.clamp(Math.max(storm.getSize() * 2.2D, 72.0D + intensity * 95.0D), 48.0D, Config.FLOODING_RANGE_BLOCKS.get());
                        budget = sampleStormFlood(level, storm, radius, intensity, budget);
                    }
                }
            }
        }

        budget = sampleMonsoonBands(level, budget);
        if (budget <= 0) {
            return;
        }

        for (AddonWeatherEvent event : AddonWeatherEventService.activeEvents(level)) {
            if (budget <= 0) {
                return;
            }
            double intensity = addonRainIntensity(event) * MonsoonService.floodMultiplier(level, event.position());
            if (intensity < Config.FLOODING_RAIN_THRESHOLD.get()) {
                continue;
            }
            if (event.type() == StormSubtype.SQUALL_LINE && event.lineStart() != null && event.lineEnd() != null) {
                budget = sampleFloodLine(level, event, intensity, budget);
            } else {
                budget = sampleFloodArea(level, event.position(), Math.min(Config.FLOODING_RANGE_BLOCKS.get(), Math.max(48.0D, event.radius() * (event.type() == StormSubtype.HURRICANE ? 1.15D : 2.0D))), intensity, budget);
            }
        }
    }

    /**
     * The season's own rain, which is the only rain in this mod that no storm is responsible for. It falls in
     * bands a few hundred blocks across, placed on the season's own grid rather than around the player, so the
     * wet season is a field of downpours crossing the world rather than a bubble following whoever is looking.
     * Nothing here is a new hazard: it is the same flood water the rest of this service places, from rain that
     * exists because of the date.
     */
    private static int sampleMonsoonBands(ServerLevel level, int budget) {
        if (!Config.MONSOON_ENABLED.get()) {
            return budget;
        }
        for (ServerPlayer player : level.players()) {
            if (budget <= 0) {
                return budget;
            }
            for (MonsoonService.Band band : MonsoonService.bandsNear(level, player.position())) {
                if (budget <= 0) {
                    return budget;
                }
                if (band.intensity() < Config.FLOODING_RAIN_THRESHOLD.get()) {
                    continue;
                }
                budget = sampleFloodArea(level, band.centre(), Math.min(Config.FLOODING_RANGE_BLOCKS.get(), band.radiusBlocks()),
                        band.intensity(), budget);
            }
        }
        return budget;
    }

    private static double weather2RainIntensity(StormObject storm) {
        if (storm == null) {
            return 0.0D;
        }
        double intensity = 0.0D;
        if (storm.levelCurIntensityStage >= StormObject.STATE_THUNDER || storm.isPrecipitating()) {
            intensity = 0.34D + Math.max(0, storm.levelCurIntensityStage) * 0.13D;
        }
        if (storm.levelCurIntensityStage >= StormObject.STATE_HAIL) {
            intensity += 0.22D;
        }
        if (storm.stormType == StormObject.TYPE_WATER || DynamicStormController.isNativeTropicalCyclone(storm) || DynamicStormController.isNativeHurricane(storm)) {
            intensity += 0.32D;
        }
        // Rainfall is what actually floods ground, and it follows the storm's precipitation character:
        // a dry LP supercell mostly hails and barely floods, a rain-wrapped HP storm soaks the map.
        float character = SupercellStructureService.precipitationCharacter(storm, DynamicStormController.stateFor(storm));
        intensity *= Mth.lerp(0.25F, 1.45F, Mth.clamp(character, 0.0F, 1.0F));
        // Virga is precipitation that never lands, so it cannot flood anything: a shaft that evaporates
        // a hundred blocks up wets the ground with nothing. This is the same strength the client draws
        // its shafts from, scored from synced storm data, so what a player sees and what the world
        // soaks up agree.
        float virga = SupercellStructureService.virgaStrength(storm, DynamicStormController.stateFor(storm));
        intensity *= 1.0F - Mth.clamp(virga, 0.0F, 1.0F);
        return Mth.clamp(intensity, 0.0D, 1.65D);
    }

    private static double addonRainIntensity(AddonWeatherEvent event) {
        if (event.type() == StormSubtype.HURRICANE) {
            return Mth.clamp(0.92D + event.maxWindMph() / 260.0D, 0.0D, 1.8D);
        }
        if (event.type() == StormSubtype.SQUALL_LINE) {
            return Mth.clamp(0.58D + event.maxWindMph() / 180.0D, 0.0D, 1.25D);
        }
        if (event.type() == StormSubtype.WATERSPOUT) {
            return Mth.clamp(0.42D + event.maxWindMph() / 220.0D, 0.0D, 0.85D);
        }
        return 0.0D;
    }

    /**
     * Ground wetness under a storm cell, drawn from the storm's own precipitation field.
     *
     * <p>A disc would do, and it is what this used to do, but a rain shield is not a disc and the
     * difference is not cosmetic: standing in the rain-free notch of a violent supercell while its
     * forward flank soaks the county is the whole reason the notch is worth modelling. Sampling the
     * field means the low ground fills where the rain lands and stays dry up the slot, with the same
     * field the client draws its sheets from, so what a player sees and what the world soaks up agree.
     */
    private static int sampleStormFlood(ServerLevel level, StormObject storm, double radius, double intensity, int budget) {
        DynamicStormState state = DynamicStormController.stateFor(storm);
        PrecipitationField.Geometry field = SupercellStructureService.precipitationGeometry(storm, state);
        SupercellStructureService.StructureSample sample = SupercellStructureService.sample(storm, state);
        Vec3 forward = sample.forward();
        Vec3 right = sample.right();
        int attempts = Math.min(64, Math.max(6, budget * 5));
        for (int i = 0; i < attempts && budget > 0; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius;
            double x = storm.pos.x + Math.cos(angle) * distance;
            double z = storm.pos.z + Math.sin(angle) * distance;
            double density = PrecipitationField.densityAt(field, storm.pos.x, storm.pos.z,
                    forward.x, forward.z, right.x, right.z, x, z);
            if (level.random.nextDouble() >= density) {
                continue;
            }
            // How hard it is raining on this block, not how hard it is raining somewhere in the storm.
            if (tryPlaceFloodWater(level, new Vec3(x, 0.0D, z), intensity * density)) {
                budget--;
            }
        }
        return budget;
    }

    private static int sampleFloodArea(ServerLevel level, Vec3 center, double radius, double intensity, int budget) {
        int attempts = Math.min(64, Math.max(6, budget * 5));
        for (int i = 0; i < attempts && budget > 0; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = Math.sqrt(level.random.nextDouble()) * radius;
            Vec3 sample = center.add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            if (tryPlaceFloodWater(level, sample, intensity)) {
                budget--;
            }
        }
        return budget;
    }

    private static int sampleFloodLine(ServerLevel level, AddonWeatherEvent event, double intensity, int budget) {
        Vec3 start = event.lineStart();
        Vec3 end = event.lineEnd();
        if (start == null || end == null) {
            return budget;
        }
        Vec3 line = end.subtract(start);
        Vec3 normal = line.horizontalDistanceSqr() < 1.0E-5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(-line.z, 0.0D, line.x).normalize();
        int attempts = Math.min(64, Math.max(6, budget * 5));
        for (int i = 0; i < attempts && budget > 0; i++) {
            Vec3 point = start.lerp(end, level.random.nextDouble()).add(normal.scale((level.random.nextDouble() - 0.5D) * event.radius() * 1.6D));
            if (tryPlaceFloodWater(level, point, intensity)) {
                budget--;
            }
        }
        return budget;
    }

    private static boolean tryPlaceFloodWater(ServerLevel level, Vec3 sample, double intensity) {
        BlockPos column = BlockPos.containing(sample);
        if (!level.isLoaded(column)) {
            return false;
        }
        BlockPos surface = BlockGrabSafety.safeSurface(level, column, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (surface == null || !level.isLoaded(surface)) {
            return false;
        }
        BlockPos ground = surface.below();
        BlockState groundState = level.getBlockState(ground);
        if (!isTerrainSurface(groundState) || level.getBlockEntity(ground) != null) {
            return false;
        }
        BlockState targetState = level.getBlockState(surface);
        if (targetState.getFluidState().is(FluidTags.WATER) || !isFloodReplaceable(targetState) || level.getBlockEntity(surface) != null) {
            return false;
        }
        if (!isLowPoolingColumn(level, surface) && !nearExistingWater(level, surface, 5) && intensity < 1.05D) {
            return false;
        }
        if (floodDepth(level, surface) >= Config.FLOODING_MAX_DEPTH_BLOCKS.get()) {
            return false;
        }
        return BlockGrabSafety.safeSetBlock(level, surface, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static boolean isFloodReplaceable(BlockState state) {
        return state.isAir()
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS);
    }

    private static boolean isTerrainSurface(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.GRAVEL);
    }

    private static boolean isLowPoolingColumn(ServerLevel level, BlockPos surface) {
        int rim = 0;
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos sample = surface.offset(dx, 0, dz);
                BlockPos neighbor = BlockGrabSafety.safeSurface(level, sample, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
                if (neighbor != null && neighbor.getY() >= surface.getY()) {
                    rim++;
                }
            }
        }
        return rim >= 5;
    }

    private static boolean nearExistingWater(ServerLevel level, BlockPos surface, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = surface.offset(dx, dy, dz);
                    if (level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int floodDepth(Level level, BlockPos surface) {
        int depth = 0;
        for (int y = surface.getY() - 1; y >= level.getMinBuildHeight() && depth < 8; y--) {
            if (!level.getFluidState(new BlockPos(surface.getX(), y, surface.getZ())).is(FluidTags.WATER)) {
                break;
            }
            depth++;
        }
        return depth;
    }
}
